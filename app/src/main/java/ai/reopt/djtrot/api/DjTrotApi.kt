package ai.reopt.djtrot.api

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class DjTrotApiException(val status: Int, val code: String, message: String) : IOException(message)

/**
 * DJ 트롯 서버(`/api/v1/dj-trot`) 클라이언트.
 * 공개: 설정·제공 목록·검색·DJ 브리프·페어링. 로그인(기기 세션 Bearer): 토큰·드라이브 노래.
 * 🔑 차는 이 API 하나만 부른다. 노래는 `trot-song/1`로만 받는다.
 */
class DjTrotApi(
    baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
) {
    val base = baseUrl.trimEnd('/')
    private val origin: HttpUrl = base.toHttpUrl().newBuilder().encodedPath("/").build()

    // ── 공개 ──
    suspend fun config(token: String? = null): DjConfig = call(get("$base/config", token), DjConfig.serializer())

    suspend fun catalog(): CatalogResponse = call(get("$base/catalog"), CatalogResponse.serializer())

    suspend fun search(query: String): List<TrotSong> {
        val url = "$base/catalog/search".toHttpUrl().newBuilder().addQueryParameter("q", query).build().toString()
        return call(get(url), SongList.serializer()).songs
    }

    suspend fun catalogSong(id: String): TrotSong = call(get("$base/catalog/songs/$id"), TrotSong.serializer())

    /**
     * 🔑 [token]은 이미 가진 세션이 있을 때만 붙인다 — 서버가 주인을 확인하면 신청곡을 제목으로 소개한다. 없어도 브리프는 온다.
     */
    suspend fun brief(drive: DriveInput, voice: Boolean, nowPlaying: NowPlaying? = null, ment: String? = null, token: String? = null, dj: String? = null): DjBrief =
        call(post("$base/dj/brief", BriefRequest(drive, voice, nowPlaying, ment, dj), BriefRequest.serializer(), token), DjBrief.serializer(), briefClient)

    /** 미리 받아 두는 DJ 음성 팩의 목록 — DJ마다 따로다. */
    suspend fun voicePack(dj: String = DjInfo.DEFAULT_ID): VoicePack {
        val url = "$base/dj/voice-pack".toHttpUrl().newBuilder().addQueryParameter("dj", dj).build().toString()
        return call(get(url), VoicePack.serializer())
    }

    /** 음성 조각 하나를 [into]에 내려받는다. 실패하면 던진다. */
    suspend fun download(url: String, into: File): Unit = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(absolute(url)).build()).execute().use { response ->
            if (!response.isSuccessful) throw DjTrotApiException(response.code, "", "HTTP ${response.code}")
            into.outputStream().use { out -> response.body.byteStream().copyTo(out) }
        }
    }

    suspend fun startPairing(deviceName: String): PairingStarted =
        call(post("$base/pairings", PairingStart(deviceName), PairingStart.serializer()), PairingStarted.serializer())

    suspend fun claimPairing(pairingId: String, claimSecret: String): PairingResult =
        call(post("$base/pairings/claim", PairingClaim(pairingId, claimSecret), PairingClaim.serializer()), PairingResult.serializer())

    suspend fun deviceSession(device: DeviceCredential): DeviceSession =
        call(post("$base/devices/session", device, DeviceCredential.serializer()), DeviceSession.serializer())

    /** 광고 목록 — 광고 음악과 틀 조건. 장소 판단은 차가 한다. */
    suspend fun ads(): AdsResponse = call(get("$base/ads"), AdsResponse.serializer())

    /** 광고가 나오기 시작했다·끝까지 나왔다고 알린다(통계용, 로그인 없이). */
    suspend fun adPlayed(adId: String, event: String) {
        call(post("$base/ads/$adId/played", AdPlayed(event), AdPlayed.serializer()), AdPlayedOk.serializer())
    }

    // ── 로그인 ──
    suspend fun adProgress(token: String): AdProgress = call(get("$base/ads/progress", token), AdProgress.serializer())

    /** 광고 한 편을 끝까지 들었다고 알린다 — 세 번이면 생성권 한 장. 같은 [playKey]는 한 번만 센다. */
    suspend fun adHeard(token: String, adId: String, playKey: String): AdProgress =
        call(post("$base/ads/$adId/heard", AdHeard(playKey), AdHeard.serializer(), token), AdProgress.serializer())

    suspend fun benefits(token: String): BenefitSnapshot = call(get("$base/benefits", token), BenefitSnapshot.serializer())

    suspend fun createSong(auth: SongAuth, request: CreateSong): DriveSong =
        call(post("$base/songs", request, CreateSong.serializer(), auth.token).guest(auth), DriveSong.serializer())

    suspend fun song(auth: SongAuth, id: String): DriveSong = call(get("$base/songs/$id", auth.token).guest(auth), DriveSong.serializer())

    suspend fun songs(auth: SongAuth, cursor: String? = null): DriveSongPage {
        val url = "$base/songs".toHttpUrl().newBuilder().apply { if (cursor != null) addQueryParameter("cursor", cursor) }.build().toString()
        return call(get(url, auth.token).guest(auth), DriveSongPage.serializer())
    }

    /** 세션이 없으면 시연 손님 ID를 붙인다. */
    private fun Request.guest(auth: SongAuth): Request = if (auth.token == null && auth.guest != null) newBuilder().header(SongAuth.GUEST_HEADER, auth.guest).build() else this

    /** 서버가 준 상대 경로(`/api/v1/dj-trot/dj/voice/…`)를 절대 주소로. 이미 절대면 그대로. */
    fun absolute(path: String): String = origin.resolve(path)?.toString() ?: path

    /** 로그인 토큰이 있어야 받는 음원인가 — 드라이브 노래(`/songs/{id}/audio`). */
    fun needsToken(url: String): Boolean = url.startsWith("$base/songs/")

    private fun get(url: String, token: String? = null) = Request.Builder().url(url).header("Accept", "application/json")
        .apply { if (token != null) header("Authorization", "Bearer $token") }.build()

    private fun <T> post(url: String, value: T, serializer: KSerializer<T>, token: String? = null) = Request.Builder().url(url)
        .header("Accept", "application/json")
        .apply { if (token != null) header("Authorization", "Bearer $token") }
        .post(json.encodeToString(serializer, value).toRequestBody(JSON))
        .build()

    private suspend fun <T> call(request: Request, serializer: KSerializer<T>, http: OkHttpClient = client): T = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                val detail = runCatching { json.decodeFromString(ApiErrorBody.serializer(), body).error }.getOrNull()
                throw DjTrotApiException(response.code, detail?.code.orEmpty(), detail?.message ?: "HTTP ${response.code}")
            }
            json.decodeFromString(serializer, body)
        }
    }

    // 🔑 DJ 브리프는 곡이 시작될 때 부른다 — 오래 기다리면 멘트가 곡을 놓친다. 넘으면 기기 템플릿으로 읽는다.
    private val briefClient = client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()

    companion object {
        private val JSON = "application/json".toMediaType()

        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
            encodeDefaults = true
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
