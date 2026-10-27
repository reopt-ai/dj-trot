package ai.reopt.djtrot.car

import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.DrivingModeType
import ai.pleos.playground.vehicle.listener.DrivingModeStateListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * DJ에게 필요한 차량 신호를 모은다 — 주행 모드·배터리·방향지시등(Pleos Vehicle),
 * 현재 주소·목적지·남은 시간·안내 시점·도착(Pleos NaviHelper), 기어·주행 중 UX 제한(AAOS 표준 Car API).
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

    private var started = false
    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null
    private val aaos = AaosCar(
        app,
        onRestrictions = { ux -> _state.update { it.copy(restricted = ux.required, uxFlags = ux.flags, maxTextLength = ux.maxTextLength) } },
        onGear = { gear -> _state.update { it.copy(gear = gear) } },
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
    private val naviListener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            val address = currentLocationInfo.address.takeIf { it.isNotBlank() } ?: return
            _state.update { it.copy(currentAddress = address) }
        }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) = _state.update { it.copy(destination = destinationInfo.toDestination()) }
        override fun onRoutingCompleted() = refreshDestination()
        override fun onReRouteEnded() = refreshDestination()
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            _state.update { it.copy(destination = null) }
            _arrivals.tryEmit(destinationArrivedInfo.toArrival())
        }
        override fun onRouteCancelled() = _state.update { it.copy(destination = null) }
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            if (tbtInfoList.any { it.type !in SILENT_TURNS && it.distance in 0.0..GUIDANCE_NEAR_M }) holdForGuidance()
        }
        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) {
            if (cautionPointList.any { it.remainDistance in 0..GUIDANCE_NEAR_M.toInt() }) holdForGuidance()
        }
        override fun onCameraAlert(cameraAlertList: List<CameraAlert>) {
            if (cameraAlertList.any { it.status && it.remainDistance in 0..GUIDANCE_NEAR_M.toInt() }) holdForGuidance()
        }
    }
    private val guidancePassed = Runnable { _state.update { it.copy(guidanceSoon = false) } }
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
        }
        vehicle?.let { car ->
            runCatching { car.getDrivingMode().getCurrentDrivingModeState({ mode -> drivingModeListener.onDrivingModeStateUpdated(mode) }, { warn("주행 모드", it) }) }
            runCatching { car.getEvBattery().getEvBatteryStateOfCharge({ percent -> batteryListener.onEvBatteryStateOfChargeUpdated(percent) }, { warn("배터리", it) }) }
        }
    }

    private fun startVehicle() = runCatching {
        val car = Vehicle(app).also { it.initialize() }
        vehicle = car
        car.getDrivingMode().registerDrivingModeState(drivingModeListener)
        car.getEvBattery().registerEvBatteryStateOfCharge(batteryListener)
        runCatching { car.getTurnSignal().registerTurnSignalState(turnSignalListener) }.onFailure { warn("방향지시등", it) }
    }.onFailure { warn("Vehicle SDK", it) }

    private fun startNavi() = runCatching {
        val helper = NaviHelper(app)
        navi = helper
        helper.initialize({
            helper.addListener(naviListener)
            main.post { refresh() }
        }, { warn("NaviHelper SDK", it) })
    }.onFailure { warn("NaviHelper SDK", it) }

    /** 안내 음성이 곧 나온다 — 이벤트가 끊기고 [GUIDANCE_HOLD_MS]가 지나면 푼다. 이벤트가 이어지면 계속 미룬다. */
    private fun holdForGuidance() {
        _state.update { it.copy(guidanceSoon = true) }
        main.removeCallbacks(guidancePassed)
        main.postDelayed(guidancePassed, GUIDANCE_HOLD_MS)
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
        /** 안내 음성이 없는 TBT — 직진과 알 수 없음은 말을 미룰 까닭이 없다. */
        val SILENT_TURNS = setOf(TurnType.TURN_STRAIGHT, TurnType.TURN_UNKNOWN)
    }
}

/** 🔑 내비의 남은 시간은 초, 남은 거리는 미터로 온다(에뮬레이터 경로 안내로 확인). 0 이하는 값이 없는 것으로 본다. */
internal fun DestinationInfo.toDestination(): Destination? {
    val label = name.ifBlank { address }
    if (label.isBlank()) return null
    return Destination(
        name = label,
        address = address.ifBlank { null },
        remainMinutes = remainTime.takeIf { it > 0 }?.let { (it + 59) / 60 },
        remainKm = remainDistance.takeIf { it > 0 }?.let { it / 1000.0 },
    )
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
