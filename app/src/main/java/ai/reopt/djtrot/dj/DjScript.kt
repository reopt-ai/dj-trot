package ai.reopt.djtrot.dj

import ai.reopt.djtrot.car.Arrival
import ai.reopt.djtrot.car.DriveContext
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

    /** 오프라인 멘트 — 서버 DJ 브리프를 받지 못했을 때. */
    fun intro(song: SongInfo, drive: DriveContext, now: LocalTime, seed: Int): String {
        val play = play(song, seed)
        val destination = drive.destination
        val minutes = destination?.remainMinutes
        val region = drive.region
        val time = timeOfDay(now)
        return when {
            destination != null && minutes != null && minutes <= 3 -> "${destination.name}, 거의 다 오셨네요. $play"
            destination != null && minutes != null -> "${destination.name}까지 ${minutes}분 남았네요. $play"
            region != null -> "$region 지나시는 ${time.road}에 $play"
            else -> "${time.greeting}. $play"
        }
    }

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
