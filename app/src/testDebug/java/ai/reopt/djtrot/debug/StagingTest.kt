package ai.reopt.djtrot.debug

import ai.reopt.djtrot.Said
import ai.reopt.djtrot.car.Departure
import ai.reopt.djtrot.car.Destination
import ai.reopt.djtrot.car.DriveContext
import ai.reopt.djtrot.car.GearState
import ai.reopt.djtrot.car.PlaceKind
import ai.reopt.djtrot.car.RoadAhead
import ai.reopt.djtrot.car.Traffic
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StagingTest {
    private val real = DriveContext(gear = GearState.PARK, destination = Destination("강릉역", remainMinutes = 40), batteryPercent = 80)

    @Test fun `덧씌운 값만 바뀌고 나머지는 실제 값 그대로다`() {
        val staged = Staging.merge(emptyMap(), mapOf("gear" to "drive", "rain" to "on", "ahead" to "tunnel", "trip" to "130"))
        val drive = Staging.apply(real, staged)
        assertEquals(GearState.DRIVE, drive.gear)
        assertTrue(drive.raining)
        assertEquals(RoadAhead.TUNNEL, drive.ahead)
        assertTrue(drive.longDrive)
        assertEquals(80, drive.batteryPercent)
        assertEquals(real.destination, drive.destination)
    }

    @Test fun `목적지는 준 값만 바꾸고 none이면 없앤다`() {
        val home = Staging.apply(real, mapOf("dest_kind" to "HOME", "dest_min" to "12"))
        assertEquals(Destination("강릉역", remainMinutes = 12, kind = PlaceKind.HOME), home.destination)
        assertEquals(Destination("목적지", remainKm = 3.5), Staging.apply(DriveContext(), mapOf("dest_km" to "3.5")).destination)
        assertNull(Staging.apply(real, mapOf("dest" to "none")).destination)
    }

    @Test fun `정체는 시간이나 사고·공사가 있어야 생긴다`() {
        assertEquals(Traffic(12, "경부고속도로"), Staging.apply(real, mapOf("jam" to "12", "jam_road" to "경부고속도로")).traffic)
        assertEquals(Traffic(0, accident = true), Staging.apply(real, mapOf("accident" to "on")).traffic)
        assertNull(Staging.apply(real.copy(traffic = Traffic(9)), mapOf("jam" to "0")).traffic)
    }

    @Test fun `빼기 표시는 그 키를 지워 실제 값으로 되돌린다`() {
        val staged = Staging.merge(mapOf("rain" to "on", "fog" to "on"), mapOf("rain" to "-"))
        assertEquals(mapOf("fog" to "on"), staged)
        assertFalse(Staging.apply(real, staged).raining)
    }

    @Test fun `모르는 키와 읽을 수 없는 값은 거절한다`() {
        assertThrows(IllegalArgumentException::class.java) { Staging.merge(emptyMap(), mapOf("speed" to "80")) }
        assertThrows(IllegalArgumentException::class.java) { Staging.merge(emptyMap(), mapOf("rain" to "maybe")) }
        assertThrows(IllegalArgumentException::class.java) { Staging.merge(emptyMap(), mapOf("gear" to "FLY")) }
        assertThrows(IllegalArgumentException::class.java) { Staging.merge(emptyMap(), mapOf("trip" to "-5")) }
    }

    @Test fun `출발하면 덧씌운 목적지가 이번 길로 바뀐다`() {
        val before = mapOf("dest" to "옛 목적지", "dest_address" to "어딘가", "jam" to "20", "rain" to "on")
        val departure = Staging.departure(mapOf("name" to "우리 집", "min" to "35", "kind" to "home"))
        assertEquals(Departure("우리 집", minutes = 35, kind = PlaceKind.HOME), departure)
        assertEquals(
            mapOf("rain" to "on", "dest" to "우리 집", "dest_min" to "35", "dest_kind" to "HOME"),
            Staging.depart(before, departure),
        )
        assertEquals("목적지", Staging.departure(emptyMap()).name)
        assertEquals("경유지", Staging.arrival(mapOf("name" to " "), "경유지").name)
    }

    @Test fun `결과 글은 번갈아 쓰는 두 멘트와 서버로 가는 맥락을 보여 준다`() {
        val staged = mapOf("jam" to "12", "rain" to "on", "company" to "on", "dest_kind" to "HOME")
        val report = Staging.report(Staging.apply(real, staged), staged, LocalTime.of(19, 0), event = "출발합니다.")
        assertTrue(report, "곡 소개 1: 가는 길이 12분쯤 막히네요." in report)
        assertTrue(report, "곡 소개 2: 비 오는 저녁길이네요." in report)
        assertTrue(report, "지금 한마디: 출발합니다." in report)
        assertTrue(report, "밀린 말 3: 집으로 가는 길, 40분 남았네요." in report)
        assertTrue(report, "밀린 말 4: 함께 타신 분과 같이 들으시죠." in report)
        assertFalse(report, "⚠️" in report)
        // 🔴 집·회사와 동승자는 기기 멘트에만 쓴다.
        val sent = report.substringAfter("서버로 가는 맥락: ")
        assertFalse(sent, "HOME" in sent || "company" in sent)
    }

    @Test fun `노래가 멈췄거나 멘트가 꺼져 있으면 한마디가 나오지 않는다고 알린다`() {
        val stopped = Staging.Stage(playing = false, talks = false, last = Said("좋은 아침입니다.", LocalTime.of(8, 5, 9)))
        val report = Staging.report(real, emptyMap(), LocalTime.NOON, event = "출발합니다.", stage = stopped)
        assertTrue(report, "재생: 멈춤 · DJ 멘트 꺼짐" in report)
        assertTrue(report, "⚠️ DJ 멘트가 꺼져 있습니다, 노래가 멈춰 있습니다" in report)
        assertTrue(report, "방금 DJ가 한 말: [08:05:09] 좋은 아침입니다." in report)
        assertTrue(Staging.LAST + "없음" in Staging.report(real, emptyMap(), LocalTime.NOON))
    }

    @Test fun `어린이보호구역에서는 곡 이름만 말한다`() {
        val report = Staging.report(Staging.apply(real, mapOf("school" to "on", "jam" to "30")), emptyMap(), LocalTime.NOON)
        assertTrue(report, "곡 소개 1: 가수의 '곡 제목' 띄워 드립니다." in report)
        assertFalse(report, "밀린 말" in report)
    }
}
