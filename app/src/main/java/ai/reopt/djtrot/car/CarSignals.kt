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
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
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
import ai.pleos.playground.vehicle.listener.HvacDefrosterStateListener
import ai.pleos.playground.vehicle.listener.HvacSeatTemperatureListener
import ai.pleos.playground.vehicle.listener.HvacSeatVentilationListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * DJ에게 필요한 차량 신호를 모은다.
 * - Pleos Vehicle: 주행 모드·배터리·방향지시등, 달린 시간·크루즈·안개등, 와이퍼(비), 동승자, 앞유리 성에 제거, 운전석 열선·통풍.
 * - Pleos NaviHelper: 현재 주소·좌표·목적지·남은 시간·안내 시점·출발·경유지·도착, 정체·사고·공사, 곧 지나는 터널·톨게이트·휴게소,
 *   주의 구간(어린이보호구역, 안개 잦은 곳, 미끄러운 길, 야생동물, 사고 잦은 곳), 집·회사 즐겨찾기.
 * - AAOS 표준 Car API: 기어·충전구·충전기 연결·바깥 기온·주행 중 UX 제한([AaosCar]).
 * 🔑 SDK마다 따로 실패할 수 있다(차종·에뮬레이터·권한). 하나가 안 돼도 나머지로 동작하고, 값은 비워 둔다.
 * 🔑 Vehicle 값은 도메인의 `checkXXXCapabilities`로 읽을 수 있는지 먼저 묻고 건다(Pleos API 호환성 가이드).
 *    읽을 수 없다고 답한 값은 걸지 않고 [DriveContext.unsupported]에 적는다.
 * 🔑 좌표는 내비가 주는 값을 받되 광고 장소를 기기 안에서 견주는 데만 쓴다. 실행 중 위치 권한(FusedLocation)은 쓰지 않는다.
 * 🔴 쓰는 쪽이 없으면 듣기를 멈춘다([hold]·[release]) — 화면도 재생도 없는 앱이 차량 서비스에 붙어 타이머를 돌리지 않게
 *    (Pleos 미사용 앱 BG→CCH 전환 가이드, 성능·리소스 설계).
 */
class CarSignals(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    /** SDK가 알려 준 실제 값. */
    private val _state = MutableStateFlow(DriveContext())
    /** 시험 도구가 덧씌운 값([stage]). 평소에는 아무것도 바꾸지 않는다. */
    private val staged = MutableStateFlow<(DriveContext) -> DriveContext> { it }

    /** 🔑 실제 값 위에 시험 값을 덧씌워 내보낸다 — SDK가 값을 다시 보내도 시험 값이 남는다. */
    val state: StateFlow<DriveContext> = combine(_state, staged) { real, stage -> stage(real) }
        .stateIn(CoroutineScope(Dispatchers.Unconfined), SharingStarted.Eagerly, DriveContext())
    private val _arrivals = MutableSharedFlow<Arrival>(extraBufferCapacity = 1)

    /** 목적지에 닿은 순간. DJ가 이번 길을 마무리하는 말을 한다. */
    val arrivals: SharedFlow<Arrival> = _arrivals.asSharedFlow()
    private val _departures = MutableSharedFlow<Departure>(extraBufferCapacity = 1)

    /** 길 안내를 시작한 순간. DJ가 이번 길을 여는 말을 한다. */
    val departures: SharedFlow<Departure> = _departures.asSharedFlow()
    private val _waypoints = MutableSharedFlow<Arrival>(extraBufferCapacity = 1)

    /** 경유지에 닿은 순간. */
    val waypoints: SharedFlow<Arrival> = _waypoints.asSharedFlow()

    /** 지금 이 신호를 쓰는 쪽(화면, 재생 서비스). 비면 듣기를 멈춘다. */
    private val holders = mutableSetOf<Any>()
    private var started = false
    private var vehicle: Vehicle? = null
    private var vehicleRetries = 0
    /** 건 리스너를 푸는 일 — [stop]이 차례로 부른다. */
    private val undo = mutableListOf<() -> Unit>()
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
        onChargerConnected = { connected -> _state.update { it.copy(chargerConnected = connected) } },
        onOutsideCelsius = { celsius -> _state.update { it.copy(outsideCelsius = celsius) } },
    )

    private val drivingModeListener = object : DrivingModeStateListener {
        override fun onDrivingModeStateUpdated(state: DrivingModeType?) = _state.update { it.copy(drivingMode = state?.name) }
        override fun onFailed(e: Exception) = failed(SignalBoard.DRIVING_MODE, e)
    }
    private val batteryListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(value: Int?) = _state.update { it.copy(batteryPercent = value) }
        override fun onFailed(e: Exception) = failed(SignalBoard.BATTERY, e)
    }
    private val turnSignalListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) =
            _state.update { it.copy(signaling = state == TurnSignalValue.LEFT || state == TurnSignalValue.RIGHT) }
        override fun onFailed(e: Exception) = failed(SignalBoard.TURN_SIGNAL, e)
    }
    private val drivingTimeListener = object : DrivingTimeListener {
        override fun onDrivingTimeUpdated(value: Int?) = _state.update { it.copy(tripMinutes = value?.takeIf { minutes -> minutes >= 0 }) }
        override fun onFailed(e: Exception) = failed(SignalBoard.TRIP_TIME, e)
    }
    private val cruiseListener = object : CruiseControlStateListener {
        override fun onCruiseControlStateUpdated(state: CruiseControl?) = _state.update { it.copy(cruising = state == CruiseControl.ACTIVATED) }
        override fun onFailed(e: Exception) = failed(SignalBoard.CRUISE, e)
    }
    private val fogLightListener = object : LightStateListener {
        override fun onLightStateUpdated(state: LightState?) = _state.update { it.copy(fog = state == LightState.ON) }
        override fun onFailed(e: Exception) = failed(SignalBoard.FOG_LIGHT, e)
    }

    private val defrostListener = object : HvacDefrosterStateListener {
        override fun onHvacDefrosterStateUpdated(state: Boolean?) = _state.update { it.copy(defrosting = state == true) }
        override fun onFailed(e: Exception) = failed(SignalBoard.DEFROST, e)
    }

    /**
     * 운전석 시트 온도 단계 — 양수는 열선, 음수는 냉방이다(AAOS `HVAC_SEAT_TEMPERATURE`와 같은 뜻).
     * 🔑 통풍과 시트 냉방은 따로 온다 — 둘 중 하나라도 켜져 있으면 "시원한 시트"로 본다.
     */
    private var seatChill = false
    private var seatVent = false
    private val seatTemperatureListener = object : HvacSeatTemperatureListener {
        override fun onHvacSeatTemperatureUpdated(temperature: Int?) {
            seatChill = (temperature ?: 0) < 0
            _state.update { it.copy(seatHeat = (temperature ?: 0) > 0, seatCool = seatChill || seatVent) }
        }
        override fun onFailed(e: Exception) = failed(SignalBoard.SEAT_HEAT, e)
    }
    private val seatVentilationListener = object : HvacSeatVentilationListener {
        override fun onHvacSeatVentilationUpdated(ventilation: Int?) {
            seatVent = (ventilation ?: 0) > 0
            _state.update { it.copy(seatCool = seatChill || seatVent) }
        }
        override fun onFailed(e: Exception) = failed(SignalBoard.SEAT_COOL, e)
    }

    /**
     * 🔑 와이퍼가 [RAIN_ON_MS] 넘게 이어서 움직여야 비로 본다 — 워셔액으로 한 번 닦는 것과 가른다.
     *    멈춘 뒤에도 [RAIN_OFF_MS] 동안은 비 오는 길로 둔다(간헐 와이퍼, 잠깐 그친 비).
     */
    private val wiperListener = object : WiperStateListener {
        override fun onWiperStateUpdated(state: WiperState?) {
            main.post {
                if (!started) return@post
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
        override fun onFailed(e: Exception) = failed(SignalBoard.WIPER, e)
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
        override fun onFailed(e: Exception) = failed(SignalBoard.COMPANY, e)
    }

    private val naviListener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            // 🔑 좌표는 광고 장소를 기기 안에서 견주는 데만 쓴다 — 서버로 가는 맥락(toInput)에는 들어가지 않는다.
            val location = point(currentLocationInfo.latitude, currentLocationInfo.longitude)
            val address = currentLocationInfo.address.takeIf { it.isNotBlank() }
            if (address == null && location == null) return
            _state.update { it.copy(currentAddress = address ?: it.currentAddress, location = location ?: it.location) }
        }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) = _state.update { it.copy(destination = destinationInfo.toDestination(bookmarks)) }
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            refreshDestination()
            depart(routeStartInfo.toDeparture(bookmarks))
        }
        override fun onRoutingCompleted() = refreshDestination()
        override fun onReRouteEnded() = refreshDestination()
        override fun onDestinationChanged() = refreshDestination()
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
            // 🔑 가장 가까운 주의 구간 하나만 DJ의 말로 삼는다 — 내비 안내와 겹치지 않게 조금 일찍([CAUTION_NEAR_M]) 잡아 둔다.
            val caution = cautionPointList.filter { it.remainDistance in 0..CAUTION_NEAR_M }.sortedBy { it.remainDistance }
                .firstNotNullOfOrNull { caution(it.type) } ?: return
            _state.update { it.copy(caution = caution) }
            main.removeCallbacks(cautionPassed)
            main.postDelayed(cautionPassed, CAUTION_HOLD_MS)
        }
        override fun onCameraAlert(cameraAlertList: List<CameraAlert>) {
            if (cameraAlertList.any { it.status && it.remainDistance in 0..GUIDANCE_NEAR_M.toInt() }) holdForGuidance()
        }
    }
    private val guidancePassed = Runnable { _state.update { it.copy(guidanceSoon = false) } }
    private val aheadPassed = Runnable { _state.update { it.copy(ahead = null) } }
    private val schoolPassed = Runnable { _state.update { it.copy(schoolZone = false) } }
    private val cautionPassed = Runnable { _state.update { it.copy(caution = null) } }
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, REFRESH_MS)
        }
    }
    private val vehicleRetry = Runnable { if (started) restartVehicle() }
    private val stopper = Runnable { synchronized(this) { if (holders.isEmpty()) stop() } }

    /**
     * 이 신호를 쓰기 시작한다 — 화면은 보이는 동안, 재생 서비스는 살아 있는 동안 잡는다. 처음 잡는 쪽이 듣기를 켠다.
     * 🔑 둘이 같은 신호를 함께 보므로 한쪽이 놓아도 다른 쪽이 잡고 있으면 그대로 듣는다.
     */
    @Synchronized
    fun hold(owner: Any) {
        holders += owner
        main.removeCallbacks(stopper)
        start()
    }

    /**
     * 다 썼다. 잡고 있는 쪽이 하나도 없으면 [LINGER_MS] 뒤에 듣기를 멈춘다.
     * 🔑 바로 멈추지 않는다 — 창을 옮기거나 화면을 다시 그릴 때 잠깐 놓았다 다시 잡는 사이에 SDK를 뗐다 붙이지 않게.
     */
    @Synchronized
    fun release(owner: Any) {
        if (!holders.remove(owner) || holders.isNotEmpty()) return
        main.removeCallbacks(stopper)
        main.postDelayed(stopper, LINGER_MS)
    }

    @Synchronized
    private fun start() {
        if (started) return
        started = true
        vehicleRetries = 0
        aaos.start()
        startVehicle()
        startNavi()
        main.postDelayed(refresher, FIRST_REFRESH_MS)
    }

    /**
     * 듣기를 멈추고 SDK 연결을 놓는다(Pleos SDK: 리스너를 먼저 풀고 `release()`).
     * 🔑 길 위의 한때 값(안내 직전, 곧 지나는 곳, 방향지시등…)은 지운다 — 다시 켰을 때 지난 장면으로 말하지 않게. 시험 값([stage])은 그대로다.
     */
    @Synchronized
    private fun stop() {
        if (!started) return
        started = false
        listOf(refresher, vehicleRetry, rainStarted, rainStopped, guidancePassed, aheadPassed, schoolPassed, cautionPassed).forEach(main::removeCallbacks)
        rainPending = false
        stopVehicle()
        navi?.let { helper ->
            runCatching { helper.removeListener(naviListener) }
            runCatching { helper.release() }
        }
        navi = null
        aaos.stop()
        synchronized(occupied) { occupied.clear() }
        seatChill = false
        seatVent = false
        _state.update { it.copy(defrosting = false, seatHeat = false, seatCool = false, guidanceSoon = false, signaling = false, ahead = null, caution = null, schoolZone = false, raining = false, company = false, restricted = false, uxFlags = 0) }
    }

    /**
     * 시험 도구(디버그 빌드의 `TriggerReceiver`, `tools/trigger.py`)와 화면의 시연 탭(`dj/Demo`)이 차량 신호를 흉내 낸다. null이면 실제 값으로 돌아간다.
     * 🔑 시연 탭을 끈 빌드(`djtrot.demo=false`)의 릴리스에는 부르는 곳이 없다.
     */
    internal fun stage(change: ((DriveContext) -> DriveContext)?) {
        staged.value = change ?: { it }
    }

    /** 길 안내 시작을 흉내 낸다. 같은 곳으로 다시 떠나도 막지 않는다. */
    internal fun stage(departure: Departure) {
        _departures.tryEmit(departure)
    }

    /** 도착([waypoint]가 참이면 경유지 도착)을 흉내 낸다. */
    internal fun stage(arrival: Arrival, waypoint: Boolean = false) {
        if (waypoint) _waypoints.tryEmit(arrival) else _arrivals.tryEmit(arrival)
    }

    /** 내비 정보는 요청하면 리스너로 돌아온다. 경로가 바뀌면 다시 묻고, 그 밖에는 주기적으로 갱신한다. */
    fun refresh() {
        if (!started) return
        navi?.let { helper ->
            runCatching { helper.getCurrentLocationInfo() }
            runCatching { helper.getDestinationInfo() }
            runCatching { helper.getRouteStateInfo() }
        }
    }

    /**
     * Pleos Vehicle에 붙어 값마다 "읽을 수 있는가"를 묻고 건다.
     * 🔑 값마다 따로 건다 — 차종에 없는 값 하나 때문에 나머지를 놓치지 않는다.
     */
    private fun startVehicle() = runCatching {
        val car = Vehicle(app).also { it.initialize() }
        vehicle = car
        _state.update { it.copy(unsupported = emptySet()) }
        watch(car, SignalBoard.DRIVING_MODE, { ok, no -> car.getDrivingMode().checkDrivingModeStateCapabilities(ok, no) }) {
            car.getDrivingMode().registerDrivingModeState(drivingModeListener)
            undo += { car.getDrivingMode().unregisterDrivingModeState(drivingModeListener) }
            car.getDrivingMode().getCurrentDrivingModeState({ mode -> drivingModeListener.onDrivingModeStateUpdated(mode) }, { })
        }
        watch(car, SignalBoard.BATTERY, { ok, no -> car.getEvBattery().checkEvBatteryStateOfChargeCapabilities(ok, no) }) {
            car.getEvBattery().registerEvBatteryStateOfCharge(batteryListener)
            undo += { car.getEvBattery().unregisterEvBatteryStateOfCharge(batteryListener) }
            car.getEvBattery().getEvBatteryStateOfCharge({ percent -> batteryListener.onEvBatteryStateOfChargeUpdated(percent) }, { })
        }
        watch(car, SignalBoard.TURN_SIGNAL, { ok, no -> car.getTurnSignal().checkTurnSignalStateCapabilities(ok, no) }) {
            car.getTurnSignal().registerTurnSignalState(turnSignalListener)
            undo += { car.getTurnSignal().unregisterTurnSignalState(turnSignalListener) }
        }
        watch(car, SignalBoard.TRIP_TIME, { ok, no -> car.getOdometer().checkTripCurrentDrivingTimeCapabilities(ok, no) }) {
            car.getOdometer().registerTripCurrentDrivingTime(drivingTimeListener)
            undo += { car.getOdometer().unregisterTripCurrentDrivingTime(drivingTimeListener) }
            car.getOdometer().getTripCurrentDrivingTime({ minutes -> drivingTimeListener.onDrivingTimeUpdated(minutes) }, { })
        }
        watch(car, SignalBoard.CRUISE, { ok, no -> car.getDrivingMode().checkCruiseControlStateCapabilities(ok, no) }) {
            car.getDrivingMode().registerCruiseControlState(cruiseListener)
            undo += { car.getDrivingMode().unregisterCruiseControlState(cruiseListener) }
        }
        watch(car, SignalBoard.FOG_LIGHT, { ok, no -> car.getLight().checkFrontFogLightStateCapabilities({ config, _ -> ok(config) }, no) }) {
            car.getLight().registerFrontFogLightState(fogLightListener)
            undo += { car.getLight().unregisterFrontFogLightState(fogLightListener) }
        }
        watch(car, SignalBoard.WIPER, { ok, no -> car.getWiper().checkWiperStateCapabilities({ config, _ -> ok(config) }, no) }) {
            val windshield = WindowArea(WindowValue.FRONT_WINDSHIELD)
            car.getWiper().registerWiperState(windshield, wiperListener)
            undo += { car.getWiper().unregisterWiperState(windshield, wiperListener) }
        }
        watch(car, SignalBoard.COMPANY, { ok, no -> car.getSeat().checkSeatOccupancyCapabilities({ config, _ -> ok(config) }, no) }) {
            for (seat in PASSENGER_SEATS) {
                val area = SeatArea(seat)
                val listener = seatListener(seat)
                runCatching {
                    car.getSeat().registerSeatOccupancy(area, listener)
                    undo += { car.getSeat().unregisterSeatOccupancy(area, listener) }
                }.onFailure { warn("좌석 $seat", it) }
            }
        }
        watch(car, SignalBoard.DEFROST, { ok, no -> car.getHVAC().checkHvacDefrosterCapabilities({ config, _ -> ok(config) }, no) }) {
            val windshield = WindowArea(WindowValue.FRONT_WINDSHIELD)
            car.getHVAC().registerHvacDefrosterState(windshield, defrostListener)
            undo += { car.getHVAC().unregisterHvacDefrosterState(windshield, defrostListener) }
            car.getHVAC().getHvacDefrosterState(windshield, { on -> defrostListener.onHvacDefrosterStateUpdated(on) }, { })
        }
        // 🔑 운전석만 본다 — DJ가 건네는 말의 상대가 운전자다.
        val driver = SeatArea(DRIVER_SEAT)
        watch(car, SignalBoard.SEAT_HEAT, { ok, no -> car.getSeat().checkSeatTemperatureCapabilities({ config, _ -> ok(config) }, no) }) {
            car.getSeat().registerHvacSeatTemperature(driver, seatTemperatureListener)
            undo += { car.getSeat().unregisterHvacSeatTemperature(driver, seatTemperatureListener) }
            car.getSeat().getHvacSeatTemperature(driver, { level -> seatTemperatureListener.onHvacSeatTemperatureUpdated(level) }, { })
        }
        watch(car, SignalBoard.SEAT_COOL, { ok, no -> car.getSeat().checkSeatVentilationCapabilities({ config, _ -> ok(config) }, no) }) {
            car.getSeat().registerHvacSeatVentilation(driver, seatVentilationListener)
            undo += { car.getSeat().unregisterHvacSeatVentilation(driver, seatVentilationListener) }
            car.getSeat().getHvacSeatVentilationState(driver, { level -> seatVentilationListener.onHvacSeatVentilationUpdated(level) }, { })
        }
    }.onFailure { warn("Vehicle SDK", it) }

    /**
     * [name] 값을 읽을 수 있는지 묻고([check]), 읽을 수 있으면 [register]로 건다.
     * 🔑 "읽을 수 없다"는 답이면 걸지 않고 지원하지 않는 값으로 적는다. 물어보는 일 자체가 실패하면(옛 서비스) 예전처럼 그냥 걸어 본다.
     *    답은 다른 스레드로 올 수 있어 메인으로 옮기고, 그사이 듣기를 멈췄으면 버린다.
     */
    private fun watch(car: Vehicle, name: String, check: ((CapabilityConfig) -> Unit, (Exception) -> Unit) -> Unit, register: () -> Unit) {
        val attempt = { main.post { if (started && vehicle === car) runCatching(register).onFailure { failed(name, it) } } }
        runCatching {
            check(
                { config ->
                    if (config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE) attempt()
                    else _state.update { it.copy(unsupported = it.unsupported + name) }.also { Log.i(TAG, "$name: 이 차는 주지 않는 값이에요") }
                },
                { error ->
                    if (connectionLost(error)) failed(name, error) else attempt()
                },
            )
        }.onFailure { attempt() }
    }

    /**
     * Vehicle 값 하나가 실패했다. 서비스 연결이 끊긴 것이면 잠시 뒤 통째로 다시 붙는다([VEHICLE_RETRY_MS], 점점 길게, [VEHICLE_RETRIES]번까지).
     * 🔑 부팅 직후처럼 Vehicle 서비스가 아직 뜨지 않았을 때 그 프로세스 내내 값이 비는 것을 막는다(Pleos Vehicle 오류 가이드: 연결 실패·내부 오류는 다시 시도).
     */
    private fun failed(name: String, error: Throwable) {
        warn(name, error)
        // 지원을 물었을 때는 읽을 수 있다고 했지만 걸어 보니 없는 값이다 — 지원하지 않는 값으로 적는다.
        if (errorName(error) in MISSING) _state.update { it.copy(unsupported = it.unsupported + name) }
        if (!connectionLost(error) || vehicleRetries >= VEHICLE_RETRIES) return
        main.removeCallbacks(vehicleRetry)
        main.postDelayed(vehicleRetry, VEHICLE_RETRY_MS shl vehicleRetries)
    }

    private fun restartVehicle() {
        vehicleRetries += 1
        Log.i(TAG, "Vehicle SDK에 다시 붙어요($vehicleRetries/$VEHICLE_RETRIES)")
        stopVehicle()
        startVehicle()
    }

    private fun stopVehicle() {
        undo.forEach { unregister -> runCatching(unregister) }
        undo.clear()
        vehicle?.let { runCatching { it.release() } }
        vehicle = null
    }

    private fun startNavi() = runCatching {
        val helper = NaviHelper(app)
        navi = helper
        helper.initialize({
            // 연결되는 사이 듣기를 멈췄으면(다른 도우미로 바뀌었으면) 걸지 않는다.
            if (navi === helper) {
                helper.addListener(naviListener)
                runCatching { helper.getBookmarkInfo() }
                main.post { refresh() }
            }
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
        main.removeCallbacks(cautionPassed)
        _state.update { it.copy(destination = null, traffic = null, ahead = null, caution = null) }
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
        /** 쓰는 쪽이 없어진 뒤 듣기를 멈추기까지. */
        const val LINGER_MS = 10_000L
        const val VEHICLE_RETRY_MS = 5_000L
        const val VEHICLE_RETRIES = 3
        /** 이 거리 안의 회전·주의 구간·카메라는 곧 안내 음성이 나온다고 본다(미터, 문서 단위). */
        const val GUIDANCE_NEAR_M = 300.0
        const val GUIDANCE_HOLD_MS = 10_000L
        /** 이 거리 안의 터널·톨게이트·휴게소는 DJ가 "곧"이라고 말할 수 있다(미터). */
        const val AHEAD_NEAR_M = 1_500.0
        const val AHEAD_HOLD_MS = 90_000L
        const val SCHOOL_NEAR_M = 500
        const val SCHOOL_HOLD_MS = 60_000L
        /** 이 거리 안의 주의 구간은 DJ가 미리 한마디 할 수 있다(미터). */
        const val CAUTION_NEAR_M = 1_000
        const val CAUTION_HOLD_MS = 90_000L
        const val RAIN_ON_MS = 20_000L
        const val RAIN_OFF_MS = 180_000L
        const val SAME_TRIP_MS = 10 * 60_000L
        /** 운전석 — 왼쪽 핸들 기준이다. */
        val DRIVER_SEAT = SeatValue.ROW_1_LEFT
        /** 🔑 운전석(1열 왼쪽) 말고 누가 탔는지만 본다. */
        val PASSENGER_SEATS = listOf(SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT)
        /** 안내 음성이 없는 TBT — 직진과 알 수 없음은 말을 미룰 까닭이 없다. */
        val SILENT_TURNS = setOf(TurnType.TURN_STRAIGHT, TurnType.TURN_UNKNOWN)
        /** Pleos Vehicle `ErrorCode`의 이름 — 서비스 연결 실패와 내부 서비스 오류는 다시 시도할 만하다. */
        val LOST = setOf("ERROR_SERVICE_CONNECTION_FAILURE", "ERROR_INTERNAL_SERVICE")

        /**
         * Vehicle 서비스 연결이 끊겨서 난 실패인가.
         * ⚠️ SDK의 예외 클래스(`VehicleException`)와 오류 코드(`ErrorCode`)는 앱에서 직접 가리킬 수 없다(이름이 가려져 있거나 모듈 안쪽 공개).
         *    예외가 가진 enum 값의 이름으로 알아본다. 모양이 바뀌어 읽지 못하면 다시 붙지 않을 뿐이다.
         */
        fun connectionLost(error: Throwable): Boolean = errorName(error) in LOST

        /** 이 차에 없는 값이라는 오류. */
        val MISSING = setOf("ERROR_NOT_SUPPORTED_PROPERTY", "ERROR_NOT_SUPPORTED_AREA")

        /** Pleos Vehicle 오류 코드의 이름. 읽지 못하면 null. */
        fun errorName(error: Throwable): String? = runCatching {
            error.javaClass.methods.filter { it.parameterCount == 0 && it.returnType.isEnum }.firstNotNullOfOrNull { (it.invoke(error) as? Enum<*>)?.name?.takeIf { name -> name.startsWith("ERROR_") } }
        }.getOrNull()
    }
}

/** DJ가 한마디 할 만한 주의 구간만 고른다. 나머지(과속방지턱, 단속 안내…)는 내비 안내로 충분하다. */
internal fun caution(type: CautionPointType): Caution? = when (type) {
    CautionPointType.FOG_AREA -> Caution.FOG
    CautionPointType.SLIPPERY_ROAD_ALERT -> Caution.SLIPPERY
    CautionPointType.WILDLIFE_CROSSING -> Caution.WILDLIFE
    CautionPointType.ACCIDENT_PRONE_AREA -> Caution.ACCIDENT
    else -> null
}

/** 🔑 내비의 남은 시간은 초, 남은 거리는 미터로 온다(에뮬레이터 경로 안내로 확인). 0 이하는 값이 없는 것으로 본다. */
/** 내비가 준 좌표 — 값이 없을 때 오는 (0, 0)은 버린다. */
internal fun point(latitude: Double, longitude: Double): GeoPoint? =
    if (latitude == 0.0 && longitude == 0.0) null else GeoPoint(latitude, longitude)

internal fun DestinationInfo.toDestination(bookmarks: List<Bookmark> = emptyList()): Destination? {
    val label = name.ifBlank { address }
    if (label.isBlank()) return null
    return Destination(
        name = label,
        address = address.ifBlank { null },
        remainMinutes = remainTime.takeIf { it > 0 }?.let { (it + 59) / 60 },
        remainKm = remainDistance.takeIf { it > 0 }?.let { it / 1000.0 },
        kind = bookmarks.firstOrNull { it.matches(poiId, name, address) }?.kind,
        location = point(latitude, longitude),
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
