package ai.reopt.djtrot

import ai.reopt.djtrot.dj.SongRequest
import java.time.Instant
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DjStateTest {
    @Test
    fun newLinePushesThePreviousOneIntoEarlier() {
        val state = DjState()
            .say("첫 인사", LocalTime.of(21, 0))
            .say("두 번째", LocalTime.of(21, 4))
            .say("세 번째", LocalTime.of(21, 8))
        assertEquals("세 번째", state.line)
        assertEquals(listOf(Said("두 번째", LocalTime.of(21, 4)), Said("첫 인사", LocalTime.of(21, 0))), state.earlier)
    }

    @Test
    fun keepsOnlyTheRecentLinesAndIgnoresRepeats() {
        var state = DjState()
        (1..6).forEach { state = state.say("멘트 $it", LocalTime.of(20, it)) }
        assertEquals(DjState.KEEP, state.earlier.size)
        assertEquals("멘트 5", state.earlier.first().text)
        assertSame(state, state.say("멘트 6"))
    }

    @Test
    fun suggestionGoesStaleWhileParked() {
        val at = Instant.parse("2026-10-24T17:43:00Z")
        val state = DjState().suggest(SongRequest("cheer", "ballad", "새벽 2시 43분, 시흥동에 잠시 멈춰"), at)
        assertEquals("cheer", state.freshSuggestion(at.plus(DjState.STALE).minusSeconds(1))?.topic)
        assertNull(state.freshSuggestion(at.plus(DjState.STALE)))
        assertNull(DjState().freshSuggestion(at))
    }
}
