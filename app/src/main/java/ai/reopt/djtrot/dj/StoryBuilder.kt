package ai.reopt.djtrot.dj

import ai.reopt.djtrot.api.BriefSong
import ai.reopt.djtrot.api.Labels
import ai.reopt.djtrot.car.DriveContext
import java.time.LocalTime

/** 드라이브 노래 만들기 요청(`POST /songs`)에 넣을 선택지와 사연. 서버 DJ 브리프가 제안하고, 없으면 [StoryBuilder]가 쓴다. */
data class SongRequest(
    val topic: String,
    val style: String,
    val story: String,
    val voice: String = "female",
    val title: String = "",
) {
    val topicLabel: String get() = Labels.topic(topic) ?: topic
    val styleLabel: String get() = Labels.style(style) ?: style

    companion object {
        fun of(song: BriefSong) = SongRequest(song.topic, song.style, song.story, song.voice, song.title)
    }
}

/**
 * 차가 아는 것으로 신청곡 사연을 쓴다 — "이 드라이브의 노래".
 * 🔑 서버 DJ 브리프(AI)가 정답이고, 이것은 네트워크가 없을 때의 오프라인 대안이다.
 * 🔴 DJ 트롯 사연은 200자 이하다. 넘으면 서버가 거절하므로 여기서 자른다.
 */
object StoryBuilder {
    const val MAX_STORY = 200

    fun build(drive: DriveContext, now: LocalTime, spoken: String? = null): SongRequest {
        val words = listOfNotNull(drive.destination?.name, drive.destination?.address, spoken).joinToString(" ")
        return SongRequest(topic = topic(words), style = style(drive, now), story = story(drive, now, spoken))
    }

    fun topic(words: String): String = when {
        listOf("어머니", "엄마", "친정", "시댁").any { it in words } -> "mother"
        listOf("여보", "당신", "아내", "남편", "결혼").any { it in words } -> "spouse"
        listOf("고향", "본가", "시골", "집").any { it in words } -> "hometown"
        listOf("병원", "의원", "보건", "요양").any { it in words } -> "health"
        listOf("친구", "동창", "모임").any { it in words } -> "friends"
        listOf("회사", "사무실", "출근", "면접", "시험").any { it in words } -> "cheer"
        listOf("데이트", "사랑", "애인").any { it in words } -> "love"
        else -> "life"
    }

    fun style(drive: DriveContext, now: LocalTime): String = when {
        drive.drivingMode == "SPORT" -> "ppongjjak"
        DjScript.timeOfDay(now) in setOf(DjScript.TimeOfDay.NIGHT, DjScript.TimeOfDay.DAWN) -> "ballad"
        drive.drivingMode == "ECO" -> "classic"
        else -> "dance"
    }

    fun story(drive: DriveContext, now: LocalTime, spoken: String?): String {
        val road = DjScript.timeOfDay(now).road
        val from = drive.region
        val to = drive.destination?.name
        val route = when {
            from != null && to != null -> "${from}에서 ${Josa.ro(to)} 가는 $road."
            to != null -> "${Josa.ro(to)} 가는 $road."
            from != null -> "$from 지나는 $road."
            else -> "차 안에서 듣는 $road 노래."
        }
        val time = drive.destination?.remainMinutes?.let { " 도착까지 ${it}분." }.orEmpty()
        val wish = spoken?.trim()?.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
        return (route + time + wish).take(MAX_STORY).trim()
    }
}
