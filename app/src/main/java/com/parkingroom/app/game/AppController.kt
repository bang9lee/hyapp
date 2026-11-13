package com.parkingroom.app.game

import android.content.Context
import android.util.Log
import com.parkingroom.app.audio.SfxPlayer
import com.parkingroom.app.car.CarHub
import com.parkingroom.app.check.CarPropProbe
import com.parkingroom.app.check.CheckLog
import com.parkingroom.app.check.VehicleProbe
import com.parkingroom.app.data.RecordStore
import com.parkingroom.app.engine.EffectEngine
import com.parkingroom.app.engine.EffectPolicy
import com.parkingroom.app.engine.PuzzleEngine
import com.parkingroom.app.scenario.Scenario
import com.parkingroom.app.voice.VoiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data class Safety(val setup: GameSetup) : Screen
    data object Game : Screen
    data class Result(val setup: GameSetup, val end: GameEnd) : Screen
    data class Stopped(val reason: String, val windowsOpen: Boolean, val restored: Boolean) : Screen
    data object Ranking : Screen
    data object Settings : Screen
    data object DevCheck : Screen
}

/**
 * 앱 전체 흐름. 안전 감시·"그만"·중단 버튼·앱 이탈은 모두 abort()로 모여
 * 게임을 즉시 멈추고 앱이 바꾼 값을 되돌린다.
 */
class AppController(private val context: Context) {

    // 화면(Activity)이 다시 만들어져도 게임과 복구가 끊기지 않도록 앱 수명과 같은 scope를 쓴다
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val hub = CarHub(context, scope)
    val voice = VoiceManager(context)
    val sfx = SfxPlayer(context).also { it.preload(SfxPlayer.ALL) }
    val store = RecordStore(context)
    val effects = EffectEngine(hub.actuators, hub.signals, sfx, voice)
    val puzzles = PuzzleEngine(hub.signals, hub.actuators, effects, voice, sfx)

    val scenario: Scenario by lazy { Scenario.load(context, "hacked_car.json") }

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    private val _session = MutableStateFlow<GameSession?>(null)
    val screen: StateFlow<Screen> = _screen.asStateFlow()
    val session: StateFlow<GameSession?> = _session.asStateFlow()

    private var gameJob: Job? = null
    private val endLock = Mutex()

    fun go(s: Screen) {
        if (gameJob == null) _screen.value = s
    }

    fun openSafety(setup: GameSetup) = go(Screen.Safety(setup))

    fun start(setup: GameSetup) {
        if (gameJob != null || !hub.safety.status.value.safe) return
        effects.policy = EffectPolicy.ADULT
        sfx.userVolume = store.settings.value.sfxVolume
        sfx.requestFocus()
        gameJob = scope.launch {
            val s = GameSession(scenario, setup, hub.signals, hub.actuators, effects, puzzles, voice, sfx)
            _session.value = s
            _screen.value = Screen.Game
            // 감시: 주차 브레이크 해제·기어 변경·움직임·주행 규제 / "그만"
            val watchers = launch {
                launch {
                    s.ui.map { it.stage to it.selected }.distinctUntilChanged().collect { (stage, id) ->
                        PuzzleEngine.testLog("stage|$stage|$id")
                    }
                }
                launch {
                    hub.safety.status.collect { st -> if (!st.safe) abort(st.violation ?: "차 상태를 확인할 수 없어요") }
                }
                launch {
                    voice.heard.collect { if (VoiceManager.isStop(it)) abort("\"그만\"이라고 해서 멈췄어요") }
                }
            }
            val end = s.run()
            watchers.cancel()
            endLock.withLock {
                if (gameJob == null) return@launch
                gameJob = null
                cleanup()
                (end as? GameEnd.Cleared)?.record?.let(store::add)
                PuzzleEngine.testLog("end|$end")
                _session.value = null
                _screen.value = Screen.Result(setup, end)
                remindWindows()
            }
        }
    }

    /** 즉시 중단 + 원상복구 */
    fun abort(reason: String) {
        scope.launch {
            endLock.withLock {
                val j = gameJob ?: return@launch
                gameJob = null
                voice.stopSpeaking()
                sfx.stopAll()
                j.cancelAndJoin()
                val restored = cleanup()
                PuzzleEngine.testLog("abort|$reason|restored=$restored")
                _session.value = null
                _screen.value = Screen.Stopped(reason, windowsOpen(), restored)
                remindWindows()
            }
        }
    }

    private suspend fun cleanup(): Boolean = withContext(NonCancellable) {
        val changed = hub.actuators.hasChanges
        sfx.stopAll()
        sfx.abandonFocus()
        voice.stopSpeaking()
        voice.stopListening()
        hub.actuators.restoreAll()
        effects.resetMirror()
        changed
    }

    private fun windowsOpen() = hub.signals.windows.value.values.any { it > 0 }

    /** 앱은 창문을 닫지 않는다. 열려 있으면 음성으로만 알린다 (손·목 끼임 방지) */
    private fun remindWindows() {
        if (windowsOpen()) scope.launch { voice.say("창문을 닫아주세요.") }
    }

    /** 앱이 화면에서 사라지면 연출을 남겨 두지 않는다 */
    fun onAppHidden() {
        if (gameJob != null) abort("앱 화면을 벗어나서 멈췄어요")
    }

    fun onSpeedPermissionGranted() = hub.signals.startAaos()

    // ───────────────────────── 개발자 메뉴: 1단계 차량 기능 확인 ─────────────────────────

    private val probe by lazy { VehicleProbe(hub.vehicle) }
    private val carProbe by lazy { CarPropProbe(context) }
    private val devLock = Mutex()
    private var devHeard: Job? = null

    fun devCommand(command: String) {
        CheckLog.log("command", command)
        when (command) {
            "tts" -> scope.launch {
                voice.say("보안팀이다. 들리면 응답해.")
                voice.say("접속 완료. 이 차는 이제 내 거야.", VoiceManager.VOICE_NULL)
            }
            "stt" -> {
                voice.startListening()
                devHeard?.cancel()
                devHeard = scope.launch { voice.heard.collect { CheckLog.log("stt", "final", it) } }
            }
            "sttStop" -> {
                voice.stopListening()
                devHeard?.cancel()
            }
            "stereo" -> sfx.play("stereo_test")
            "loc" -> CheckLog.log("location", "unused", "이 버전은 위치를 쓰지 않아요")
            "aaos" -> carProbe.start()
            else -> scope.launch {
                devLock.withLock {
                    when (command) {
                        "caps" -> probe.checkCapabilities()
                        "read" -> probe.readAll()
                        "listen" -> probe.registerAll()
                        "write" -> probe.writeTests()
                        "all" -> {
                            carProbe.start()
                            probe.checkCapabilities()
                            probe.readAll()
                            probe.registerAll()
                        }
                    }
                    CheckLog.log("command", "$command-done")
                }
            }
        }
    }

    fun leaveDev() {
        probe.unregisterAll()
        carProbe.stop()
        voice.stopListening()
        devHeard?.cancel()
        go(Screen.Settings)
    }

    fun release() {
        scope.launch {
            gameJob?.cancelAndJoin()
            hub.actuators.restoreAll()
            voice.release()
            hub.release()
            scope.cancel()
        }
    }
}
