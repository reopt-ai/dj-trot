package ai.reopt.djtrot.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalBoardTest {
    private fun value(drive: DriveContext, label: String) = SignalBoard.rows(drive).first { it.label == label }.value

    @Test fun `값이 없는 신호는 비어 있다`() {
        val empty = DriveContext()
        assertNull(value(empty, "배터리"))
        assertNull(value(empty, "목적지"))
        assertNull(value(empty, "가는 길"))
        assertEquals("확인 중", value(empty, "기어"))
        assertEquals("꺼짐", value(empty, "와이퍼(비)"))
    }

    @Test fun `차가 알려 주는 값을 읽기 쉬운 글로 보여 준다`() {
        val drive = DriveContext(
            gear = GearState.DRIVE, batteryPercent = 72, tripMinutes = 130, raining = true, company = true, restricted = true,
            destination = Destination("강릉역", remainMinutes = 95), traffic = Traffic(18, "영동고속도로"), ahead = RoadAhead.TUNNEL,
        )
        assertEquals("72%", value(drive, "배터리"))
        assertEquals("2시간 10분", value(drive, "달린 시간"))
        assertEquals("강릉역 · 95분 남음", value(drive, "목적지"))
        assertEquals("정체 18분", value(drive, "가는 길"))
        assertEquals("터널", value(drive, "곧 지나는 곳"))
        assertEquals("주행", value(drive, "기어"))
        assertEquals("걸림", value(drive, "주행 중 화면 제한"))
        assertEquals("있음", value(drive, "동승자"))
        assertEquals("원활", value(drive.copy(traffic = null), "가는 길"))
        assertEquals("공사 구간", value(drive.copy(traffic = Traffic(0, roadwork = true)), "가는 길"))
        assertEquals("미끄러운 길", value(drive.copy(caution = Caution.SLIPPERY), "주의 구간"))
        assertEquals("-3℃", value(drive.copy(outsideCelsius = -3), "바깥 기온"))
        assertEquals("꽂힘", value(drive.copy(chargerConnected = true), "충전기"))
        assertEquals("켜짐", value(drive.copy(seatHeat = true), SignalBoard.SEAT_HEAT))
        assertEquals("꺼짐", value(drive, SignalBoard.DEFROST))
    }

    @Test fun `차가 주지 않는 값은 지원 안 함으로 보인다`() {
        val drive = DriveContext(unsupported = setOf(SignalBoard.CRUISE, SignalBoard.COMPANY))
        assertEquals(SignalBoard.UNSUPPORTED, value(drive, SignalBoard.CRUISE))
        assertEquals(SignalBoard.UNSUPPORTED, value(drive, SignalBoard.COMPANY))
        assertEquals("꺼짐", value(drive, SignalBoard.WIPER))
    }

    @Test fun `모든 신호가 어느 SDK에서 오는지 적혀 있다`() {
        val rows = SignalBoard.rows(DriveContext())
        assertEquals(rows.size, rows.map { it.label }.toSet().size)
        assertTrue(SignalSource.entries.all { source -> rows.any { it.source == source } })
    }
}
