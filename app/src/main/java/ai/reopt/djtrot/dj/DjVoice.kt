package ai.reopt.djtrot.dj

import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.tts.listener.OnServerConnectionListener
import ai.reopt.djtrot.BuildConfig
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ai.pleos.playground.tts.TextToSpeech as PleosTextToSpeech

/**
 * DJ 목소리(기기 TTS). 말할 수 없는 환경이면 조용히 넘어가고 화면에 멘트만 보인다.
 * 🔑 마지막 수단이다 — DJ의 말은 서버가 만든 음성(브리프의 조각·받아 둔 음성 팩)으로 먼저 내고,
 *    그 음성이 하나도 없을 때만 이 목소리로 읽는다(재생 서비스의 `talk()`).
 */
interface DjVoice {
    val engine: String

    /** 지금 소리 내어 읽는 중. 재생 서비스가 이 동안 음악을 줄인다. */
    val speaking: StateFlow<Boolean>
    fun speak(text: String)
    fun stop()
    fun release()
}

/**
 * 🔑 Pleos TTS를 먼저 쓰고, 초기화나 엔진이 실패하면 안드로이드 TTS로 바꾼다.
 *    Pleos Playground Client ID/Secret이 빌드에 있으면 하이브리드(서버 우선, 실패 시 온디바이스), 없으면 온디바이스 모드다.
 *    엔진 실패(ON_DEVICE_ENGINE_ERROR 등)는 말하는 도중에 온다 — 그때도 바꾸고 방금 멘트를 다시 읽는다.
 */
class FallbackDjVoice(context: Context) : DjVoice {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val _speaking = MutableStateFlow(false)
    @Volatile private var current: DjVoice = NoVoice
    @Volatile private var pending: String? = null
    @Volatile private var last: String? = null
    @Volatile private var fellBack = false

    init {
        PleosVoice.create(app, _speaking, onReady = { voice -> use(voice) }, onFailure = { reason -> fallBack("초기화 실패: $reason", replay = false) },
            onEngineError = { reason -> fallBack("엔진 오류: $reason", replay = true) })
    }

    override val engine: String get() = current.engine
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    override fun speak(text: String) {
        last = text
        if (current === NoVoice) pending = text else current.speak(text)
    }

    override fun stop() {
        pending = null
        current.stop()
        _speaking.value = false
    }

    override fun release() {
        current.release()
        current = NoVoice
        _speaking.value = false
    }

    @Synchronized
    private fun fallBack(reason: String, replay: Boolean) {
        if (fellBack) return
        fellBack = true
        Log.w(TAG, "Pleos TTS를 쓸 수 없어 안드로이드 TTS로 바꿔요 — $reason")
        current.release()
        current = NoVoice
        _speaking.value = false
        if (replay) pending = last
        AndroidVoice.create(app, _speaking, onReady = { voice -> use(voice) }, onFailure = { Log.w(TAG, "안드로이드 TTS도 쓸 수 없어요: $it") })
    }

    private fun use(voice: DjVoice) {
        Log.i(TAG, "DJ 목소리: ${voice.engine}")
        current = voice
        // 🔑 초기화 콜백 안에서 바로 말하면 SDK가 콜백 등록을 마치기 전이라 무시된다 — 한 박자 늦춰 보낸다.
        pending?.let { text -> pending = null; main.postDelayed({ voice.speak(text) }, FIRST_LINE_DELAY_MS) }
    }

    private companion object {
        const val TAG = "DjTrotVoice"
        const val FIRST_LINE_DELAY_MS = 600L
    }
}

private object NoVoice : DjVoice {
    override val engine = "없음"
    override val speaking: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    override fun speak(text: String) = Unit
    override fun stop() = Unit
    override fun release() = Unit
}

private class PleosVoice(
    private val tts: PleosTextToSpeech,
    private val talking: MutableStateFlow<Boolean>,
    private val cutOff: AtomicInteger,
) : DjVoice {
    override val engine = "Pleos TTS"
    override val speaking: StateFlow<Boolean> = talking.asStateFlow()

    /**
     * 🔴 `speak(text, isFinal)`의 두 번째 값은 "이 글이 끝인가"다. false면 SDK가 뒷글을 기다린다.
     *    앞 멘트를 끊는 일은 [stop]이 한다.
     * 🔑 새 멘트로 끊은 앞 멘트의 onStop은 새 멘트의 onStart보다 늦게 올 수 있다 — [cutOff]만큼 무시해 음악이 말 도중 커지지 않게 한다.
     */
    override fun speak(text: String) {
        Log.d("DjTrotVoice", "Pleos TTS 말하기 ${text.length}자")
        if (talking.value && runCatching { tts.stop() }.isSuccess) cutOff.incrementAndGet()
        runCatching { tts.speak(text, true) }.onFailure { Log.w("DjTrotVoice", "Pleos TTS speak 실패", it) }
    }
    override fun stop() { runCatching { tts.stop() } }
    override fun release() { runCatching { tts.release() } }

    companion object {
        private const val TAG = "DjTrotVoice"
        private const val SERVER_WAIT_MS = 5_000L

        /** 말할 수 없는 상태로 보는 오류 — 같은 엔진으로 다시 시도해도 소용없다. */
        private val fatal = setOf(ErrorType.ON_DEVICE_ENGINE_ERROR, ErrorType.NOT_SUPPORTED_API, ErrorType.UNAVAILABLE_LANGUAGE, ErrorType.PERMISSION_DENIED, ErrorType.UNAUTHENTICATED)

        fun create(
            context: Context,
            talking: MutableStateFlow<Boolean>,
            onReady: (DjVoice) -> Unit,
            onFailure: (String) -> Unit,
            onEngineError: (String) -> Unit,
        ) {
            val clientId = BuildConfig.PLEOS_CLIENT_ID
            val server = clientId.isNotBlank() && BuildConfig.PLEOS_CLIENT_SECRET.isNotBlank()
            val main = Handler(Looper.getMainLooper())
            runCatching {
                // 🔑 앱 등록 값이 있으면 하이브리드(서버 우선, 실패 시 온디바이스), 없으면 온디바이스만.
                val tts = PleosTextToSpeech(context, if (server) Mode.HYBRID else Mode.ON_DEVICE)
                tts.initialize(object : OnInitializeListener {
                    override fun onSuccess() {
                        // 🔴 리스너·로케일은 초기화가 끝난 뒤에 건다(SDK 내부 연결 전이면 예외).
                        val cutOff = AtomicInteger(0)
                        runCatching {
                            tts.addEventListener(object : EventListener {
                                override fun onStart() { talking.value = true }
                                override fun onReady() = Unit
                                override fun onDone() {
                                    cutOff.set(0)
                                    talking.value = false
                                }
                                override fun onStop() {
                                    if (cutOff.getAndUpdate { maxOf(it - 1, 0) } == 0) talking.value = false
                                }
                                override fun onUpdatedRms(rms: Double) = Unit
                                override fun onError(errorType: ErrorType) {
                                    talking.value = false
                                    if (errorType in fatal) onEngineError(errorType.name)
                                }
                            })
                        }
                        // 🔴 나라까지 붙은 ko_KR이어야 한다 — SDK가 `toLanguageTag()`로 보내는데 지원 로케일은 `ko-KR`이다(Pleos TTS Set locale).
                        //    언어만(ko) 주면 온디바이스 엔진이 거절한다. 말로 넣는 사연([StoryMic])과 같은 값이다.
                        runCatching { tts.setLocale(Locale.KOREA) }
                        val voice = PleosVoice(tts, talking, cutOff)
                        if (!server) return onReady(voice)
                        // 🔑 서버 모드는 앱 등록이 끝난 뒤에 말해야 한다(Pleos TTS 문서). 등록이 실패해도 온디바이스로 말한다.
                        //    콜백이 오지 않는 경우에 대비해 [SERVER_WAIT_MS] 뒤에는 기다리지 않는다.
                        val ready = AtomicBoolean(false)
                        val once = { if (ready.compareAndSet(false, true)) onReady(voice) }
                        main.postDelayed({ once() }, SERVER_WAIT_MS)
                        runCatching {
                            tts.registerApp(clientId, BuildConfig.PLEOS_CLIENT_SECRET, object : OnServerConnectionListener {
                                override fun onConnected() {
                                    Log.i(TAG, "Pleos TTS 서버 연결")
                                    main.post { once() }
                                }
                                override fun onFailed(errorType: ErrorType) {
                                    Log.w(TAG, "Pleos TTS 서버 연결 실패: ${errorType.name}")
                                    main.post { once() }
                                }
                            })
                        }.onFailure { main.post { once() } }
                    }
                    override fun onFailure(errorType: ErrorType) {
                        runCatching { tts.release() }
                        onFailure(errorType.name)
                    }
                })
            }.onFailure { onFailure(it.message ?: it.javaClass.simpleName) }
        }
    }
}

private class AndroidVoice(private val tts: TextToSpeech, private val talking: MutableStateFlow<Boolean>) : DjVoice {
    override val engine = "Android TTS"
    override val speaking: StateFlow<Boolean> = talking.asStateFlow()
    private val said = AtomicInteger(0)
    @Volatile var latest: String? = null
        private set

    /** 🔑 QUEUE_FLUSH로 끊긴 앞 멘트의 onStop이 새 멘트 뒤에 와도 무시하도록 멘트마다 ID를 따로 준다. */
    override fun speak(text: String) {
        val id = "$UTTERANCE_PREFIX${said.incrementAndGet()}"
        latest = id
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }
    override fun stop() { tts.stop() }
    override fun release() { tts.shutdown() }

    companion object {
        private const val UTTERANCE_PREFIX = "dj-"

        fun create(context: Context, talking: MutableStateFlow<Boolean>, onReady: (DjVoice) -> Unit, onFailure: (String) -> Unit) {
            var tts: TextToSpeech? = null
            tts = TextToSpeech(context) { status ->
                val engine = tts ?: return@TextToSpeech
                val language = if (status == TextToSpeech.SUCCESS) engine.setLanguage(Locale.KOREAN) else TextToSpeech.ERROR
                Log.i("DjTrotVoice", "안드로이드 TTS 상태 status=$status language=$language engine=${engine.defaultEngine}")
                if (language >= TextToSpeech.LANG_AVAILABLE) {
                    val voice = AndroidVoice(engine, talking)
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) { talking.value = true }
                        override fun onDone(utteranceId: String?) = ended(utteranceId)
                        override fun onStop(utteranceId: String?, interrupted: Boolean) = ended(utteranceId)
                        override fun onError(utteranceId: String?, errorCode: Int) = ended(utteranceId)
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) = ended(utteranceId)

                        private fun ended(utteranceId: String?) {
                            if (utteranceId == voice.latest) talking.value = false
                        }
                    })
                    onReady(voice)
                } else {
                    engine.shutdown()
                    onFailure("status=$status language=$language")
                }
            }
        }
    }
}
