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
import android.util.Log
import java.util.Locale
import ai.pleos.playground.tts.TextToSpeech as PleosTextToSpeech

/** DJ 목소리. 말할 수 없는 환경이면 조용히 넘어가고 화면에 멘트만 보인다. */
interface DjVoice {
    val engine: String
    fun speak(text: String)
    fun stop()
    fun release()
}

/**
 * 🔑 Pleos TTS(온디바이스 모드 — 앱 등록·네트워크 없이 동작)를 먼저 쓰고, 초기화나 엔진이 실패하면 안드로이드 TTS로 바꾼다.
 *    엔진 실패(ON_DEVICE_ENGINE_ERROR 등)는 말하는 도중에 온다 — 그때도 바꾸고 방금 멘트를 다시 읽는다.
 *    서버/하이브리드 모드는 Pleos Playground 프로젝트의 Client ID/Secret 등록 뒤에 켠다.
 */
class FallbackDjVoice(context: Context) : DjVoice {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var current: DjVoice = NoVoice
    @Volatile private var pending: String? = null
    @Volatile private var last: String? = null
    @Volatile private var fellBack = false

    init {
        PleosVoice.create(app, onReady = { voice -> use(voice) }, onFailure = { reason -> fallBack("초기화 실패: $reason", replay = false) },
            onEngineError = { reason -> fallBack("엔진 오류: $reason", replay = true) })
    }

    override val engine: String get() = current.engine

    override fun speak(text: String) {
        last = text
        if (current === NoVoice) pending = text else current.speak(text)
    }

    @Synchronized
    private fun fallBack(reason: String, replay: Boolean) {
        if (fellBack) return
        fellBack = true
        Log.w(TAG, "Pleos TTS를 쓸 수 없어 안드로이드 TTS로 바꿔요 — $reason")
        current.release()
        current = NoVoice
        if (replay) pending = last
        AndroidVoice.create(app, onReady = { voice -> use(voice) }, onFailure = { Log.w(TAG, "안드로이드 TTS도 쓸 수 없어요: $it") })
    }

    override fun stop() {
        pending = null
        current.stop()
    }

    override fun release() {
        current.release()
        current = NoVoice
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
    override fun speak(text: String) = Unit
    override fun stop() = Unit
    override fun release() = Unit
}

private class PleosVoice(private val tts: PleosTextToSpeech) : DjVoice {
    override val engine = "Pleos TTS"
    // append=false — 앞 곡 멘트가 남아 있으면 끊고 새 멘트를 읽는다.
    override fun speak(text: String) {
        Log.d("DjTrotVoice", "Pleos TTS 말하기 ${text.length}자")
        runCatching { tts.speak(text, false) }.onFailure { Log.w("DjTrotVoice", "Pleos TTS speak 실패", it) }
    }
    override fun stop() { runCatching { tts.stop() } }
    override fun release() { runCatching { tts.release() } }

    companion object {
        private const val TAG = "DjTrotVoice"

        /** 말할 수 없는 상태로 보는 오류 — 같은 엔진으로 다시 시도해도 소용없다. */
        private val fatal = setOf(ErrorType.ON_DEVICE_ENGINE_ERROR, ErrorType.NOT_SUPPORTED_API, ErrorType.UNAVAILABLE_LANGUAGE, ErrorType.PERMISSION_DENIED, ErrorType.UNAUTHENTICATED)

        fun create(context: Context, onReady: (DjVoice) -> Unit, onFailure: (String) -> Unit, onEngineError: (String) -> Unit) {
            val clientId = BuildConfig.PLEOS_CLIENT_ID
            val server = clientId.isNotBlank() && BuildConfig.PLEOS_CLIENT_SECRET.isNotBlank()
            runCatching {
                // 🔑 앱 등록 값이 있으면 하이브리드(서버 우선, 실패 시 온디바이스), 없으면 온디바이스만.
                val tts = PleosTextToSpeech(context, if (server) Mode.HYBRID else Mode.ON_DEVICE)
                tts.initialize(object : OnInitializeListener {
                    override fun onSuccess() {
                        // 🔴 리스너·로케일은 초기화가 끝난 뒤에 건다(SDK 내부 연결 전이면 예외).
                        runCatching {
                            tts.addEventListener(object : EventListener {
                                override fun onStart() = Unit
                                override fun onReady() = Unit
                                override fun onDone() = Unit
                                override fun onStop() = Unit
                                override fun onUpdatedRms(rms: Double) = Unit
                                override fun onError(errorType: ErrorType) {
                                    if (errorType in fatal) onEngineError(errorType.name)
                                }
                            })
                        }
                        runCatching { tts.setLocale(Locale.KOREAN) }
                        if (server) {
                            runCatching {
                                tts.registerApp(clientId, BuildConfig.PLEOS_CLIENT_SECRET, object : OnServerConnectionListener {
                                    override fun onConnected() = Log.i(TAG, "Pleos TTS 서버 연결").let { }
                                    override fun onFailed(errorType: ErrorType) = Log.w(TAG, "Pleos TTS 서버 연결 실패: ${errorType.name}").let { }
                                })
                            }
                        }
                        onReady(PleosVoice(tts))
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

private class AndroidVoice(private val tts: TextToSpeech) : DjVoice {
    override val engine = "Android TTS"
    override fun speak(text: String) { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dj-${text.hashCode()}") }
    override fun stop() { tts.stop() }
    override fun release() { tts.shutdown() }

    companion object {
        fun create(context: Context, onReady: (DjVoice) -> Unit, onFailure: (String) -> Unit) {
            var tts: TextToSpeech? = null
            tts = TextToSpeech(context) { status ->
                val engine = tts ?: return@TextToSpeech
                val language = if (status == TextToSpeech.SUCCESS) engine.setLanguage(Locale.KOREAN) else TextToSpeech.ERROR
                Log.i("DjTrotVoice", "안드로이드 TTS 상태 status=$status language=$language engine=${engine.defaultEngine}")
                if (language >= TextToSpeech.LANG_AVAILABLE) onReady(AndroidVoice(engine))
                else { engine.shutdown(); onFailure("status=$status language=$language") }
            }
        }
    }
}
