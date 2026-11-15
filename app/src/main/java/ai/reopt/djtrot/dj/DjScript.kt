package ai.reopt.djtrot.dj

import ai.reopt.djtrot.car.Arrival
import ai.reopt.djtrot.car.Caution
import ai.reopt.djtrot.car.Departure
import ai.reopt.djtrot.car.DriveContext
import ai.reopt.djtrot.car.PlaceKind
import ai.reopt.djtrot.car.RoadAhead
import ai.reopt.djtrot.car.Traffic
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import kotlin.math.roundToInt

/** [requested] — 맡긴 사연으로 만든 드라이브 노래(신청곡). [fresh] — 만든 지 [DjScript.FRESH] 안이다. */
data class SongInfo(val title: String, val singer: String, val requested: Boolean = false, val fresh: Boolean = false)

/**
 * DJ 멘트 — 곡이 시작할 때 TTS로 읽는다.
 * 🔑 운전 중에 듣는 말이라 두 문장을 넘기지 않는다. 같은 곡은 같은 멘트(seed=곡 ID)라 다시 들어도 어색하지 않다.
 */
object DjScript {
    /**
     * 곡 소개 한 문장. 서버 DJ 멘트가 곡을 말하지 않을 때 뒤에 붙인다.
     * 🔑 드라이브 노래의 가수명은 DJ 자신(`DJ 트롯`)이라 부르지 않는다 — 서버 DJ 브리프와 같은 규칙.
     */
    fun play(song: SongInfo, seed: Int): String {
        val title = "'${song.title.trim()}'"
        if (song.requested && song.fresh) return when (Math.floorMod(seed, 2)) {
            0 -> "아까 맡기신 사연으로 막 만든 노래 $title 띄워 드립니다."
            else -> "맡기신 사연으로 방금 완성한 신청곡 $title 들려 드립니다."
        }
        if (song.requested) return when (Math.floorMod(seed, 2)) {
            0 -> "지난번 맡기신 사연으로 만든 노래 $title 띄워 드립니다."
            else -> "전에 맡기신 사연으로 만든 신청곡 $title 다시 들려 드립니다."
        }
        val singer = song.singer.trim().takeUnless(::isDjName).orEmpty()
        if (singer.isEmpty()) return when (Math.floorMod(seed, 3)) {
            0 -> "$title 띄워 드립니다."
            1 -> "이번 곡은 ${title}입니다."
            else -> "$title 들어 보시죠."
        }
        return when (Math.floorMod(seed, 3)) {
            0 -> "${singer}의 $title 띄워 드립니다."
            1 -> "이번 곡은 ${singer}의 ${title}입니다."
            else -> "${singer}의 목소리로 $title 들어 보시죠."
        }
    }

    /**
     * 오프라인 멘트 — 서버 DJ 브리프를 받지 못했을 때.
     * 🔑 지금 길에서 할 말([remarks])이 여럿이면 앞의 두 가지를 곡마다 번갈아 쓴다 — 막히는 길에서 매 곡 "막히네요"만 하지 않게.
     * 🔴 어린이보호구역에서는 곡 이름만 말한다.
     */
    fun intro(song: SongInfo, drive: DriveContext, now: LocalTime, seed: Int): String {
        val play = play(song, seed)
        if (drive.schoolZone) return play
        val destination = drive.destination
        val minutes = destination?.remainMinutes
        if (destination != null && minutes != null && minutes <= 3) return "${destination.name}, 거의 다 오셨네요. $play"
        val remarks = remarks(drive, now)
        if (remarks.isEmpty()) return "${timeOfDay(now).greeting}. $play"
        return "${remarks[Math.floorMod(seed, minOf(remarks.size, 2))]} $play"
    }

    /** 지금 길에서 DJ가 꺼낼 만한 말 — 급한 것부터. 곡 소개 앞에 그대로 붙는다. */
    fun remarks(drive: DriveContext, now: LocalTime): List<String> {
        val time = timeOfDay(now)
        val destination = drive.destination
        val minutes = destination?.remainMinutes
        return listOfNotNull(
            when (drive.ahead) {
                RoadAhead.REST_AREA -> "곧 휴게소네요. 잠깐 쉬어 가셔도 좋겠어요."
                RoadAhead.TUNNEL -> "곧 터널입니다. 터널 안에서도 노래는 이어집니다."
                RoadAhead.TOLL -> "곧 톨게이트를 지납니다."
                null -> null
            },
            when (drive.caution) {
                Caution.FOG -> "안개가 잦은 구간입니다. 속도를 조금 줄여 가시죠."
                Caution.SLIPPERY -> "길이 미끄러운 구간입니다. 천천히 가시죠."
                Caution.WILDLIFE -> "야생동물이 나오는 길입니다. 앞을 잘 살펴 가시죠."
                Caution.ACCIDENT -> "사고가 잦은 곳입니다. 조심히 가시죠."
                null -> null
            },
            drive.traffic?.let(::jam),
            drive.tripMinutes?.takeIf { drive.longDrive }?.let { "벌써 ${duration(it)}째 달리고 계시네요. 잠깐 쉬어 가셔도 좋겠어요." },
            if (drive.lowBattery) "배터리가 얼마 남지 않았네요. 가까운 충전소에 들렀다 가시죠." else null,
            when {
                drive.fog -> "안개 낀 ${time.road}이네요. 천천히 가시죠."
                drive.raining -> "비 오는 ${time.road}이네요."
                else -> null
            },
            if (destination != null && minutes != null) toward(destination.name, destination.kind, "${minutes}분 남았네요.") else null,
            if (drive.charging) "충전하는 동안 한 곡 들으시죠." else null,
            if (drive.company) "함께 타신 분과 같이 들으시죠." else null,
            when {
                drive.defrosting -> "유리에 김이 서렸나 보네요. 앞이 맑아질 때까지 천천히 가시죠."
                drive.seatHeat -> "시트를 따뜻하게 데우셨네요. 따뜻한 노래 한 곡 들으시죠."
                drive.seatCool -> "시트에 시원한 바람을 트셨네요. 시원한 노래 한 곡 들으시죠."
                drive.cold -> "바깥이 많이 춥네요. 따뜻한 노래 한 곡 들으시죠."
                drive.hot -> "바깥이 참 덥네요. 시원한 노래 한 곡 들으시죠."
                else -> null
            },
            if (drive.sport && !drive.stopped) "스포츠 모드로 신나게 가는 길이네요. 안전하게 가시죠." else null,
            if (drive.cruising) "크루즈 켜고 편안히 가는 길이네요." else null,
            drive.region?.let { "$it 지나시는 ${time.road}에" },
            if (drive.freeDrive && destination == null && !drive.stopped) "발길 닿는 대로 달리는 ${time.road}이네요." else null,
        )
    }

    /** 목적지를 부르며 시작하는 말 — 즐겨찾기의 집·회사는 이름 대신 "집으로 가는 길". */
    private fun toward(name: String, kind: PlaceKind?, rest: String): String = when (kind) {
        PlaceKind.HOME -> "집으로 가는 길, $rest"
        PlaceKind.OFFICE -> "회사 가는 길, $rest"
        null -> "${name}까지 $rest"
    }

    private fun jam(traffic: Traffic): String {
        val where = traffic.road?.let { "$it 쪽이" } ?: "가는 길이"
        return when {
            traffic.accident -> "가는 길에 사고 소식이 있네요. 천천히 가시죠."
            traffic.roadwork && traffic.jamMinutes < Traffic.JAM_MINUTES -> "가는 길에 공사 구간이 있네요. 천천히 가시죠."
            traffic.roadwork -> "$where 공사로 ${traffic.jamMinutes}분쯤 막히네요. 느긋하게 가시죠."
            else -> "$where ${traffic.jamMinutes}분쯤 막히네요. 느긋하게 가시죠."
        }
    }

    /** 길 안내를 시작할 때 이번 길을 여는 한 문장. 노래는 끊지 않는다. */
    fun departure(departure: Departure): String {
        val minutes = departure.minutes?.takeIf { it > 0 }
        return when {
            minutes == null && departure.kind == null -> "${Josa.ro(departure.name)} 출발합니다. 편안히 모실게요."
            minutes == null -> toward(departure.name, departure.kind, "출발합니다. 편안히 모실게요.")
            minutes < SHORT_TRIP_MINUTES -> toward(departure.name, departure.kind, "${duration(minutes)}이면 닿습니다. 한 곡 들으며 가시죠.")
            else -> toward(departure.name, departure.kind, "${duration(minutes)} 길입니다. 편안히 모실게요.")
        }
    }

    /** 경유지에 닿았을 때. */
    fun waypoint(arrival: Arrival): String = "${arrival.name}에 들렀습니다. 남은 길도 함께할게요."

    /** 목적지에 닿았을 때 이번 길을 마무리하는 한 문장. 노래는 끊지 않는다. 가까운 길에는 수고했다고 하지 않는다. */
    fun arrival(arrival: Arrival): String {
        val minutes = arrival.minutes?.takeIf { it > 0 }
        val km = arrival.km?.takeIf { it >= 1 }?.let { "${it.roundToInt()}km" }
        val arrived = "${arrival.name}에 도착했습니다."
        if (minutes != null && minutes < SHORT_TRIP_MINUTES) return "$arrived 가까운 길도 함께해서 즐거웠어요."
        val took = listOfNotNull(minutes?.let(::duration), km).joinToString(", ")
        return if (took.isEmpty()) "$arrived 운전하시느라 고생 많으셨어요." else "$arrived $took 운전하시느라 고생 많으셨어요."
    }

    private fun duration(minutes: Int): String = when {
        minutes < 60 -> "${minutes}분"
        minutes % 60 == 0 -> "${minutes / 60}시간"
        else -> "${minutes / 60}시간 ${minutes % 60}분"
    }

    private const val SHORT_TRIP_MINUTES = 15

    /** 🔑 "아까 막 만든"이라 부를 수 있는 시간 — 서버 DJ 브리프와 같은 1시간. */
    val FRESH: Duration = Duration.ofHours(1)

    /** 드라이브 노래 생성 시각(`provenance.createdAt`, ISO)이 [FRESH] 안인가. 모르면 지난 곡으로 본다. */
    fun isFresh(createdAt: String?, now: Instant = Instant.now()): Boolean {
        val made = createdAt?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: return false
        return !made.isAfter(now.plusSeconds(60)) && Duration.between(made, now) < FRESH
    }

    private fun isDjName(singer: String) = singer.filterNot(Char::isWhitespace).equals("DJ트롯", ignoreCase = true)

    enum class TimeOfDay(val greeting: String, val road: String) {
        DAWN("새벽길 조심히 가세요", "새벽길"),
        MORNING("좋은 아침입니다", "아침길"),
        AFTERNOON("나른한 오후입니다", "오후 길"),
        EVENING("오늘 하루도 고생 많으셨습니다", "저녁길"),
        NIGHT("밤길 운전 조심하세요", "밤길"),
    }

    fun timeOfDay(now: LocalTime): TimeOfDay = when (now.hour) {
        in 0..4 -> TimeOfDay.DAWN
        in 5..10 -> TimeOfDay.MORNING
        in 11..16 -> TimeOfDay.AFTERNOON
        in 17..20 -> TimeOfDay.EVENING
        else -> TimeOfDay.NIGHT
    }
}
