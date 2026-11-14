package ai.reopt.djtrot.dj

import ai.reopt.djtrot.account.DeviceAccount
import ai.reopt.djtrot.api.Ad
import ai.reopt.djtrot.api.AdConditions
import ai.reopt.djtrot.api.AdProgress
import ai.reopt.djtrot.api.AdReward
import ai.reopt.djtrot.api.DjTrotApi
import ai.reopt.djtrot.car.DriveContext
import ai.reopt.djtrot.car.GeoPoint
import java.util.UUID
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 광고 장소 판단 — 조건 셋(주소·목적지 낱말, 목적지 종류의 낱말, 좌표 반경) 가운데 하나라도 맞으면 참.
 * 🔴 서버의 `matchesAd`와 같은 규칙이다. 한쪽만 바꾸지 않는다.
 * 🔴 판단은 기기 안에서 한다 — 좌표와 주소를 서버로 보내지 않는다.
 */
object AdMatcher {
    fun matches(conditions: AdConditions, drive: DriveContext): Boolean {
        val destination = drive.destination
        fun has(text: String?, words: List<String>) = text != null && words.any { it in text }
        if (has(drive.currentAddress, conditions.keywords) || has(destination?.name, conditions.keywords) || has(destination?.address, conditions.keywords)) return true
        if (has(destination?.name, conditions.destinationWords)) return true
        return conditions.places.any { place ->
            listOfNotNull(drive.location, destination?.location).any { distanceMeters(it, GeoPoint(place.latitude, place.longitude)) <= place.radiusMeters }
        }
    }

    /** 두 좌표 사이의 거리(m) — 하버사인. */
    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    private const val EARTH_RADIUS_M = 6_371_000.0
}

/**
 * 광고 — 서버에서 광고 목록을 받아 두고, 지금 장소에 맞는 광고를 고르고, 끝까지 들은 것을 서버에 알린다.
 * 🔑 같은 광고는 [AD_COOLDOWN_MS] 동안 다시 틀지 않고, 어떤 광고든 [GAP_MS]에 한 편만 튼다 — 같은 동네를 지나는 내내 광고가 이어지지 않게.
 * 🔑 휴대폰을 연결하지 않았으면 광고는 나오지만 생성권은 쌓이지 않는다.
 */
class Ads(
    private val api: DjTrotApi,
    private val account: DeviceAccount,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val list = MutableStateFlow<List<Ad>>(emptyList())
    val reward = MutableStateFlow(AdReward())

    /** 다음 생성권까지의 진행. 연결하지 않았거나 아직 받지 못했으면 null. */
    val progress = MutableStateFlow<AdProgress?>(null)
    private val playedAt = HashMap<String, Long>()
    private var lastAt = 0L

    /** 광고 목록과 진행을 다시 받는다. 실패하면 가진 것을 그대로 둔다. */
    fun refresh() {
        scope.launch {
            runCatching { api.ads() }.onSuccess {
                list.value = it.ads
                reward.value = it.reward
            }
            runCatching { account.authorized { token -> api.adProgress(token) } }.onSuccess { progress.value = it }
        }
    }

    fun adOf(songId: String?): Ad? = songId?.let { id -> list.value.firstOrNull { it.song.id == id } }

    /** 지금 틀 광고 — 장소가 맞고, 쉬는 시간이 지난 것 하나. [force]면 쉬는 시간을 보지 않는다(시뮬레이션). */
    @Synchronized
    fun due(drive: DriveContext, force: Boolean = false): Ad? {
        val now = clock()
        if (!force && now - lastAt < GAP_MS) return null
        return list.value.firstOrNull { ad ->
            AdMatcher.matches(ad.conditions, drive) && (force || now - (playedAt[ad.id] ?: 0L) >= AD_COOLDOWN_MS)
        }
    }

    /** 광고를 대기열에 넣었다 — 쉬는 시간을 센다. */
    @Synchronized
    fun queued(ad: Ad) {
        val now = clock()
        playedAt[ad.id] = now
        lastAt = now
    }

    /**
     * 광고를 끝까지 들었다 — 서버에 알리고 진행을 갈아 끼운다. 연결하지 않았거나 실패하면 null.
     * 🔑 재생 한 번마다 새 키를 쓴다. 서버는 같은 키를 한 번만 센다.
     */
    suspend fun heard(ad: Ad): AdProgress? {
        val key = UUID.randomUUID().toString()
        return runCatching { account.authorized { token -> api.adHeard(token, ad.id, key) } }.getOrNull()?.also { progress.value = it }
    }

    companion object {
        /** 같은 광고를 다시 틀기까지. */
        const val AD_COOLDOWN_MS = 30 * 60_000L

        /** 광고와 광고 사이. */
        const val GAP_MS = 5 * 60_000L
    }
}
