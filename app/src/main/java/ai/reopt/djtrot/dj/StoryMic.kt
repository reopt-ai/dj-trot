package ai.reopt.djtrot.dj

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.OnServerConnectionListener
import ai.pleos.playground.stt.listener.ResultListener
import ai.reopt.djtrot.BuildConfig
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 말로 넣는 사연을 듣는 상태. [heard]는 지금까지 알아들은 말, [problem]은 듣지 못한 까닭. */
data class Hearing(val listening: Boolean = false, val heard: String = "", val problem: String? = null)

/**
 * 말로 넣는 사연 — Pleos SpeechToText로 한 번 듣고, 알아들은 말을 [onHeard]로 넘긴다.
 * 🔑 Pleos Playground Client ID/Secret이 빌드에 있으면 하이브리드(서버 우선), 없으면 온디바이스 모드다([FallbackDjVoice]와 같은 값).
 * 🔑 처음 누를 때 SDK를 켠다 — 쓰지 않는 사람에게는 마이크 서비스를 붙이지 않는다.
 * 🔑 [LISTEN_MAX_MS]가 지나거나 다시 누르면 그때까지 들은 말로 끝낸다. 사연은 [StoryBuilder.MAX_STORY]자까지다.
 * ⚠️ 중간 결과(`onUpdated`의 completed=false)는 그때까지의 문장 전체로 보고 바꿔 쓴다. 문서에 누적 여부가 없다.
 * 모든 상태 변경은 메인 스레드에서 한다. SDK 콜백은 메인으로 옮겨 받는다.
 */
class StoryMic(context: Context, private val onHeard: (String) -> Unit) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(Hearing())
    val state: StateFlow<Hearing> = _state.asStateFlow()

    private var stt: SpeechToText? = null
    private var ready = false
    private var wanted = false
    private val timeout = Runnable { finish(NOT_HEARD) }

    private val listener = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() = Unit
        override fun onUpdated(stt: String, completed: Boolean) {
            main.post {
                if (!wanted) return@post
                _state.update { it.copy(heard = stt) }
                if (completed) finish(NOT_HEARD)
            }
        }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() {
            main.post { finish(NOT_HEARD) }
        }
        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "Pleos STT 오류: ${errorType.name}")
            main.post {
                if (errorType in fatal) drop()
                finish(if (errorType in fatal) UNAVAILABLE else NOT_HEARD)
            }
        }
    }

    /** 듣기 시작. 이미 듣는 중이면 그대로 둔다. */
    fun listen() {
        if (wanted) return
        wanted = true
        _state.value = Hearing(listening = true)
        main.postDelayed(timeout, LISTEN_MAX_MS)
        val engine = stt
        when {
            engine == null -> start()
            ready -> request(engine)
        }
    }

    /** 그만 듣는다. 그때까지 알아들은 말이 있으면 사연으로 쓴다. */
    fun stop() = finish(null)

    private fun start() {
        val clientId = BuildConfig.PLEOS_CLIENT_ID
        val server = clientId.isNotBlank() && BuildConfig.PLEOS_CLIENT_SECRET.isNotBlank()
        runCatching {
            val engine = SpeechToText(app, if (server) Mode.HYBRID else Mode.ON_DEVICE)
            stt = engine
            engine.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    main.post {
                        // 🔴 리스너·로케일은 초기화가 끝난 뒤에 건다(Pleos TTS와 같은 순서).
                        runCatching { engine.addListener(listener) }
                        runCatching { engine.setLocale(Locale.KOREAN) }
                        if (!server) return@post begin(engine)
                        // 🔑 서버 모드는 앱 등록 뒤에 듣는다. 등록이 실패하거나 답이 없어도 온디바이스로 듣는다.
                        val once = AtomicBoolean(false)
                        val go = { if (once.compareAndSet(false, true)) begin(engine) }
                        main.postDelayed({ go() }, SERVER_WAIT_MS)
                        runCatching {
                            engine.registerApp(clientId, BuildConfig.PLEOS_CLIENT_SECRET, object : OnServerConnectionListener {
                                override fun onConnected() {
                                    main.post { go() }
                                }
                                override fun onFailed(errorType: ErrorType) {
                                    Log.w(TAG, "Pleos STT 서버 연결 실패: ${errorType.name}")
                                    main.post { go() }
                                }
                            })
                        }.onFailure { main.post { go() } }
                    }
                }
                override fun onFailure(errorType: ErrorType) {
                    Log.w(TAG, "Pleos STT 초기화 실패: ${errorType.name}")
                    main.post {
                        drop()
                        finish(UNAVAILABLE)
                    }
                }
            })
        }.onFailure {
            Log.w(TAG, "Pleos STT를 쓸 수 없어요: ${it.message}")
            drop()
            finish(UNAVAILABLE)
        }
    }

    private fun begin(engine: SpeechToText) {
        if (stt !== engine) return
        ready = true
        if (wanted) request(engine)
    }

    private fun request(engine: SpeechToText) {
        runCatching { engine.request() }.onFailure {
            Log.w(TAG, "Pleos STT request 실패", it)
            finish(NOT_HEARD)
        }
    }

    /** 듣기를 끝낸다. 들은 말이 있으면 넘기고, 없으면 [problem]을 보인다. */
    private fun finish(problem: String?) {
        if (!wanted) return
        wanted = false
        main.removeCallbacks(timeout)
        if (ready) runCatching { stt?.stop() }
        val heard = _state.value.heard.trim().take(StoryBuilder.MAX_STORY)
        _state.value = Hearing(problem = problem.takeIf { heard.isEmpty() })
        if (heard.isNotEmpty()) onHeard(heard)
    }

    /** 엔진을 버린다. 다음에 누르면 새로 켠다. */
    private fun drop() {
        runCatching { stt?.release() }
        stt = null
        ready = false
    }

    private companion object {
        const val TAG = "DjTrotMic"
        const val LISTEN_MAX_MS = 12_000L
        const val SERVER_WAIT_MS = 5_000L
        const val NOT_HEARD = "잘 듣지 못했어요. 다시 말씀해 주세요."
        const val UNAVAILABLE = "지금은 말로 넣을 수 없어요."

        /** 같은 엔진으로 다시 해도 소용없는 오류 — 엔진을 버리고 다음에 새로 켠다. */
        val fatal = setOf(ErrorType.ON_DEVICE_ENGINE_ERROR, ErrorType.NOT_SUPPORTED_API, ErrorType.UNAVAILABLE_LANGUAGE, ErrorType.PERMISSION_DENIED, ErrorType.UNAUTHENTICATED, ErrorType.MIC_ERROR)
    }
}
