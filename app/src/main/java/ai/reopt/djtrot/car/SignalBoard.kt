package ai.reopt.djtrot.car

/** 차량 신호가 어디서 오는가 — 설정의 '차량 신호' 화면이 묶어 보여 준다. */
enum class SignalSource(val label: String) {
    VEHICLE("Pleos Vehicle"),
    NAVI("Pleos NaviHelper"),
    AAOS("AAOS 표준 속성"),
}

/** 차량 신호 한 줄 — 이름과 지금 값. 값이 없으면(SDK가 주지 않음) null. */
data class Signal(val label: String, val value: String?, val source: SignalSource)

/**
 * 차가 지금 알려 주는 것을 사람이 읽는 표로 — DJ 멘트의 재료가 실제 차량 신호임을 화면으로 보여 준다.
 * 🔑 안드로이드에 기대지 않는 순수 함수다. 어느 SDK가 어느 값을 주는지는 `CarSignals`·`AaosCar`의 구독과 같아야 한다.
 */
object SignalBoard {
    fun rows(drive: DriveContext): List<Signal> = listOf(
        Signal("주행 모드", drive.drivingMode, SignalSource.VEHICLE),
        Signal("배터리", drive.batteryPercent?.let { "$it%" }, SignalSource.VEHICLE),
        Signal("달린 시간", drive.tripMinutes?.let { if (it >= 60) "${it / 60}시간 ${it % 60}분" else "${it}분" }, SignalSource.VEHICLE),
        Signal("크루즈 컨트롤", onOff(drive.cruising), SignalSource.VEHICLE),
        Signal("와이퍼(비)", onOff(drive.raining), SignalSource.VEHICLE),
        Signal("앞 안개등", onOff(drive.fog), SignalSource.VEHICLE),
        Signal("방향지시등", onOff(drive.signaling), SignalSource.VEHICLE),
        Signal("동승자", if (drive.company) "있음" else "없음", SignalSource.VEHICLE),
        Signal("지금 위치", drive.region ?: drive.currentAddress, SignalSource.NAVI),
        Signal("목적지", drive.destination?.let { place -> place.remainMinutes?.let { "${place.name} · ${it}분 남음" } ?: place.name }, SignalSource.NAVI),
        Signal("가는 길", drive.traffic?.let { if (it.accident) "사고 소식" else "정체 ${it.jamMinutes}분" } ?: "원활".takeIf { drive.destination != null }, SignalSource.NAVI),
        Signal(
            "곧 지나는 곳",
            when (drive.ahead) {
                RoadAhead.TUNNEL -> "터널"
                RoadAhead.TOLL -> "톨게이트"
                RoadAhead.REST_AREA -> "휴게소"
                null -> null
            },
            SignalSource.NAVI,
        ),
        Signal("어린이보호구역", onOff(drive.schoolZone), SignalSource.NAVI),
        Signal("목적지 없이 달림", onOff(drive.freeDrive), SignalSource.NAVI),
        Signal("기어", drive.gear.label, SignalSource.AAOS),
        Signal("주행 중 화면 제한", if (drive.restricted) "걸림" else "없음", SignalSource.AAOS),
        Signal("충전구", if (drive.chargePortOpen) "열림" else "닫힘", SignalSource.AAOS),
    )

    private fun onOff(on: Boolean) = if (on) "켜짐" else "꺼짐"
}
