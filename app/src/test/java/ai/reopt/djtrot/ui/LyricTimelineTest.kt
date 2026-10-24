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

    @Test
    fun introShowsOnlyTheFirstLineAhead() {
        val moment = LyricTimeline.at(lines, 3.0)
        assertNull(moment.current)
        assertEquals("다 잊었다 큰소리쳤지", moment.next)
        assertEquals(8.36, moment.countdown!!, 0.001)
    }

    @Test
    fun singingLineAndNextLine() {
        val moment = LyricTimeline.at(lines, 12.0)
        assertEquals("다 잊었다 큰소리쳤지", moment.current)
        assertEquals("네 번호도 지웠다 했지", moment.next)
        assertNull(moment.countdown)
    }

    @Test
    fun sungPartGrowsAcrossTheLine() {
        assertEquals(0f, LyricTimeline.at(lines, 11.36).sung, 0.001f)
        assertEquals(0.5f, LyricTimeline.at(lines, 43.0).sung, 0.001f)
        assertEquals(1f, LyricTimeline.at(lines, 47.0).sung, 0.001f)
    }

    @Test
    fun shortBreathKeepsTheLine() {
        assertEquals("다 잊었다 큰소리쳤지", LyricTimeline.at(lines, 18.45).current)
    }

    @Test
    fun longInterludeClearsTheLineAndCountsDown() {
        val moment = LyricTimeline.at(lines, 32.0)
        assertNull(moment.current)
        assertEquals("여보세요 그 한마디에", moment.next)
        assertEquals(8.0, moment.countdown!!, 0.001)
    }

    @Test
    fun outroHasNothingAhead() {
        val moment = LyricTimeline.at(lines, 60.0)
        assertNull(moment.current)
        assertNull(moment.next)
        assertNull(moment.countdown)
    }

    @Test
    fun noLyrics() {
        val moment = LyricTimeline.at(emptyList(), 10.0)
        assertNull(moment.current)
        assertNull(moment.next)
    }
}
