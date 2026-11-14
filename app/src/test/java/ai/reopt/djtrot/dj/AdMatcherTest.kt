package ai.reopt.djtrot.dj

import ai.reopt.djtrot.api.AdConditions
import ai.reopt.djtrot.api.AdPlace
import ai.reopt.djtrot.car.Destination
import ai.reopt.djtrot.car.DriveContext
import ai.reopt.djtrot.car.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 서버의 `matchesAd`와 같은 기대값이다(같은 조건·같은 좌표). */
class AdMatcherTest {
    private val conditions = AdConditions(
        keywords = listOf("수산시장"),
        destinationWords = listOf("부대찌개", "찌개"),
        places = listOf(AdPlace("의정부 부대찌개 거리", 37.7381, 127.0338, 500)),
    )

    @Test fun `지나는 주소나 목적지에 낱말이 있으면 맞는다`() {
        assertTrue(AdMatcher.matches(conditions, DriveContext(currentAddress = "부산 중구 자갈치 수산시장")))
        assertTrue(AdMatcher.matches(conditions, DriveContext(destination = Destination("노량진", address = "노량진 수산시장로 12"))))
    }

    @Test fun `목적지 종류는 목적지 이름만 본다`() {
        assertTrue(AdMatcher.matches(conditions, DriveContext(destination = Destination("원조 부대찌개 본점"))))
        assertFalse(AdMatcher.matches(conditions, DriveContext(currentAddress = "원조 부대찌개 골목")))
    }

    @Test fun `차나 목적지가 반경 안이면 맞는다`() {
        assertTrue(AdMatcher.matches(conditions, DriveContext(location = GeoPoint(37.7390, 127.0340))))
        assertTrue(AdMatcher.matches(conditions, DriveContext(destination = Destination("어딘가", location = GeoPoint(37.7381, 127.0338)))))
        assertFalse(AdMatcher.matches(conditions, DriveContext(location = GeoPoint(37.75, 127.05), currentAddress = "서울 강남구", destination = Destination("회사"))))
        assertEquals(101.7, AdMatcher.distanceMeters(GeoPoint(37.7381, 127.0338), GeoPoint(37.7390, 127.0340)), 1.0)
    }

    @Test fun `조건이 비면 어디서도 맞지 않는다`() {
        assertFalse(AdMatcher.matches(AdConditions(), DriveContext(currentAddress = "어디든", location = GeoPoint(37.0, 127.0))))
    }

    @Test fun `좌표는 서버로 가는 맥락에 들어가지 않는다`() {
        val input = DriveContext(location = GeoPoint(37.7390, 127.0340), destination = Destination("강릉역", location = GeoPoint(37.76, 128.9))).toInput(java.time.LocalTime.NOON)
        val sent = ai.reopt.djtrot.api.DjTrotApi.json.encodeToString(ai.reopt.djtrot.api.DriveInput.serializer(), input)
        assertFalse("37.7" in sent)
        assertFalse("latitude" in sent)
    }
}
