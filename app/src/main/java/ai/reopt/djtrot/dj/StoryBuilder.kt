package ai.reopt.djtrot.dj

import ai.reopt.djtrot.api.BriefSong
import ai.reopt.djtrot.api.Labels
import ai.reopt.djtrot.car.DriveContext
import ai.reopt.djtrot.car.PlaceKind
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

    /**
     * 예시 사연 — 트롯 제작실에서 한 번 눌러 넣는다(버튼 이름 to 사연). 말로 넣은 사연과 같은 길로 간다.
     * 🔑 운전석에서 글자를 치지 않게 하려는 것이다. 사연의 낱말이 주제 고르기에 걸리도록 쓴다(어머니·여보·친구…).
     */
    val IDEAS: List<Pair<String, String>> = listOf(
        "어머니 뵈러" to "어머니 뵈러 고향 가는 길이에요",
        "고생한 퇴근길" to "오늘 하루 고생한 나를 위한 퇴근길 노래",
        "여보 고마워" to "여보에게 고맙다고 전하고 싶어요",
        "친구 만나러" to "오랜 친구들 만나러 가는 길이에요",
        "설레는 여행" to "오랜만에 떠나는 여행길이 설레요",
        "힘내자 응원" to "오늘도 힘내자고 응원하고 싶어요",
    )

    fun build(drive: DriveContext, now: LocalTime, spoken: String? = null): SongRequest {
        val place = when (drive.destination?.kind) {
            PlaceKind.HOME -> "집"
            PlaceKind.OFFICE -> "회사"
            null -> null
        }
        val words = listOfNotNull(drive.destination?.name, drive.destination?.address, place, spoken).joinToString(" ")
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
        drive.raining || drive.fog -> "ballad"
        DjScript.timeOfDay(now) in setOf(DjScript.TimeOfDay.NIGHT, DjScript.TimeOfDay.DAWN) -> "ballad"
        // 🔑 세미 트로트(dance)는 만들지 않는다 — 서버 선택지에서 뺐다. 그 밖에는 정통 트로트.
        else -> "classic"
    }

    fun story(drive: DriveContext, now: LocalTime, spoken: String?): String {
        val road = (if (drive.raining) "비 오는 " else "") + DjScript.timeOfDay(now).road
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
