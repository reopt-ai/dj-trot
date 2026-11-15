package ai.reopt.djtrot.car

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Build
import android.util.Log
import kotlin.math.roundToInt

/**
 * AAOS 표준 Car API — 주행 중 UX 제한(CarUxRestrictions), 기어(GEAR_SELECTION), 충전구(EV_CHARGE_PORT_OPEN·EV_CHARGE_PORT_CONNECTED),
 * 바깥 기온(ENV_OUTSIDE_TEMPERATURE).
 * 🔑 매니페스트에서 화면을 distractionOptimized로 선언했으므로, 제한이 걸리면 화면이 스스로 긴 글·목록·버튼을 줄인다
 *    (Pleos Driving Restriction Development Guide).
 * 🔑 Pleos SDK API 정책은 "AAOS 표준에 같은 값이 있으면 표준을 쓴다"이다. 기어와 충전구는 Pleos Vehicle SDK 쪽이 폐기 예정이고
 *    (`Gear`, `registerEvChargePortOpen`), 바깥 기온은 Pleos SDK에 없다.
 * 🔑 속성마다 따로 건다 — 차종에 없는 속성(전기차가 아닌 차의 충전구 등)은 그 값만 비고 나머지는 받는다
 *    (Pleos API 호환성 가이드: 모델명으로 가르지 말고 CarPropertyManager로 확인).
 */
class AaosCar(
    private val context: Context,
    private val onRestrictions: (UxState) -> Unit,
    private val onGear: (GearState) -> Unit,
    private val onChargePort: (Boolean) -> Unit = {},
    private val onChargerConnected: (Boolean) -> Unit = {},
    private val onOutsideCelsius: (Int) -> Unit = {},
) {
    /** 주행 최적화 요구 여부와 지금 걸린 제한 항목. */
    data class UxState(val required: Boolean, val flags: Int, val maxTextLength: Int)

    private var car: Car? = null
    private var restrictions: CarUxRestrictionsManager? = null
    private var properties: CarPropertyManager? = null
    private val callbacks = mutableListOf<CarPropertyManager.CarPropertyEventCallback>()

    private fun callback(name: String, onValue: (Any?) -> Unit) = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            // 값을 줄 수 없는 상태(UNAVAILABLE·ERROR)의 자리 채움 값은 쓰지 않는다. 차(API 34)에는 새 접근자가 없어 `status`로 읽는다.
            @Suppress("DEPRECATION")
            val available = value.status == CarPropertyValue.STATUS_AVAILABLE
            if (available) onValue(value.value)
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) = Log.w(TAG, "$name 속성 오류 $propertyId").let { }
    }

    @Synchronized
    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || car != null) return
        val connected = runCatching { Car.createCar(context) }.getOrNull() ?: return
        car = connected
        runCatching {
            val manager = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            restrictions = manager
            manager.currentCarUxRestrictions?.let { onRestrictions(it.toState()) }
            manager.registerListener { restrictions -> onRestrictions(restrictions.toState()) }
        }.onFailure { Log.w(TAG, "주행 제한 서비스를 쓸 수 없어요", it) }
        val manager = runCatching { connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager }.getOrNull() ?: return
        properties = manager
        watch(manager, "기어", VehiclePropertyIds.GEAR_SELECTION) { value -> (value as? Int)?.let { onGear(gearState(it)) } }
        watch(manager, "충전구", VehiclePropertyIds.EV_CHARGE_PORT_OPEN) { value -> (value as? Boolean)?.let(onChargePort) }
        watch(manager, "충전기 연결", VehiclePropertyIds.EV_CHARGE_PORT_CONNECTED) { value -> (value as? Boolean)?.let(onChargerConnected) }
        watch(manager, "바깥 기온", VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE, rate = TEMPERATURE_RATE_HZ) { value ->
            (value as? Float)?.takeIf { it.isFinite() }?.let { onOutsideCelsius(it.roundToInt()) }
        }
    }

    /** 이 차에 그 속성이 있을 때만 건다. 없거나 권한이 없으면 그 값만 비워 둔다. */
    private fun watch(manager: CarPropertyManager, name: String, propertyId: Int, rate: Float = CarPropertyManager.SENSOR_RATE_ONCHANGE, onValue: (Any?) -> Unit) {
        runCatching {
            if (manager.getCarPropertyConfig(propertyId) == null) return Log.i(TAG, "$name 속성이 이 차에 없어요").let { }
            val callback = callback(name, onValue)
            @Suppress("DEPRECATION")
            if (manager.registerCallback(callback, propertyId, rate)) callbacks += callback
        }.onFailure { Log.w(TAG, "$name 속성을 읽을 수 없어요: ${it.message}") }
    }

    /** 듣기를 그만두고 차량 서비스 연결을 끊는다. 다시 [start]하면 새로 붙는다. */
    @Synchronized
    fun stop() {
        val connected = car ?: return
        @Suppress("DEPRECATION")
        callbacks.forEach { callback -> runCatching { properties?.unregisterCallback(callback) } }
        callbacks.clear()
        runCatching { restrictions?.unregisterListener() }
        runCatching { connected.disconnect() }
        restrictions = null
        properties = null
        car = null
    }

    private companion object {
        const val TAG = "DjTrotCar"

        /** 바깥 기온은 천천히 바뀐다 — 연속 속성이라 가장 낮은 빈도로 받는다. */
        const val TEMPERATURE_RATE_HZ = 0.1f
    }
}

private fun CarUxRestrictions.toState() = AaosCar.UxState(
    required = isRequiresDistractionOptimization,
    flags = activeRestrictions,
    maxTextLength = maxRestrictedStringLength,
)

internal fun gearState(value: Int): GearState = when (value) {
    VehicleGear.GEAR_PARK -> GearState.PARK
    VehicleGear.GEAR_REVERSE -> GearState.REVERSE
    VehicleGear.GEAR_NEUTRAL -> GearState.NEUTRAL
    VehicleGear.GEAR_UNKNOWN -> GearState.UNKNOWN
    else -> GearState.DRIVE
}
