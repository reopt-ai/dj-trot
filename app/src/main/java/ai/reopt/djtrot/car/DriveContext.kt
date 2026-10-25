package ai.reopt.djtrot.car

enum class GearState(val label: String) {
    PARK("주차"),
    DRIVE("주행"),
    REVERSE("후진"),
    NEUTRAL("중립"),
    UNKNOWN("확인 중"),
}

data class Destination(
    val name: String,
    val address: String? = null,
    /** 남은 시간(분). 내비가 값을 주지 않으면 null. */
    val remainMinutes: Int? = null,
    /** 남은 거리(km). */
    val remainKm: Double? = null,
)

/** 차가 지금 알려 주는 것 — DJ 멘트와 신청곡 사연의 재료. 모든 값은 SDK가 없거나 실패하면 비어 있다. */
data class DriveContext(
    val gear: GearState = GearState.UNKNOWN,
    /** NORMAL·ECO·SPORT */
    val drivingMode: String? = null,
    val currentAddress: String? = null,
    val destination: Destination? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val batteryPercent: Int? = null,
    /** 🔑 CarUxRestrictions가 주행 최적화(DO)를 요구하는 중. 화면은 이 값으로 스스로 제한한다. */
    val restricted: Boolean = false,
    /** 지금 걸린 제한 항목(CarUxRestrictions.getActiveRestrictions 비트). 지역 정책에 따라 플랫폼이 정한다. */
    val uxFlags: Int = 0,
    /** 글 길이 제한이 걸렸을 때 한 덩어리 글의 최대 글자 수(CarUxRestrictions.getMaxRestrictedStringLength). */
    val maxTextLength: Int = 0,
) {
    val region: String? get() = Region.label(currentAddress ?: destination?.address)

    /** 정차 중으로 볼 수 있는가 — 기어가 P이거나, 기어를 모르면 주행 제한이 없을 때. */
    val stopped: Boolean get() = gear == GearState.PARK || (gear == GearState.UNKNOWN && !restricted)

    /** 긴 글을 줄여야 한다(UX_RESTRICTIONS_LIMIT_STRING_LENGTH). */
    val limitText: Boolean get() = restricted && (uxFlags and UX_LIMIT_STRING_LENGTH) != 0

    /**
     * 설정·로그인 화면을 열 수 없다(UX_RESTRICTIONS_NO_SETUP).
     * 🔴 QR 로그인은 Pleos 정책상 모든 지역에서 주행 중 금지 — 휴대폰 연결은 이 값이 참이면 열지 않는다.
     */
    val noSetup: Boolean get() = restricted && (uxFlags and UX_NO_SETUP) != 0

    /** 제한이 걸렸으면 글을 [maxTextLength]자로 자른다. */
    fun brief(text: String): String =
        if (limitText && maxTextLength in 1 until text.length) text.take(maxTextLength - 1).trimEnd() + "…" else text

    private companion object {
        // android.car.drivingstate.CarUxRestrictions 값 — 순수 모델이라 상수만 옮겨 둔다.
        const val UX_LIMIT_STRING_LENGTH = 4
        const val UX_NO_SETUP = 64
    }
}
