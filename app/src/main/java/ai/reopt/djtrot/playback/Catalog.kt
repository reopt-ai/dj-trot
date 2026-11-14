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
 * 트리의 모양과 ID는 [MediaPath]·[Shelf]에 있다.
 * 🔑 앱 화면은 고른 곡과 그 목록을 통째로 큐로 보낸다. 미디어 센터처럼 곡 하나만 보내는 쪽은 그 곡을 마지막으로 본 목록을 이어 튼다(`queueFor`).
 * 🔑 선반 응답 하나에 모든 곡과 가사 시각이 들어 있다 — 1분 동안 다시 받지 않는다.
 */
class Catalog(private val api: DjTrotApi, private val account: DeviceAccount) {
    private val songs = ConcurrentHashMap<String, TrotSong>()
    private val lists = ConcurrentHashMap<String, List<String>>()
    /** 곡 ID → 그 곡을 마지막으로 보여 준 목록 ID. 곡 하나만 받은 재생 요청을 목록으로 늘릴 때 쓴다. */
    private val lastSeenIn = ConcurrentHashMap<String, String>()
    private val fetch = Mutex()
    @Volatile private var cached: Pair<Long, CatalogResponse>? = null

    fun root(): MediaItem = folder(MediaPath.Root.id, "DJ 트롯")

    /** 루트 힌트 — 폴더는 격자, 곡은 목록으로 보이게 하고 검색을 지원한다고 알린다. */
    @OptIn(UnstableApi::class)
    fun rootExtras(): Bundle = Bundle().apply {
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        putBoolean(SEARCH_SUPPORTED, true)
    }

    suspend fun children(shelf: Shelf): List<MediaItem> = children(MediaPath.Top(shelf))

    suspend fun children(parentId: String): List<MediaItem> = MediaPath.parse(parentId)?.let { children(it) }.orEmpty()

    private suspend fun children(path: MediaPath): List<MediaItem> = when (path) {
        MediaPath.Root -> Shelf.entries.filter { it != Shelf.MINE || account.connected }.map(::folder)
        is MediaPath.Top -> when (path.shelf) {
            Shelf.STYLES -> styles()
            Shelf.MINE -> remember(path.id, mine())
            Shelf.PICKS, Shelf.LATEST -> remember(path.id, shelfSongs(path.id))
        }
        is MediaPath.Style -> remember(path.id, shelfSongs(path.id))
        is MediaPath.Song -> emptyList()
    }

    fun isFolder(mediaId: String) = MediaPath.parse(mediaId).let { it != null && it !is MediaPath.Song }

    /** 곡 ID로 재생 가능한 항목(주소 포함)을 돌려준다. 한 번도 목록에서 본 적 없는 곡이면 null. */
    fun resolve(mediaId: String): MediaItem? =
        (MediaPath.parse(mediaId) as? MediaPath.Song)?.let { songs[it.songId] }?.let(::playable)

    suspend fun item(mediaId: String): MediaItem? = when (val path = MediaPath.parse(mediaId)) {
        MediaPath.Root -> root()
        is MediaPath.Top -> folder(path.shelf)
        is MediaPath.Style -> styles().firstOrNull { it.mediaId == mediaId }
        is MediaPath.Song -> resolve(mediaId) ?: runCatching { api.catalogSong(path.songId) }.getOrNull()?.let(::keep)
        null -> null
    }

    /** 고른 곡을 마지막으로 보여 준 목록 전체와 그 안의 위치. 목록을 모르면 그 곡 하나. */
    fun queueFor(mediaId: String): Pair<List<MediaItem>, Int> {
        val id = (MediaPath.parse(mediaId) as? MediaPath.Song)?.songId ?: return emptyList<MediaItem>() to 0
        val list = lastSeenIn[id]?.let { lists[it] }
        if (list != null) {
            val items = list.mapNotNull { songs[it]?.let(::playable) }
            val index = items.indexOfFirst { it.mediaId == mediaId }
            if (index >= 0) return items to index
        }
        return listOfNotNull(resolve(mediaId)) to 0
    }

    /** 노래방 자막용 줄별 시각. 정렬되지 않은 곡이면 빈 목록. */
    fun lyrics(songId: String?): List<LyricLine> =
        songId?.let { songs[it] }?.lyricSync?.takeIf { it.state == "ready" }?.lines.orEmpty()

    /** 드라이브에서 새로 만든 곡을 내 노래 맨 앞에 넣고 재생 항목으로 돌려준다. */
    fun register(song: TrotSong): MediaItem {
        val mine = Shelf.MINE.id
        lastSeenIn.putIfAbsent(song.id, mine)
        lists[mine] = listOf(song.id) + lists[mine].orEmpty().filter { it != song.id }
        return keep(song)
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

    /** 🔑 선반 ID는 미디어 ID와 같다(`picks`, `latest`, `style:{id}`). */
    private suspend fun shelfSongs(id: String): List<TrotSong> = catalog().shelves.firstOrNull { it.id == id }?.songs.orEmpty()

    private suspend fun styles(): List<MediaItem> = catalog().shelves.mapNotNull { shelf ->
        val path = MediaPath.parse(shelf.id) as? MediaPath.Style ?: return@mapNotNull null
        if (shelf.songs.isEmpty()) return@mapNotNull null
        folder(path.id, shelf.title, shelf.description.ifBlank { null }, shelf.songs.size, style = path.style)
    }

    private suspend fun mine(): List<TrotSong> = runCatching {
        account.authorized { token -> api.songs(token) }.songs.mapNotNull { it.song }
    }.getOrDefault(emptyList())

    private fun remember(listId: String, found: List<TrotSong>): List<MediaItem> {
        lists[listId] = found.map { it.id }
        found.forEach { lastSeenIn[it.id] = listId }
        return found.map(::keep)
    }

    /** 광고 음악을 재생 항목으로 — 목록(선반·내 노래)에는 넣지 않는다. */
    fun ad(song: TrotSong): MediaItem = keep(song)

    private fun keep(song: TrotSong): MediaItem {
        songs[song.id] = song
        return playable(song)
    }

    private fun playable(song: TrotSong): MediaItem = MediaItem.Builder()
        .setMediaId(MediaPath.Song(song.id).id)
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

    private fun folder(shelf: Shelf): MediaItem = folder(shelf.id, shelf.title, shelf.subtitle)

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
        const val EXTRA_SONG_ID = "djtrot.songId"
        const val EXTRA_STYLE = "djtrot.style"
        const val EXTRA_TOPIC = "djtrot.topic"
        const val EXTRA_TOPIC_LABEL = "djtrot.topicLabel"
        const val EXTRA_MADE = "djtrot.made"
        const val EXTRA_MADE_AT = "djtrot.madeAt"
        /** 검색 결과도 목록 하나로 기억한다. 트리에는 없는 ID다. */
        private const val SEARCH = "search"
        private const val CACHE_MS = 60_000L
        private const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
        private val GENERIC = setOf("", "트롯", "트로트", "djtrot", "dj트롯", "아무거나", "음악", "노래")

        fun normalize(text: String) = text.lowercase().replace(Regex("[\\s'\"·,.!?~]"), "")
    }
}
