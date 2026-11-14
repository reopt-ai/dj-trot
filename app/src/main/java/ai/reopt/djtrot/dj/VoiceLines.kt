package ai.reopt.djtrot.dj

import ai.reopt.djtrot.car.Arrival
import ai.reopt.djtrot.car.Departure
import ai.reopt.djtrot.car.DriveContext
import ai.reopt.djtrot.car.GearState
import ai.reopt.djtrot.car.PlaceKind
import ai.reopt.djtrot.car.RoadAhead

/**
 * DJ 음성 팩의 조각 고르기 — 멘트를 조각(여는 말 → 남은 시간 → 곡 소개)으로 이어 틀 때 어떤 조각을 쓸지 정한다.
 * 🔴 조각 ID와 고르는 규칙은 서버와의 계약이다. 서버가 같은 입력에 같은 조각을 고르므로, 여기 규칙을 혼자 바꾸면 온라인과 오프라인의 멘트가 달라진다.
 * 🔑 순수 함수다 — 받아 둔 조각이 있는지는 [VoicePackStore]가 따로 본다.
 */
object VoiceLines {
    /** 길 위의 장면 — 지금 가장 먼저 꺼낼 말 하나. [variants]는 그 장면의 변주 수. */
    enum class Scene(val id: String, val variants: Int = 1) {
        REST_AREA("rest_area"),
        TUNNEL("tunnel"),
        TOLL("toll"),
        JAM("jam", 2),
        ACCIDENT("accident"),
        LONG_DRIVE("long_drive"),
        CHARGING("charging"),
        COMPANY("company"),
        CRUISING("cruising"),
        FREE_DRIVE("free_drive"),
    }

    /** 곡마다 변주를 고르는 씨앗(FNV-1a, 0..2^32-1). 같은 곡은 같은 멘트가 나온다. */
    fun seedOf(text: String): Long {
        var hash = 2166136261L.toInt()
        for (unit in text) hash = (hash xor unit.code) * 16777619
        return hash.toLong() and 0xFFFFFFFFL
    }

    /** 곡의 씨앗 — 제목과 가수. 🔑 가수명이 DJ 자신이면 비운다(서버가 그렇게 센다). */
    fun seedOf(song: SongInfo): Long {
        val singer = song.singer.trim().takeUnless { it.filterNot(Char::isWhitespace).equals("DJ트롯", ignoreCase = true) }.orEmpty()
        return seedOf("${song.title.trim()}\n$singer")
    }

    fun timeSlot(hour: Int): String = when {
        hour <= 4 -> "dawn"
        hour <= 10 -> "morning"
        hour <= 16 -> "afternoon"
        hour <= 20 -> "evening"
        else -> "night"
    }

    /** 남은 시간(분)의 구간 — 가장 가까운 구간, 같은 거리면 짧은 쪽. */
    fun etaBucket(minutes: Int): String = when {
        minutes <= 3 -> "near"
        minutes > 150 -> "far"
        else -> ETA_MINUTES.minBy { kotlin.math.abs(it - minutes) }.toString()
    }

    /**
     * 지금 길의 장면. 급한 것부터 — 곧 지나는 곳, 정체·사고, 오래 달림. 그다음은 분위기(충전, 동승, 크루즈, 발길 닿는 대로).
     * 🔑 분위기 장면은 곡의 절반([seed] 짝수)에만 쓴다 — 크루즈를 켜 둔 내내 같은 말만 하지 않게.
     *    [always]가 참이면(시연) 곡을 가리지 않고 쓴다.
     * 🔴 [local]이 거짓이면 서버로 보낼 값이다 — 동승자 유무는 기기 밖으로 내보내지 않는다.
     */
    fun scene(drive: DriveContext, seed: Long, local: Boolean, always: Boolean = false): Scene? {
        when (drive.ahead) {
            RoadAhead.REST_AREA -> return Scene.REST_AREA
            RoadAhead.TUNNEL -> return Scene.TUNNEL
            RoadAhead.TOLL -> return Scene.TOLL
            null -> Unit
        }
        drive.traffic?.let { return if (it.accident) Scene.ACCIDENT else Scene.JAM }
        if (drive.longDrive) return Scene.LONG_DRIVE
        if (!always && seed % 2 != 0L) return null
        return when {
            drive.charging -> Scene.CHARGING
            drive.company && local -> Scene.COMPANY
            drive.cruising -> Scene.CRUISING
            drive.freeDrive && drive.destination == null && !drive.stopped -> Scene.FREE_DRIVE
            else -> null
        }
    }

    /** 서버로 보내는 날씨 — 안개가 먼저. */
    fun weather(drive: DriveContext): String? = when {
        drive.fog -> "fog"
        drive.raining -> "rain"
        else -> null
    }

    /**
     * 곡 소개 앞에 붙는 조각 ID — 여는 말 하나, 목적지와 남은 시간이 있으면 남은 시간 하나.
     * 주차 중이면 한마디만(장면이 있으면 그 말). 장면이 날씨·시간대 인사보다 먼저다.
     */
    fun leadIds(scene: Scene?, weather: String?, parked: Boolean, hasDestination: Boolean, remainMinutes: Int?, place: String, hour: Int, seed: Long): List<String> {
        val sceneId = scene?.let { pick("scene.${it.id}", it.variants, seed) }
        if (parked) return listOf(sceneId ?: pick("open.parked", 2, seed))
        val opener = sceneId ?: when (weather) {
            "rain" -> pick("open.rain", 3, seed)
            "fog" -> pick("open.fog", 2, seed)
            else -> pick("open.${timeSlot(hour)}", 3, seed)
        }
        if (!hasDestination || remainMinutes == null) return listOf(opener)
        return listOf(opener, "eta.$place.${etaBucket(remainMinutes)}")
    }

    /**
     * 기기가 고르는 조각 — 차만 아는 것(즐겨찾기의 집·회사, 동승자)까지 쓴다. 기기 밖으로 나가는 값이 없다.
     */
    fun leadIds(drive: DriveContext, hour: Int, seed: Long, always: Boolean = false): List<String> = leadIds(
        scene = scene(drive, seed, local = true, always = always),
        weather = weather(drive),
        parked = drive.gear == GearState.PARK,
        hasDestination = drive.destination != null,
        remainMinutes = drive.destination?.remainMinutes,
        place = place(drive.destination?.kind),
        hour = hour,
        seed = seed,
    )

    /** 제목 없이 말하는 곡 소개 조각. */
    fun genericPlayId(song: SongInfo, seed: Long): String = when {
        song.requested && song.fresh -> "play.request.fresh"
        song.requested -> "play.request.old"
        else -> pick("play", PLAY_VARIANTS, seed)
    }

    /** 길 안내를 시작할 때의 조각 — 기준은 [DjScript.departure]와 같다. */
    fun departureId(departure: Departure): String {
        val minutes = departure.minutes?.takeIf { it > 0 }
        val length = when {
            minutes == null -> "plain"
            minutes < SHORT_TRIP_MINUTES -> "short"
            else -> "long"
        }
        return "depart.${place(departure.kind)}.$length"
    }

    /** 목적지에 닿았을 때의 조각 — 가까운 길에는 수고했다고 하지 않는다. */
    fun arrivalId(arrival: Arrival): String {
        val minutes = arrival.minutes?.takeIf { it > 0 }
        return if (minutes != null && minutes < SHORT_TRIP_MINUTES) "arrive.short" else "arrive.long"
    }

    const val WAYPOINT_ID = "waypoint"

    /** 제목 없는 곡 소개의 변주 수. 🔴 서버의 조각 수와 같아야 한다. */
    private const val PLAY_VARIANTS = 6

    private fun place(kind: PlaceKind?): String = when (kind) {
        PlaceKind.HOME -> "home"
        PlaceKind.OFFICE -> "office"
        null -> "place"
    }

    private fun pick(prefix: String, count: Int, seed: Long): String = "$prefix.${seed % count + 1}"

    private val ETA_MINUTES = listOf(5, 10, 15, 20, 25, 30, 40, 50, 60, 90, 120)
    private const val SHORT_TRIP_MINUTES = 15
}
