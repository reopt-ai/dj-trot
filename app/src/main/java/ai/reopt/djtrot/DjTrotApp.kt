package ai.reopt.djtrot

import ai.reopt.djtrot.account.Connection
import ai.reopt.djtrot.account.DeviceAccount
import ai.reopt.djtrot.account.DeviceVault
import ai.reopt.djtrot.account.SongAccess
import ai.reopt.djtrot.api.Ad
import ai.reopt.djtrot.api.DjConfig
import ai.reopt.djtrot.api.DjInfo
import ai.reopt.djtrot.api.DjTrotApi
import ai.reopt.djtrot.api.Labels
import ai.reopt.djtrot.car.CarSignals
import ai.reopt.djtrot.car.Destination
import ai.reopt.djtrot.car.GeoPoint
import ai.reopt.djtrot.dj.Ads
import ai.reopt.djtrot.dj.AutoDemo
import ai.reopt.djtrot.dj.Demo
import ai.reopt.djtrot.dj.adPauseSeconds
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
import android.content.Intent
import androidx.core.content.edit
import java.time.Duration
import java.time.Instant
import ai.reopt.djtrot.dj.VoicePackStore
import java.io.File
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
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

/**
 * 볼 수 있는 서버. [BUILT]는 빌드가 정한 서버(`djtrot.api`)다 — 운영을 보게 빌드했으면 운영, 아니면 로컬이다.
 * 나머지 하나가 개발자 모드에서 고르는 "다른 쪽" 서버다.
 */
enum class Server {
    BUILT,
    OTHER,
    ;

    /** 이 서버가 운영인가. */
    val production: Boolean get() = url == PRODUCTION_API

    val label: String get() = if (production) "프로덕션" else "로컬"

    val url: String
        get() = when (this) {
            BUILT -> BuildConfig.DJ_TROT_API
            OTHER -> if (BuildConfig.DJ_TROT_API == PRODUCTION_API) BuildConfig.DJ_TROT_LOCAL_API else PRODUCTION_API
        }

    companion object {
        const val PRODUCTION_API = "https://miniapp.reopt.ai/api/v1/dj-trot"
    }
}

/**
 * 멘트 요청 한 번의 기록 — 서버에 보낸 것([request], JSON), 받은 것([response]), 기기가 받아 둔 팩에서 고른 조각([local]).
 * [request]가 null이면 서버에 묻지 않고 기기 조각만으로 말했다.
 */
data class BriefTrace(val at: LocalTime, val request: String?, val response: String, val local: List<String> = emptyList())

class DjTrotApp : Application() {
    private val scope = MainScope()
    /**
     * 지금 보는 서버 — 개발자 모드(설정 › 앱 정보)에서 바꾼다. 기본은 빌드가 정한 서버다.
     * 🔑 앱을 시작할 때 한 번 읽는다. 바꾸면 앱을 다시 시작해 모든 것(계정·목록·주문·음성 팩)을 새 서버로 다시 만든다.
     */
    val server: Server by lazy {
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SERVER, null)?.let { saved -> Server.entries.firstOrNull { it.name == saved } } ?: Server.BUILT
    }
    val api by lazy { DjTrotApi(server.url) }
    // 🔑 빌드가 정한 서버가 아니면 자격 보관함과 음성 팩 폴더를 따로 쓴다 — 서버마다 계정과 팩이 다르다.
    val account by lazy { DeviceAccount(api, if (server == Server.BUILT) DeviceVault(this) else DeviceVault(this, "djtrot_device_${server.name.lowercase()}")) }
    private val packRoot: File get() = if (server == Server.BUILT) filesDir else File(filesDir, "server-${server.name.lowercase()}")

    /** 개발자 모드 — 앱 정보에서 버전을 여러 번 누르면 켜진다. 기기에만 저장한다. */
    var developer: Boolean
        get() = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_DEVELOPER, false)
        set(value) { getSharedPreferences(PREFS, MODE_PRIVATE).edit { putBoolean(KEY_DEVELOPER, value) } }

    /** 서버에 마지막으로 멘트를 청한 기록 — 개발자 모드가 "왜 이 멘트가 나왔나"를 보여 준다. 기기 밖으로 나가지 않는다. */
    val lastBrief = MutableStateFlow<BriefTrace?>(null)

    /**
     * 받아 둔 것을 지우고 처음 상태로 돌아간다 — 지금 서버의 DJ 음성 팩과 DJ 설정(멘트 켜기·간격·음악 크기·고른 DJ).
     * 🔑 휴대폰 연결(기기 자격)과 개발자 모드·서버 선택은 그대로 둔다. 끝나면 앱을 다시 시작한다.
     */
    fun resetData() {
        packRoot.listFiles { file -> file.isDirectory && file.name.startsWith("voice-pack") }?.forEach { it.deleteRecursively() }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit(commit = true) { remove(KEY_TALKS).remove(KEY_FULL_EVERY).remove(KEY_DUCK).remove(KEY_DJ) }
        restart()
    }

    private fun restart() {
        packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) }
        Runtime.getRuntime().exit(0)
    }

    /**
     * 서버를 바꾸고 앱을 다시 시작한다.
     * 🔴 재생 중이던 노래와 진행 중이던 시연은 끊긴다. 개발자 모드에서만 부른다.
     */
    fun switchServer(next: Server) {
        if (next == server) return
        getSharedPreferences(PREFS, MODE_PRIVATE).edit(commit = true) { putString(KEY_SERVER, next.name) }
        restart()
    }
    val catalog by lazy { Catalog(api) { songAccess } }

    /** 드라이브 노래 자격 — 연결한 차는 기기 세션, 아니면 시연 손님 ID. */
    val songAccess by lazy { SongAccess(account, ::guestId, { creationGuest.value }) }

    /**
     * 시연 손님 ID — 처음 쓸 때 만들어 기기에 둔다. 🔑 데이터 지우기에도 남긴다(이 차가 시연으로 만든 곡을 다시 찾는 열쇠).
     * 서버마다 따로 둔다 — 로컬에서 만든 곡의 ID로 운영 서버에 묻지 않게.
     */
    private fun guestId(): String {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val key = "guest_${server.name.lowercase()}"
        return prefs.getString(key, null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit(commit = true) { putString(key, it) } }
    }
    val orders by lazy { SongOrders(api, account, songAccess, catalog, scope) }

    /** 광고 — 장소에 맞는 광고 음악과 생성권 진행. */
    val ads by lazy { Ads(api, account, scope) }

    /** 시뮬레이션이 "이 광고의 장소에 가까워졌다"고 재생 서비스에 보내는 신호(광고 ID). */
    val adCues = MutableSharedFlow<AdCue>(extraBufferCapacity = 1)

    /** 광고 신호 — [now]가 참이면 다음 곡으로 미루지 않고 지금 곡을 넘겨 바로 튼다(자동 시연). */
    data class AdCue(val id: String, val now: Boolean = false)

    /**
     * 시뮬레이션 — 그 광고의 조건에 맞는 장소를 덧씌우고 광고를 바로 틀게 한다.
     * 🔑 조건의 첫 번째(낱말 → 목적지 종류 → 좌표)로 장소를 만든다. 쉬는 시간은 보지 않는다.
     */
    fun demoAd(ad: Ad, now: Boolean = false) {
        val before = demo.value ?: DemoDrive()
        val conditions = ad.conditions
        val next = when {
            conditions.keywords.isNotEmpty() -> before.road().copy(address = "${conditions.keywords.first()} 앞")
            conditions.destinationWords.isNotEmpty() -> before.road().copy(destination = Destination("${conditions.destinationWords.first()} 본점", remainMinutes = 5, remainKm = 2.0))
            conditions.places.isNotEmpty() -> conditions.places.first().let { before.road().copy(location = GeoPoint(it.latitude, it.longitude), address = it.name.ifBlank { null } ?: before.address) }
            else -> before
        }
        demo.value = next
        car.stage(next::apply)
        adCues.tryEmit(AdCue(ad.id, now))
    }
    val car by lazy { CarSignals(this) }
    val voice: DjVoice by lazy { FallbackDjVoice(this) }
    /** 받아 둔 DJ 음성 조각 — 서버가 닿지 않을 때와 출발·도착 인사에 쓴다. */
    val voicePack: VoicePackStore get() = pack(djId.value)

    /** 고를 수 있는 DJ — 서버 설정이 알려 준다. 받기 전이나 예전 서버면 기본 DJ 하나다. */
    val djs = MutableStateFlow(listOf(DjInfo.DEFAULT))

    /** 노래 만들기가 열려 있는가 — 서버 설정이 닫으면 트롯생성 칸이 주문을 받지 않는다. 설정을 받기 전에는 열림으로 본다. */
    val creationOpen = MutableStateFlow(true)

    /** 시연 모드 — 휴대폰을 연결하지 않아도 생성권 없이 만든다(서버 설정). */
    val creationGuest = MutableStateFlow(false)

    /** 지금 고른 DJ의 ID. 기기에만 저장한다. */
    val djId by lazy { MutableStateFlow(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_DJ, null) ?: DjInfo.DEFAULT_ID) }

    /** 지금 고른 DJ. 목록에 없으면(서버가 내렸다) 기본 DJ. */
    val djInfo: DjInfo get() = djs.value.firstOrNull { it.id == djId.value } ?: DjInfo.DEFAULT

    private val packs = ConcurrentHashMap<String, VoicePackStore>()

    /** 그 DJ의 받아 둔 음성 팩 — DJ마다 폴더가 따로다. 기본 DJ는 예전 폴더를 그대로 쓴다. */
    private fun pack(id: String): VoicePackStore = packs.getOrPut(id) {
        VoicePackStore(File(packRoot, if (id == DjInfo.DEFAULT_ID) "voice-pack" else "voice-pack-$id"), { api.voicePack(id) }, api::download)
    }

    /**
     * DJ를 바꾼다 — 다음 멘트부터 그 DJ가 말한다. 준비되지 않은 DJ는 고를 수 없다.
     * 🔑 그 DJ의 음성 팩을 바로 받기 시작한다. 받는 동안은 서버가 주는 음성 주소로 튼다.
     */
    fun selectDj(id: String) {
        if (djs.value.none { it.id == id && it.ready }) return
        djId.value = id
        getSharedPreferences(PREFS, MODE_PRIVATE).edit { putString(KEY_DJ, id) }
        syncVoicePack()
    }

    /** 서버 설정을 받았다 — 선택지 이름과 DJ 목록을 갈아 끼운다. 고른 DJ가 준비되지 않았으면 기본 DJ로 돌아간다. */
    private fun configured(config: DjConfig) {
        Labels.update(config)
        creationOpen.value = config.creation.open
        creationGuest.value = config.creation.open && config.creation.guest
        djs.value = config.djs.ifEmpty { listOf(DjInfo.DEFAULT) }
        if (djs.value.none { it.id == djId.value && it.ready }) djId.value = DjInfo.DEFAULT_ID
    }
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
            // 🔑 광고 차례는 등록된 광고를 길마다 돌려 가며 고른다. 광고가 없으면 건너뛴다.
            val ads = this@DjTrotApp.ads.list.value
            val steps = scenario.steps.filter { !it.ad || ads.isNotEmpty() }
            val pick = ads.getOrNull(Math.floorMod(Demo.SCENARIOS.indexOf(scenario), ads.size.coerceAtLeast(1)))
            steps.forEachIndexed { index, step ->
                val ad = pick.takeIf { step.ad }
                autoDemo.value = AutoDemo(scenario.id, scenario.title, index, steps.size, ad?.let { "광고 · ${it.title}" } ?: step.label)
                if (ad != null) {
                    demoAd(ad, now = true)
                    delay(adPauseSeconds(ad.maxSeconds) * 1000L)
                } else {
                    demo(step)
                    delay(step.pauseSeconds * 1000L)
                }
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
                runCatching { api.config(token) }.onSuccess(::configured)
                if (token != null) orders.refreshTokens()
                ads.refresh()
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
        private const val KEY_DJ = "dj_id"
        private const val KEY_SERVER = "server"
        private const val KEY_DEVELOPER = "developer"
    }
}
