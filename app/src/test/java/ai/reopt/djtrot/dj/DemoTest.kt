package ai.reopt.djtrot.dj

import ai.reopt.djtrot.car.DriveContext
import ai.reopt.djtrot.car.GearState
import ai.reopt.djtrot.car.PlaceKind
import ai.reopt.djtrot.car.RoadAhead
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoTest {
    private fun step(id: String) = Demo.STEPS.first { it.id == id }
    private fun press(vararg ids: String): DemoDrive = ids.fold(DemoDrive()) { drive, id -> step(id).change(drive) }

    @Test fun `버튼 ID는 겹치지 않는다`() {
        assertEquals(Demo.STEPS.size, Demo.STEPS.map { it.id }.toSet().size)
    }

    @Test fun `집으로 출발하면 목적지가 생기고 출발 사건이 난다`() {
        val home = step("depart.home")
        val event = home.event!!(DemoDrive()) as DemoEvent.Depart
        assertEquals(PlaceKind.HOME, event.departure.kind)
        assertEquals("depart.home.long", VoiceLines.departureId(event.departure))
        assertEquals("depart.office.short", VoiceLines.departureId((step("depart.office").event!!(DemoDrive()) as DemoEvent.Depart).departure))
        val drive = home.change(DemoDrive()).apply(DriveContext(gear = GearState.PARK))
        assertEquals(GearState.DRIVE, drive.gear)
        assertEquals(35, drive.destination?.remainMinutes)
        assertFalse(home.speak)
    }

    @Test fun `길 위의 버튼은 앞서 누른 장면을 지우고 그 장면만 남긴다`() {
        val drive = press("depart.far", "rain", "company", "tunnel").apply(DriveContext())
        assertEquals(RoadAhead.TUNNEL, drive.ahead)
        assertFalse(drive.raining)
        assertFalse(drive.company)
        assertEquals("강릉역", drive.destination?.name)
        // 시연은 분위기 장면도 곡을 가리지 않고 쓴다 — 누른 말이 바로 나와야 한다.
        val cruise = press("cruise").apply(DriveContext())
        assertNull(VoiceLines.scene(cruise, seed = 1, local = true))
        assertEquals(VoiceLines.Scene.CRUISING, VoiceLines.scene(cruise, seed = 1, local = true, always = true))
        assertEquals(listOf("scene.company.1"), VoiceLines.leadIds(press("company").apply(DriveContext()), 14, seed = 1, always = true))
        assertEquals(VoiceLines.Scene.LONG_DRIVE, VoiceLines.scene(press("long").apply(DriveContext()), seed = 1, local = true))
    }

    @Test fun `시간대 버튼은 인사에 쓰는 시각만 바꾼다`() {
        val night = press("depart.home", "hour.night")
        assertEquals(23, night.clock(LocalTime.of(14, 30)).hour)
        assertEquals("우리 집", night.destination?.name)
        assertEquals(LocalTime.of(14, 30), press("hour.night", "hour.now").clock(LocalTime.of(14, 30)))
    }

    @Test fun `도착하면 서고 목적지가 지워진다`() {
        val before = press("depart.far", "jam")
        val arrive = step("arrive")
        val event = arrive.event!!(before) as DemoEvent.Arrive
        assertEquals("강릉역", event.arrival.name)
        assertEquals("arrive.long", VoiceLines.arrivalId(event.arrival))
        val drive = arrive.change(before).apply(DriveContext())
        assertEquals(GearState.PARK, drive.gear)
        assertNull(drive.destination)
        assertNull(drive.traffic)
        assertTrue(press("charge").apply(DriveContext()).charging)
    }

    @Test fun `방향지시등은 누를 때마다 켜지고 꺼진다`() {
        assertTrue(press("signal").apply(DriveContext()).holdTalk)
        assertFalse(press("signal", "signal").apply(DriveContext()).holdTalk)
    }

    @Test fun `멘트 살펴보기는 조각을 다섯 묶음으로 나눈다`() {
        val ids = listOf("open.rain.1", "open.parked.2", "scene.jam.2", "eta.home.25", "depart.place.long", "waypoint", "arrive.short", "play.3", "play.request.fresh")
        assertEquals(listOf(2, 1, 1, 3, 2), LineGroup.entries.map { group -> ids.count(group::has) })
        assertTrue(ids.all { id -> LineGroup.entries.count { it.has(id) } == 1 })
    }

    @Test fun `자동 시연은 버튼을 차례로 눌러 길을 끝까지 간다`() {
        assertEquals(listOf("trip", "home", "wander"), Demo.SCENARIOS.map { it.id })
        val trip = Demo.SCENARIOS.first { it.id == "trip" }
        assertEquals("depart.far", trip.steps.first().id)
        assertEquals("arrive", trip.steps.last().id)
        // 끝까지 누르면 도착해 서 있다.
        val end = trip.steps.fold(DemoDrive()) { drive, step -> step.change(drive) }.apply(DriveContext())
        assertEquals(GearState.PARK, end.gear)
        assertNull(end.destination)
        // 출발·도착은 한마디라 짧게, 곡 소개를 다시 하는 버튼은 DJ가 말을 마칠 만큼 기다린다.
        assertEquals(10, step("depart.far").pauseSeconds)
        assertEquals(17, step("rain").pauseSeconds)
        assertEquals(6, step("signal").pauseSeconds)
        // 비 오는 퇴근길은 저녁 인사로 시작해 집에 닿는다.
        val home = Demo.SCENARIOS.first { it.id == "home" }.steps.fold(DemoDrive()) { drive, step -> step.change(drive) }
        assertEquals(19, home.hour)
    }

    @Test fun `지금 상황을 한 줄로 보여 준다`() {
        assertEquals("주행 · 우리 집까지 35분 · 비", press("depart.home", "rain").summary())
        assertEquals("충전 중", press("charge").summary())
    }
}
