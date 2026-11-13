package com.parkingroom.app.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 임시 효과음 합성기. assets/sfx/에 실제 음원이 없을 때만 쓰인다.
 * 결과는 16bit 스테레오 PCM (L,R 교차).
 */
object SfxSynth {
    const val RATE = 22050

    fun render(name: String): ShortArray? = when (name) {
        "wave" -> wave(reverse = false)
        "wave_reverse" -> wave(reverse = true)
        "rain" -> rain(heavyFrom = 0.6f)
        "rain_left" -> rain(heavyFrom = 0.6f, pan = -0.9f)
        "thunder" -> thunder()
        "heartbeat" -> heartbeat(freq = 55.0, gain = 1f)
        "heartbeat_fake" -> heartbeat(freq = 140.0, gain = 0.55f)
        "engine_start" -> engine()
        "snow_step" -> snowSteps()
        "boot" -> boot()
        "glitch" -> glitch()
        "tick" -> tone(1320.0, 0.06f, decay = 60.0)
        "note_brake" -> heartbeat(freq = 70.0, gain = 0.9f)
        "chirp_left" -> pan(chirp(), -0.9f)
        "chime_right" -> pan(chime(), 0.9f)
        "jewel" -> arpeggio(listOf(1047.0, 1319.0, 1568.0, 2093.0), 0.09f)
        "celebrate" -> arpeggio(listOf(523.0, 659.0, 784.0, 1047.0, 784.0, 1047.0, 1319.0), 0.14f)
        "success" -> arpeggio(listOf(660.0, 880.0, 1320.0), 0.12f)
        "fail" -> tone(110.0, 0.5f, decay = 4.0, square = true)
        "wind" -> wind()
        "stereo_test" -> stereoTest()
        else -> null
    }

    private fun frames(sec: Float) = (sec * RATE).toInt()

    private fun stereo(n: Int, f: (i: Int) -> Pair<Float, Float>): ShortArray {
        val out = ShortArray(n * 2)
        for (i in 0 until n) {
            val (l, r) = f(i)
            out[i * 2] = (l.coerceIn(-1f, 1f) * 30000).toInt().toShort()
            out[i * 2 + 1] = (r.coerceIn(-1f, 1f) * 30000).toInt().toShort()
        }
        return out
    }

    private fun pan(src: ShortArray, p: Float): ShortArray {
        val l = min(1f, 1f - p)
        val r = min(1f, 1f + p)
        return ShortArray(src.size) { i -> (src[i] * if (i % 2 == 0) l else r).toInt().toShort() }
    }

    /** 파도: 5초 주기로 밀려오며 왼쪽 → 오른쪽으로 이동 (10초 루프) */
    private fun wave(reverse: Boolean): ShortArray {
        val n = frames(10f)
        val rnd = Random(7)
        var brown = 0f
        val raw = FloatArray(n) {
            brown = (brown + (rnd.nextFloat() * 2 - 1) * 0.06f) * 0.995f
            brown
        }
        val out = stereo(n) { i ->
            val t = i.toFloat() / RATE
            val phase = (t % 5f) / 5f
            val env = (sin(PI * phase).toFloat()).let { it * it } * 0.9f + 0.05f
            val p = phase * 2 - 1 // -1(왼쪽) → +1(오른쪽)
            val s = raw[i] * 3.2f * env
            s * min(1f, 1f - p) to s * min(1f, 1f + p)
        }
        if (!reverse) return out
        val rev = ShortArray(out.size)
        for (i in 0 until n) {
            rev[i * 2] = out[(n - 1 - i) * 2]
            rev[i * 2 + 1] = out[(n - 1 - i) * 2 + 1]
        }
        return rev
    }

    /** 빗소리: 점점 굵어지는 빗방울 (8초 루프) */
    private fun rain(heavyFrom: Float, pan: Float = 0f): ShortArray {
        val n = frames(8f)
        val rnd = Random(11)
        var lp = 0f
        val drops = FloatArray(n)
        repeat(1400) {
            val at = rnd.nextInt(n - 400)
            val amp = 0.15f + rnd.nextFloat() * 0.5f
            for (k in 0 until 300) drops[at + k] += amp * exp(-k / 40f) * (rnd.nextFloat() * 2 - 1)
        }
        return stereo(n) { i ->
            val t = i.toFloat() / n
            lp += ((rnd.nextFloat() * 2 - 1) - lp) * 0.35f
            val heavy = if (t > heavyFrom) 1f else 0.55f + t
            val s = (lp * 0.25f + drops[i]) * heavy * 0.8f
            s * min(1f, 1f - pan) to s * min(1f, 1f + pan)
        }
    }

    private fun thunder(): ShortArray {
        val n = frames(3.5f)
        val rnd = Random(3)
        var brown = 0f
        return stereo(n) { i ->
            val t = i.toFloat() / RATE
            brown = (brown + (rnd.nextFloat() * 2 - 1) * 0.08f) * 0.997f
            val crack = if (t < 0.15f) (rnd.nextFloat() * 2 - 1) * (1 - t / 0.15f) * 0.7f else 0f
            val s = brown * 4f * exp(-t * 1.1f) + crack
            s to s
        }
    }

    /** 심장 박동 한 번 (쿵-쿵) */
    private fun heartbeat(freq: Double, gain: Float): ShortArray {
        val n = frames(0.45f)
        return stereo(n) { i ->
            val t = i.toDouble() / RATE
            val a = if (t < 0.18) exp(-t * 28) else 0.0
            val b = if (t >= 0.2) exp(-(t - 0.2) * 30) * 0.75 else 0.0
            val s = (sin(2 * PI * freq * t) * (a + b) * gain).toFloat()
            s to s
        }
    }

    private fun engine(): ShortArray {
        val n = frames(3f)
        val rnd = Random(5)
        return stereo(n) { i ->
            val t = i.toFloat() / RATE
            val f = 30 + 50 * min(1f, t / 1.2f)
            val crank = if (t < 1f) sin(2 * PI * 9 * t).toFloat().let { max(0f, it) } else 1f
            val s = (sin(2 * PI * f * t).toFloat() * 0.5f + (rnd.nextFloat() * 2 - 1) * 0.12f) * crank * min(1f, (3f - t) * 2)
            s to s
        }
    }

    private fun snowSteps(): ShortArray {
        val n = frames(4f)
        val rnd = Random(9)
        val out = FloatArray(n)
        for (step in 0 until 6) {
            val at = (step * 0.65f * RATE).toInt() + 2000
            for (k in 0 until frames(0.22f)) {
                if (at + k >= n) break
                val env = exp(-k / (RATE * 0.06f))
                out[at + k] += (rnd.nextFloat() * 2 - 1) * env * (if (rnd.nextInt(6) == 0) 1f else 0.4f)
            }
        }
        return stereo(n) { i -> out[i] * 0.8f to out[i] * 0.8f }
    }

    private fun boot(): ShortArray {
        val notes = listOf(392.0, 523.0, 659.0, 1047.0)
        return arpeggio(notes, 0.18f, glitchy = true)
    }

    private fun glitch(): ShortArray {
        val n = frames(1.2f)
        val rnd = Random(13)
        var hold = 0f
        return stereo(n) { i ->
            if (i % (60 + (i / 500) % 7 * 40) == 0) hold = (rnd.nextFloat() * 2 - 1) * 0.6f
            val gate = if ((i / 1800) % 3 == 1) 0.15f else 1f
            hold * gate to hold * gate * 0.8f
        }
    }

    private fun wind(): ShortArray {
        val n = frames(6f)
        val rnd = Random(21)
        var lp = 0f
        return stereo(n) { i ->
            val t = i.toFloat() / RATE
            lp += ((rnd.nextFloat() * 2 - 1) - lp) * 0.05f
            val env = 0.6f + 0.4f * sin(2 * PI * t / 3).toFloat()
            val s = lp * 2.5f * env
            s to s
        }
    }

    private fun tone(freq: Double, sec: Float, decay: Double, square: Boolean = false): ShortArray {
        val n = frames(sec)
        return stereo(n) { i ->
            val t = i.toDouble() / RATE
            var s = sin(2 * PI * freq * t)
            if (square) s = if (s >= 0) 0.6 else -0.6
            val v = (s * exp(-t * decay) * 0.8).toFloat()
            v to v
        }
    }

    /** 보로롱: 아래에서 위로 떨리며 올라가는 소리 (왼쪽 신호) */
    private fun chirp(): ShortArray {
        val n = frames(0.7f)
        return stereo(n) { i ->
            val t = i.toDouble() / RATE
            val f = 500 + 900 * t + 60 * sin(2 * PI * 18 * t)
            val v = (sin(2 * PI * f * t) * exp(-t * 2.5) * 0.8).toFloat()
            v to v
        }
    }

    /** 딩동: 두 음 차임 (오른쪽 신호) */
    private fun chime(): ShortArray {
        val n = frames(0.9f)
        return stereo(n) { i ->
            val t = i.toDouble() / RATE
            val f = if (t < 0.35) 1319.0 else 1047.0
            val tt = if (t < 0.35) t else t - 0.35
            val v = (sin(2 * PI * f * t) * exp(-tt * 5) * 0.7).toFloat()
            v to v
        }
    }

    private fun arpeggio(freqs: List<Double>, step: Float, glitchy: Boolean = false): ShortArray {
        val per = frames(step)
        val n = per * freqs.size + frames(0.4f)
        val rnd = Random(1)
        return stereo(n) { i ->
            var v = 0.0
            for ((k, f) in freqs.withIndex()) {
                val start = k * per
                if (i >= start) {
                    val t = (i - start).toDouble() / RATE
                    v += sin(2 * PI * f * t) * exp(-t * 6) * 0.35
                }
            }
            if (glitchy && rnd.nextInt(900) == 0) v += 0.4
            v.toFloat() to v.toFloat()
        }
    }

    private fun stereoTest(): ShortArray {
        val n = frames(2f)
        return stereo(n) { i ->
            val s = (sin(2 * PI * 440 * i / RATE) * 0.4).toFloat()
            if (i < n / 2) s to 0f else 0f to s
        }
    }
}
