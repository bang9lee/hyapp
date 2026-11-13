package com.parkingroom.app.engine

import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import android.util.Log
import com.parkingroom.app.BuildConfig
import com.parkingroom.app.audio.SfxPlayer
import com.parkingroom.app.car.CarActuators
import com.parkingroom.app.car.CarInput
import com.parkingroom.app.car.CarSignals
import com.parkingroom.app.voice.KoreanNumber
import com.parkingroom.app.voice.VoiceManager
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** 패턴 자물쇠 입력 종류 */
enum class Signal { LEFT, RIGHT, BRAKE }

/** 자물쇠 화면 상태 */
sealed interface PuzzleUi {
    /** ① 색 신호: 무드등이 색별로 깜빡인 횟수를 센다 */
    data class Blink(val lamp: Int?, val playing: Boolean, val clue: String, val legend: List<Pair<String, Int>>, val feedback: String?) : PuzzleUi

    /** ② 온도 금고: 차의 공조를 목표 값에 맞춘다 */
    data class Climate(
        val targetTemp: Float, val targetFan: Int,
        val temp: Float?, val fan: Int?,
        val round: Int, val rounds: Int, val feedback: String?,
    ) : PuzzleUi

    /** ③ 창문 비밀번호: 막대 높이(0..10)대로 창문 4개를 연다 */
    data class Windows(val targets: List<Int>, val values: List<Int>, val tolerance: Int, val showBars: Boolean, val feedback: String?) : PuzzleUi

    /** ④ 패턴 자물쇠: 들은 신호 순서를 방향지시등·브레이크로 따라 한다 */
    data class Pattern(
        val sequence: List<Signal>, val input: List<Signal>, val playing: Int?,
        val listening: Boolean, val round: Int, val rounds: Int, val feedback: String?,
    ) : PuzzleUi

    /** 마스터 코드 */
    data class Code(val length: Int, val reverse: Boolean, val collected: List<Int>, val feedback: String?) : PuzzleUi
}

/** 성공 시 얻는 코드 조각 */
data class PuzzleResult(val digit: Int?)

class PuzzleContext(
    val digits: List<Int>,
    /** 진전이 있을 때마다 불러 힌트 타이머를 늦춘다 */
    val onProgress: () -> Unit,
    /** 틀렸을 때: NULL의 반격 + 벌점 */
    val onWrong: () -> Unit,
)

/**
 * 판정 엔진: 시나리오의 puzzle(JSON)을 받아 차량 입력·음성으로 자물쇠를 판정한다.
 * NULL의 방해(차를 직접 움직이는 연출)도 자물쇠마다 여기서 건다. 게임 세션과 같은 메인 스레드에서 돈다.
 */
class PuzzleEngine(
    private val signals: CarSignals,
    private val car: CarActuators,
    private val effects: EffectEngine,
    private val voice: VoiceManager,
    private val sfx: SfxPlayer,
) {
    private val _ui = MutableStateFlow<PuzzleUi?>(null)
    val ui: StateFlow<PuzzleUi?> = _ui.asStateFlow()

    private val typed = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private val replayReq = MutableSharedFlow<Unit>(extraBufferCapacity = 2)

    /** 음성 대신 화면 키패드로 입력 */
    fun submitTyped(text: String) = typed.tryEmit(text)

    /** 신호 다시 보기/듣기 */
    fun replay() = replayReq.tryEmit(Unit)

    private val rnd = Random(System.currentTimeMillis())

    suspend fun run(spec: JSONObject, ctx: PuzzleContext): PuzzleResult {
        try {
            return when (spec.optString("type")) {
                "blinkCode" -> blinkCode(spec, ctx)
                "climateLock" -> climateLock(spec, ctx)
                "windowCode" -> windowCode(spec, ctx)
                "patternLock" -> patternLock(spec, ctx)
                "masterCode" -> masterCode(spec, ctx)
                else -> PuzzleResult(null)
            }
        } finally {
            _ui.value = null
            effects.resetMirror()
        }
    }

    /** 음성·키패드로 들어온 숫자 답 (그만·힌트 말은 무시) */
    private fun answers() = merge(voice.heard, typed)

    private fun isCommand(text: String) = VoiceManager.isStop(text) || VoiceManager.isHintRequest(text)

    // ───────────────────────── ① 색 신호 ─────────────────────────

    private suspend fun blinkCode(o: JSONObject, ctx: PuzzleContext): PuzzleResult = coroutineScope {
        val n = o.optInt("colors", 3).coerceIn(2, PALETTE.size)
        val chosen = PALETTE.shuffled(rnd).take(n)
        val counts = (1..5).shuffled(rnd).take(n)
        val target = rnd.nextInt(n)
        val answer = counts[target]
        val clue = "NULL은 ${chosen[target].clue}을 싫어해. 그 색이 몇 번 깜빡였을까?"
        val legend = chosen.map { it.name to it.argb }
        var feedback: String? = null
        var lamp: Int? = null
        var playing = false
        fun show() {
            _ui.value = PuzzleUi.Blink(lamp, playing, clue, legend, feedback)
        }
        testLog("blink|answer=$answer|" + chosen.zip(counts).joinToString(",") { (c, k) -> "${c.name}:$k" })
        var player: Job? = null
        fun playSignal() {
            player?.cancel()
            player = launch {
                playing = true
                show()
                delay(600)
                for ((c, k) in chosen.zip(counts)) {
                    repeat(k) {
                        lamp = c.argb
                        show()
                        effects.light(c.argb, 100)
                        delay(320)
                        lamp = null
                        show()
                        effects.light(c.argb, 5)
                        delay(320)
                    }
                    delay(1100)
                }
                playing = false
                show()
            }
        }
        show()
        playSignal()
        val replays = launch { replayReq.collect { playSignal() } }
        answers().first { text ->
            if (isCommand(text)) return@first false
            val got = KoreanNumber.candidates(text)
            if (got.isEmpty()) return@first false
            val ok = answer.toString() in got
            if (!ok) {
                feedback = "\"$text\" — 틀렸어. NULL이 반격한다!"
                ctx.onWrong()
                show()
            }
            ok
        }
        replays.cancel()
        player?.cancel()
        PuzzleResult(answer)
    }

    // ───────────────────────── ② 온도 금고 ─────────────────────────

    private suspend fun climateLock(o: JSONObject, ctx: PuzzleContext): PuzzleResult {
        val rounds = o.optInt("rounds", 1)
        // NULL의 방해: 찬바람 최대
        car.hvacPower(true)
        car.fan(car.fanRange.last)
        car.temperature(car.tempRange.start)
        var feedback: String? = "NULL이 에어컨을 장악했어. 금고 다이얼에 맞춰 공조를 되돌려!"
        for (round in 1..rounds) {
            val tt = pickTemp(signals.hvacTemp.value)
            val tf = pickFan(signals.fanSpeed.value)
            testLog("climate|round=$round|temp=$tt|fan=$tf")
            combine(signals.hvacTemp, signals.fanSpeed) { t, f -> t to f }.first { (t, f) ->
                val tempOk = t != null && abs(t - tt) < 0.26f
                val fanOk = f == tf
                if (tempOk || fanOk) ctx.onProgress()
                _ui.value = PuzzleUi.Climate(tt, tf, t, f, round, rounds, feedback)
                tempOk && fanOk
            }
            sfx.play("success", volume = 0.6f)
            if (round < rounds) {
                voice.say(o.optString("twist", "흥, 그렇게 쉽게 줄 것 같아? 다시 바꿨지."), VoiceManager.VOICE_NULL)
                sfx.play("glitch", volume = 0.7f)
                car.temperature(pickTemp(tt))
                car.fan(pickFan(tf))
                feedback = "NULL이 다이얼을 다시 돌렸어!"
            }
        }
        return PuzzleResult(rnd.nextInt(10))
    }

    private fun pickTemp(avoid: Float?): Float {
        val options = (40..52).map { it / 2f }.filter { avoid == null || abs(it - avoid) > 1.4f }
        return options[rnd.nextInt(options.size)]
    }

    private fun pickFan(avoid: Int?): Int {
        val options = (1..5).filter { it != avoid }
        return options[rnd.nextInt(options.size)]
    }

    // ───────────────────────── ③ 창문 비밀번호 ─────────────────────────

    private val windowOrder = listOf(WindowValue.ROW_1_LEFT, WindowValue.ROW_1_RIGHT, WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT)

    private suspend fun windowCode(o: JSONObject, ctx: PuzzleContext): PuzzleResult = coroutineScope {
        val tol = o.optInt("tolerance", 0)
        val mode = o.optString("bars", "always")
        val targets = List(4) { 1 + rnd.nextInt(9) }
        testLog("windows|" + targets.joinToString(","))
        var showBars = true
        var feedback: String? = null
        fun values() = windowOrder.map { signals.windows.value[it] ?: 0 }
        fun show() {
            _ui.value = PuzzleUi.Windows(targets, values(), tol, showBars, feedback)
        }
        // NULL의 방해: 와이퍼가 신호처럼 튄다
        launch { car.wipeStutter() }
        val flicker = launch {
            when (mode) {
                "flicker" -> while (true) {
                    showBars = true; show(); delay(2500)
                    showBars = false; show(); delay(3500)
                }
                "flash" -> while (true) {
                    showBars = true; show(); delay(1000)
                    showBars = false; show(); delay(6000)
                }
                else -> show()
            }
        }
        val replays = launch {
            replayReq.collect {
                showBars = true
                show()
            }
        }
        signals.windows.first { m ->
            val v = windowOrder.map { m[it] ?: 0 }
            if (v.zip(targets).any { (a, b) -> abs(a - b) <= tol }) ctx.onProgress()
            show()
            v.zip(targets).all { (a, b) -> abs(a - b) <= tol }
        }
        flicker.cancel()
        replays.cancel()
        PuzzleResult(rnd.nextInt(10))
    }

    // ───────────────────────── ④ 패턴 자물쇠 ─────────────────────────

    private suspend fun patternLock(o: JSONObject, ctx: PuzzleContext): PuzzleResult = coroutineScope {
        val lengths = o.optJSONArray("rounds")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: listOf(3, 4)
        val brakeShare = o.optDouble("brake", 0.25)
        var feedback: String? = null
        for ((ri, len) in lengths.withIndex()) {
            val seq = List(len) { i ->
                if (i > 0 && rnd.nextDouble() < brakeShare) Signal.BRAKE else if (rnd.nextBoolean()) Signal.LEFT else Signal.RIGHT
            }
            testLog("pattern|round=${ri + 1}|" + seq.joinToString(","))
            var done = false
            while (!done) {
                // 듣기: NULL이 신호를 들려준다
                for (i in seq.indices) {
                    _ui.value = PuzzleUi.Pattern(seq, emptyList(), i, true, ri + 1, lengths.size, feedback)
                    sfx.play(soundOf(seq[i]), pan = panOf(seq[i]))
                    effects.light(colorOf(seq[i]), 100)
                    delay(650)
                    effects.light(colorOf(seq[i]), 5)
                    delay(300)
                }
                feedback = null
                val input = mutableListOf<Signal>()
                _ui.value = PuzzleUi.Pattern(seq, input.toList(), null, false, ri + 1, lengths.size, "따라 해 봐!")
                testLog("pattern-input|round=${ri + 1}")
                // 따라 하기: 12초 동안 입력이 없으면 다시 들려준다
                val result = withTimeoutOrNull(12_000) {
                    var ok = true
                    signals.inputs.first { inp ->
                        val s = when (inp) {
                            is CarInput.Turn -> if (inp.dir == TurnSignalValue.LEFT) Signal.LEFT else Signal.RIGHT
                            is CarInput.Brake -> Signal.BRAKE
                            else -> return@first false
                        }
                        input += s
                        ctx.onProgress()
                        if (s != seq[input.size - 1]) {
                            ok = false
                            true
                        } else {
                            _ui.value = PuzzleUi.Pattern(seq, input.toList(), null, false, ri + 1, lengths.size, "좋아!")
                            input.size == seq.size
                        }
                    }
                    ok
                }
                when (result) {
                    true -> done = true
                    false -> {
                        feedback = "순서가 틀렸어. 다시 들어 봐"
                        ctx.onWrong()
                        delay(1500)
                    }
                    null -> feedback = "다시 들려줄게"
                }
            }
            sfx.play("success", volume = 0.6f)
            delay(700)
        }
        PuzzleResult(rnd.nextInt(10))
    }

    private fun soundOf(s: Signal) = when (s) {
        Signal.LEFT -> "chirp_left"
        Signal.RIGHT -> "chime_right"
        Signal.BRAKE -> "note_brake"
    }

    private fun panOf(s: Signal) = when (s) {
        Signal.LEFT -> -0.8f
        Signal.RIGHT -> 0.8f
        Signal.BRAKE -> 0f
    }

    // ───────────────────────── 마스터 코드 ─────────────────────────

    private suspend fun masterCode(o: JSONObject, ctx: PuzzleContext): PuzzleResult {
        val reverse = o.optBoolean("reverse")
        val code = (if (reverse) ctx.digits.reversed() else ctx.digits).joinToString("")
        testLog("master|code=$code")
        var feedback: String? = null
        fun show() {
            _ui.value = PuzzleUi.Code(code.length, reverse, ctx.digits, feedback)
        }
        show()
        answers().first { text ->
            if (isCommand(text)) return@first false
            val got = KoreanNumber.candidates(text)
            if (got.isEmpty()) return@first false
            val ok = code in got
            if (!ok) {
                feedback = "코드 오류. NULL이 비웃고 있어"
                ctx.onWrong()
                show()
            }
            ok
        }
        return PuzzleResult(null)
    }

    private class Lamp(val name: String, val argb: Int, val clue: String)

    companion object {
        const val TEST_TAG = "PRGAME"

        /** 자동 테스트용 진행 로그 (정답이 들어 있어서 디버그 빌드에서만 남긴다) */
        fun testLog(msg: String) {
            if (BuildConfig.DEBUG) Log.i(TEST_TAG, msg)
        }

        private val PALETTE = listOf(
            Lamp("빨강", 0xFFFF1744.toInt(), "불꽃의 색"),
            Lamp("파랑", 0xFF2979FF.toInt(), "바다의 색"),
            Lamp("초록", 0xFF00E676.toInt(), "숲의 색"),
            Lamp("노랑", 0xFFFFD600.toInt(), "번개의 색"),
        )

        fun colorOf(s: Signal) = when (s) {
            Signal.LEFT -> 0xFF2979FF.toInt()
            Signal.RIGHT -> 0xFF7C4DFF.toInt()
            Signal.BRAKE -> 0xFFFF1744.toInt()
        }
    }
}
