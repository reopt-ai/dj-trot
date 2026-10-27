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

/** 목적지에 닿았을 때 내비가 알려 주는 이번 길. 값이 없으면 null. */
data class Arrival(
    val name: String,
    /** 걸린 시간(분). */
    val minutes: Int? = null,
    /** 달린 거리(km). */
    val km: Double? = null,
)

/** 차가 지금 알려 주는 것 — DJ 멘트와 신청곡 사연의 재료. 모든 값은 SDK가 없거나 실패하면 비어 있다. */
data class DriveContext(
    val gear: GearState = GearState.UNKNOWN,
    /** NORMAL·ECO·SPORT */
    val drivingMode: String? = null,
    val currentAddress: String? = null,
    val destination: Destination? = null,
    val batteryPercent: Int? = null,
    /** 🔑 CarUxRestrictions가 주행 최적화(DO)를 요구하는 중. 화면은 이 값으로 스스로 제한한다. */
    val restricted: Boolean = false,
    /** 지금 걸린 제한 항목(CarUxRestrictions.getActiveRestrictions 비트). 지역 정책에 따라 플랫폼이 정한다. */
    val uxFlags: Int = 0,
    /** 글 길이 제한이 걸렸을 때 한 덩어리 글의 최대 글자 수(CarUxRestrictions.getMaxRestrictedStringLength). */
    val maxTextLength: Int = 0,
    /** 내비가 곧 안내 음성을 낸다(회전 직전, 주의 구간, 단속 카메라). */
    val guidanceSoon: Boolean = false,
    /** 방향지시등이 켜져 있다(차선 변경·회전 중). */
    val signaling: Boolean = false,
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

    /**
     * 🔑 DJ가 지금 말을 꺼내지 않는다 — 내비 안내 음성과 겹치거나 운전자가 차선을 바꾸는 중이다.
     *    멘트는 화면에 먼저 보이고, 목소리는 이 값이 풀린 뒤에 낸다.
     */
    val holdTalk: Boolean get() = guidanceSoon || signaling

    /** 제한이 걸렸으면 글을 [maxTextLength]자로 자른다. */
    fun brief(text: String): String =
        if (limitText && maxTextLength in 1 until text.length) text.take(maxTextLength - 1).trimEnd() + "…" else text

    private companion object {
        // android.car.drivingstate.CarUxRestrictions 값 — 순수 모델이라 상수만 옮겨 둔다.
        const val UX_LIMIT_STRING_LENGTH = 4
        const val UX_NO_SETUP = 64
    }
}
