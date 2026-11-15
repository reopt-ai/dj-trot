package ai.reopt.djtrot.dj

import ai.reopt.djtrot.car.Arrival
import ai.reopt.djtrot.car.Caution
import ai.reopt.djtrot.car.Departure
import ai.reopt.djtrot.car.Destination
import ai.reopt.djtrot.car.DriveContext
import ai.reopt.djtrot.car.GearState
import ai.reopt.djtrot.car.PlaceKind
import ai.reopt.djtrot.car.RoadAhead
import ai.reopt.djtrot.car.Traffic
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 🔴 음성 조각 고르기는 서버와 같은 값을 내야 한다 — 기대값은 서버 규칙이 내는 값이다. */
class VoiceLinesTest {
    private fun lead(scene: VoiceLines.Scene? = null, weather: String? = null, parked: Boolean = false, minutes: Int? = null, place: String = "place", hour: Int, seed: Long) =
        VoiceLines.leadIds(scene, weather, parked, hasDestination = minutes != null, remainMinutes = minutes, place = place, hour = hour, seed = seed)

    @Test fun `씨앗은 서버와 같은 값이다`() {
        assertEquals(3816466114L, VoiceLines.seedOf("고향 가는 길\n마스터트롯 악단"))
        assertEquals(2639422071L, VoiceLines.seedOf("힘내라 오늘도\n"))
        assertEquals(350953279L, VoiceLines.seedOf("19"))
        assertEquals(666016288L, VoiceLines.seedOf("Trot 🎵 노래\nA"))
        assertEquals(2166136261L, VoiceLines.seedOf(""))
    }

    @Test fun `DJ 자신의 가수명은 씨앗에서 뺀다`() {
        assertEquals(2639422071L, VoiceLines.seedOf(SongInfo(" 힘내라 오늘도 ", "DJ 트롯")))
        assertEquals(3816466114L, VoiceLines.seedOf(SongInfo("고향 가는 길", "마스터트롯 악단")))
    }

    @Test fun `남은 시간은 가까운 구간으로, 같은 거리면 짧은 쪽`() {
        assertEquals("5,5,10,20,30,40,60,90,90,120,far", listOf(4, 7, 8, 22, 35, 45, 75, 76, 105, 150, 151).joinToString(",", transform = VoiceLines::etaBucket))
        assertEquals("near", VoiceLines.etaBucket(3))
    }

    @Test fun `여는 말과 남은 시간을 서버와 같게 고른다`() {
        assertEquals(listOf("open.parked.1"), lead(parked = true, hour = 19, seed = 0))
        assertEquals(listOf("open.rain.1", "eta.home.25"), lead(weather = "rain", minutes = 23, place = "home", hour = 19, seed = 0))
        assertEquals(listOf("open.morning.2", "eta.place.near"), lead(minutes = 2, hour = 9, seed = 1))
        assertEquals(listOf("scene.jam.2", "eta.place.40"), lead(scene = VoiceLines.Scene.JAM, weather = "rain", minutes = 41, hour = 9, seed = 1))
        assertEquals(listOf("scene.charging.1"), lead(scene = VoiceLines.Scene.CHARGING, parked = true, hour = 9, seed = 0))
        val seed = VoiceLines.seedOf("고향 가는 길\n마스터트롯 악단")
        assertEquals(listOf("open.rain.2", "eta.place.25"), lead(weather = "rain", minutes = 23, hour = 19, seed = seed))
        assertEquals(listOf("open.dawn.2", "eta.place.far"), lead(minutes = 200, hour = 3, seed = seed))
        assertEquals(listOf("open.fog.1"), lead(weather = "fog", hour = 23, seed = 2))
        assertEquals(listOf("open.night.3"), lead(hour = 23, seed = 2))
    }

    @Test fun `제목 없는 곡 소개`() {
        val seed = VoiceLines.seedOf("고향 가는 길\n마스터트롯 악단")
        assertEquals("play.5", VoiceLines.genericPlayId(SongInfo("고향 가는 길", "마스터트롯 악단"), seed))
        assertEquals("play.request.fresh", VoiceLines.genericPlayId(SongInfo("a", "DJ 트롯", requested = true, fresh = true), seed))
        assertEquals("play.request.old", VoiceLines.genericPlayId(SongInfo("a", "DJ 트롯", requested = true), seed))
    }

    @Test fun `장면은 급한 것부터, 분위기는 곡의 절반에만`() {
        val busy = DriveContext(ahead = RoadAhead.TUNNEL, traffic = Traffic(12), tripMinutes = 200, cruising = true)
        assertEquals(VoiceLines.Scene.TUNNEL, VoiceLines.scene(busy, seed = 1, local = false))
        assertEquals(VoiceLines.Scene.JAM, VoiceLines.scene(busy.copy(ahead = null), seed = 1, local = false))
        assertEquals(VoiceLines.Scene.ACCIDENT, VoiceLines.scene(DriveContext(traffic = Traffic(2, accident = true)), seed = 1, local = false))
        assertEquals(VoiceLines.Scene.LONG_DRIVE, VoiceLines.scene(busy.copy(ahead = null, traffic = null), seed = 1, local = false))
        val calm = DriveContext(gear = GearState.DRIVE, cruising = true)
        assertEquals(VoiceLines.Scene.CRUISING, VoiceLines.scene(calm, seed = 2, local = false))
        assertNull(VoiceLines.scene(calm, seed = 3, local = false))
        assertEquals(VoiceLines.Scene.CHARGING, VoiceLines.scene(DriveContext(gear = GearState.PARK, chargePortOpen = true, company = true), seed = 0, local = true))
        assertEquals(VoiceLines.Scene.FREE_DRIVE, VoiceLines.scene(DriveContext(gear = GearState.DRIVE, freeDrive = true), seed = 0, local = false))
        assertNull(VoiceLines.scene(DriveContext(gear = GearState.DRIVE, freeDrive = true, destination = Destination("부산역")), seed = 0, local = false))
    }

    @Test fun `동승자와 집 회사는 기기 안에서만 쓴다`() {
        val drive = DriveContext(gear = GearState.DRIVE, company = true, destination = Destination("우리 집", remainMinutes = 23, kind = PlaceKind.HOME))
        // 🔴 서버로 가는 값에는 동승자 장면도, 목적지의 종류도 없다.
        val input = drive.toInput(LocalTime.of(19, 0), seed = 0)
        assertNull(input.scene)
        assertEquals(VoiceLines.Scene.COMPANY, VoiceLines.scene(drive, seed = 0, local = true))
        assertEquals(listOf("scene.company.1", "eta.home.25"), VoiceLines.leadIds(drive, hour = 19, seed = 0))
        assertEquals(listOf("open.evening.2", "eta.home.25"), VoiceLines.leadIds(drive, hour = 19, seed = 1))
    }

    @Test fun `날씨와 장면을 서버가 아는 값으로 보낸다`() {
        assertEquals("fog", DriveContext(fog = true, raining = true).toInput(LocalTime.NOON).weather)
        assertEquals("rain", DriveContext(raining = true).toInput(LocalTime.NOON).weather)
        assertNull(DriveContext().toInput(LocalTime.NOON).weather)
        assertEquals("rest_area", DriveContext(ahead = RoadAhead.REST_AREA).toInput(LocalTime.NOON, seed = 1).scene)
        assertEquals("toll", DriveContext(ahead = RoadAhead.TOLL).toInput(LocalTime.NOON).scene)
        assertEquals("long_drive", DriveContext(tripMinutes = 130).toInput(LocalTime.NOON, seed = 1).scene)
        assertNull(DriveContext(gear = GearState.DRIVE, cruising = true).toInput(LocalTime.NOON, seed = 1).scene)
        // 🔴 서버가 받는 값의 전부다 — 여기에 없는 값을 보내면 400이다.
        assertEquals(
            listOf(
                "rest_area", "tunnel", "toll", "jam", "accident", "long_drive", "charging", "company", "cruising", "free_drive",
                "roadwork", "caution_fog", "caution_slippery", "caution_wildlife", "caution_accident", "low_battery", "cold", "hot", "sport",
                "defrost", "seat_heat", "seat_cool",
            ),
            VoiceLines.Scene.entries.map { it.id },
        )
        assertEquals(VoiceLines.Scene.entries.take(10).map { it.id }.toSet(), VoiceLines.LEGACY_SCENES)
    }

    @Test fun `뒤에 더한 장면 — 주의 구간, 공사, 배터리, 기온, 스포츠 모드`() {
        val drive = DriveContext(gear = GearState.DRIVE)
        assertEquals(VoiceLines.Scene.CAUTION_FOG, VoiceLines.scene(drive.copy(caution = Caution.FOG, traffic = Traffic(12)), seed = 1, local = false))
        assertEquals(VoiceLines.Scene.TUNNEL, VoiceLines.scene(drive.copy(caution = Caution.WILDLIFE, ahead = RoadAhead.TUNNEL), seed = 1, local = false))
        // 공사는 막히는 시간이 짧을 때만 따로 말한다.
        assertEquals(VoiceLines.Scene.ROADWORK, VoiceLines.scene(drive.copy(traffic = Traffic(0, roadwork = true)), seed = 1, local = false))
        assertEquals(VoiceLines.Scene.JAM, VoiceLines.scene(drive.copy(traffic = Traffic(12, roadwork = true)), seed = 1, local = false))
        assertEquals(VoiceLines.Scene.ACCIDENT, VoiceLines.scene(drive.copy(traffic = Traffic(0, accident = true, roadwork = true)), seed = 1, local = false))
        // 배터리 부족은 급한 말이라 곡을 가리지 않는다. 충전 중이거나 값이 0(전기차가 아님)이면 말하지 않는다.
        assertEquals(VoiceLines.Scene.LOW_BATTERY, VoiceLines.scene(drive.copy(batteryPercent = 12), seed = 1, local = false))
        assertNull(VoiceLines.scene(drive.copy(batteryPercent = 0), seed = 1, local = false))
        assertEquals(VoiceLines.Scene.CHARGING, VoiceLines.scene(DriveContext(gear = GearState.PARK, chargerConnected = true, batteryPercent = 12), seed = 0, local = false))
        // 기온과 스포츠 모드는 분위기라 곡의 절반에만.
        assertEquals(VoiceLines.Scene.COLD, VoiceLines.scene(drive.copy(outsideCelsius = -3), seed = 0, local = false))
        assertNull(VoiceLines.scene(drive.copy(outsideCelsius = -3), seed = 1, local = false))
        assertEquals(VoiceLines.Scene.HOT, VoiceLines.scene(drive.copy(outsideCelsius = 31), seed = 0, local = false))
        assertEquals(VoiceLines.Scene.SPORT, VoiceLines.scene(drive.copy(drivingMode = "SPORT", cruising = true), seed = 0, local = false))
        assertNull(VoiceLines.scene(drive.copy(outsideCelsius = 18, drivingMode = "ECO"), seed = 0, local = false))
        // 공조 — 성에 제거와 운전석 열선·통풍도 분위기 장면이다. 추운 날 열선을 켰으면 열선 이야기를 한다.
        assertEquals(VoiceLines.Scene.DEFROST, VoiceLines.scene(drive.copy(defrosting = true, seatHeat = true), seed = 0, local = false))
        assertEquals(VoiceLines.Scene.SEAT_HEAT, VoiceLines.scene(drive.copy(seatHeat = true, outsideCelsius = -3), seed = 0, local = false))
        assertEquals(VoiceLines.Scene.SEAT_COOL, VoiceLines.scene(drive.copy(seatCool = true, outsideCelsius = 33), seed = 0, local = false))
        assertNull(VoiceLines.scene(drive.copy(seatHeat = true), seed = 1, local = false))
    }

    @Test fun `서버가 모르는 장면은 보내지 않는다`() {
        val cold = DriveContext(gear = GearState.DRIVE, outsideCelsius = -3)
        try {
            // 🔴 옛 서버(장면 목록을 주지 않는다)에는 처음 열 가지만 보낸다 — 모르는 값은 400이다.
            VoiceLines.serverScenes(emptyList())
            assertNull(cold.toInput(LocalTime.NOON, seed = 0).scene)
            assertEquals("tunnel", cold.copy(ahead = RoadAhead.TUNNEL).toInput(LocalTime.NOON, seed = 0).scene)
            VoiceLines.serverScenes(VoiceLines.Scene.entries.map { it.id })
            assertEquals("cold", cold.toInput(LocalTime.NOON, seed = 0).scene)
        } finally {
            VoiceLines.serverScenes(emptyList())
        }
    }

    @Test fun `장면 조각이 없으면 장면 없이 고른다`() {
        val drive = DriveContext(gear = GearState.DRIVE, caution = Caution.SLIPPERY, raining = true)
        assertEquals(listOf("scene.caution_slippery.1"), VoiceLines.leadIds(drive, hour = 9, seed = 0))
        assertEquals(listOf("open.rain.1"), VoiceLines.leadIds(drive, hour = 9, seed = 0, plain = true))
    }

    @Test fun `출발과 도착 조각은 기기 멘트와 같은 기준이다`() {
        assertEquals("depart.place.plain", VoiceLines.departureId(Departure("부산역")))
        assertEquals("depart.home.plain", VoiceLines.departureId(Departure("우리 집", kind = PlaceKind.HOME, minutes = 0)))
        assertEquals("depart.home.short", VoiceLines.departureId(Departure("우리 집", minutes = 14, kind = PlaceKind.HOME)))
        assertEquals("depart.office.long", VoiceLines.departureId(Departure("회사", minutes = 15, kind = PlaceKind.OFFICE)))
        assertEquals("arrive.short", VoiceLines.arrivalId(Arrival("부산역", minutes = 9)))
        assertEquals("arrive.long", VoiceLines.arrivalId(Arrival("부산역", minutes = 40)))
        assertEquals("arrive.long", VoiceLines.arrivalId(Arrival("부산역")))
    }
}
