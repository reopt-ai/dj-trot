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

/**
 * AAOS 표준 Car API — 주행 중 UX 제한(CarUxRestrictions)과 기어(GEAR_SELECTION).
 * 🔑 매니페스트에서 화면을 distractionOptimized로 선언했으므로, 제한이 걸리면 화면이 스스로 긴 글·목록·버튼을 줄인다
 *    (Pleos Driving Restriction Development Guide).
 * 🔑 기어는 Pleos Vehicle SDK의 Gear API가 폐기 예정이라("use the Google GEAR_SELECTION property") 표준 속성으로 읽는다.
 */
class AaosCar(
    private val context: Context,
    private val onRestrictions: (UxState) -> Unit,
    private val onGear: (GearState) -> Unit,
) {
    /** 주행 최적화 요구 여부와 지금 걸린 제한 항목. */
    data class UxState(val required: Boolean, val flags: Int, val maxTextLength: Int)

    private var car: Car? = null
    private var ux: CarUxRestrictionsManager? = null
    private var properties: CarPropertyManager? = null

    private val gearCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            (value.value as? Int)?.let { onGear(gearState(it)) }
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) = Log.w(TAG, "기어 속성 오류 $propertyId").let { }
    }

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || car != null) return
        val connected = runCatching { Car.createCar(context) }.getOrNull() ?: return
        car = connected
        runCatching {
            val manager = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            ux = manager
            manager.currentCarUxRestrictions?.let { onRestrictions(it.toState()) }
            manager.registerListener { restrictions -> onRestrictions(restrictions.toState()) }
        }.onFailure { Log.w(TAG, "주행 제한 서비스를 쓸 수 없어요", it) }
        runCatching {
            val manager = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            properties = manager
            @Suppress("DEPRECATION")
            manager.registerCallback(gearCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        }.onFailure { Log.w(TAG, "기어 속성을 읽을 수 없어요", it) }
    }

    fun stop() {
        runCatching { ux?.unregisterListener() }
        @Suppress("DEPRECATION")
        runCatching { properties?.unregisterCallback(gearCallback) }
        runCatching { car?.disconnect() }
        ux = null
        properties = null
        car = null
    }

    private companion object {
        const val TAG = "DjTrotCar"
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
