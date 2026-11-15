package ai.reopt.djtrot.debug

import ai.reopt.djtrot.Said
import ai.reopt.djtrot.car.Arrival
import ai.reopt.djtrot.car.Caution
import ai.reopt.djtrot.car.Departure
import ai.reopt.djtrot.car.Destination
import ai.reopt.djtrot.car.DriveContext
import ai.reopt.djtrot.car.GearState
import ai.reopt.djtrot.car.PlaceKind
import ai.reopt.djtrot.car.RoadAhead
import ai.reopt.djtrot.car.Traffic
import ai.reopt.djtrot.dj.DjScript
import ai.reopt.djtrot.dj.SongInfo
import ai.reopt.djtrot.dj.toInput
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 시험 도구가 흉내 내는 차량 신호 — `키=값` 글을 [DriveContext]에 덧씌운다.
 * 🔑 디버그 빌드에만 들어간다. 안드로이드에 기대지 않는 순수 함수라 JVM 테스트로 검사한다.
 */
internal object Staging {
    /** 값을 지워 실제 신호로 되돌린다. */
    const val UNSET = "-"

    /** 실제 신호가 있어도 없는 것으로 덮는다(목적지, 앞길). */
    const val NONE = "none"

    /** 쓸 수 있는 키와 설명 — `tools/trigger.py keys`가 그대로 보여 준다. */
    val KEYS: Map<String, String> = linkedMapOf(
        "gear" to "기어 PARK|DRIVE|REVERSE|NEUTRAL|UNKNOWN",
        "mode" to "주행 모드 NORMAL|ECO|SPORT",
        "address" to "현재 주소(동네 이름을 여기서 뽑는다)",
        "battery" to "배터리 %",
        "dest" to "목적지 이름, none이면 목적지 없음",
        "dest_address" to "목적지 주소",
        "dest_min" to "남은 시간(분)",
        "dest_km" to "남은 거리(km)",
        "dest_kind" to "즐겨찾기 종류 HOME|OFFICE",
        "jam" to "정체 시간(분), 0이면 정체 없음",
        "jam_road" to "가장 막히는 도로 이름",
        "accident" to "가는 길에 사고 on|off",
        "roadwork" to "가는 길에 공사 on|off",
        "ahead" to "곧 지나는 곳 TUNNEL|TOLL|REST_AREA, none이면 없음",
        "caution" to "곧 지나는 주의 구간 FOG|SLIPPERY|WILDLIFE|ACCIDENT, none이면 없음",
        "school" to "어린이보호구역 on|off",
        "free" to "목적지 없이 달림 on|off",
        "trip" to "시동 뒤 달린 시간(분)",
        "cruise" to "크루즈 컨트롤 on|off",
        "port" to "충전구 열림 on|off",
        "charger" to "충전기 꽂힘 on|off",
        "defrost" to "앞유리 성에 제거 on|off",
        "seat_heat" to "운전석 열선 on|off",
        "seat_cool" to "운전석 통풍 on|off",
        "temp" to "바깥 기온(섭씨, 영하는 -5처럼)",
        "fog" to "앞 안개등 on|off",
        "rain" to "비(와이퍼) on|off",
        "company" to "동승자 on|off",
        "signal" to "방향지시등 on|off (DJ가 말을 미룬다)",
        "guidance" to "내비 안내 직전 on|off (DJ가 말을 미룬다)",
        "restricted" to "주행 중 UX 제한 on|off",
    )

    private val FLAGS: Map<String, (DriveContext, Boolean) -> DriveContext> = mapOf(
        "school" to { drive, on -> drive.copy(schoolZone = on) },
        "free" to { drive, on -> drive.copy(freeDrive = on) },
        "cruise" to { drive, on -> drive.copy(cruising = on) },
        "port" to { drive, on -> drive.copy(chargePortOpen = on) },
        "charger" to { drive, on -> drive.copy(chargerConnected = on) },
        "defrost" to { drive, on -> drive.copy(defrosting = on) },
        "seat_heat" to { drive, on -> drive.copy(seatHeat = on) },
        "seat_cool" to { drive, on -> drive.copy(seatCool = on) },
        "fog" to { drive, on -> drive.copy(fog = on) },
        "rain" to { drive, on -> drive.copy(raining = on) },
        "company" to { drive, on -> drive.copy(company = on) },
        "signal" to { drive, on -> drive.copy(signaling = on) },
        "guidance" to { drive, on -> drive.copy(guidanceSoon = on) },
        "restricted" to { drive, on -> drive.copy(restricted = on) },
    )
    private val DESTINATION = setOf("dest", "dest_address", "dest_min", "dest_km", "dest_kind")
    private val TRAFFIC = setOf("jam", "jam_road", "accident", "roadwork")

    /** 도착하면 내비가 지우는 값. */
    val ROUTE: Set<String> = DESTINATION + TRAFFIC + "ahead" + "caution"

    /** 지금 덧씌운 값에 새 값을 더한다. [UNSET]은 그 키를 지운다. 모르는 키나 읽을 수 없는 값이면 [IllegalArgumentException]. */
    fun merge(staged: Map<String, String>, changes: Map<String, String>): Map<String, String> {
        val merged = staged.toMutableMap()
        for ((key, value) in changes) {
            require(key in KEYS) { "모르는 키 '$key'. 쓸 수 있는 키: ${KEYS.keys.joinToString(" ")}" }
            if (value == UNSET) merged -= key else merged[key] = value.trim()
        }
        apply(DriveContext(), merged)
        return merged
    }

    /** 실제 신호 [drive] 위에 [staged]를 덧씌운다. */
    fun apply(drive: DriveContext, staged: Map<String, String>): DriveContext {
        var result = drive
        staged["gear"]?.let { result = result.copy(gear = choice<GearState>("gear", it)) }
        staged["mode"]?.let { result = result.copy(drivingMode = it.uppercase()) }
        staged["address"]?.let { result = result.copy(currentAddress = it) }
        staged["battery"]?.let { result = result.copy(batteryPercent = number("battery", it)) }
        staged["trip"]?.let { result = result.copy(tripMinutes = number("trip", it)) }
        staged["ahead"]?.let { result = result.copy(ahead = if (it == NONE) null else choice<RoadAhead>("ahead", it)) }
        staged["caution"]?.let { result = result.copy(caution = if (it == NONE) null else choice<Caution>("caution", it)) }
        staged["temp"]?.let { result = result.copy(outsideCelsius = requireNotNull(it.toIntOrNull()) { "temp 값은 정수입니다: '$it'" }) }
        for ((key, set) in FLAGS) staged[key]?.let { result = set(result, flag(key, it)) }
        if (staged.keys.any { it in DESTINATION }) result = result.copy(destination = destination(result.destination, staged))
        if (staged.keys.any { it in TRAFFIC }) result = result.copy(traffic = traffic(result.traffic, staged))
        return result
    }

    private fun destination(real: Destination?, staged: Map<String, String>): Destination? {
        if (staged["dest"] == NONE) return null
        val base = real ?: Destination(name = "목적지")
        return base.copy(
            name = staged["dest"] ?: base.name,
            address = staged["dest_address"] ?: base.address,
            remainMinutes = staged["dest_min"]?.let { number("dest_min", it) } ?: base.remainMinutes,
            remainKm = staged["dest_km"]?.let { decimal("dest_km", it) } ?: base.remainKm,
            kind = staged["dest_kind"]?.let { choice<PlaceKind>("dest_kind", it) } ?: base.kind,
        )
    }

    private fun traffic(real: Traffic?, staged: Map<String, String>): Traffic? {
        val base = real ?: Traffic(jamMinutes = 0)
        val traffic = base.copy(
            jamMinutes = staged["jam"]?.let { number("jam", it) } ?: base.jamMinutes,
            road = staged["jam_road"] ?: base.road,
            accident = staged["accident"]?.let { flag("accident", it) } ?: base.accident,
            roadwork = staged["roadwork"]?.let { flag("roadwork", it) } ?: base.roadwork,
        )
        return traffic.takeIf { it.jamMinutes > 0 || it.accident || it.roadwork }
    }

    /** `depart` — 길 안내 시작. 이름이 없으면 "목적지". */
    fun departure(args: Map<String, String>): Departure = Departure(
        name = args["name"]?.trim().orEmpty().ifEmpty { "목적지" },
        minutes = args["min"]?.let { number("min", it) },
        km = args["km"]?.let { decimal("km", it) },
        kind = args["kind"]?.let { choice<PlaceKind>("kind", it) },
    )

    /** `arrive`·`waypoint` — 도착. 이름이 없으면 [fallback]. */
    fun arrival(args: Map<String, String>, fallback: String): Arrival = Arrival(
        name = args["name"]?.trim().orEmpty().ifEmpty { fallback },
        minutes = args["min"]?.let { number("min", it) },
        km = args["km"]?.let { decimal("km", it) },
    )

    /** 길 안내를 시작하면 내비가 목적지도 함께 알려 준다 — 덧씌운 목적지를 이번 길로 바꾼다. */
    fun depart(staged: Map<String, String>, departure: Departure): Map<String, String> = staged - ROUTE + listOfNotNull(
        "dest" to departure.name,
        departure.minutes?.let { "dest_min" to it.toString() },
        departure.km?.let { "dest_km" to it.toString() },
        departure.kind?.let { "dest_kind" to it.name },
    )

    /** 앱이 지금 어떤 상태인가 — 멘트가 소리로 나올 수 있는지와 DJ가 실제로 마지막에 한 말. */
    data class Stage(val playing: Boolean = true, val talks: Boolean = true, val last: Said? = null)

    /**
     * 시험 결과로 돌려주는 글 — 지금 신호로 기기 DJ가 할 말, DJ가 실제로 한 말, 서버로 가는 맥락.
     * 🔑 곡 소개는 곡마다 앞의 두 멘트를 번갈아 쓰므로 둘 다 보여 주고, 뒤로 밀려 나오지 않는 말도 함께 적는다.
     */
    fun report(
        drive: DriveContext,
        staged: Map<String, String>,
        now: LocalTime,
        spoken: String = "",
        event: String? = null,
        stage: Stage = Stage(),
    ): String {
        val song = SongInfo("곡 제목", "가수")
        val intros = listOf(0, 1).map { DjScript.intro(song, drive, now, it) }.distinct()
        val remarks = DjScript.remarks(drive, now)
        // 어린이보호구역이나 도착 직전처럼 곡 소개가 [remarks]를 쓰지 않을 때는 밀린 말도 없다.
        val waiting = if (remarks.isNotEmpty() && intros.first().startsWith(remarks.first())) remarks.drop(2) else emptyList()
        val silent = listOfNotNull(
            "DJ 멘트가 꺼져 있습니다".takeIf { !stage.talks },
            "노래가 멈춰 있습니다".takeIf { !stage.playing },
        )
        return buildString {
            appendLine(if (staged.isEmpty()) "덧씌운 신호: 없음(실제 값 그대로)" else "덧씌운 신호: " + staged.entries.joinToString(" ") { "${it.key}=${it.value}" })
            appendLine("재생: ${if (stage.playing) "노래 나는 중" else "멈춤"} · DJ 멘트 ${if (stage.talks) "켜짐" else "꺼짐"}")
            if (event != null) {
                appendLine("지금 한마디: $event")
                if (silent.isNotEmpty()) appendLine("⚠️ ${silent.joinToString(", ")} — 이 한마디는 화면에도 소리로도 나오지 않습니다.")
            }
            intros.forEachIndexed { index, line -> appendLine("곡 소개 ${index + 1}: $line") }
            waiting.forEachIndexed { index, line -> appendLine("밀린 말 ${index + 3}: $line") }
            if (drive.holdTalk) appendLine("⚠️ 안내 직전이거나 방향지시등이 켜져 있어 목소리는 미뤄집니다.")
            appendLine(LAST + (stage.last?.let { "[${it.at.format(CLOCK)}] ${it.text}" } ?: "없음"))
            append("서버로 가는 맥락: ${drive.toInput(now, spoken)}")
        }
    }

    /** 🔑 `tools/trigger.py`가 이 머리말로 DJ가 실제로 한 말을 찾는다. 바꾸면 함께 바꾼다. */
    const val LAST = "방금 DJ가 한 말: "
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    private fun flag(key: String, value: String): Boolean = when (value.lowercase()) {
        "on", "true", "1", "yes" -> true
        "off", "false", "0", "no" -> false
        else -> throw IllegalArgumentException("$key 값은 on 또는 off입니다: '$value'")
    }

    private fun number(key: String, value: String): Int =
        requireNotNull(value.toIntOrNull()?.takeIf { it >= 0 }) { "$key 값은 0 이상의 정수입니다: '$value'" }

    private fun decimal(key: String, value: String): Double =
        requireNotNull(value.toDoubleOrNull()?.takeIf { it >= 0 }) { "$key 값은 0 이상의 숫자입니다: '$value'" }

    private inline fun <reified T : Enum<T>> choice(key: String, value: String): T =
        requireNotNull(enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }) {
            "$key 값은 ${enumValues<T>().joinToString("|") { it.name }} 가운데 하나입니다: '$value'"
        }
}
