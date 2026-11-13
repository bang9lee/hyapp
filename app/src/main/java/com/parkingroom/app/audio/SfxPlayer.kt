package com.parkingroom.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 효과음 재생.
 * assets/sfx/<이름>.ogg|.mp3|.wav 가 있으면 그 파일을, 없으면 SfxSynth 임시 소리를 쓴다.
 * maxVolume: 모드별 음량 상한 (가족 모드·글리치 깜짝 효과 보호)
 */
class SfxPlayer(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cache = ConcurrentHashMap<String, ShortArray>()
    private val playing = ConcurrentHashMap<String, MutableList<Stoppable>>()
    private val assetFiles: Set<String> = runCatching { context.assets.list("sfx")?.toSet() }.getOrNull().orEmpty()

    @Volatile var maxVolume = 1f

    @Volatile var userVolume = 1f

    /** 오디오 포커스 상태에 따른 배율 (오디오 포커스 개발 가이드: 상실 시 정지, 감쇄 요청 시 duck) */
    @Volatile private var focusFactor = 1f

    private val audio = context.getSystemService(android.media.AudioManager::class.java)
    private val focusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                android.media.AudioManager.AUDIOFOCUS_GAIN -> focusFactor = 1f
                android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> focusFactor = 0.3f
                android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    focusFactor = 0f
                    stopAll()
                }
                android.media.AudioManager.AUDIOFOCUS_LOSS -> {
                    focusFactor = 0f
                    stopAll()
                }
            }
        }
        .build()

    /** 게임 시작 전에 포커스를 요청한다 */
    fun requestFocus() {
        val r = runCatching { audio.requestAudioFocus(focusRequest) }.getOrNull()
        focusFactor = if (r == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) 1f else 0.3f
    }

    fun abandonFocus() {
        runCatching { audio.abandonAudioFocusRequest(focusRequest) }
        focusFactor = 1f
    }

    private interface Stoppable {
        fun stop()
    }

    /** 합성음을 미리 만들어 두어 첫 재생이 늦지 않게 한다 */
    fun preload(names: Collection<String>) {
        scope.launch { names.forEach { synth(it) } }
    }

    private fun synth(name: String): ShortArray? = cache[name] ?: SfxSynth.render(name)?.also { cache[name] = it }

    private fun assetFor(name: String) = listOf("ogg", "mp3", "wav").map { "$name.$it" }.firstOrNull { it in assetFiles }

    /** pan: -1(왼쪽)..1(오른쪽). volume은 0..1, 상한이 적용된다 */
    fun play(name: String, loop: Boolean = false, volume: Float = 1f, pan: Float = 0f) {
        val v = (volume * userVolume).coerceAtMost(maxVolume).coerceIn(0f, 1f) * focusFactor
        if (v <= 0f) return
        val l = v * minOf(1f, 1f - pan)
        val r = v * minOf(1f, 1f + pan)
        val file = assetFor(name)
        if (file != null) playFile(name, file, loop, l, r) else scope.launch { playSynth(name, loop, l, r) }
    }

    private fun playFile(name: String, file: String, loop: Boolean, l: Float, r: Float) {
        runCatching {
            val mp = MediaPlayer()
            context.assets.openFd("sfx/$file").use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            mp.setAudioAttributes(attrs)
            mp.isLooping = loop
            mp.setVolume(l, r)
            mp.prepare()
            val handle = object : Stoppable {
                override fun stop() {
                    runCatching { mp.stop(); mp.release() }
                }
            }
            track(name, handle)
            mp.setOnCompletionListener { if (!loop) untrack(name, handle).also { handle.stop() } }
            mp.start()
        }.onFailure { Log.w(TAG, "play file $file: ${it.message}") }
    }

    @Suppress("DEPRECATION")
    private suspend fun playSynth(name: String, loop: Boolean, l: Float, r: Float) {
        val pcm = synth(name) ?: run {
            Log.w(TAG, "unknown sfx $name")
            return
        }
        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SfxSynth.RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
        }.getOrElse {
            Log.w(TAG, "track: ${it.message}")
            return
        }
        t.write(pcm, 0, pcm.size)
        if (loop) t.setLoopPoints(0, pcm.size / 2, -1)
        t.setStereoVolume(l, r)
        val handle = object : Stoppable {
            override fun stop() {
                runCatching { t.stop(); t.release() }
            }
        }
        track(name, handle)
        t.play()
        if (!loop) {
            delay(pcm.size / 2 * 1000L / SfxSynth.RATE + 200)
            if (untrack(name, handle)) handle.stop()
        }
    }

    private fun track(name: String, h: Stoppable) {
        playing.getOrPut(name) { mutableListOf() }.let { synchronized(it) { it += h } }
    }

    private fun untrack(name: String, h: Stoppable): Boolean =
        playing[name]?.let { synchronized(it) { it.remove(h) } } ?: false

    fun stop(name: String) {
        playing.remove(name)?.let { list -> synchronized(list) { list.forEach { it.stop() } } }
    }

    fun stopAll() {
        playing.keys.toList().forEach(::stop)
    }

    companion object {
        private const val TAG = "SfxPlayer"
        private val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        /** 시나리오에서 쓰는 효과음 이름 (docs/효과음_목록.md와 같은 목록) */
        val ALL = listOf(
            "wave", "wave_reverse", "rain", "rain_left", "thunder", "heartbeat", "heartbeat_fake", "engine_start",
            "snow_step", "boot", "glitch", "tick", "note_brake", "chirp_left", "chime_right", "jewel", "celebrate",
            "success", "fail", "wind", "stereo_test",
        )
    }
}
