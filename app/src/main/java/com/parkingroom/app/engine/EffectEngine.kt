package com.parkingroom.app.engine

import android.os.SystemClock
import com.parkingroom.app.audio.SfxPlayer
import com.parkingroom.app.car.CarActuators
import com.parkingroom.app.car.CarSignals
import com.parkingroom.app.scenario.Action
import com.parkingroom.app.scenario.Scenario
import com.parkingroom.app.scenario.Scene
import com.parkingroom.app.voice.VoiceManager
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 모드별 연출 강도 상한 */
data class EffectPolicy(
    val allowScary: Boolean,
    val maxVolume: Float,
) {
    /** 빛 깜빡임은 1초에 3번을 넘지 않는다 (빛에 민감한 사람 보호) */
    val minFlashPeriodMs = 334L

    /** 찬바람은 한 번에 10초를 넘지 않는다 */
    val maxColdMs = 10_000L

    companion object {
        val ADULT = EffectPolicy(allowScary = true, maxVolume = 1f)
        val GLITCH = EffectPolicy(allowScary = true, maxVolume = 0.8f)
        val FAMILY = EffectPolicy(allowScary = false, maxVolume = 0.6f)
    }
}

/** 화면에 비추는 무드등 상태 (차에서 색을 못 바꿔도 화면에서는 보여 준다) */
data class AmbientMirror(val color: Int, val brightness: Int)

/**
 * 연출 엔진: 시나리오의 scene(actions 타임라인)을 시간에 맞춰 차·소리·목소리로 재생한다.
 * 창문·문 제어는 의도적으로 지원하지 않는다.
 */
class EffectEngine(
    private val car: CarActuators,
    private val signals: CarSignals,
    private val sfx: SfxPlayer,
    private val voice: VoiceManager,
) {
    private val _mirror = MutableStateFlow(AmbientMirror(DARK, 0))
    private val _caption = MutableStateFlow<String?>(null)
    val mirror: StateFlow<AmbientMirror> = _mirror.asStateFlow()
    val caption: StateFlow<String?> = _caption.asStateFlow()

    @Volatile var policy = EffectPolicy.ADULT
        set(value) {
            field = value
            sfx.maxVolume = value.maxVolume
        }

    /** 대사 안의 {km}, {name} 같은 자리 */
    @Volatile var vars: Map<String, String> = emptyMap()

    private var hvacOn = false
    private var lastFan: Int? = null

    private fun now() = SystemClock.elapsedRealtime()

    fun fill(text: String) = vars.entries.fold(text) { s, (k, v) -> s.replace("{$k}", v) }

    suspend fun play(scene: Scene) = coroutineScope {
        val start = now()
        var shift = 0L
        val loops = mutableSetOf<String>()
        try {
            for (a in scene.actions) {
                val wait = start + a.at + shift - now()
                if (wait > 0) delay(wait)
                when {
                    a.op == "waitWindowsClosed" -> {
                        val t0 = now()
                        waitWindowsClosed(a)
                        shift += now() - t0
                    }
                    a.op == "say" && a.args.optBoolean("wait") -> {
                        val t0 = now()
                        voice.say(fill(a.args.optString("text")), voiceOf(a))
                        shift += now() - t0
                    }
                    else -> launch { perform(a, loops) }
                }
            }
            val end = start + scene.durationMs + shift - now()
            if (end > 0) delay(end)
            coroutineContext.cancelChildren()
        } finally {
            loops.forEach(sfx::stop)
            _caption.value = null
        }
    }

    private suspend fun perform(a: Action, loops: MutableSet<String>) {
        val o = a.args
        when (a.op) {
            "say" -> voice.say(fill(o.optString("text")), voiceOf(a))
            "caption" -> _caption.value = fill(o.optString("text"))
            "sfx" -> {
                if (o.optBoolean("scary") && !policy.allowScary) return
                val name = o.getString("name")
                val loop = o.optBoolean("loop")
                if (loop) loops += name
                sfx.play(name, loop = loop, volume = o.optDouble("volume", 1.0).toFloat(), pan = o.optDouble("pan", 0.0).toFloat())
            }
            "sfxStop" -> sfx.stop(o.getString("name"))
            "fan" -> setFan(o.getInt("level"))
            "fanWave" -> wave(o.optLong("periodMs", 5000), o.optLong("untilMs", 30_000) - a.at) { x ->
                val lv = (o.optInt("min", 2) + (o.optInt("max", 6) - o.optInt("min", 2)) * x).roundToInt()
                if (lv != lastFan) setFan(lv)
            }
            "temp" -> {
                ensureHvac()
                car.temperature(o.getDouble("celsius").toFloat())
            }
            "ambient" -> ambient(o.optString("color").ifBlank { null }, o.optInt("brightness", -1).takeIf { it >= 0 })
            "ambientWave" -> {
                o.optString("color").ifBlank { null }?.let { ambient(it, null) }
                wave(o.optLong("periodMs", 5000), o.optLong("untilMs", 30_000) - a.at) { x ->
                    val b = (o.optInt("min", 20) + (o.optInt("max", 100) - o.optInt("min", 20)) * x).roundToInt()
                    _mirror.value = _mirror.value.copy(brightness = b)
                    car.ambientBrightness(b)
                }
            }
            "flash" -> flash(o.optString("color", "#FFFFFF"), o.optInt("count", 1), o.optLong("periodMs", 500))
            "colorCycle" -> {
                val arr = o.getJSONArray("colors")
                val step = maxOf(o.optLong("stepMs", 700), policy.minFlashPeriodMs)
                val until = now() + o.optLong("untilMs", 6000) - a.at
                var i = 0
                while (now() < until) {
                    ambient(arr.getString(i % arr.length()), 100)
                    i++
                    delay(step)
                }
            }
            "wiperOnce" -> car.wipeOnce()
            "wiperStutter" -> car.wipeStutter()
            "steeringHeat" -> car.steeringHeat(o.getInt("level"))
            "coldBlast" -> {
                ensureHvac()
                val ms = o.optLong("durationMs", 6000).coerceAtMost(policy.maxColdMs)
                val prevFan = lastFan
                setFan(car.fanRange.last)
                car.temperature(car.tempRange.start)
                delay(ms)
                setFan(o.optInt("backFan", prevFan ?: 3))
                if (o.has("backTemp")) car.temperature(o.getDouble("backTemp").toFloat())
            }
            "restore" -> car.restoreAll()
        }
    }

    /** "voice":"null"이면 해커 NULL, 아니면 보안팀 목소리 */
    private fun voiceOf(a: Action) = if (a.args.optString("voice") == "null") VoiceManager.VOICE_NULL else VoiceManager.VOICE_TEAM

    /** 자물쇠 신호용: 무드등과 화면을 한 색으로 켠다 */
    suspend fun light(argb: Int, brightness: Int) {
        _mirror.value = AmbientMirror(argb, brightness)
        car.ambient(argb, brightness)
    }

    /** 0..1 사이를 sin 모양으로 오가며 block을 250ms마다 부른다 */
    private suspend fun wave(periodMs: Long, durationMs: Long, block: suspend (Float) -> Unit) {
        val t0 = now()
        while (now() - t0 < durationMs) {
            val x = ((sin(2 * PI * (now() - t0) / periodMs - PI / 2) + 1) / 2).toFloat()
            block(x)
            delay(250)
        }
    }

    private suspend fun ensureHvac() {
        if (hvacOn) return
        hvacOn = true
        car.hvacPower(true)
    }

    private suspend fun setFan(level: Int) {
        ensureHvac()
        lastFan = level
        car.fan(level)
    }

    /**
     * 무드등 색. 이 차가 색을 못 바꾸면 시나리오 규칙대로 "밝기와 소리"로 대신한다.
     */
    private suspend fun ambient(color: String?, brightness: Int?) {
        val argb = color?.let(Scenario::parseColor)
        _mirror.value = AmbientMirror(argb ?: _mirror.value.color, brightness ?: maxOf(_mirror.value.brightness, 60))
        val colorOk = car.ambient(argb, brightness)
        if (argb != null && !colorOk) {
            car.ambientBrightness(100)
            sfx.play("tick", volume = 0.6f)
        }
    }

    private suspend fun flash(color: String, count: Int, periodMs: Long) {
        val period = maxOf(periodMs, policy.minFlashPeriodMs)
        val base = _mirror.value
        repeat(count.coerceAtMost(12)) {
            ambient(color, 100)
            delay(period / 2)
            _mirror.value = base
            car.ambientBrightness(base.brightness.coerceAtLeast(10))
            delay(period / 2)
        }
    }

    private suspend fun waitWindowsClosed(a: Action) {
        val prompt = a.args.optString("text")
        if (prompt.isNotBlank()) voice.say(fill(prompt))
        withTimeoutOrNull(a.args.optLong("timeoutMs", 30_000)) {
            signals.windows.first { m -> m.values.all { it <= 0 } }
        }
    }

    /** 장면이 끝나거나 중단될 때 화면 표시를 기본으로 */
    fun resetMirror() {
        _mirror.value = AmbientMirror(DARK, 0)
        _caption.value = null
        hvacOn = false
        lastFan = null
    }

    fun setMirror(color: Int, brightness: Int) {
        _mirror.value = AmbientMirror(color, brightness)
    }

    companion object {
        const val DARK = 0xFF0B1020.toInt()
    }
}
