package ai.reopt.djtrot.dj

import ai.reopt.djtrot.api.DjTrotApi
import ai.reopt.djtrot.api.VoiceClip
import ai.reopt.djtrot.api.VoicePack
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 받아 둔 DJ 음성 팩 — 서버가 미리 만든 멘트 조각을 기기에 내려받아 둔다.
 * 🔑 받아 둔 조각이 있으면 서버가 닿지 않아도, 출발·도착 인사도 같은 DJ 목소리로 말한다. 없으면 기기 TTS가 읽는다.
 * 🔑 음성 주소의 마지막 마디가 내용의 지문이라 파일 이름으로 쓴다 — 같은 주소는 다시 받지 않고, 팩이 바뀌어도 같은 조각은 그대로 쓴다.
 * 내려받기는 조용히 실패하고 다음에 이어 받는다. 받는 중인 파일은 `.part`로 쓰고 다 받으면 이름을 바꾼다.
 */
class VoicePackStore(
    private val dir: File,
    private val fetch: suspend () -> VoicePack,
    private val download: suspend (String, File) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 받아 둔 조각 하나 — 화면에 보일 글과 음성 파일. */
    data class Fragment(val id: String, val text: String, val file: File)

    private val lock = Mutex()
    @Volatile private var byId: Map<String, Fragment> = emptyMap()
    @Volatile private var byKey: Map<String, File> = emptyMap()
    @Volatile private var ordered: List<Fragment> = emptyList()
    @Volatile private var loaded = false
    private var syncedAt = 0L

    /** 받아 둔 조각. 없으면 null. */
    fun fragment(id: String): Fragment? {
        restore()
        return byId[id]
    }

    /** 받아 둔 조각 전부 — 팩에 적힌 순서대로(시연 탭의 멘트 살펴보기). */
    fun fragments(): List<Fragment> {
        restore()
        return ordered
    }

    /** 서버가 준 음성 주소를 받아 둔 파일로. 받아 둔 적이 없으면 null. */
    fun local(url: String): File? {
        restore()
        return keyOf(url)?.let(byKey::get)
    }

    /**
     * 팩 목록을 받아 빠진 조각을 내려받는다. 다 받았으면 true.
     * 🔑 [SYNC_INTERVAL_MS] 안에 다 받은 적이 있으면 서버를 다시 부르지 않는다. 연달아 [GIVE_UP_AFTER]번 실패하면(오프라인) 그만두고 다음에 잇는다.
     */
    suspend fun sync(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            restore()
            if (!force && syncedAt != 0L && clock() - syncedAt < SYNC_INTERVAL_MS) return@withLock true
            val pack = runCatching { fetch() }.getOrNull() ?: return@withLock false
            dir.mkdirs()
            val ready = mutableListOf<VoiceClip>()
            var failed = 0
            var failedInRow = 0
            for (clip in pack.clips.takeIf { pack.version != null }.orEmpty()) {
                val key = keyOf(clip.url) ?: continue
                val file = File(dir, "$key.mp3")
                if (file.length() == 0L) {
                    if (failedInRow >= GIVE_UP_AFTER) { failed += 1; continue }
                    val part = File(dir, "$key.part")
                    val saved = runCatching { download(clip.url, part); part.length() > 0 && part.renameTo(file) }.getOrDefault(false)
                    if (!saved) {
                        part.delete()
                        failed += 1
                        failedInRow += 1
                        continue
                    }
                    failedInRow = 0
                }
                ready += clip
            }
            val saved = VoicePack(pack.version, ready)
            runCatching { File(dir, INDEX).writeText(DjTrotApi.json.encodeToString(VoicePack.serializer(), saved)) }
            apply(saved)
            if (failed > 0) return@withLock false
            // 다 받았다 — 이번 팩에 없는 지난 파일을 치운다.
            val keep = byKey.values.map { it.name }.toSet()
            dir.listFiles()?.filter { it.name != INDEX && it.name !in keep }?.forEach { it.delete() }
            syncedAt = clock()
            true
        }
    }

    /** 지난번에 받아 둔 목록을 읽는다(한 번만). 파일이 사라진 조각은 뺀다. */
    private fun restore() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching { DjTrotApi.json.decodeFromString(VoicePack.serializer(), File(dir, INDEX).readText()) }.getOrNull()?.let(::apply)
            loaded = true
        }
    }

    private fun apply(pack: VoicePack) {
        val fragments = pack.clips.mapNotNull { clip ->
            val key = keyOf(clip.url) ?: return@mapNotNull null
            val file = File(dir, "$key.mp3")
            if (file.length() > 0) key to Fragment(clip.id, clip.text, file) else null
        }
        ordered = fragments.map { (_, fragment) -> fragment }
        byId = fragments.associate { (_, fragment) -> fragment.id to fragment }
        byKey = fragments.associate { (key, fragment) -> key to fragment.file }
    }

    private companion object {
        const val INDEX = "pack.json"
        const val SYNC_INTERVAL_MS = 60 * 60_000L
        const val GIVE_UP_AFTER = 3
        private val KEY = Regex("[0-9a-f-]{36}")

        /** 음성 주소의 마지막 마디. 🔴 파일 이름으로 쓰므로 정해진 모양만 받는다. */
        fun keyOf(url: String): String? = url.substringBefore('?').substringAfterLast('/').takeIf(KEY::matches)
    }
}
