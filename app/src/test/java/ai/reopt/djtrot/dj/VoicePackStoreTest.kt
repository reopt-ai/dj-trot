package ai.reopt.djtrot.dj

import ai.reopt.djtrot.api.VoiceClip
import ai.reopt.djtrot.api.VoicePack
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoicePackStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val rain = VoiceClip("open.rain.1", "아아... 비가, 오네요...", "/api/v1/dj-trot/dj/voice/0b6c6d1e-0000-4000-8000-000000000001")
    private val play = VoiceClip("play.1", "이 노래... 띄워, 드립니다.", "/api/v1/dj-trot/dj/voice/0b6c6d1e-0000-4000-8000-000000000002")
    private var pack: VoicePack? = VoicePack("v1", listOf(rain, play))
    private val downloads = mutableListOf<String>()
    private var failing = setOf<String>()
    private var now = 1_000_000L

    private fun store(dir: File = folder.root) = VoicePackStore(
        dir = dir,
        fetch = { pack ?: throw IOException("offline") },
        download = { url, into -> downloads += url; if (url in failing) { into.writeText("half"); throw IOException("cut") } else into.writeText("mp3") },
        clock = { now },
    )

    @Test fun `받아 둔 조각은 글과 파일로 찾는다`() = runBlocking {
        val store = store()
        assertNull(store.fragment("open.rain.1"))
        assertTrue(store.sync())
        assertEquals("아아... 비가, 오네요...", store.fragment("open.rain.1")!!.text)
        assertEquals("mp3", store.fragment("play.1")!!.file.readText())
        // 서버가 준 주소(상대·절대)를 받아 둔 파일로 바꾼다. 모르는 주소는 null.
        assertEquals(store.fragment("open.rain.1")!!.file, store.local("https://example.com${rain.url}"))
        assertNull(store.local("/api/v1/dj-trot/dj/voice/0b6c6d1e-0000-4000-8000-000000000009"))
        assertNull(store.local("/api/v1/dj-trot/dj/voice/../../etc"))
        assertEquals(2, downloads.size)
    }

    @Test fun `다 받았으면 한동안 다시 받지 않고, 앱을 다시 켜도 남아 있다`() = runBlocking {
        val first = store()
        assertTrue(first.sync())
        assertTrue(first.sync())
        assertEquals(2, downloads.size)
        // 다시 켠 앱 — 서버가 닿지 않아도 지난번에 받은 조각을 쓴다.
        pack = null
        val again = store()
        assertNotNull(again.fragment("play.1"))
        assertFalse(again.sync())
        assertNotNull(again.fragment("play.1"))
    }

    @Test fun `받다가 끊긴 조각은 쓰지 않고 다음에 이어 받는다`() = runBlocking {
        failing = setOf(play.url)
        val store = store()
        assertFalse(store.sync())
        assertNotNull(store.fragment("open.rain.1"))
        assertNull(store.fragment("play.1"))
        assertFalse(File(folder.root, "0b6c6d1e-0000-4000-8000-000000000002.part").exists())
        failing = emptySet()
        assertTrue(store.sync())
        assertNotNull(store.fragment("play.1"))
        assertEquals(listOf(rain.url, play.url, play.url), downloads)
    }

    @Test fun `팩이 바뀌면 새 조각만 받고 지난 파일은 치운다`() = runBlocking {
        val store = store()
        assertTrue(store.sync())
        val old = store.fragment("play.1")!!.file
        val next = play.copy(url = "/api/v1/dj-trot/dj/voice/0b6c6d1e-0000-4000-8000-000000000003")
        pack = VoicePack("v2", listOf(rain, next))
        now += 2 * 60 * 60_000L
        assertTrue(store.sync())
        assertEquals(listOf(rain.url, play.url, next.url), downloads)
        assertFalse(old.exists())
        assertEquals(store.fragment("play.1")!!.file, store.local(next.url))
        // 서버가 팩을 끄면 받아 둔 조각도 쓰지 않는다.
        pack = VoicePack(null, emptyList())
        assertTrue(store.sync(force = true))
        assertNull(store.fragment("open.rain.1"))
    }
}
