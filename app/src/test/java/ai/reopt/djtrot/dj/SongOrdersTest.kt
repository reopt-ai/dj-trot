package ai.reopt.djtrot.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SongOrdersTest {
    private val request = SongRequest(topic = "cheer", style = "ppongjjak", story = "수정구 지나는 오후 길")

    @Test fun `응답 전에 실패한 같은 주문은 같은 키로 다시 보낸다`() {
        val failed = Order(request, "failed", problem = "노래를 맡기지 못했어요.", requestKey = "key-1")
        assertEquals("key-1", SongOrders.retryKey(failed, request))
    }

    @Test fun `서버가 받은 주문이나 다른 사연은 새 키를 쓴다`() {
        val made = Order(request, "failed", songId = "song-1", requestKey = "key-1")
        assertNull(SongOrders.retryKey(made, request))
        val failed = Order(request, "failed", requestKey = "key-1")
        assertNull(SongOrders.retryKey(failed, request.copy(story = "부산역까지 42분")))
        assertNull(SongOrders.retryKey(Order(request, "ready", songId = "song-1"), request))
        assertNull(SongOrders.retryKey(null, request))
    }
}
