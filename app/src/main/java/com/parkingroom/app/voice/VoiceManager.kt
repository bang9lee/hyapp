package com.parkingroom.app.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

/**
 * 차 목소리(TTS)와 음성 명령(STT).
 * - 서버 등록 없이 동작하도록 ON_DEVICE 모드를 쓴다.
 * - 차가 말하는 동안에는 인식을 멈춘다 (스피커 소리를 "그만"으로 잘못 듣지 않게).
 */
class VoiceManager(context: Context) {

    private val tts = TextToSpeech(context, TtsMode.ON_DEVICE)
    private val stt = SpeechToText(context, SttMode.ON_DEVICE)
    private val main = Handler(Looper.getMainLooper())
    private val speakLock = Mutex()

    private var ttsReady = false
    private var sttReady = false
    private var speakDone: CompletableDeferred<Unit>? = null

    @Volatile private var wantListening = false
    @Volatile private var recognizing = false
    @Volatile private var speaking = false

    private val _subtitle = MutableStateFlow<String?>(null)
    private val _partial = MutableStateFlow<String?>(null)
    private val _heard = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private val _listening = MutableStateFlow(false)

    /** 지금 차가 말하고 있는 문장 (화면 자막) */
    val subtitle: StateFlow<String?> = _subtitle.asStateFlow()

    /** 인식 중간 결과 */
    val partial: StateFlow<String?> = _partial.asStateFlow()

    /** 인식 최종 결과 */
    val heard: SharedFlow<String> = _heard.asSharedFlow()
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val ttsEvents = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() = finishSpeak()
        override fun onStop() = finishSpeak()
        override fun onError(errorType: TtsError) {
            Log.w(TAG, "tts error $errorType")
            finishSpeak()
        }
    }

    private val sttEvents = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() {
            recognizing = true
        }

        override fun onUpdated(stt: String, completed: Boolean) {
            if (completed) {
                _partial.value = null
                Log.i("PRGAME", "heard|$stt")
                if (stt.isNotBlank()) _heard.tryEmit(stt)
            } else {
                _partial.value = stt
            }
        }

        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() {
            recognizing = false
            scheduleListen(300)
        }

        override fun onError(errorType: SttError) {
            Log.w(TAG, "stt error $errorType")
            recognizing = false
            scheduleListen(1200)
        }
    }

    init {
        // 리스너 등록은 initialize 성공 이후에만 가능하다 (먼저 부르면 SDK 내부에서 크래시)
        runCatching {
            tts.initialize(object : TtsInit {
                override fun onSuccess() {
                    ttsReady = true
                    tts.addEventListener(ttsEvents)
                    tts.setLocale(Locale.KOREA)
                }

                override fun onFailure(errorType: TtsError) {
                    Log.w(TAG, "tts init $errorType")
                }
            })
            stt.initialize(object : SttInit {
                override fun onSuccess() {
                    sttReady = true
                    stt.setLocale(Locale.KOREA)
                    stt.addListener(sttEvents)
                    scheduleListen(0)
                }

                override fun onFailure(errorType: SttError) {
                    Log.w(TAG, "stt init $errorType")
                }
            })
        }.onFailure { Log.w(TAG, "voice init: ${it.message}") }
    }

    private fun finishSpeak() {
        speakDone?.complete(Unit)
    }

    private var currentVoice: String? = null

    /** 지금 말하는 사람 (화면에서 NULL/보안팀 구분 표시) */
    private val _speaker = MutableStateFlow<String?>(null)
    val speaker: StateFlow<String?> = _speaker.asStateFlow()

    /** 말하고 끝날 때까지 기다린다. 여러 곳에서 불러도 차례대로 말한다. voice: TTS 화자 */
    suspend fun say(text: String, voice: String = VOICE_TEAM) {
        if (text.isBlank()) return
        speakLock.withLock {
            if (ttsReady && voice != currentVoice) {
                runCatching { tts.setSpeakerType(voice) }
                currentVoice = voice
            }
            _speaker.value = voice
            _subtitle.value = text
            if (!ttsReady) {
                // TTS를 못 쓰면 자막만 보여 주고 읽는 시간만큼 기다린다
                withTimeoutOrNull(readingMs(text)) { CompletableDeferred<Unit>().await() }
                _subtitle.value = null
                return
            }
            pauseRecognition()
            val done = CompletableDeferred<Unit>()
            speakDone = done
            speaking = true
            try {
                tts.speak(text)
                withTimeoutOrNull(readingMs(text) + 4000) { done.await() }
            } finally {
                speaking = false
                speakDone = null
                _subtitle.value = null
                _speaker.value = null
                scheduleListen(250)
            }
        }
    }

    private fun readingMs(text: String) = 1200L + text.length * 140L

    fun stopSpeaking() {
        if (ttsReady) runCatching { tts.stop() }
        finishSpeak()
    }

    /** 게임 중에는 계속 듣는다 ("그만"·정답·힌트) */
    fun startListening() {
        wantListening = true
        _listening.value = true
        scheduleListen(0)
    }

    fun stopListening() {
        wantListening = false
        _listening.value = false
        pauseRecognition()
    }

    private fun pauseRecognition() {
        main.removeCallbacksAndMessages(LISTEN_TOKEN)
        if (sttReady && recognizing) runCatching { stt.stop() }
        recognizing = false
    }

    private fun scheduleListen(delayMs: Long) {
        main.removeCallbacksAndMessages(LISTEN_TOKEN)
        main.postAtTime({
            if (wantListening && sttReady && !speaking && !recognizing) {
                runCatching {
                    stt.request()
                    recognizing = true
                }.onFailure { Log.w(TAG, "stt request: ${it.message}") }
            }
        }, LISTEN_TOKEN, android.os.SystemClock.uptimeMillis() + delayMs)
    }

    fun release() {
        stopListening()
        if (ttsReady) runCatching { tts.removeEventListener(ttsEvents); tts.release() }
        if (sttReady) runCatching { stt.removeListener(sttEvents); stt.release() }
    }

    companion object {
        /** 해커 NULL과 보안팀은 다른 목소리로 말한다 (TTS setSpeakerType 한국어 화자) */
        const val VOICE_NULL = "sanghyeok"
        const val VOICE_TEAM = "seoyoon"
        private const val TAG = "VoiceManager"
        private val LISTEN_TOKEN = Any()

        private fun norm(s: String) = s.replace(Regex("[\\s.,!?~…]"), "")

        /**
         * "그만"(멈춰·스톱)이라고 짧게 말했을 때만 종료.
         * 효과음이 마이크로 들어가 긴 문장으로 잘못 인식될 때 안의 글자만 보고 멈추지 않도록 길이를 제한한다.
         */
        fun isStop(text: String) = norm(text).let { t -> t.length <= 8 && STOP_WORDS.any { it in t } }

        fun isHintRequest(text: String) = norm(text).let { "힌트" in it || "도와줘" in it }

        fun containsAny(text: String, words: List<String>) = norm(text).let { t -> words.any { norm(it) in t } }

        private val STOP_WORDS = listOf("그만", "멈춰", "스톱", "스탑")
    }
}
