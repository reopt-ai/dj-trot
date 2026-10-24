package ai.reopt.djtrot.dj

import ai.reopt.djtrot.car.Destination
import ai.reopt.djtrot.car.DriveContext
import java.time.Instant
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DjScriptTest {
    private val song = SongInfo(title = "이름 좀 불러줘", singer = "마스터트로트 악단")

    @Test fun `목적지가 있으면 남은 시간을 말한다`() {
        val line = DjScript.intro(song, DriveContext(destination = Destination("부산역", remainMinutes = 42)), LocalTime.of(15, 0), seed = 0)
        assertEquals("부산역까지 42분 남았네요. 마스터트로트 악단의 '이름 좀 불러줘' 띄워 드립니다.", line)
    }

    @Test fun `거의 다 왔을 때`() {
        val line = DjScript.intro(song, DriveContext(destination = Destination("집", remainMinutes = 2)), LocalTime.of(15, 0), seed = 1)
        assertEquals("집, 거의 다 오셨네요. 이번 곡은 마스터트로트 악단의 '이름 좀 불러줘'입니다.", line)
    }

    @Test fun `목적지가 없으면 지역과 시간대`() {
        val line = DjScript.intro(song, DriveContext(currentAddress = "경기도 성남시 수정구 시흥동"), LocalTime.of(22, 10), seed = 2)
        assertEquals("성남 지나시는 밤길에 마스터트로트 악단의 목소리로 '이름 좀 불러줘' 들어 보시죠.", line)
    }

    @Test fun `아무 정보가 없으면 인사`() {
        val line = DjScript.intro(song, DriveContext(), LocalTime.of(8, 0), seed = 0)
        assertTrue(line, line.startsWith("좋은 아침입니다. "))
    }

    @Test fun `신청곡은 맡긴 사연으로 만든 노래라고 소개한다`() {
        val made = SongInfo(title = "힘내라 오늘도", singer = "DJ 트롯", requested = true, fresh = true)
        val line = DjScript.intro(made, DriveContext(currentAddress = "경기도 성남시 수정구 시흥동"), LocalTime.of(10, 21), seed = 0)
        assertEquals("성남 지나시는 아침길에 아까 맡기신 사연으로 막 만든 노래 '힘내라 오늘도' 띄워 드립니다.", line)
        assertEquals("지난번 맡기신 사연으로 만든 노래 '힘내라 오늘도' 띄워 드립니다.", DjScript.play(made.copy(fresh = false), 0))
    }

    @Test fun `만든 지 1시간이 지나면 아까 만든 노래가 아니다`() {
        val now = Instant.parse("2026-10-24T07:07:00Z")
        assertTrue(DjScript.isFresh("2026-10-24T06:30:00.000Z", now))
        assertTrue(DjScript.isFresh("2026-10-24T15:30:00+09:00", now))
        assertFalse(DjScript.isFresh("2026-10-24T01:22:51.000Z", now))
        assertFalse(DjScript.isFresh("", now))
        assertFalse(DjScript.isFresh(null, now))
    }

    @Test fun `DJ 자신의 이름은 가수로 부르지 않는다`() {
        for (seed in 0..2) {
            val line = DjScript.play(SongInfo(title = "힘내라 오늘도", singer = " dj트롯 "), seed)
            assertFalse(line, "트롯" in line.substringBefore("'"))
            assertTrue(line, line.contains("'힘내라 오늘도'"))
        }
        assertEquals("'고향역' 띄워 드립니다.", DjScript.play(SongInfo(title = "고향역", singer = ""), 0))
    }

    @Test fun `seed가 음수여도 멘트를 고른다`() {
        val line = DjScript.intro(song, DriveContext(), LocalTime.of(8, 0), seed = -7)
        assertTrue(line.contains("이름 좀 불러줘"))
    }
}
