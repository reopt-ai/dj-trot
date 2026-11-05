package ai.reopt.djtrot.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPathTest {
    @Test fun `만든 ID를 다시 읽으면 같은 자리다`() {
        val paths = listOf(MediaPath.Root, MediaPath.Style("ppongjjak"), MediaPath.Song("abc-1")) + Shelf.entries.map(MediaPath::Top)
        paths.forEach { assertEquals(it, MediaPath.parse(it.id)) }
    }

    @Test fun `모르는 ID나 빈 곡 ID는 읽지 않는다`() {
        assertNull(MediaPath.parse("search"))
        assertNull(MediaPath.parse("song:"))
        assertNull(MediaPath.parse("style:"))
        assertNull(MediaPath.parse(""))
    }

    @Test fun `장르 묶음만 곡을 바로 담지 않는다`() {
        assertFalse(MediaPath.Root.holdsSongs)
        assertFalse(MediaPath.Top(Shelf.STYLES).holdsSongs)
        assertFalse(MediaPath.Song("abc").holdsSongs)
        assertTrue(MediaPath.Top(Shelf.PICKS).holdsSongs)
        assertTrue(MediaPath.Top(Shelf.MINE).holdsSongs)
        assertTrue(MediaPath.Style("ballad").holdsSongs)
    }

    @Test fun `루트의 폴더는 미디어 센터 탭 권장 수를 넘지 않는다`() {
        assertTrue(Shelf.entries.size <= 4)
    }
}
