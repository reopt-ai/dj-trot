package ai.reopt.djtrot.ui

import ai.reopt.djtrot.api.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricTimelineTest {
    private val lines = listOf(
        LyricLine("다 잊었다 큰소리쳤지", 11.36, 18.34),
        LyricLine("네 번호도 지웠다 했지", 18.54, 25.26),
        LyricLine("여보세요 그 한마디에", 40.0, 46.0),
    )

    @Test fun `전주에는 첫 줄만 미리 보여 준다`() {
        val moment = LyricTimeline.at(lines, 3.0)
        assertNull(moment.current)
        assertEquals("다 잊었다 큰소리쳤지", moment.next)
        assertEquals(8.36, moment.countdown!!, 0.001)
    }

    @Test fun `부르는 줄과 다음 줄을 보여 준다`() {
        val moment = LyricTimeline.at(lines, 12.0)
        assertEquals("다 잊었다 큰소리쳤지", moment.current)
        assertEquals("네 번호도 지웠다 했지", moment.next)
        assertNull(moment.countdown)
    }

    @Test fun `부른 부분이 줄을 따라 늘어난다`() {
        assertEquals(0f, LyricTimeline.at(lines, 11.36).sung, 0.001f)
        assertEquals(0.5f, LyricTimeline.at(lines, 43.0).sung, 0.001f)
        assertEquals(1f, LyricTimeline.at(lines, 47.0).sung, 0.001f)
    }

    @Test fun `짧은 숨에는 줄을 그대로 둔다`() {
        assertEquals("다 잊었다 큰소리쳤지", LyricTimeline.at(lines, 18.45).current)
    }

    @Test fun `긴 간주에는 줄을 비우고 수를 센다`() {
        val moment = LyricTimeline.at(lines, 32.0)
        assertNull(moment.current)
        assertEquals("여보세요 그 한마디에", moment.next)
        assertEquals(8.0, moment.countdown!!, 0.001)
    }

    @Test fun `후주에는 다음 줄이 없다`() {
        val moment = LyricTimeline.at(lines, 60.0)
        assertNull(moment.current)
        assertNull(moment.next)
        assertNull(moment.countdown)
    }

    @Test fun `가사가 없으면 아무것도 없다`() {
        val moment = LyricTimeline.at(emptyList(), 10.0)
        assertNull(moment.current)
        assertNull(moment.next)
    }
}
