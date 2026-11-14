package ai.reopt.djtrot.dj

import ai.reopt.djtrot.api.DriveSong
import ai.reopt.djtrot.api.DjConfig
import ai.reopt.djtrot.api.SongProgress
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 제작 중계 — 서버가 알려 준 단계를 네 칸으로 옮기고, 단계를 모르는 예전 서버에서는 시간으로 가른다. */
class RelayTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `접수와 차례 기다림은 접수 칸이다`() {
        assertEquals(Relay.Stage.ACCEPT, Relay.stage("sending", null, 0))
        assertEquals(Relay.Stage.ACCEPT, Relay.stage("queued", SongProgress("compose"), 90))
    }

    @Test fun `만드는 중에는 서버 단계를 따른다`() {
        assertEquals(Relay.Stage.LYRICS, Relay.stage("running", SongProgress("lyrics"), 80))
        assertEquals(Relay.Stage.COMPOSE, Relay.stage("running", SongProgress("compose", "고향 가는 길", listOf("먼 길 돌아")), 5))
        assertEquals(Relay.Stage.FINISH, Relay.stage("running", SongProgress("finish"), 5))
        assertEquals("모르는 단계는 작사로 본다", Relay.Stage.LYRICS, Relay.stage("running", SongProgress("mastering"), 5))
    }

    @Test fun `단계를 주지 않는 서버는 시간으로 가른다`() {
        assertEquals(Relay.Stage.LYRICS, Relay.stage("running", null, 10))
        assertEquals(Relay.Stage.COMPOSE, Relay.stage("running", null, 40))
    }

    @Test fun `한마디는 DJ마다 다르고 제목을 알면 부른다`() {
        val hwang = Relay.comment(Relay.Stage.COMPOSE, "hwanggu", "고향 가는 길")
        val mari = Relay.comment(Relay.Stage.COMPOSE, "mari", "고향 가는 길")
        assertTrue(hwang.contains("‘고향 가는 길’") && mari.contains("‘고향 가는 길’"))
        assertFalse(hwang == mari)
        assertFalse(Relay.comment(Relay.Stage.COMPOSE, "mari", " ").contains("‘"))
        Relay.Stage.entries.forEach { stage -> listOf("hwanggu", "mari").forEach { dj -> assertTrue(Relay.comment(stage, dj, null).isNotBlank()) } }
    }

    @Test fun `서버 응답의 진행과 열림을 읽고 없으면 기본값이다`() {
        val song = json.decodeFromString<DriveSong>("""{"id":"a","state":"running","progress":{"phase":"compose","title":"드라이브 송","lines":["가자 가자"]}}""")
        assertEquals(SongProgress("compose", "드라이브 송", listOf("가자 가자")), song.progress)
        assertNull(json.decodeFromString<DriveSong>("""{"id":"a","state":"running"}""").progress)
        assertFalse(json.decodeFromString<DjConfig>("""{"creation":{"open":false}}""").creation.open)
        assertTrue("예전 서버는 열림으로 본다", json.decodeFromString<DjConfig>("{}").creation.open)
    }

    @Test fun `시연 모드를 읽고 시연 상한은 따로 알린다`() {
        assertTrue(json.decodeFromString<DjConfig>("""{"creation":{"open":true,"guest":true}}""").creation.guest)
        assertFalse("예전 서버는 시연 모드가 아니다", json.decodeFromString<DjConfig>("""{"creation":{"open":true}}""").creation.guest)
        assertTrue(SongOrders.message(ai.reopt.djtrot.api.DjTrotApiException(429, "GUEST_LIMIT", "상한")).contains("시연"))
        assertEquals("X-DjTrot-Guest", ai.reopt.djtrot.api.SongAuth.GUEST_HEADER)
    }

    @Test fun `닫힌 제작실은 쉬는 중이라고 알린다`() {
        val closed = ai.reopt.djtrot.api.DjTrotApiException(403, "CREATION_CLOSED", "닫힘")
        assertTrue(SongOrders.message(closed).contains("쉬고 있어요"))
    }
}
