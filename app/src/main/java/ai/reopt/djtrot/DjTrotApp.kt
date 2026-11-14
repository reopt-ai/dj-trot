package ai.reopt.djtrot

import ai.reopt.djtrot.account.Connection
import ai.reopt.djtrot.account.DeviceAccount
import ai.reopt.djtrot.account.DeviceVault
import ai.reopt.djtrot.api.DjTrotApi
import ai.reopt.djtrot.api.Labels
import ai.reopt.djtrot.car.CarSignals
import ai.reopt.djtrot.dj.AutoDemo
import ai.reopt.djtrot.dj.DemoDrive
import ai.reopt.djtrot.dj.DemoEvent
import ai.reopt.djtrot.dj.DemoScenario
import ai.reopt.djtrot.dj.DemoStep
import ai.reopt.djtrot.dj.DjVoice
import ai.reopt.djtrot.dj.FallbackDjVoice
import ai.reopt.djtrot.dj.SongOrders
import ai.reopt.djtrot.dj.SongRequest
import ai.reopt.djtrot.dj.StoryMic
import ai.reopt.djtrot.dj.pauseSeconds
import ai.reopt.djtrot.dj.toInput
import ai.reopt.djtrot.playback.Catalog
import android.app.Application
import androidx.core.content.edit
import java.time.Duration
import java.time.Instant
import ai.reopt.djtrot.dj.VoicePackStore
import java.io.File
import java.time.LocalTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** DJ가 한 말 하나와 그 시각. */
data class Said(val text: String, val at: LocalTime)

/** 화면과 미디어 서비스가 함께 보는 DJ 상태. */
data class DjState(
    /** 방금 DJ가 한 말. 화면에 자막처럼 보인다. */
    val line: String? = null,
    /** [line]을 말한 시각. */
    val lineAt: LocalTime? = null,
    /** 이번 드라이브에서 앞서 한 말 — 최근 것부터 [KEEP]개. */
    val earlier: List<Said> = emptyList(),
    /** 재생 문제 안내(음원을 받지 못함 등). */
    val problem: String? = null,
    /** DJ 브리프가 제안한 이 길의 신청곡. 없으면 화면이 기기에서 쓴다. */
    val suggestion: SongRequest? = null,
    /** [suggestion]을 받은 때. */
    val suggestedAt: Instant? = null,
    /** 운전자가 말로 넣은 사연(Pleos SpeechToText). */
    val spoken: String? = null,
    /** [spoken]을 들은 때. */
    val spokenAt: Instant? = null,
) {
    /** 새 멘트 — 방금 한 말은 [earlier] 맨 앞으로 밀려난다. 같은 말을 다시 하면 그대로다. */
    fun say(text: String, at: LocalTime = LocalTime.now()): DjState {
        if (text == line) return this
        val previous = line?.let { Said(it, lineAt ?: at) }
        return copy(line = text, lineAt = at, earlier = (listOfNotNull(previous) + earlier).take(KEEP))
    }

    fun suggest(request: SongRequest, at: Instant = Instant.now()): DjState = copy(suggestion = request, suggestedAt = at)

    /** 아직 쓸 만한 제안. 사연에 시각과 동네가 들어 있어서, 멈춰 둔 사이 [STALE]이 지나면 버린다. */
    fun freshSuggestion(now: Instant = Instant.now()): SongRequest? =
        suggestion?.takeIf { suggestedAt != null && Duration.between(suggestedAt, now) < STALE }

    /** 말로 넣은 새 사연 — 앞선 제안은 이 말을 모르므로 버린다. */
    fun hear(text: String, at: Instant = Instant.now()): DjState = copy(spoken = text, spokenAt = at, suggestion = null, suggestedAt = null)

    /** 말로 넣은 사연을 다 썼다(노래를 맡겼다). */
    fun forget(): DjState = copy(spoken = null, spokenAt = null)

    /** 아직 쓸 만한 말 — 제안과 같이 [STALE]이 지나면 버린다. */
    fun freshSpoken(now: Instant = Instant.now()): String? =
        spoken?.takeIf { spokenAt != null && Duration.between(spokenAt, now) < STALE }

    companion object {
        const val KEEP = 3
        val STALE: Duration = Duration.ofMinutes(20)
    }
}

class DjTrotApp : Application() {
    private val scope = MainScope()
    val api by lazy { DjTrotApi(BuildConfig.DJ_TROT_API) }
    val account by lazy { DeviceAccount(api, DeviceVault(this)) }
    val catalog by lazy { Catalog(api, account) }
    val orders by lazy { SongOrders(api, account, catalog, scope) }
    val car by lazy { CarSignals(this) }
    val voice: DjVoice by lazy { FallbackDjVoice(this) }
    /** 받아 둔 DJ 음성 조각 — 서버가 닿지 않을 때와 출발·도착 인사에 쓴다. */
    val voicePack by lazy { VoicePackStore(File(filesDir, "voice-pack"), api::voicePack, api::download) }
    val dj = MutableStateFlow(DjState())
    val mic by lazy { StoryMic(this, ::wish) }

    /** 지금 쓸 수 있는 말로 넣은 사연. 없으면 빈 글. */
    val spoken: String get() = dj.value.freshSpoken().orEmpty()

    /**
     * 말로 넣은 사연을 받았다 — 화면은 바로 기기에서 쓴 사연([StoryBuilder])을 보이고,
     * 서버 DJ 브리프가 답하면 그 말을 담은 제안으로 바꾼다. 디버그 빌드의 시험 도구도 마이크 대신 이 길로 넣는다.
     */
    internal fun wish(spoken: String) {
        dj.update { it.hear(spoken) }
        scope.launch {
            runCatching { api.brief(car.state.value.toInput(LocalTime.now(), spoken), voice = false) }
                .onSuccess { brief -> dj.update { if (it.spoken == spoken) it.suggest(SongRequest.of(brief.song)) else it } }
        }
    }

    /** 시연 탭이 덧씌운 길 위의 상황. null이면 실제 차량 신호 그대로다. */
    val demo = MutableStateFlow<DemoDrive?>(null)

    /** 시연 버튼이 "지금 이 상황으로 다시 말해 줘"라고 재생 서비스에 보내는 신호. */
    val demoCues = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 시연 탭의 멘트 살펴보기에서 고른 조각 ID — 재생 서비스가 그 조각을 바로 들려준다. */
    val demoLines = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** 시연 중이면 DJ가 인사에 쓰는 시각을 시연이 정한다. */
    fun clock(): LocalTime = LocalTime.now().let { now -> demo.value?.clock(now) ?: now }

    /**
     * 시연 버튼 — 상황을 덧씌우고, 출발·경유·도착이면 그 사건을 내고, 아니면 DJ에게 지금 곡 위에서 다시 말하게 한다.
     * 🔑 노래가 멈춰 있으면 DJ는 말하지 않는다(실제와 같다). 화면이 그 사실을 알린다.
     */
    fun demo(step: DemoStep) {
        val before = demo.value ?: DemoDrive()
        val next = step.change(before)
        demo.value = next
        car.stage(next::apply)
        when (val event = step.event?.invoke(before)) {
            is DemoEvent.Depart -> car.stage(event.departure)
            is DemoEvent.Arrive -> car.stage(event.arrival)
            is DemoEvent.Waypoint -> car.stage(event.arrival, waypoint = true)
            null -> if (step.speak) demoCues.tryEmit(Unit)
        }
    }

    /** 지금 돌고 있는 자동 시연. 없으면 null. */
    val autoDemo = MutableStateFlow<AutoDemo?>(null)
    private var autoJob: Job? = null

    /**
     * 자동 시연을 시작한다 — [leadMs] 뒤부터 버튼을 차례로 누르고, 버튼마다 DJ가 말을 마칠 만큼 기다린다.
     * 🔑 노래를 트는 일은 화면이 한다(사용자가 누른 동작). 여기서는 상황만 바꾼다.
     */
    fun startAuto(scenario: DemoScenario, leadMs: Long) {
        autoJob?.cancel()
        autoJob = scope.launch {
            delay(leadMs)
            scenario.steps.forEachIndexed { index, step ->
                autoDemo.value = AutoDemo(scenario.id, scenario.title, index, scenario.steps.size, step.label)
                demo(step)
                delay(step.pauseSeconds * 1000L)
            }
            autoDemo.value = null
        }
    }

    /** 자동 시연을 멈춘다. 덧씌운 상황은 그대로 둔다. */
    fun stopAuto() {
        autoJob?.cancel()
        autoJob = null
        autoDemo.value = null
    }

    /** 시연을 끝내고 실제 차량 신호로 돌아간다. */
    fun endDemo() {
        stopAuto()
        demo.value = null
        car.stage(null)
    }

    /** DJ 음성 팩을 뒤에서 받아 둔다. 다 받은 지 얼마 안 됐으면 아무것도 하지 않고, 실패하면 다음에 잇는다. */
    fun syncVoicePack() {
        scope.launch { runCatching { voicePack.sync() } }
    }

    override fun onCreate() {
        super.onCreate()
        syncVoicePack()
        // 선택지 이름을 서버 표로. 연결된 계정이면 같은 호출이 첫 선물 토큰을 넣는다(서버가 계정당 한 번만).
        scope.launch {
            account.connection.collect { connection ->
                val token = if (connection is Connection.Connected) runCatching { account.token() }.getOrNull() else null
                runCatching { api.config(token) }.onSuccess(Labels::update)
                if (token != null) orders.refreshTokens()
            }
        }
    }

    /** DJ 멘트 켜기·끄기 — 기기에만 저장한다. */
    var djTalks: Boolean
        get() = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_TALKS, true)
        set(value) { getSharedPreferences(PREFS, MODE_PRIVATE).edit { putBoolean(KEY_TALKS, value) } }

    /**
     * DJ가 길게 말하는 간격 — 1이면 매 곡, 3이면 첫 곡과 그 뒤 세 곡마다(기본), 0이면 늘 곡 소개만. 기기에만 저장한다.
     */
    var fullEvery: Int
        get() = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_FULL_EVERY, DEFAULT_FULL_EVERY)
        set(value) { getSharedPreferences(PREFS, MODE_PRIVATE).edit { putInt(KEY_FULL_EVERY, value) } }

    /** DJ가 말하는 동안 음악 크기(0~1) — 작을수록 음악이 많이 줄어든다. 기기에만 저장한다. */
    var duckLevel: Float
        get() = getSharedPreferences(PREFS, MODE_PRIVATE).getFloat(KEY_DUCK, DEFAULT_DUCK)
        set(value) { getSharedPreferences(PREFS, MODE_PRIVATE).edit { putFloat(KEY_DUCK, value) } }

    companion object {
        const val DEFAULT_FULL_EVERY = 3
        const val DEFAULT_DUCK = 0.3f
        private const val PREFS = "djtrot"
        private const val KEY_TALKS = "dj_talks"
        private const val KEY_FULL_EVERY = "dj_full_every"
        private const val KEY_DUCK = "dj_duck"
    }
}
