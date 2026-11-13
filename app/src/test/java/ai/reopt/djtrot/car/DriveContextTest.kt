package ai.reopt.djtrot.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveContextTest {
    private val line = "수정구 지나시는 새벽길에 이번 곡은 마스터트로트 악단의 '그 목소리'입니다."

    @Test fun `글 길이 제한이 걸렸을 때만 자른다`() {
        val limited = DriveContext(gear = GearState.DRIVE, restricted = true, uxFlags = 4, maxTextLength = 20)
        assertEquals(20, limited.brief(line).length)
        assertTrue(limited.brief(line).endsWith("…"))
        assertEquals(line, DriveContext(gear = GearState.DRIVE, restricted = true, uxFlags = 16, maxTextLength = 20).brief(line))
        assertEquals("짧은 말", limited.brief("짧은 말"))
    }

    @Test fun `안내 음성 직전이나 방향지시등이 켜지면 DJ가 말을 미룬다`() {
        assertFalse(DriveContext().holdTalk)
        assertTrue(DriveContext(guidanceSoon = true).holdTalk)
        assertTrue(DriveContext(signaling = true).holdTalk)
    }

    @Test fun `짧은 정체는 말할 거리가 아니다`() {
        assertNull(Traffic.of(jamSeconds = 200, road = "경부고속도로", accident = false, roadwork = false))
        assertEquals(Traffic(jamMinutes = 12, road = "경부고속도로"), Traffic.of(jamSeconds = 700, road = " 경부고속도로 ", accident = false, roadwork = false))
        assertEquals(Traffic(jamMinutes = 1, accident = true), Traffic.of(jamSeconds = 60, road = "", accident = true, roadwork = false))
    }

    @Test fun `즐겨찾기는 POI, 주소, 이름 순으로 맞춘다`() {
        val home = Bookmark(PlaceKind.HOME, poiId = "p1", name = "우리집", address = "성남시 수정구 1")
        assertTrue(home.matches("p1", "다른 이름", "다른 주소"))
        assertFalse(home.matches("p2", "우리집", "성남시 수정구 1"))
        assertTrue(home.matches("", "다른 이름", "성남시 수정구 1"))
        assertTrue(Bookmark(PlaceKind.OFFICE, name = "회사").matches("", " 회사 ", "어딘가"))
        assertFalse(Bookmark(PlaceKind.OFFICE).matches("", "", ""))
    }

    @Test fun `충전은 서 있을 때만, 오래 달렸는지는 두 시간부터`() {
        assertTrue(DriveContext(gear = GearState.PARK, chargePortOpen = true).charging)
        assertFalse(DriveContext(gear = GearState.DRIVE, chargePortOpen = true).charging)
        assertFalse(DriveContext(tripMinutes = 119).longDrive)
        assertTrue(DriveContext(tripMinutes = 120).longDrive)
    }

    @Test fun `설정 금지는 주행 제한 중에만 걸린다`() {
        assertTrue(DriveContext(gear = GearState.DRIVE, restricted = true, uxFlags = 64 or 8).noSetup)
        assertFalse(DriveContext(gear = GearState.PARK, restricted = false, uxFlags = 64).noSetup)
    }
}
