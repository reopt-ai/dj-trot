package ai.reopt.djtrot.dj

import ai.reopt.djtrot.car.Destination
import ai.reopt.djtrot.car.DriveContext
import ai.reopt.djtrot.car.PlaceKind
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryBuilderTest {
    private val afternoon = LocalTime.of(14, 0)

    @Test fun `출발지와 목적지로 사연을 쓴다`() {
        val drive = DriveContext(currentAddress = "경기도 성남시 수정구 시흥동", destination = Destination("부산역", remainMinutes = 250))
        val request = StoryBuilder.build(drive, afternoon)
        assertEquals("성남에서 부산역으로 가는 오후 길. 도착까지 250분.", request.story)
        assertEquals("life", request.topic)
        assertEquals("classic", request.style)
    }

    @Test fun `목적지 이름으로 주제를 고른다`() {
        assertEquals("mother", StoryBuilder.build(DriveContext(destination = Destination("엄마네 집")), afternoon).topic)
        assertEquals("hometown", StoryBuilder.build(DriveContext(destination = Destination("우리집")), afternoon).topic)
        assertEquals("health", StoryBuilder.build(DriveContext(destination = Destination("분당서울대병원")), afternoon).topic)
        assertEquals("cheer", StoryBuilder.build(DriveContext(destination = Destination("회사")), afternoon).topic)
    }

    @Test fun `말로 넣은 사연이 주제를 정한다`() {
        val request = StoryBuilder.build(DriveContext(), afternoon, spoken = "뒷자리 어머니 칠순이에요")
        assertEquals("mother", request.topic)
        assertTrue(request.story.endsWith("뒷자리 어머니 칠순이에요"))
    }

    @Test fun `주행 모드와 시간대로 장르를 고른다`() {
        assertEquals("ppongjjak", StoryBuilder.style(DriveContext(drivingMode = "SPORT"), afternoon))
        assertEquals("ballad", StoryBuilder.style(DriveContext(), LocalTime.of(23, 30)))
        assertEquals("classic", StoryBuilder.style(DriveContext(drivingMode = "ECO"), afternoon))
    }

    @Test fun `비 오는 길은 발라드와 사연에 담는다`() {
        val request = StoryBuilder.build(DriveContext(destination = Destination("부산역"), raining = true), afternoon)
        assertEquals("ballad", request.style)
        assertTrue(request.story.startsWith("부산역으로 가는 비 오는 오후 길."))
    }

    @Test fun `즐겨찾기의 집과 회사가 주제를 정한다`() {
        assertEquals("hometown", StoryBuilder.build(DriveContext(destination = Destination("래미안 101동", kind = PlaceKind.HOME)), afternoon).topic)
        assertEquals("cheer", StoryBuilder.build(DriveContext(destination = Destination("판교 사옥", kind = PlaceKind.OFFICE)), afternoon).topic)
    }

    @Test fun `사연은 200자를 넘지 않는다`() {
        val long = "가".repeat(300)
        val request = StoryBuilder.build(DriveContext(destination = Destination("부산역")), afternoon, spoken = long)
        assertTrue(request.story.length <= StoryBuilder.MAX_STORY)
    }
}
