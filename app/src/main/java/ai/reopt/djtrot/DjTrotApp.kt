package ai.reopt.djtrot

import ai.reopt.djtrot.account.Connection
import ai.reopt.djtrot.account.DeviceAccount
import ai.reopt.djtrot.account.DeviceVault
import ai.reopt.djtrot.api.DjTrotApi
import ai.reopt.djtrot.api.Labels
import ai.reopt.djtrot.car.CarSignals
import ai.reopt.djtrot.dj.DjVoice
import ai.reopt.djtrot.dj.FallbackDjVoice
import ai.reopt.djtrot.dj.SongOrders
import ai.reopt.djtrot.dj.SongRequest
import ai.reopt.djtrot.playback.Catalog
import android.app.Application
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
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
    val dj = MutableStateFlow(DjState())

    override fun onCreate() {
        super.onCreate()
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
        set(value) { getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_TALKS, value).apply() }

    private companion object {
        const val PREFS = "djtrot"
        const val KEY_TALKS = "dj_talks"
    }
}
