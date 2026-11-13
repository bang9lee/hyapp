package com.parkingroom.app.game

import android.os.SystemClock
import android.util.Log
import com.parkingroom.app.audio.SfxPlayer
import com.parkingroom.app.car.CarActuators
import com.parkingroom.app.car.CarSignals
import com.parkingroom.app.data.GameRecord
import com.parkingroom.app.engine.EffectEngine
import com.parkingroom.app.engine.PuzzleContext
import com.parkingroom.app.engine.PuzzleEngine
import com.parkingroom.app.engine.PuzzleResult
import com.parkingroom.app.scenario.HintMode
import com.parkingroom.app.scenario.Piece
import com.parkingroom.app.scenario.Scenario
import com.parkingroom.app.voice.VoiceManager
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class GameSetup(val levelKey: String, val coop: Boolean = false)

/** INTRO: 해킹 장면 / PLAY: 구역 탐색 / ATTACK: NULL 기습·반격 / ENDING: 복구 / FAILED: 시간 초과 */
enum class Stage { INTRO, PLAY, ATTACK, ENDING, FAILED }

data class Zone(val id: String, val title: String, val subtitle: String, val solved: Boolean, val digit: Int?, val master: Boolean)

data class GameUi(
    val stage: Stage = Stage.INTRO,
    val zones: List<Zone> = emptyList(),
    val selected: String? = null,
    val taunt: String? = null,
    val hintsUsed: Int = 0,
    val hintsLeft: Int? = null,
    val penaltySec: Int = 0,
    val mistakes: Int = 0,
    val attackText: String? = null,
) {
    val digits: List<Int?> get() = zones.filter { !it.master }.map { it.digit }
    val masterOpen: Boolean get() = zones.filter { !it.master }.all { it.solved }
}

sealed interface GameEnd {
    data class Cleared(val record: GameRecord, val remainingMs: Long) : GameEnd
    data object TimeOver : GameEnd
}

/**
 * 한 판의 진행 (해킹된 차 방탈출).
 * 왼쪽 메뉴로 보안 구역을 자유롭게 오가며 자물쇠를 풀고, 코드 4자리로 마스터 코드를 연다.
 * 안전 중단·"그만"은 바깥(AppController)에서 이 세션의 Job을 취소하는 방식으로 처리한다.
 */
class GameSession(
    private val scenario: Scenario,
    val setup: GameSetup,
    private val signals: CarSignals,
    private val car: CarActuators,
    private val effects: EffectEngine,
    private val puzzles: PuzzleEngine,
    private val voice: VoiceManager,
    private val sfx: SfxPlayer,
) {
    val level = scenario.level(setup.levelKey)
    val title = scenario.title
    private val levelKey get() = level.key
    private val zonePieces = scenario.pieces
    private val master = scenario.final!!

    private val _ui = MutableStateFlow(
        GameUi(
            zones = zonePieces.map { Zone(it.id, it.title, it.subtitle, false, null, false) } +
                Zone(master.id, master.title, master.subtitle, false, null, true),
            selected = zonePieces.first().id,
            hintsLeft = when (level.hint) {
                HintMode.ASK -> level.maxHints
                else -> null
            },
        ),
    )
    val ui: StateFlow<GameUi> = _ui.asStateFlow()

    private val startedAt = SystemClock.elapsedRealtime()
    val timeLimitMs: Long = level.timeLimitSec * 1000L
    private val penaltyPerMistake = scenario.text("penalty_${level.key}").toIntOrNull() ?: 20

    fun elapsedMs() = SystemClock.elapsedRealtime() - startedAt + _ui.value.penaltySec * 1000L
    fun remainingMs() = (timeLimitMs - elapsedMs()).coerceAtLeast(0)

    private val picks = Channel<String>(Channel.CONFLATED)
    private var scope: CoroutineScope? = null
    private val fx = Mutex() // 반격·기습 연출이 겹치지 않게
    private var lastProgress = SystemClock.elapsedRealtime()
    private var hintIndex = 0

    private fun now() = SystemClock.elapsedRealtime()

    // ───────────────────────── 화면에서 오는 조작 ─────────────────────────

    fun select(id: String) {
        val z = _ui.value.zones.firstOrNull { it.id == id } ?: return
        if (z.master && !_ui.value.masterOpen) return
        picks.trySend(id)
    }

    fun replay() = puzzles.replay()

    fun requestHint() {
        if (_ui.value.stage != Stage.PLAY) return
        if (level.hint == HintMode.NONE && level.maxHints == 0) return
        if (level.hint == HintMode.ASK && _ui.value.hintsUsed >= level.maxHints) {
            scope?.launch { voice.say(scenario.text("noHints")) }
            return
        }
        giveHint()
    }

    private fun giveHint() {
        val piece = currentPiece() ?: return
        val hints = piece.hints.get(levelKey).orEmpty()
        if (hints.isEmpty()) return
        val text = hints[hintIndex % hints.size]
        hintIndex++
        lastProgress = now()
        PuzzleEngine.testLog("hint|$text")
        _ui.update { u -> u.copy(hintsUsed = u.hintsUsed + 1, hintsLeft = u.hintsLeft?.let { maxOf(0, it - 1) }) }
        scope?.launch { voice.say("보안팀이다. $text") }
    }

    private fun currentPiece(): Piece? = _ui.value.selected?.let { id -> zonePieces.firstOrNull { it.id == id } ?: master.takeIf { it.id == id } }

    /** 틀렸을 때: 벌점 + NULL의 반격 */
    private fun onWrong() {
        _ui.update { it.copy(mistakes = it.mistakes + 1, penaltySec = it.penaltySec + penaltyPerMistake) }
        PuzzleEngine.testLog("wrong|penalty=$penaltyPerMistake")
        scope?.launch { attack(scenario.counterScene.get(levelKey), scenario.text("counterText")) }
    }

    private suspend fun attack(scene: com.parkingroom.app.scenario.Scene?, text: String?) {
        if (scene == null || fx.isLocked) return
        fx.withLock {
            _ui.update { it.copy(attackText = text) }
            sfx.play("glitch", volume = 0.7f)
            effects.play(scene)
            _ui.update { it.copy(attackText = null) }
        }
    }

    // ───────────────────────── 진행 ─────────────────────────

    suspend fun run(): GameEnd = coroutineScope {
        scope = this
        voice.startListening()
        launch { voice.heard.collect { if (VoiceManager.isHintRequest(it)) requestHint() } }
        if (level.hint == HintMode.AUTO) launch { autoHints() }
        var timeOver = false
        val play = async { play() }
        if (timeLimitMs > 0) launch {
            while (remainingMs() > 0) delay(250)
            timeOver = true
            play.cancel()
        }
        val end = try {
            play.await()
        } catch (e: CancellationException) {
            if (timeOver && isActive) failEnding() else throw e
        }
        coroutineContext.cancelChildren()
        end
    }

    private suspend fun autoHints() {
        while (true) {
            delay(1000)
            if (_ui.value.stage == Stage.PLAY && now() - lastProgress > level.hintAfterSec * 1000L) giveHint()
        }
    }

    private suspend fun play(): GameEnd = coroutineScope {
        // 1. 해킹 발생
        _ui.update { it.copy(stage = Stage.INTRO) }
        val intro = launch { scenario.introScene.get(levelKey)?.let { effects.play(it) } }
        delay(1200)
        scenario.intro.get(levelKey)?.let { voice.say(it, VoiceManager.VOICE_NULL) }
        intro.join()
        car.restoreAll()
        effects.resetMirror()
        _ui.update { it.copy(stage = Stage.PLAY) }
        scenario.introTeam.get(levelKey)?.let { voice.say(it) }

        // 2. NULL의 기습 (70~110초마다)
        val raids = launch {
            val rnd = Random(now())
            while (true) {
                delay(70_000L + rnd.nextLong(40_000))
                if (_ui.value.stage == Stage.PLAY && scenario.attacks.isNotEmpty()) {
                    val s = scenario.attacks[rnd.nextInt(scenario.attacks.size)]
                    attack(s, scenario.text("attackText"))
                }
            }
        }

        // 3. 보안 구역 탐색
        var current = zonePieces.first().id
        while (true) {
            val piece = zonePieces.firstOrNull { it.id == current } ?: master
            val isMaster = piece === master
            if (isMaster && !_ui.value.masterOpen) {
                current = nextUnsolved() ?: master.id
                continue
            }
            hintIndex = 0
            lastProgress = now()
            _ui.update { it.copy(selected = piece.id, taunt = piece.riddle.get(levelKey)) }
            val solved = _ui.value.zones.first { it.id == piece.id }.solved
            if (solved) {
                current = picks.receive()
                continue
            }
            piece.riddle.get(levelKey)?.let { launch { voice.say(it, VoiceManager.VOICE_NULL) } }
            val spec = piece.puzzle.get(levelKey) ?: break
            val ctx = PuzzleContext(_ui.value.digits.filterNotNull(), { lastProgress = now() }, ::onWrong)
            val job = async { puzzles.run(spec, ctx) }
            val switch = async { picks.receive() }
            var result: PuzzleResult? = null
            select {
                job.onAwait { r ->
                    switch.cancel()
                    result = r
                }
                switch.onAwait { id ->
                    job.cancel()
                    current = id
                }
            }
            val r = result ?: continue
            if (isMaster) break
            solveZone(piece, r.digit ?: 0)
            current = nextUnsolved() ?: master.id
        }

        // 4. 복구 엔딩
        raids.cancel()
        ending()
    }

    private fun nextUnsolved() = _ui.value.zones.firstOrNull { !it.master && !it.solved }?.id

    private suspend fun solveZone(piece: Piece, digit: Int) {
        _ui.update { u -> u.copy(zones = u.zones.map { if (it.id == piece.id) it.copy(solved = true, digit = digit) else it }) }
        PuzzleEngine.testLog("solved|${piece.id}|digit=$digit")
        sfx.play("success")
        effects.light(0xFF00E676.toInt(), 100)
        val left = _ui.value.zones.count { !it.master && !it.solved }
        voice.say(
            if (left == 0) scenario.text("allFound") else scenario.text("pieceFound", "digit" to digit, "left" to left),
        )
        car.restoreAll()
        effects.resetMirror()
    }

    private suspend fun ending(): GameEnd {
        _ui.update { it.copy(stage = Stage.ENDING) }
        coroutineScope {
            val s = launch { scenario.endingScene.get(levelKey)?.let { effects.play(it) } }
            delay(2500)
            scenario.endingLine.get(levelKey)?.let { voice.say(it) }
            s.join()
        }
        car.restoreAll()
        effects.resetMirror()
        voice.stopListening()
        val u = _ui.value
        val record = GameRecord(
            levelKey = level.key,
            levelLabel = level.label,
            coop = setup.coop,
            elapsedMs = elapsedMs(),
            hintsUsed = u.hintsUsed,
            rhythmAccuracy = null,
            penalties = u.mistakes,
            atMillis = System.currentTimeMillis(),
        )
        return GameEnd.Cleared(record, remainingMs())
    }

    private suspend fun failEnding(): GameEnd {
        _ui.update { it.copy(stage = Stage.FAILED) }
        coroutineScope {
            val s = launch { scenario.failScene.get(levelKey)?.let { effects.play(it) } }
            scenario.failLine.get(levelKey)?.let { voice.say(it, VoiceManager.VOICE_NULL) }
            s.join()
        }
        car.restoreAll()
        effects.resetMirror()
        voice.stopListening()
        return GameEnd.TimeOver
    }
}
