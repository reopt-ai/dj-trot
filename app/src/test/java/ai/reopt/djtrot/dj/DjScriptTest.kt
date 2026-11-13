package ai.reopt.djtrot.dj

import ai.reopt.djtrot.car.Arrival
import ai.reopt.djtrot.car.Departure
import ai.reopt.djtrot.car.GearState
import ai.reopt.djtrot.car.PlaceKind
import ai.reopt.djtrot.car.RoadAhead
import ai.reopt.djtrot.car.Traffic
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

    @Test fun `도착하면 걸린 시간과 거리로 마무리한다`() {
        assertEquals("부산역에 도착했습니다. 2시간 13분, 186km 운전하시느라 고생 많으셨어요.", DjScript.arrival(Arrival("부산역", minutes = 133, km = 186.4)))
        assertEquals("집에 도착했습니다. 2시간 운전하시느라 고생 많으셨어요.", DjScript.arrival(Arrival("집", minutes = 120, km = 0.4)))
        assertEquals("회사에 도착했습니다. 운전하시느라 고생 많으셨어요.", DjScript.arrival(Arrival("회사")))
        assertEquals("공항에 도착했습니다. 52km 운전하시느라 고생 많으셨어요.", DjScript.arrival(Arrival("공항", km = 52.3)))
    }

    @Test fun `가까운 길에는 수고했다고 하지 않는다`() {
        assertEquals("마트에 도착했습니다. 가까운 길도 함께해서 즐거웠어요.", DjScript.arrival(Arrival("마트", minutes = 8, km = 3.1)))
    }

    @Test fun `막히는 길과 남은 시간을 곡마다 번갈아 말한다`() {
        val drive = DriveContext(destination = Destination("부산역", remainMinutes = 42), traffic = Traffic(jamMinutes = 12, road = "경부고속도로"))
        assertTrue(DjScript.intro(song, drive, LocalTime.of(15, 0), seed = 0).startsWith("경부고속도로 쪽이 12분쯤 막히네요. 느긋하게 가시죠."))
        assertTrue(DjScript.intro(song, drive, LocalTime.of(15, 0), seed = 1).startsWith("부산역까지 42분 남았네요."))
    }

    @Test fun `사고 소식이 있으면 천천히 가자고 한다`() {
        val remarks = DjScript.remarks(DriveContext(traffic = Traffic(jamMinutes = 0, accident = true)), LocalTime.of(15, 0))
        assertEquals("가는 길에 사고 소식이 있네요. 천천히 가시죠.", remarks.first())
    }

    @Test fun `곧 지나는 휴게소와 터널이 가장 먼저다`() {
        val drive = DriveContext(ahead = RoadAhead.REST_AREA, traffic = Traffic(jamMinutes = 9), raining = true)
        assertEquals("곧 휴게소네요. 잠깐 쉬어 가셔도 좋겠어요.", DjScript.remarks(drive, LocalTime.of(15, 0)).first())
        assertEquals("곧 터널입니다. 터널 안에서도 노래는 이어집니다.", DjScript.remarks(DriveContext(ahead = RoadAhead.TUNNEL), LocalTime.of(15, 0)).first())
    }

    @Test fun `두 시간 넘게 달리면 쉬어 가자고 한다`() {
        assertTrue(DjScript.remarks(DriveContext(tripMinutes = 90), LocalTime.of(15, 0)).isEmpty())
        assertEquals("벌써 2시간 10분째 달리고 계시네요. 잠깐 쉬어 가셔도 좋겠어요.", DjScript.remarks(DriveContext(tripMinutes = 130), LocalTime.of(15, 0)).single())
    }

    @Test fun `비와 안개는 시간대와 함께 말한다`() {
        assertEquals("비 오는 밤길이네요.", DjScript.remarks(DriveContext(raining = true), LocalTime.of(22, 0)).single())
        assertEquals("안개 낀 새벽길이네요. 천천히 가시죠.", DjScript.remarks(DriveContext(raining = true, fog = true), LocalTime.of(4, 0)).single())
    }

    @Test fun `즐겨찾기의 집과 회사는 이름 대신 가는 길로 부른다`() {
        val home = DriveContext(destination = Destination("래미안 101동", remainMinutes = 25, kind = PlaceKind.HOME))
        assertEquals("집으로 가는 길, 25분 남았네요.", DjScript.remarks(home, LocalTime.of(19, 0)).single())
        assertEquals("회사 가는 길, 40분 길입니다. 편안히 모실게요.", DjScript.departure(Departure("판교 사옥", minutes = 40, kind = PlaceKind.OFFICE)))
    }

    @Test fun `어린이보호구역에서는 곡 이름만 말한다`() {
        val drive = DriveContext(destination = Destination("부산역", remainMinutes = 42), schoolZone = true)
        assertEquals("마스터트로트 악단의 '이름 좀 불러줘' 띄워 드립니다.", DjScript.intro(song, drive, LocalTime.of(15, 0), seed = 0))
    }

    @Test fun `충전과 동승자와 크루즈는 서 있거나 달릴 때 맞춰 말한다`() {
        assertEquals(listOf("충전하는 동안 한 곡 들으시죠."), DjScript.remarks(DriveContext(gear = GearState.PARK, chargePortOpen = true), LocalTime.of(15, 0)))
        assertTrue(DjScript.remarks(DriveContext(gear = GearState.DRIVE, chargePortOpen = true), LocalTime.of(15, 0)).isEmpty())
        assertEquals(listOf("함께 타신 분과 같이 들으시죠.", "크루즈 켜고 편안히 가는 길이네요."), DjScript.remarks(DriveContext(company = true, cruising = true), LocalTime.of(15, 0)))
    }

    @Test fun `목적지 없이 달리면 발길 닿는 대로`() {
        assertEquals("발길 닿는 대로 달리는 오후 길이네요.", DjScript.remarks(DriveContext(gear = GearState.DRIVE, freeDrive = true), LocalTime.of(15, 0)).single())
        assertTrue(DjScript.remarks(DriveContext(gear = GearState.PARK, freeDrive = true), LocalTime.of(15, 0)).isEmpty())
    }

    @Test fun `출발할 때 이번 길을 연다`() {
        assertEquals("부산역까지 2시간 10분 길입니다. 편안히 모실게요.", DjScript.departure(Departure("부산역", minutes = 130, km = 390.0)))
        assertEquals("편의점까지 6분이면 닿습니다. 한 곡 들으며 가시죠.", DjScript.departure(Departure("편의점", minutes = 6)))
        assertEquals("서울역으로 출발합니다. 편안히 모실게요.", DjScript.departure(Departure("서울역")))
        assertEquals("집으로 가는 길, 출발합니다. 편안히 모실게요.", DjScript.departure(Departure("우리집", kind = PlaceKind.HOME)))
    }

    @Test fun `경유지에 닿으면 남은 길을 잇는다`() {
        assertEquals("휴게소에 들렀습니다. 남은 길도 함께할게요.", DjScript.waypoint(Arrival("휴게소", minutes = 60)))
    }

    @Test fun `seed가 음수여도 멘트를 고른다`() {
        val line = DjScript.intro(song, DriveContext(), LocalTime.of(8, 0), seed = -7)
        assertTrue(line.contains("이름 좀 불러줘"))
    }
}
