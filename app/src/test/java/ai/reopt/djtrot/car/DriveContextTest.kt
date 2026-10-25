package ai.reopt.djtrot.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun `설정 금지는 주행 제한 중에만 걸린다`() {
        assertTrue(DriveContext(gear = GearState.DRIVE, restricted = true, uxFlags = 64 or 8).noSetup)
        assertFalse(DriveContext(gear = GearState.PARK, restricted = false, uxFlags = 64).noSetup)
    }
}
