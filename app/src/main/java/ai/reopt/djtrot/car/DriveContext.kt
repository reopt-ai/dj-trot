package ai.reopt.djtrot.car

enum class GearState(val label: String) {
    PARK("주차"),
    DRIVE("주행"),
    REVERSE("후진"),
    NEUTRAL("중립"),
    UNKNOWN("확인 중"),
}

/** 내비 즐겨찾기가 알려 주는 곳의 종류. DJ가 "집으로 가는 길"이라고 부를 수 있다. */
enum class PlaceKind { HOME, OFFICE }

/** 좌표. 🔴 차 안에서 광고 장소를 견줄 때만 쓰고 서버로 보내지 않는다. */
data class GeoPoint(val latitude: Double, val longitude: Double)

data class Destination(
    val name: String,
    val address: String? = null,
    /** 남은 시간(분). 내비가 값을 주지 않으면 null. */
    val remainMinutes: Int? = null,
    /** 남은 거리(km). */
    val remainKm: Double? = null,
    /** 즐겨찾기의 집·회사와 같은 곳이면 그 종류. */
    val kind: PlaceKind? = null,
    /** 목적지 좌표. 🔴 기기 안에서만 쓴다. */
    val location: GeoPoint? = null,
)

/**
 * 내비 즐겨찾기 한 곳 — 목적지가 집·회사인지 알아보는 데만 쓴다.
 * 🔴 기기 밖으로 나가지 않는다. 서버에는 지금처럼 목적지 이름과 주소만 간다.
 */
data class Bookmark(val kind: PlaceKind, val poiId: String = "", val name: String = "", val address: String = "") {
    fun matches(poiId: String, name: String, address: String): Boolean = when {
        this.poiId.isNotBlank() && poiId.isNotBlank() -> this.poiId == poiId
        this.address.isNotBlank() && address.isNotBlank() -> this.address.trim() == address.trim()
        else -> this.name.isNotBlank() && this.name.trim() == name.trim()
    }
}

/** 길 안내를 시작할 때 내비가 알려 주는 이번 길. 값이 없으면 null. */
data class Departure(
    val name: String,
    /** 예상 시간(분). */
    val minutes: Int? = null,
    /** 전체 거리(km). */
    val km: Double? = null,
    val kind: PlaceKind? = null,
)

/** 가는 길의 정체 — [JAM_MINUTES]분 넘게 막힐 때만 만든다. */
data class Traffic(
    /** 정체 구간을 지나는 데 걸리는 시간(분). */
    val jamMinutes: Int,
    /** 가장 오래 막히는 도로 이름. */
    val road: String? = null,
    val accident: Boolean = false,
    val roadwork: Boolean = false,
) {
    companion object {
        const val JAM_MINUTES = 5

        /** 🔑 내비의 정체 시간은 초다(Pleos NaviHelper `TrafficCongestionSummary`). 짧은 정체는 말할 거리가 아니다. */
        fun of(jamSeconds: Long, road: String?, accident: Boolean, roadwork: Boolean): Traffic? {
            val minutes = ((jamSeconds + 30) / 60).toInt()
            if (minutes < JAM_MINUTES && !accident && !roadwork) return null
            return Traffic(minutes, road?.trim()?.takeIf { it.isNotEmpty() }, accident, roadwork)
        }
    }
}

/** 곧 지나는 곳 — 내비 TBT가 알려 준다. */
enum class RoadAhead { TUNNEL, TOLL, REST_AREA }

/** 곧 지나는 주의 구간 — 내비가 알려 주는 것 가운데 DJ가 한마디 할 만한 것만(Pleos NaviHelper `CautionPointType`). */
enum class Caution(val label: String) {
    FOG("안개 잦은 곳"),
    SLIPPERY("미끄러운 길"),
    WILDLIFE("야생동물 주의"),
    ACCIDENT("사고 잦은 곳"),
}

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
    /** 지금 좌표(내비). 🔴 광고 장소를 차 안에서 견줄 때만 쓰고, 서버로 보내지도 저장하지도 않는다. */
    val location: GeoPoint? = null,
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
    /** 가는 길이 막힌다(내비 경로 상태). */
    val traffic: Traffic? = null,
    /** 곧 터널·톨게이트·휴게소를 지난다. 지나고 나면 비운다. */
    val ahead: RoadAhead? = null,
    /** 어린이보호구역이 가깝다. DJ는 곡 이름만 말한다. */
    val schoolZone: Boolean = false,
    /** 목적지 없이 달리는 중(내비 FREE_DRIVE). */
    val freeDrive: Boolean = false,
    /** 시동 뒤 달린 시간(분, Pleos Vehicle Odometer). */
    val tripMinutes: Int? = null,
    /** 크루즈 컨트롤로 달리는 중. */
    val cruising: Boolean = false,
    /** 충전구가 열려 있다 — 서 있을 때는 충전 중으로 본다. */
    val chargePortOpen: Boolean = false,
    /** 충전기가 꽂혀 있다(AAOS `EV_CHARGE_PORT_CONNECTED`). 충전구가 열린 것보다 확실한 신호다. */
    val chargerConnected: Boolean = false,
    /** 곧 지나는 주의 구간(내비). 지나고 나면 비운다. */
    val caution: Caution? = null,
    /** 바깥 기온(섭씨, AAOS `ENV_OUTSIDE_TEMPERATURE`). 차가 주지 않으면 null. */
    val outsideCelsius: Int? = null,
    /** 앞 안개등이 켜져 있다. */
    val fog: Boolean = false,
    /** 앞 유리 와이퍼가 계속 움직인다 — 비나 눈이 온다. */
    val raining: Boolean = false,
    /** 운전석 말고도 누가 타고 있다. 🔴 기기 멘트에만 쓰고 서버로 보내지 않는다. */
    val company: Boolean = false,
    /** 이 차가 주지 않는 Vehicle 값의 이름([SignalBoard]의 줄 이름) — `checkXXXCapabilities`가 읽을 수 없다고 답한 것. */
    val unsupported: Set<String> = emptySet(),
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

    /** 쉬어 가자고 권할 만큼 오래 달렸다. */
    val longDrive: Boolean get() = (tripMinutes ?: 0) >= LONG_DRIVE_MINUTES

    /** 서서 충전하는 중으로 본다 — 충전기가 꽂혀 있거나 충전구가 열려 있다. */
    val charging: Boolean get() = (chargerConnected || chargePortOpen) && gear == GearState.PARK

    /** 배터리가 얼마 남지 않았다 — 충전하는 중에는 말하지 않는다. 0은 값이 없는 차(전기차가 아님)로 본다. */
    val lowBattery: Boolean get() = !charging && (batteryPercent ?: 100) in 1..LOW_BATTERY_PERCENT

    /** 바깥이 얼 만큼 춥다. */
    val cold: Boolean get() = (outsideCelsius ?: 15) <= COLD_CELSIUS

    /** 바깥이 한여름처럼 덥다. */
    val hot: Boolean get() = (outsideCelsius ?: 15) >= HOT_CELSIUS

    /** 스포츠 모드로 달린다. */
    val sport: Boolean get() = drivingMode == "SPORT"

    companion object {
        const val LONG_DRIVE_MINUTES = 120
        const val LOW_BATTERY_PERCENT = 15
        const val COLD_CELSIUS = 0
        const val HOT_CELSIUS = 30

        // android.car.drivingstate.CarUxRestrictions 값 — 순수 모델이라 상수만 옮겨 둔다.
        private const val UX_LIMIT_STRING_LENGTH = 4
        private const val UX_NO_SETUP = 64
    }
}
