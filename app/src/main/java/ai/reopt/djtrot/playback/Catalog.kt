package ai.reopt.djtrot.playback

import ai.reopt.djtrot.account.DeviceAccount
import ai.reopt.djtrot.api.CatalogResponse
import ai.reopt.djtrot.api.DjTrotApi
import ai.reopt.djtrot.api.LyricLine
import ai.reopt.djtrot.api.TrotSong
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 미디어 센터·음성 검색·화면이 함께 보는 노래 목록(브라우즈 트리). DJ 트롯 서버의 선반(`/catalog`)과 내 드라이브 노래.
 *
 * ```
 * root
 * ├─ picks          DJ 트롯 추천
 * ├─ latest         새로 들어온 노래
 * ├─ styles         장르별 → style:{id}
 * └─ mine           내 드라이브 노래 (휴대폰 연결 뒤)
 * ```
 * 🔑 곡을 하나 고르면 그 곡이 있던 목록을 이어 튼다(`queueFor`). 마지막으로 본 목록을 기억한다.
 * 🔑 선반 응답 하나에 모든 곡과 가사 시각이 들어 있다 — 1분 동안 다시 받지 않는다.
 */
class Catalog(private val api: DjTrotApi, private val account: DeviceAccount) {
    private val songs = ConcurrentHashMap<String, TrotSong>()
    private val lists = ConcurrentHashMap<String, List<String>>()
    private val parents = ConcurrentHashMap<String, String>()
    private val fetch = Mutex()
    @Volatile private var cached: Pair<Long, CatalogResponse>? = null

    fun root(): MediaItem = folder(ROOT, "DJ 트롯")

    /** 루트 힌트 — 폴더는 격자, 곡은 목록으로 보이게 하고 검색을 지원한다고 알린다. */
    @OptIn(UnstableApi::class)
    fun rootExtras(): Bundle = Bundle().apply {
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        putBoolean(SEARCH_SUPPORTED, true)
    }

    suspend fun children(parentId: String): List<MediaItem> = when {
        parentId == ROOT -> buildList {
            add(folder(PICKS, "DJ 트롯 추천", "오늘의 트로트"))
            add(folder(LATEST, "새로 들어온 노래", "방금 무대에 오른 노래"))
            add(folder(STYLES, "장르별", "뽕짝·정통·세미·발라드"))
            if (account.connected) add(folder(MINE, "내 드라이브 노래", "길에서 만든 노래"))
        }
        parentId == STYLES -> catalog().shelves.filter { it.id.startsWith(STYLE_PREFIX) && it.songs.isNotEmpty() }
            .map { folder(it.id, it.title, it.description.ifBlank { null }, it.songs.size, style = it.id.removePrefix(STYLE_PREFIX)) }
        parentId == MINE -> remember(MINE, mine())
        isShelf(parentId) -> remember(parentId, catalog().shelves.firstOrNull { it.id == parentId }?.songs.orEmpty())
        else -> emptyList()
    }

    fun isFolder(mediaId: String) = mediaId == ROOT || mediaId == STYLES || mediaId == MINE || isShelf(mediaId)

    private fun isShelf(id: String) = id == PICKS || id == LATEST || id.startsWith(STYLE_PREFIX)

    /** 곡 ID로 재생 가능한 항목(주소 포함)을 돌려준다. 한 번도 목록에서 본 적 없는 곡이면 null. */
    fun resolve(mediaId: String): MediaItem? = songs[mediaId.removePrefix(SONG_PREFIX)]?.let(::playable)

    suspend fun item(mediaId: String): MediaItem? = when {
        mediaId == ROOT -> root()
        isFolder(mediaId) -> children(ROOT).firstOrNull { it.mediaId == mediaId } ?: folder(mediaId, mediaId)
        else -> resolve(mediaId) ?: runCatching { api.catalogSong(mediaId.removePrefix(SONG_PREFIX)) }.getOrNull()?.let { register(it) }
    }

    /** 고른 곡이 들어 있던 목록 전체와 그 안의 위치. 목록을 모르면 그 곡 하나. */
    fun queueFor(mediaId: String): Pair<List<MediaItem>, Int> {
        val id = mediaId.removePrefix(SONG_PREFIX)
        val list = parents[id]?.let { lists[it] }
        if (list != null) {
            val items = list.mapNotNull { songs[it]?.let(::playable) }
            val index = items.indexOfFirst { it.mediaId == SONG_PREFIX + id }
            if (index >= 0) return items to index
        }
        return listOfNotNull(resolve(mediaId)) to 0
    }

    /** 노래방 자막용 줄별 시각. 정렬되지 않은 곡이면 빈 목록. */
    fun lyrics(songId: String?): List<LyricLine> =
        songId?.let { songs[it] }?.lyricSync?.takeIf { it.state == "ready" }?.lines.orEmpty()

    /** 드라이브에서 새로 만든 곡을 목록에 넣고 재생 항목으로 돌려준다. */
    fun register(song: TrotSong, parent: String = MINE): MediaItem {
        songs[song.id] = song
        if (parents[song.id] == null) {
            parents[song.id] = parent
            lists[parent] = listOf(song.id) + lists[parent].orEmpty().filter { it != song.id }
        }
        return playable(song)
    }

    /**
     * 음성·텍스트 검색. 빈 검색어("트롯 틀어줘")는 추천 목록을 준다.
     */
    suspend fun search(query: String): List<MediaItem> {
        val q = query.trim().removeSuffix("틀어줘").removeSuffix("들려줘").trim()
        val found = if (normalize(q) in GENERIC) api.search("") else api.search(q)
        return remember(SEARCH, found)
    }

    private suspend fun catalog(): CatalogResponse = fetch.withLock {
        val now = System.currentTimeMillis()
        cached?.takeIf { now - it.first < CACHE_MS }?.second ?: api.catalog().also { cached = now to it }
    }

    private suspend fun mine(): List<TrotSong> = runCatching {
        account.authorized { token -> api.songs(token) }.songs.mapNotNull { it.song }
    }.getOrDefault(emptyList())

    private fun remember(parentId: String, found: List<TrotSong>): List<MediaItem> {
        found.forEach { songs[it.id] = it }
        lists[parentId] = found.map { it.id }
        found.forEach { parents[it.id] = parentId }
        return found.map(::playable)
    }

    private fun playable(song: TrotSong): MediaItem = MediaItem.Builder()
        .setMediaId(SONG_PREFIX + song.id)
        .setUri(api.absolute(song.audio.url))
        .setMimeType(MimeTypes.AUDIO_MPEG)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artist)
                .setAlbumTitle(song.labels.style.ifBlank { "트로트" })
                .setGenre("트로트")
                .setArtworkUri(song.cover?.url?.let { api.absolute(it).toUri() })
                .setDurationMs(song.durationSeconds?.let { (it * 1000).toLong() })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(
                    Bundle().apply {
                        putString(EXTRA_SONG_ID, song.id)
                        putString(EXTRA_STYLE, song.style)
                        putString(EXTRA_TOPIC, song.topic)
                        putString(EXTRA_TOPIC_LABEL, song.labels.topic.ifBlank { null })
                        putBoolean(EXTRA_MADE, song.provenance.kind == "generated")
                        putString(EXTRA_MADE_AT, song.provenance.createdAt.ifBlank { null })
                    },
                )
                .build(),
        )
        .build()

    private fun folder(id: String, title: String, subtitle: String? = null, songs: Int? = null, style: String? = null): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setTotalTrackCount(songs)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .setExtras(Bundle().apply { if (style != null) putString(EXTRA_STYLE, style) })
                .build(),
        )
        .build()

    companion object {
        const val ROOT = "root"
        const val PICKS = "picks"
        const val LATEST = "latest"
        const val STYLES = "styles"
        const val STYLE_PREFIX = "style:"
        const val MINE = "mine"
        const val SONG_PREFIX = "song:"
        const val EXTRA_SONG_ID = "djtrot.songId"
        const val EXTRA_STYLE = "djtrot.style"
        const val EXTRA_TOPIC = "djtrot.topic"
        const val EXTRA_TOPIC_LABEL = "djtrot.topicLabel"
        const val EXTRA_MADE = "djtrot.made"
        const val EXTRA_MADE_AT = "djtrot.madeAt"
        private const val SEARCH = "search"
        private const val CACHE_MS = 60_000L
        private const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
        private val GENERIC = setOf("", "트롯", "트로트", "djtrot", "dj트롯", "아무거나", "음악", "노래")

        fun normalize(text: String) = text.lowercase().replace(Regex("[\\s'\"·,.!?~]"), "")
    }
}
