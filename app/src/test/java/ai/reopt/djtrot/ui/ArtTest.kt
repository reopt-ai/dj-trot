package ai.reopt.djtrot.ui

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtTest {
    @Test
    fun balladMoodsShareOneFamily() {
        assertEquals("ballad", Art.family("ballad-calm"))
        assertEquals(Art.cover("ballad"), Art.cover("ballad-calm"))
    }

    @Test
    fun unknownStyleFallsBackToTheConcertHall() {
        assertEquals(Art.stage(null), Art.stage("unknown"))
    }

    @Test
    fun eachSongGetsItsOwnSteadyLook() {
        val ids = (1..12).map { "380faad4-0000-4000-8000-0000000000%02d".format(it) }
        val looks = ids.map(Art::look)
        assertEquals(looks, ids.map(Art::look))
        assertTrue(looks.toSet().size >= 6)
        assertEquals(Art.Look.Plain, Art.look(null))
        // 옮긴 만큼 확대돼 있어야 표지 가장자리가 비지 않는다.
        looks.forEach { look ->
            assertTrue(abs(look.dx) <= (look.zoom - 1f) / 2f)
            assertTrue(abs(look.dy) <= (look.zoom - 1f) / 2f)
        }
    }
}
