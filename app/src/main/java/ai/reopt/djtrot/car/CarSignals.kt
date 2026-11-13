package ai.reopt.djtrot.car

import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.TrafficEventType
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.TrafficStatus
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import ai.pleos.playground.vehicle.constant.control.DrivingModeType
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.CruiseControlStateListener
import ai.pleos.playground.vehicle.listener.DrivingModeStateListener
import ai.pleos.playground.vehicle.listener.DrivingTimeListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * DJ에게 필요한 차량 신호를 모은다.
 * - Pleos Vehicle: 주행 모드·배터리·방향지시등, 달린 시간·크루즈·안개등, 와이퍼(비), 동승자.
 * - Pleos NaviHelper: 현재 주소·목적지·남은 시간·안내 시점·출발·경유지·도착, 정체, 곧 지나는 터널·톨게이트·휴게소, 집·회사 즐겨찾기.
 * - AAOS 표준 Car API: 기어·충전구·주행 중 UX 제한.
 * 🔑 SDK마다 따로 실패할 수 있다(차종·에뮬레이터·권한). 하나가 안 돼도 나머지로 동작하고, 값은 비워 둔다.
 * 🔑 위치는 NaviHelper 주소만 쓴다. 좌표는 받지 않는다 — DJ에게 필요 없고, 실행 중 위치 권한을 묻지 않아도 된다.
 */
class CarSignals(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(DriveContext())
    val state: StateFlow<DriveContext> = _state.asStateFlow()
    private val _arrivals = MutableSharedFlow<Arrival>(extraBufferCapacity = 1)

    /** 목적지에 닿은 순간. DJ가 이번 길을 마무리하는 말을 한다. */
    val arrivals: SharedFlow<Arrival> = _arrivals.asSharedFlow()
    private val _departures = MutableSharedFlow<Departure>(extraBufferCapacity = 1)

    /** 길 안내를 시작한 순간. DJ가 이번 길을 여는 말을 한다. */
    val departures: SharedFlow<Departure> = _departures.asSharedFlow()
    private val _waypoints = MutableSharedFlow<Arrival>(extraBufferCapacity = 1)

    /** 경유지에 닿은 순간. */
    val waypoints: SharedFlow<Arrival> = _waypoints.asSharedFlow()

    private var started = false
    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null
    /** 🔴 집·회사 즐겨찾기는 목적지 종류를 알아보는 데만 쓰고 기기 밖으로 내보내지 않는다. */
    @Volatile private var bookmarks: List<Bookmark> = emptyList()
    private var departed: Pair<String, Long>? = null
    private val occupied = mutableSetOf<SeatValue>()
    private var rainPending = false
    private val aaos = AaosCar(
        app,
        onRestrictions = { ux -> _state.update { it.copy(restricted = ux.required, uxFlags = ux.flags, maxTextLength = ux.maxTextLength) } },
        onGear = { gear -> _state.update { it.copy(gear = gear) } },
        onChargePort = { open -> _state.update { it.copy(chargePortOpen = open) } },
    )

    private val drivingModeListener = object : DrivingModeStateListener {
        override fun onDrivingModeStateUpdated(state: DrivingModeType?) = _state.update { it.copy(drivingMode = state?.name) }
        override fun onFailed(e: Exception) = warn("주행 모드", e)
    }
    private val batteryListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(value: Int?) = _state.update { it.copy(batteryPercent = value) }
        override fun onFailed(e: Exception) = warn("배터리", e)
    }
    private val turnSignalListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) =
            _state.update { it.copy(signaling = state == TurnSignalValue.LEFT || state == TurnSignalValue.RIGHT) }
        override fun onFailed(e: Exception) = warn("방향지시등", e)
    }
    private val drivingTimeListener = object : DrivingTimeListener {
        override fun onDrivingTimeUpdated(value: Int?) = _state.update { it.copy(tripMinutes = value?.takeIf { minutes -> minutes >= 0 }) }
        override fun onFailed(e: Exception) = warn("달린 시간", e)
    }
    private val cruiseListener = object : CruiseControlStateListener {
        override fun onCruiseControlStateUpdated(state: CruiseControl?) = _state.update { it.copy(cruising = state == CruiseControl.ACTIVATED) }
        override fun onFailed(e: Exception) = warn("크루즈 컨트롤", e)
    }
    private val fogLightListener = object : LightStateListener {
        override fun onLightStateUpdated(state: LightState?) = _state.update { it.copy(fog = state == LightState.ON) }
        override fun onFailed(e: Exception) = warn("안개등", e)
    }

    /**
     * 🔑 와이퍼가 [RAIN_ON_MS] 넘게 이어서 움직여야 비로 본다 — 워셔액으로 한 번 닦는 것과 가른다.
     *    멈춘 뒤에도 [RAIN_OFF_MS] 동안은 비 오는 길로 둔다(간헐 와이퍼, 잠깐 그친 비).
     */
    private val wiperListener = object : WiperStateListener {
        override fun onWiperStateUpdated(state: WiperState?) {
            main.post {
                if (state == WiperState.ON) {
                    main.removeCallbacks(rainStopped)
                    if (!rainPending && !_state.value.raining) {
                        rainPending = true
                        main.postDelayed(rainStarted, RAIN_ON_MS)
                    }
                } else {
                    rainPending = false
                    main.removeCallbacks(rainStarted)
                    main.removeCallbacks(rainStopped)
                    if (_state.value.raining) main.postDelayed(rainStopped, RAIN_OFF_MS)
                }
            }
        }
        override fun onFailed(e: Exception) = warn("와이퍼", e)
    }
    private val rainStarted = Runnable {
        rainPending = false
        _state.update { it.copy(raining = true) }
    }
    private val rainStopped = Runnable { _state.update { it.copy(raining = false) } }

    /** 좌석마다 따로 듣는다 — 콜백이 어느 좌석인지 알려 주지 않는다. */
    private fun seatListener(seat: SeatValue) = object : SeatOccupancyListener {
        override fun onSeatOccupancyUpdated(occupancy: Boolean?) {
            synchronized(occupied) {
                if (occupancy == true) occupied += seat else occupied -= seat
                val company = occupied.isNotEmpty()
                _state.update { it.copy(company = company) }
            }
        }
        override fun onFailed(e: Exception) = warn("좌석", e)
    }

    private val naviListener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            val address = currentLocationInfo.address.takeIf { it.isNotBlank() } ?: return
            _state.update { it.copy(currentAddress = address) }
        }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) = _state.update { it.copy(destination = destinationInfo.toDestination(bookmarks)) }
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            refreshDestination()
            depart(routeStartInfo.toDeparture(bookmarks))
        }
        override fun onRoutingCompleted() = refreshDestination()
        override fun onReRouteEnded() = refreshDestination()
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            endRoute()
            _arrivals.tryEmit(destinationArrivedInfo.toArrival())
        }
        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
            _waypoints.tryEmit(waypointArrivedInfo.toArrival())
        }
        override fun onRouteCancelled() = endRoute()
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) =
            _state.update { it.copy(traffic = routeStateInfo.trafficStatus?.takeIf { routeStateInfo.isRouting }?.toTraffic()) }
        override fun onNavigationStatus(status: NavigationStatus) = _state.update { it.copy(freeDrive = status == NavigationStatus.FREE_DRIVE) }
        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) {
            bookmarks = bookmarkInfoList.mapNotNull { it.toBookmark() }
            refreshDestination()
        }
        override fun onBookMarkChanged() {
            navi?.let { runCatching { it.getBookmarkInfo() } }
        }
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            if (tbtInfoList.any { it.type !in SILENT_TURNS && it.distance in 0.0..GUIDANCE_NEAR_M }) holdForGuidance()
            val ahead = tbtInfoList.firstNotNullOfOrNull { tbt -> roadAhead(tbt.type).takeIf { tbt.distance in 0.0..AHEAD_NEAR_M } } ?: return
            _state.update { it.copy(ahead = ahead) }
            main.removeCallbacks(aheadPassed)
            main.postDelayed(aheadPassed, AHEAD_HOLD_MS)
        }
        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) {
            if (cautionPointList.any { it.remainDistance in 0..GUIDANCE_NEAR_M.toInt() }) holdForGuidance()
            if (cautionPointList.any { it.type == CautionPointType.SCHOOL_ZONE && it.remainDistance in 0..SCHOOL_NEAR_M }) {
                _state.update { it.copy(schoolZone = true) }
                main.removeCallbacks(schoolPassed)
                main.postDelayed(schoolPassed, SCHOOL_HOLD_MS)
            }
        }
        override fun onCameraAlert(cameraAlertList: List<CameraAlert>) {
            if (cameraAlertList.any { it.status && it.remainDistance in 0..GUIDANCE_NEAR_M.toInt() }) holdForGuidance()
        }
    }
    private val guidancePassed = Runnable { _state.update { it.copy(guidanceSoon = false) } }
    private val aheadPassed = Runnable { _state.update { it.copy(ahead = null) } }
    private val schoolPassed = Runnable { _state.update { it.copy(schoolZone = false) } }
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, REFRESH_MS)
        }
    }

    /**
     * 🔑 화면과 재생 서비스 중 먼저 뜬 쪽이 켜고, 프로세스가 사는 동안 끄지 않는다.
     *    둘이 같은 신호를 함께 보므로 한쪽이 닫힐 때 끄면 다른 쪽의 DJ 멘트·주행 제한이 멈춘다.
     */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        aaos.start()
        startVehicle()
        startNavi()
        main.postDelayed(refresher, FIRST_REFRESH_MS)
    }

    /** 내비 정보는 요청하면 리스너로 돌아온다. 경로가 바뀌면 다시 묻고, 그 밖에는 주기적으로 갱신한다. */
    fun refresh() {
        navi?.let { helper ->
            runCatching { helper.getCurrentLocationInfo() }
            runCatching { helper.getDestinationInfo() }
            runCatching { helper.getRouteStateInfo() }
        }
        vehicle?.let { car ->
            runCatching { car.getDrivingMode().getCurrentDrivingModeState({ mode -> drivingModeListener.onDrivingModeStateUpdated(mode) }, { warn("주행 모드", it) }) }
            runCatching { car.getEvBattery().getEvBatteryStateOfCharge({ percent -> batteryListener.onEvBatteryStateOfChargeUpdated(percent) }, { warn("배터리", it) }) }
            runCatching { car.getOdometer().getTripCurrentDrivingTime({ minutes -> drivingTimeListener.onDrivingTimeUpdated(minutes) }, { warn("달린 시간", it) }) }
        }
    }

    private fun startVehicle() = runCatching {
        val car = Vehicle(app).also { it.initialize() }
        vehicle = car
        car.getDrivingMode().registerDrivingModeState(drivingModeListener)
        car.getEvBattery().registerEvBatteryStateOfCharge(batteryListener)
        runCatching { car.getTurnSignal().registerTurnSignalState(turnSignalListener) }.onFailure { warn("방향지시등", it) }
        // 🔑 아래는 차종에 따라 없는 값이다. 하나씩 따로 걸어 하나가 안 돼도 나머지는 받는다.
        runCatching { car.getOdometer().registerTripCurrentDrivingTime(drivingTimeListener) }.onFailure { warn("달린 시간", it) }
        runCatching { car.getDrivingMode().registerCruiseControlState(cruiseListener) }.onFailure { warn("크루즈 컨트롤", it) }
        runCatching { car.getLight().registerFrontFogLightState(fogLightListener) }.onFailure { warn("안개등", it) }
        runCatching { car.getWiper().registerWiperState(WindowArea(WindowValue.FRONT_WINDSHIELD), wiperListener) }.onFailure { warn("와이퍼", it) }
        for (seat in PASSENGER_SEATS) {
            runCatching { car.getSeat().registerSeatOccupancy(SeatArea(seat), seatListener(seat)) }.onFailure { warn("좌석", it) }
        }
    }.onFailure { warn("Vehicle SDK", it) }

    private fun startNavi() = runCatching {
        val helper = NaviHelper(app)
        navi = helper
        helper.initialize({
            helper.addListener(naviListener)
            runCatching { helper.getBookmarkInfo() }
            main.post { refresh() }
        }, { warn("NaviHelper SDK", it) })
    }.onFailure { warn("NaviHelper SDK", it) }

    /** 안내 음성이 곧 나온다 — 이벤트가 끊기고 [GUIDANCE_HOLD_MS]가 지나면 푼다. 이벤트가 이어지면 계속 미룬다. */
    private fun holdForGuidance() {
        _state.update { it.copy(guidanceSoon = true) }
        main.removeCallbacks(guidancePassed)
        main.postDelayed(guidancePassed, GUIDANCE_HOLD_MS)
    }

    /** 🔑 같은 곳으로 다시 찾은 경로(재탐색, 옵션 변경)에는 출발 인사를 되풀이하지 않는다. */
    private fun depart(departure: Departure) {
        val now = SystemClock.elapsedRealtime()
        val last = departed
        if (last != null && last.first == departure.name && now - last.second < SAME_TRIP_MS) return
        departed = departure.name to now
        _departures.tryEmit(departure)
    }

    private fun endRoute() {
        main.removeCallbacks(aheadPassed)
        _state.update { it.copy(destination = null, traffic = null, ahead = null) }
    }

    private fun refreshDestination() {
        navi?.let { runCatching { it.getDestinationInfo() } }
    }

    private fun warn(what: String, error: Throwable) {
        Log.w(TAG, "$what 연결 실패: ${error.message}")
    }

    private companion object {
        const val TAG = "DjTrotCar"
        const val FIRST_REFRESH_MS = 1_500L
        const val REFRESH_MS = 15_000L
        /** 이 거리 안의 회전·주의 구간·카메라는 곧 안내 음성이 나온다고 본다(미터, 문서 단위). */
        const val GUIDANCE_NEAR_M = 300.0
        const val GUIDANCE_HOLD_MS = 10_000L
        /** 이 거리 안의 터널·톨게이트·휴게소는 DJ가 "곧"이라고 말할 수 있다(미터). */
        const val AHEAD_NEAR_M = 1_500.0
        const val AHEAD_HOLD_MS = 90_000L
        const val SCHOOL_NEAR_M = 500
        const val SCHOOL_HOLD_MS = 60_000L
        const val RAIN_ON_MS = 20_000L
        const val RAIN_OFF_MS = 180_000L
        const val SAME_TRIP_MS = 10 * 60_000L
        /** 🔑 운전석(1열 왼쪽) 말고 누가 탔는지만 본다. */
        val PASSENGER_SEATS = listOf(SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT)
        /** 안내 음성이 없는 TBT — 직진과 알 수 없음은 말을 미룰 까닭이 없다. */
        val SILENT_TURNS = setOf(TurnType.TURN_STRAIGHT, TurnType.TURN_UNKNOWN)
    }
}

/** 🔑 내비의 남은 시간은 초, 남은 거리는 미터로 온다(에뮬레이터 경로 안내로 확인). 0 이하는 값이 없는 것으로 본다. */
internal fun DestinationInfo.toDestination(bookmarks: List<Bookmark> = emptyList()): Destination? {
    val label = name.ifBlank { address }
    if (label.isBlank()) return null
    return Destination(
        name = label,
        address = address.ifBlank { null },
        remainMinutes = remainTime.takeIf { it > 0 }?.let { (it + 59) / 60 },
        remainKm = remainDistance.takeIf { it > 0 }?.let { it / 1000.0 },
        kind = bookmarks.firstOrNull { it.matches(poiId, name, address) }?.kind,
    )
}

/** 출발 정보도 초·미터다. 이름만 오므로 즐겨찾기는 이름으로 맞춘다. */
internal fun RouteStartInfo.toDeparture(bookmarks: List<Bookmark> = emptyList()): Departure {
    val label = name.trim()
    return Departure(
        name = label.ifBlank { "목적지" },
        minutes = duration.takeIf { it > 0 }?.let { (it + 30) / 60 },
        km = distance.takeIf { it > 0 }?.let { it / 1000.0 },
        kind = bookmarks.firstOrNull { it.matches("", label, "") }?.kind,
    )
}

internal fun WaypointArrivedInfo.toArrival(): Arrival = Arrival(
    name = name.trim().ifBlank { "경유지" },
    minutes = duration.takeIf { it > 0 }?.let { (it + 30) / 60 },
    km = distance.takeIf { it > 0 }?.let { it / 1000.0 },
)

internal fun BookmarkInfo.toBookmark(): Bookmark? {
    val kind = when (category) {
        BookmarkType.HOME -> PlaceKind.HOME
        BookmarkType.OFFICE -> PlaceKind.OFFICE
        else -> return null
    }
    return Bookmark(kind, poiId, name, address)
}

internal fun TrafficStatus.toTraffic(): Traffic? = Traffic.of(
    jamSeconds = summary.totalCongestionTime,
    road = congestionItemList.maxByOrNull { it.trafficCongestionTime }?.roadName,
    accident = trafficEventList.any { it.eventType == TrafficEventType.ACCIDENT },
    roadwork = trafficEventList.any { it.eventType == TrafficEventType.REPAIRING },
)

internal fun roadAhead(turn: TurnType): RoadAhead? = when (turn) {
    TurnType.TURN_TUNNEL -> RoadAhead.TUNNEL
    TurnType.TURN_TOLL -> RoadAhead.TOLL
    TurnType.TURN_ENTER_SERVICEAREA, TurnType.TURN_RESTAREA -> RoadAhead.REST_AREA
    else -> null
}

/**
 * 도착 정보도 초·미터다(Pleos NaviHelper 문서).
 * 🔑 이름이 없으면 주소 대신 "목적지"라고 부른다 — 소리 내어 읽는 말이라 긴 주소는 듣기 어렵다.
 */
internal fun DestinationArrivedInfo.toArrival(): Arrival {
    return Arrival(
        name = name.trim().ifBlank { "목적지" },
        minutes = duration.takeIf { it > 0 }?.let { (it + 30) / 60 },
        km = distance.takeIf { it > 0 }?.let { it / 1000.0 },
    )
}
