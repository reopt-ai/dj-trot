package ai.reopt.djtrot.dj

import ai.reopt.djtrot.car.Arrival
import ai.reopt.djtrot.car.Departure
import ai.reopt.djtrot.car.Destination
import ai.reopt.djtrot.car.DriveContext
import ai.reopt.djtrot.car.GearState
import ai.reopt.djtrot.car.PlaceKind
import ai.reopt.djtrot.car.RoadAhead
import ai.reopt.djtrot.car.Traffic
import java.time.LocalTime

/**
 * 시연 — 차가 서 있는 전시장·심사장에서 길 위의 상황을 버튼으로 흉내 낸다(화면의 '시연' 탭).
 * 🔑 실제 차량 신호 위에 [DemoDrive]를 덧씌울 뿐이다. 멘트·음성·재생은 실제와 같은 길로 나온다.
 * 🔑 안드로이드에 기대지 않는 순수 모델이라 JVM 테스트로 검사한다. 빌드 속성 `djtrot.demo=false`면 탭이 보이지 않는다.
 */
data class DemoDrive(
    val gear: GearState = GearState.DRIVE,
    val address: String? = null,
    val destination: Destination? = null,
    val traffic: Traffic? = null,
    val ahead: RoadAhead? = null,
    val raining: Boolean = false,
    val fog: Boolean = false,
    val company: Boolean = false,
    val cruising: Boolean = false,
    val schoolZone: Boolean = false,
    val signaling: Boolean = false,
    val charging: Boolean = false,
    val freeDrive: Boolean = false,
    val tripMinutes: Int? = null,
    /** 시간대 인사를 바꿔 보이는 시(0~23). null이면 지금 시각. */
    val hour: Int? = null,
) {
    /** 실제 신호 [real] 위에 시연 상황을 덧씌운다. 길 위의 값은 시연이 통째로 정한다. */
    fun apply(real: DriveContext): DriveContext = real.copy(
        gear = gear,
        currentAddress = address ?: real.currentAddress,
        destination = destination,
        traffic = traffic,
        ahead = ahead,
        raining = raining,
        fog = fog,
        company = company,
        cruising = cruising,
        schoolZone = schoolZone,
        signaling = signaling,
        guidanceSoon = false,
        chargePortOpen = charging,
        freeDrive = freeDrive,
        tripMinutes = tripMinutes,
    )

    /** DJ가 인사에 쓰는 시각. */
    fun clock(now: LocalTime): LocalTime = hour?.let { LocalTime.of(it, now.minute) } ?: now

    /** 길 위의 한 장면만 남긴다 — 버튼 하나가 그 장면의 말을 꺼내게, 앞서 누른 장면을 지운다. 목적지·기어·시간대는 둔다. */
    fun road(): DemoDrive = copy(
        gear = GearState.DRIVE, traffic = null, ahead = null, raining = false, fog = false, company = false, cruising = false,
        schoolZone = false, signaling = false, charging = false, freeDrive = false, tripMinutes = null,
    )

    /** 화면에 보이는 지금 상황 한 줄. */
    fun summary(): String = parts().joinToString(" · ")

    /** 지금 상황을 이루는 조각들 — 화면이 알약으로 하나씩 보여 준다. */
    fun parts(): List<String> = listOfNotNull(
        if (charging) "충전 중" else gear.label,
        destination?.let { place -> place.remainMinutes?.let { "${place.name}까지 ${it}분" } ?: place.name },
        "목적지 없이 달림".takeIf { freeDrive },
        "비".takeIf { raining },
        "안개".takeIf { fog },
        traffic?.let { if (it.accident) "사고 소식" else "정체 ${it.jamMinutes}분" },
        when (ahead) {
            RoadAhead.TUNNEL -> "곧 터널"
            RoadAhead.TOLL -> "곧 톨게이트"
            RoadAhead.REST_AREA -> "곧 휴게소"
            null -> null
        },
        tripMinutes?.let { "${it / 60}시간째 주행" },
        "동승자".takeIf { company },
        "크루즈".takeIf { cruising },
        "어린이보호구역".takeIf { schoolZone },
        "방향지시등".takeIf { signaling },
        hour?.let { "${it}시" },
    )
}

/** 시연 버튼이 일으키는 순간 — 내비가 알려 주는 것과 같은 사건. */
sealed interface DemoEvent {
    data class Depart(val departure: Departure) : DemoEvent
    data class Arrive(val arrival: Arrival) : DemoEvent
    data class Waypoint(val arrival: Arrival) : DemoEvent
}

/**
 * 시연 버튼 하나.
 * [speak]가 참이면 누르자마자 DJ가 지금 곡 위에서 이 상황으로 다시 말한다(노래가 나오는 중일 때).
 * [event]가 있으면 출발·경유·도착 한마디가 나온다. [hint]는 화면 낭독기가 읽는 설명이다.
 */
data class DemoStep(
    val id: String,
    val label: String,
    val hint: String,
    val change: (DemoDrive) -> DemoDrive,
    val speak: Boolean = true,
    val event: ((DemoDrive) -> DemoEvent)? = null,
)

/** [columns] — 한 줄에 놓을 버튼 수. null이면 화면이 정한다. */
data class DemoGroup(val title: String, val steps: List<DemoStep>, val columns: Int? = null)

/** 멘트 살펴보기의 묶음 — 조각 ID의 앞머리로 나눈다. */
enum class LineGroup(val title: String, private val prefixes: List<String>) {
    OPEN("여는 말", listOf("open.")),
    SCENE("장면", listOf("scene.")),
    ETA("남은 시간", listOf("eta.")),
    TRIP("출발·도착", listOf("depart.", "waypoint", "arrive.")),
    PLAY("곡 소개", listOf("play.")),
    ;

    fun has(id: String): Boolean = prefixes.any(id::startsWith)
}

object Demo {
    private val HOME = Destination("우리 집", address = "경기도 성남시 수정구 태평동", remainMinutes = 35, remainKm = 14.0, kind = PlaceKind.HOME)
    private val OFFICE = Destination("회사", address = "서울특별시 강남구 역삼동", remainMinutes = 12, remainKm = 5.0, kind = PlaceKind.OFFICE)
    private val FAR = Destination("강릉역", address = "강원특별자치도 강릉시 교동", remainMinutes = 150, remainKm = 210.0)

    private fun depart(id: String, label: String, hint: String, to: Destination, from: String) = DemoStep(
        id, label, hint,
        change = { DemoDrive(address = from, destination = to, hour = it.hour) },
        speak = false,
        event = { DemoEvent.Depart(Departure(to.name, to.remainMinutes, to.remainKm, to.kind)) },
    )

    private fun road(id: String, label: String, hint: String, change: (DemoDrive) -> DemoDrive) =
        DemoStep(id, label, hint, change = { change(it.road()) })

    private fun hour(id: String, label: String, hour: Int?) =
        DemoStep(id, label, "인사가 바뀌어요", change = { it.road().copy(hour = hour) })

    val GROUPS: List<DemoGroup> = listOf(
        DemoGroup("길 떠나기", listOf(
            depart("depart.home", "집으로 출발", "35분 길", HOME, "서울특별시 강남구 역삼동"),
            depart("depart.office", "회사로 출발", "12분 길", OFFICE, "경기도 성남시 수정구 태평동"),
            depart("depart.far", "강릉으로 출발", "2시간 30분 길", FAR, "서울특별시 강남구 역삼동"),
        )),
        DemoGroup("길 위에서", listOf(
            road("rain", "비 오는 길", "와이퍼가 움직여요") { it.copy(raining = true) },
            road("fog", "안개 낀 길", "안개등이 켜져요") { it.copy(fog = true) },
            road("jam", "길이 막혀요", "정체 18분") { it.copy(traffic = Traffic(18, "영동고속도로")) },
            road("accident", "사고 소식", "가는 길에 사고") { it.copy(traffic = Traffic(9, "올림픽대로", accident = true)) },
            road("tunnel", "곧 터널", "터널 진입 전") { it.copy(ahead = RoadAhead.TUNNEL) },
            road("toll", "톨게이트", "톨게이트 통과") { it.copy(ahead = RoadAhead.TOLL) },
            road("rest", "곧 휴게소", "휴게소가 가까워요") { it.copy(ahead = RoadAhead.REST_AREA) },
            road("long", "오래 달렸어요", "두 시간 넘게 주행") { it.copy(tripMinutes = 130) },
            road("company", "동승자 탑승", "옆자리 탑승") { it.copy(company = true) },
            road("cruise", "크루즈 주행", "크루즈 켬") { it.copy(cruising = true) },
            road("free", "발길 닿는 대로", "목적지 없이 달려요") { it.copy(destination = null, freeDrive = true) },
            road("school", "스쿨존", "어린이보호구역 · 곡 이름만 말해요") { it.copy(schoolZone = true) },
        )),
        DemoGroup("시간대", listOf(
            hour("hour.dawn", "새벽", 4), hour("hour.morning", "아침", 8), hour("hour.afternoon", "오후", 14),
            hour("hour.evening", "저녁", 19), hour("hour.night", "밤", 23), hour("hour.now", "지금", null),
        ), columns = 6),
        DemoGroup("멈추고 닿기", listOf(
            DemoStep(
                "signal", "방향지시등", "DJ가 말을 미뤄요",
                change = { it.copy(signaling = !it.signaling) }, speak = false,
            ),
            DemoStep(
                "waypoint", "휴게소 들르기", "경유지 인사",
                change = { it.road() }, speak = false, event = { DemoEvent.Waypoint(Arrival("횡성휴게소")) },
            ),
            DemoStep(
                "arrive", "도착", "마무리 인사",
                change = { DemoDrive(gear = GearState.PARK, address = it.destination?.address ?: it.address, hour = it.hour) },
                speak = false,
                event = { before ->
                    val to = before.destination
                    DemoEvent.Arrive(Arrival(to?.name ?: "목적지", minutes = to?.remainMinutes, km = to?.remainKm))
                },
            ),
            DemoStep(
                "charge", "서서 충전", "충전하며 한 곡",
                change = { DemoDrive(gear = GearState.PARK, address = it.address, charging = true, hour = it.hour) },
            ),
        )),
    )

    val STEPS: List<DemoStep> = GROUPS.flatMap { it.steps }

    private fun scenario(id: String, title: String, description: String, vararg steps: String) =
        DemoScenario(id, title, description, steps.map { step -> STEPS.first { it.id == step } })

    /** 자동 시연 — 버튼을 차례로 눌러 주는 정해 둔 길. 발표 중에 손을 떼거나 전시장에서 혼자 돌릴 때 쓴다. */
    val SCENARIOS: List<DemoScenario> = listOf(
        scenario("trip", "강릉 가는 먼 길", "출발 · 크루즈 · 터널 · 정체 · 휴게소 · 도착", "depart.far", "cruise", "tunnel", "jam", "rest", "waypoint", "long", "arrive"),
        scenario("home", "비 오는 퇴근길", "저녁 · 출발 · 비 · 동승자 · 사고 · 어린이보호구역 · 도착", "hour.evening", "depart.home", "rain", "company", "accident", "school", "arrive"),
        scenario("wander", "발길 닿는 대로", "새벽 · 목적지 없이 · 안개 · 방향지시등 · 충전", "hour.dawn", "free", "fog", "signal", "signal", "charge"),
    )
}

/** 자동 시연의 한 길 — 버튼([steps])을 차례로 누른다. */
data class DemoScenario(val id: String, val title: String, val description: String, val steps: List<DemoStep>)

/** 자동 시연이 버튼 하나를 누른 뒤 다음 버튼까지 기다리는 시간(초) — DJ가 말을 마칠 만큼. */
val DemoStep.pauseSeconds: Int
    get() = when {
        event != null -> 10
        speak -> 17
        else -> 6
    }

/** 지금 돌고 있는 자동 시연 — 몇 번째 버튼인가. */
data class AutoDemo(val scenarioId: String, val title: String, val index: Int, val total: Int, val label: String)
