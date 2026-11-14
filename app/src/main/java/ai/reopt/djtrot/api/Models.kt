package ai.reopt.djtrot.api

import kotlinx.serialization.Serializable

/**
 * DJ 트롯 서버(`/api/v1/dj-trot`)와 주고받는 모양.
 * 🔑 서버는 키를 더해 가므로 `ignoreUnknownKeys`로 읽는다(DjTrotApi.json). 노래는 출처와 무관하게 `trot-song/1` 하나다.
 */

/** 가사 한 줄과 노래 안에서의 시각(초). */
@Serializable
data class LyricLine(
    val text: String,
    val start: Double,
    val end: Double,
)

/** 줄별 시각. 정렬이 끝난 곡만 `state == "ready"`이고, 아니면 줄이 비어 화면은 가사를 띄우지 않는다. */
@Serializable
data class LyricSync(
    val state: String = "missing",
    val lines: List<LyricLine> = emptyList(),
)

@Serializable
data class TrotLabels(val style: String = "", val topic: String = "", val voice: String = "")

@Serializable
data class TrotAudio(val url: String, val contentType: String = "audio/mpeg")

@Serializable
data class TrotCover(val url: String)

@Serializable
data class TrotLyrics(val title: String = "", val lines: List<String> = emptyList())

@Serializable
data class Provenance(
    /** house(운영곡) · generated(드라이브 노래) */
    val kind: String = "house",
    val createdAt: String = "",
)

/** `trot-song/1` — 제공 목록의 곡이든 드라이브에서 만든 곡이든 같은 모양. */
@Serializable
data class TrotSong(
    val schema: String = "trot-song/1",
    val id: String,
    val title: String,
    val artist: String = "",
    val style: String = "",
    val topic: String = "",
    val voice: String = "",
    val labels: TrotLabels = TrotLabels(),
    val durationSeconds: Double? = null,
    /** 제공 목록은 공개 절대 주소, 드라이브 노래는 로그인이 필요한 상대 경로 `/api/v1/dj-trot/songs/{id}/audio`. */
    val audio: TrotAudio,
    val cover: TrotCover? = null,
    val lyrics: TrotLyrics = TrotLyrics(),
    val lyricSync: LyricSync = LyricSync(),
    val provenance: Provenance = Provenance(),
)

/** 선반 — `picks`, `latest`, `style:{style}`. */
@Serializable
data class Shelf(
    val id: String,
    val title: String,
    val description: String = "",
    val songs: List<TrotSong> = emptyList(),
)

@Serializable
data class CatalogResponse(val generatedAt: String = "", val shelves: List<Shelf> = emptyList())

@Serializable
data class SongList(val songs: List<TrotSong> = emptyList())

@Serializable
data class Choice(val id: String, val label: String, val description: String = "")

@Serializable
data class Choices(
    val topic: List<Choice> = emptyList(),
    val style: List<Choice> = emptyList(),
    val voice: List<Choice> = emptyList(),
)

@Serializable
data class ChoiceDefaults(val topic: String = "life", val style: String = "classic", val voice: String = "female")

@Serializable
data class CreationPolicy(val open: Boolean = true, val guest: Boolean = false)

@Serializable
data class TokenPolicy(val welcome: Int = 0, val dailyLimit: Int = 0)

@Serializable
data class DjConfig(
    val choices: Choices = Choices(),
    val defaults: ChoiceDefaults = ChoiceDefaults(),
    val tokens: TokenPolicy = TokenPolicy(),
    /** 노래 만들기가 열려 있는가. 예전 서버는 주지 않는다 — 그때는 열림으로 본다. */
    val creation: CreationPolicy = CreationPolicy(),
    /** 고를 수 있는 DJ. 예전 서버는 주지 않는다 — 그때는 기본 DJ만 쓴다. */
    val djs: List<DjInfo> = emptyList(),
)

/**
 * DJ — 목소리와 말투가 다른 진행자. [ready]가 참이면 서버에 그 DJ의 음성 팩이 준비돼 있어 고를 수 있다.
 * 🔴 [id]는 서버와의 계약이다(`hwanggu`·`mari`).
 */
@Serializable
data class DjInfo(val id: String, val name: String, val description: String = "", val ready: Boolean = false) {
    companion object {
        const val DEFAULT_ID = "hwanggu"

        /** 서버가 DJ 목록을 주지 않을 때 — 기본 DJ 하나. */
        val DEFAULT = DjInfo(DEFAULT_ID, "DJ 황", "느끼한 다방 DJ · 낮고 느린 목소리", ready = true)
    }
}

/**
 * 서버에 보내는 드라이브 맥락. 🔴 서버 스키마가 strict라 이 키만 보낸다(모르는 키는 400).
 */
@Serializable
data class DriveInput(
    val destination: DestinationInput? = null,
    val currentAddress: String? = null,
    val region: String? = null,
    /** NORMAL · ECO · SPORT 만 */
    val drivingMode: String? = null,
    /** HH:mm */
    val localTime: String? = null,
    /** 말로 넣은 사연(STT). 최대 200자. */
    val spoken: String = "",
    /** 달리는 중인가. 모르면 null — 서버 멘트가 단정하지 않는다. */
    val moving: Boolean? = null,
    /** PARK · DRIVE · REVERSE · NEUTRAL · UNKNOWN */
    val gear: String? = null,
    /** rain · fog 만. 맑거나 모르면 보내지 않는다. */
    val weather: String? = null,
    /** 지금 길에서 가장 먼저 꺼낼 말 하나(휴게소·터널·정체…). 🔴 서버가 아는 값만 — 다른 값은 400이다. */
    val scene: String? = null,
)

@Serializable
data class DestinationInput(val name: String, val address: String? = null, val remainMinutes: Int? = null)

/**
 * 지금 트는 곡 — DJ가 곡을 소개하게 함께 보낸다.
 * 🔑 [songId]는 드라이브 노래(맡긴 사연으로 만든 신청곡)일 때만 보낸다. 서버가 "아까 맡기신 사연으로 막 만든 노래"로 소개한다.
 * 🔴 서버는 uuid만 받는다 — 운영곡 ID를 넣으면 400이다.
 */
@Serializable
data class NowPlaying(val title: String, val artist: String, val songId: String? = null)

@Serializable
data class BriefRequest(
    val drive: DriveInput,
    val voice: Boolean,
    val nowPlaying: NowPlaying? = null,
    /** full · short · lead. short면 곡 소개만, lead면 곡 소개 없이 상황의 말만 받는다. 정하지 않으면 보내지 않는다. */
    val ment: String? = null,
    /** 말하는 DJ의 ID. 정하지 않으면 보내지 않고, 서버가 기본 DJ로 답한다. */
    val dj: String? = null,
)

/** DJ 브리프가 제안하는 신청곡. */
@Serializable
data class BriefSong(
    val topic: String,
    val style: String,
    val voice: String,
    val story: String,
    val title: String = "",
)

/**
 * DJ 브리프 — 멘트와 신청곡 제안. [voiceUrl]이 있으면 서버가 만든 멘트 음성(상대 경로)이다.
 * 🔑 [voiceClips]가 있으면 그 음성 조각들을 순서대로 이어 튼다([voiceUrl]보다 먼저). 주소가 곧 내용이라 받아 둔 파일을 다시 써도 된다.
 */
@Serializable
data class DjBrief(
    val script: String,
    val song: BriefSong,
    val voiceUrl: String? = null,
    val voiceClips: List<String> = emptyList(),
    /** ai · template */
    val source: String = "template",
)

@Serializable
data class PairingStart(val deviceName: String)

@Serializable
data class PairingStarted(
    val pairingId: String,
    val pin: String,
    val claimSecret: String,
    val connectUrl: String,
    val expiresAt: String,
)

@Serializable
data class PairingClaim(val pairingId: String, val claimSecret: String)

@Serializable
data class DeviceCredential(val deviceId: String, val deviceSecret: String)

/** pending · approved · claimed · expired. claimed일 때만 [device]가 한 번 온다. */
@Serializable
data class PairingResult(val state: String, val device: DeviceCredential? = null)

@Serializable
data class DeviceSession(
    val accountId: String,
    val accessToken: String,
    val expiresAt: String,
    val provider: String = "",
)

@Serializable
data class Benefit(val id: String, val title: String = "", val kind: String = "", val balance: Int? = null)

@Serializable
data class BenefitSnapshot(val checkedAt: String = "", val benefits: List<Benefit> = emptyList()) {
    /** 드라이브 제작 토큰 잔액. */
    val tokens: Int get() = benefits.firstOrNull { it.id == "token" }?.balance ?: 0
}

@Serializable
data class CreateSong(
    /** 8~64자 영숫자·하이픈. 같은 키로 다시 보내면 새로 만들지 않는다. */
    val requestKey: String,
    val topic: String,
    val style: String,
    val voice: String,
    val story: String,
    val drive: DriveInput? = null,
)

@Serializable
data class SongBrief(val topic: String = "", val style: String = "", val voice: String = "", val story: String = "")

/** 드라이브 노래 — queued · running · ready · failed. ready면 [song]이 채워진다. 실패하면 토큰은 서버가 돌려준다. */
@Serializable
data class DriveSong(
    val id: String,
    val state: String,
    val errorCode: String? = null,
    val brief: SongBrief = SongBrief(),
    val song: TrotSong? = null,
    /** 제작 중계 — 만드는 동안의 단계와 제목·가사 몇 줄. 예전 서버는 주지 않는다. */
    val progress: SongProgress? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
)

/** 만드는 중인 노래의 진행 — [phase]는 lyrics(작사) · compose(작곡) · finish(마무리). 작사가 끝나면 [title]과 [lines]가 채워진다. */
@Serializable
data class SongProgress(val phase: String = "lyrics", val title: String? = null, val lines: List<String> = emptyList())

@Serializable
data class DriveSongPage(val songs: List<DriveSong> = emptyList(), val nextCursor: String? = null)

@Serializable
data class ApiErrorBody(val error: ApiErrorDetail? = null)

@Serializable
data class ApiErrorDetail(val code: String = "", val message: String = "")

/**
 * 선택지 이름. 서버 `/config`로 바꿔 끼우고, 받기 전·오프라인에는 아래 기본값을 쓴다(서버 기본값과 같은 표).
 */
object Labels {
    @Volatile
    private var config = DjConfig(
        choices = Choices(
            topic = listOf(
                Choice("love", "사랑"), Choice("spouse", "여보 당신"), Choice("mother", "어머니"), Choice("hometown", "고향"),
                Choice("life", "인생"), Choice("friends", "친구"), Choice("cheer", "힘내라 응원가"), Choice("health", "건강 장수"),
            ),
            style = listOf(
                Choice("ppongjjak", "신나는 뽕짝"), Choice("classic", "정통 트로트"), Choice("ballad", "트로트 발라드"),
            ),
            voice = listOf(Choice("male", "남자 목소리"), Choice("female", "여자 목소리")),
        ),
    )

    val current: DjConfig get() = config

    fun update(next: DjConfig) {
        if (next.choices.style.isNotEmpty()) config = next
    }

    fun style(id: String?): String? = id?.let { key -> config.choices.style.firstOrNull { it.id == key }?.label }
    fun topic(id: String?): String? = id?.let { key -> config.choices.topic.firstOrNull { it.id == key }?.label }
    fun voice(id: String?): String? = id?.let { key -> config.choices.voice.firstOrNull { it.id == key }?.label }
}

/** DJ 음성 팩의 조각 하나 — [id]는 조각의 뜻(서버와의 계약), [text]는 화면에 보일 글, [url]은 음성 주소. */
@Serializable
data class VoiceClip(val id: String, val text: String, val url: String)

/** 광고 장소 — 이 좌표의 [radiusMeters] 안에 들어오면 맞는다. */
@Serializable
data class AdPlace(val name: String = "", val latitude: Double, val longitude: Double, val radiusMeters: Int = 500)

/** 광고 조건 — 셋 가운데 하나라도 맞으면 튼다. 판단은 차가 한다(좌표·주소는 서버로 가지 않는다). */
@Serializable
data class AdConditions(
    /** 지나는 주소나 목적지의 이름·주소에 이 낱말이 있으면 맞는다. */
    val keywords: List<String> = emptyList(),
    /** 목적지 이름에 이 낱말이 있으면 맞는다(서버가 목적지 종류를 낱말로 풀어 준다). */
    val destinationWords: List<String> = emptyList(),
    val places: List<AdPlace> = emptyList(),
)

/** 광고 한 편 — 광고 음악([song])과 틀 조건. */
@Serializable
data class Ad(
    val id: String,
    val title: String,
    val song: TrotSong,
    /** 광고 음악을 앞에서부터 이만큼만 튼다(초). 0이면 곡 끝까지. 이 길이를 다 들으면 끝까지 들은 것이다. */
    val maxSeconds: Int = 40,
    val conditions: AdConditions = AdConditions(),
)

/** 재생 알림의 답. */
@Serializable
data class AdPlayedOk(val ok: Boolean = true)

/** 재생 알림(통계용) — started(나오기 시작) · completed(끝까지 나옴). 생성권과는 무관하다. */
@Serializable
data class AdPlayed(val event: String)

@Serializable
data class AdReward(val listens: Int = 3, val tokens: Int = 1, val maxBalance: Int = 2)

@Serializable
data class AdsResponse(val ads: List<Ad> = emptyList(), val reward: AdReward = AdReward())

/** 광고 한 편을 끝까지 들었다는 알림 — [playKey]는 그 재생 한 번의 키다(같은 키는 한 번만 센다). */
@Serializable
data class AdHeard(val playKey: String)

/** 광고 진행 — 다음 생성권까지 들은 횟수. [capped]면 생성권이 가득 차 기다리는 중이다. */
@Serializable
data class AdProgress(
    val progress: Int = 0,
    val needed: Int = 3,
    val granted: Boolean = false,
    val capped: Boolean = false,
    /** 오늘 광고로 받을 수 있는 생성권을 다 받았다. */
    val limited: Boolean = false,
    val balance: Int = 0,
)

/** 차가 미리 받아 두는 DJ 음성 팩. [version]이 null이면 서버가 팩을 쓰지 않는 중이다. */
@Serializable
data class VoicePack(val version: String? = null, val clips: List<VoiceClip> = emptyList())

/** 드라이브 노래 요청의 자격 — 기기 세션([token]) 또는 시연 손님 ID([guest]). 둘 중 하나만 쓴다(세션이 먼저). */
data class SongAuth(val token: String? = null, val guest: String? = null) {
    companion object {
        const val GUEST_HEADER = "X-DjTrot-Guest"
    }
}
