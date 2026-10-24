package ai.reopt.djtrot.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegionTest {
    @Test fun `도 아래 시는 시 이름만`() = assertEquals("성남", Region.label("경기도 성남시 수정구 시흥동"))
    @Test fun `특별시·광역시는 구까지`() {
        assertEquals("서울 강남구", Region.label("서울특별시 강남구 역삼동"))
        assertEquals("부산 해운대구", Region.label("부산광역시 해운대구 우동"))
        assertEquals("대구 중구", Region.label("대구광역시 중구"))
    }
    @Test fun `줄여 쓴 광역시 이름`() = assertEquals("서울 강남구", Region.label("서울 강남구 역삼동"))
    @Test fun `특별자치도 아래 시·군`() {
        assertEquals("양양", Region.label("강원특별자치도 양양군 현북면"))
        assertEquals("제주", Region.label("제주특별자치도 제주시 연동"))
    }
    @Test fun `세종`() = assertEquals("세종", Region.label("세종특별자치시 한솔동"))
    @Test fun `구와 동만 있으면 구`() = assertEquals("수정구", Region.label("수정구 시흥동"))
    @Test fun `시흥동의 시는 시가 아니다`() = assertEquals("시흥동", Region.label("시흥동"))
    @Test fun `빈 주소`() {
        assertNull(Region.label(null))
        assertNull(Region.label("  "))
    }
}
