package ai.reopt.djtrot.car

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.DrivingModeType
import ai.pleos.playground.vehicle.listener.DrivingModeStateListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * DJ에게 필요한 차량 신호를 모은다 — 주행 모드·배터리(Pleos Vehicle), 위치(Pleos FusedLocation),
 * 현재 주소·목적지·남은 시간(Pleos NaviHelper), 기어·주행 중 UX 제한(AAOS 표준 Car API).
 * 🔑 SDK마다 따로 실패할 수 있다(차종·에뮬레이터·권한). 하나가 안 돼도 나머지로 동작하고, 값은 비워 둔다.
 */
class CarSignals(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(DriveContext())
    val state: StateFlow<DriveContext> = _state.asStateFlow()

    private var started = false
    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null
    private var location: FusedLocation? = null
    private val aaos = AaosCar(
        app,
        onRestrictions = { ux -> _state.update { it.copy(restricted = ux.required, uxFlags = ux.flags, maxTextLength = ux.maxTextLength) } },
        onGear = { gear -> _state.update { it.copy(gear = gear, connected = it.connected + "AAOS") } },
    )

    private val drivingModeListener = object : DrivingModeStateListener {
        override fun onDrivingModeStateUpdated(state: DrivingModeType?) = _state.update { it.copy(drivingMode = state?.name) }
        override fun onFailed(e: Exception) = warn("주행 모드", e)
    }
    private val batteryListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(value: Int?) = _state.update { it.copy(batteryPercent = value) }
        override fun onFailed(e: Exception) = warn("배터리", e)
    }
    private val naviListener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            val address = currentLocationInfo.address.takeIf { it.isNotBlank() }
            _state.update { it.copy(currentAddress = address ?: it.currentAddress, latitude = currentLocationInfo.latitude, longitude = currentLocationInfo.longitude) }
        }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) = _state.update { it.copy(destination = destinationInfo.toDestination()) }
        override fun onRoutingCompleted() = refreshDestination()
        override fun onReRouteEnded() = refreshDestination()
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = _state.update { it.copy(destination = null) }
        override fun onRouteCancelled() = _state.update { it.copy(destination = null) }
    }
    private val locationListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            if (location != null) _state.update { it.copy(latitude = location.latitude, longitude = location.longitude) }
        }
        override fun onError(exception: Exception) = warn("위치", exception)
    }
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, REFRESH_MS)
        }
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        aaos.start()
        startVehicle()
        startNavi()
        startLocation()
        main.postDelayed(refresher, FIRST_REFRESH_MS)
    }

    /** 위치 권한(🔑 pleos.car.permission.FUSED_LOCATION은 실행 중 승인 대상)을 받은 뒤 다시 연결한다. */
    @Synchronized
    fun retryLocation() {
        if (!started || location != null) return
        startLocation()
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

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(refresher)
        aaos.stop()
        vehicle?.let { car ->
            runCatching { car.getDrivingMode().unregisterDrivingModeState(drivingModeListener) }
            runCatching { car.getEvBattery().unregisterEvBatteryStateOfCharge(batteryListener) }
            runCatching { car.release() }
        }
        navi?.let { helper ->
            runCatching { helper.removeListener(naviListener) }
            runCatching { helper.release() }
        }
        location?.let { fused ->
            runCatching { fused.unregisterFusedLocationCallback(locationListener) }
            runCatching { fused.release() }
        }
        vehicle = null
        navi = null
        location = null
        _state.update { DriveContext() }
    }

    private fun startVehicle() = runCatching {
        val car = Vehicle(app).also { it.initialize() }
        vehicle = car
        car.getDrivingMode().registerDrivingModeState(drivingModeListener)
        car.getEvBattery().registerEvBatteryStateOfCharge(batteryListener)
        connected("Vehicle")
    }.onFailure { warn("Vehicle SDK", it) }

    private fun startNavi() = runCatching {
        val helper = NaviHelper(app)
        navi = helper
        helper.initialize({
            helper.addListener(naviListener)
            connected("NaviHelper")
            main.post { refresh() }
        }, { warn("NaviHelper SDK", it) })
    }.onFailure { warn("NaviHelper SDK", it) }

    private fun startLocation() = runCatching {
        val fused = FusedLocation(app)
        if (!fused.isSupported()) return@runCatching
        fused.initialize()
        location = fused
        fused.registerFusedLocationCallback(locationListener)
        connected("FusedLocation")
    }.onFailure { warn("FusedLocation SDK", it) }

    private fun refreshDestination() {
        navi?.let { runCatching { it.getDestinationInfo() } }
    }

    private fun connected(name: String) = _state.update { it.copy(connected = it.connected + name) }

    private fun warn(what: String, error: Throwable) {
        Log.w(TAG, "$what 연결 실패: ${error.message}")
    }

    private companion object {
        const val TAG = "DjTrotCar"
        const val FIRST_REFRESH_MS = 1_500L
        const val REFRESH_MS = 15_000L
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
