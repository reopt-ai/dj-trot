package ai.reopt.djtrot.playback

import ai.reopt.djtrot.DjTrotApp
import ai.reopt.djtrot.api.NowPlaying
import ai.reopt.djtrot.dj.DjScript
import ai.reopt.djtrot.dj.SongInfo
import ai.reopt.djtrot.dj.SongRequest
import ai.reopt.djtrot.dj.toInput
import ai.reopt.djtrot.ui.MainActivity
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.future

/**
 * DJ 트롯 재생 서비스 — Pleos Music/Audio 카테고리 요건을 이 한 곳에서 지킨다.
 * - Media3 MediaLibraryService + MediaSession: 미디어 센터·스티어링 휠·Gleo 음성 검색이 붙는다.
 * - 🔴 자동 재생 금지: 서비스가 뜨거나 연결돼도 재생하지 않는다. 사용자 동작·시스템 재개 요청에서만 재생한다.
 * - 🔴 오디오 포커스: 영구 손실이면 정지해 포커스를 돌려준다. 일시 손실은 ExoPlayer가 멈췄다 이어 튼다.
 * - 🔴 앱 종료(onTaskRemoved): 재생 정지 → stopSelf → super 순서.
 * - 🔑 Pleos 서비스 수명 요건: onCreate에서 startService로 Started 상태를 만든다. 포그라운드 전환은 재생 시 Media3가 한다.
 */
@OptIn(UnstableApi::class)
class DjTrotLibraryService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private val scope: CoroutineScope = MainScope()
    private val app get() = application as DjTrotApp
    private val catalog get() = app.catalog

    private var announced: String? = null
    private var briefJob: Job? = null
    /** 서버가 만든 DJ 멘트 음성을 트는 작은 플레이어. 오디오 포커스는 본 플레이어가 쥐고, 말하는 동안 음악을 줄인다. */
    private var clip: ExoPlayer? = null
    private val unplayable = mutableSetOf<String>()
    /** 🔑 연속 실패 수 — 서버가 음원을 못 주는 상황이면 목록 전체를 돌지 않고 몇 곡 만에 멈춘다. */
    private var failuresInRow = 0

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(authorizedSource()))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(PlayerEvents())
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(openApp)
            .build()
        app.car.start()
        // 신청한 노래가 완성되면 지금 곡 다음에 넣고 DJ가 알린다.
        scope.launch {
            app.orders.ready.collect { song ->
                val item = catalog.resolve(Catalog.SONG_PREFIX + song.id) ?: return@collect
                if (player.mediaItemCount == 0) player.setMediaItem(item) else player.addMediaItem(player.currentMediaItemIndex + 1, item)
                val line = "신청하신 노래 '${song.title}'가 완성됐어요. 다음 곡으로 들려 드릴게요."
                app.dj.update { it.say(line) }
                if (app.djTalks) app.voice.speak(line)
            }
        }
        // 백그라운드에서 시스템이 바인딩해 뜬 경우 시작 제한에 걸릴 수 있다 — 그때는 Media3의 포그라운드 처리에 맡긴다.
        runCatching { startService(Intent(this, DjTrotLibraryService::class.java)) }
            .onFailure { Log.i(TAG, "Started 상태 전환 생략: ${it.javaClass.simpleName}") }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        player.stop()
        app.voice.stop()
        clip?.stop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        app.voice.stop()
        clip?.release()
        session.release()
        player.release()
        super.onDestroy()
    }

    /**
     * 곡이 실제로 울리기 시작할 때 한 번 DJ가 소개한다.
     * 🔑 멘트는 서버 DJ 브리프(AI)로 쓰고, 받지 못하면(8초·오프라인) 기기 템플릿으로 읽는다.
     *    서버 음성(voiceUrl)은 멘트가 이 곡을 말할 때만 튼다 — 곡 소개를 기기 TTS로 덧붙이면 두 목소리가 섞인다.
     *    음성은 전주 위에 음악을 줄이고 얹는다(라디오 진행처럼). 곡을 미리 멈추면 시작이 늦어진다.
     */
    private fun announce(item: MediaItem?) {
        if (item == null || !app.djTalks) return
        if (item.mediaId == announced) return refreshSuggestion(item)
        announced = item.mediaId
        val meta = item.mediaMetadata
        val nowPlaying = meta.nowPlaying()
        val song = SongInfo(
            title = nowPlaying.title,
            singer = nowPlaying.artist,
            requested = nowPlaying.songId != null,
            fresh = DjScript.isFresh(meta.extras?.getString(Catalog.EXTRA_MADE_AT)),
        )
        val drive = app.car.state.value
        val now = LocalTime.now()
        val seed = item.mediaId.hashCode()
        briefJob?.cancel()
        briefJob = scope.launch {
            val brief = runCatching { app.api.brief(drive.toInput(now), voice = true, nowPlaying = nowPlaying) }
                .onFailure { Log.i(TAG, "DJ 브리프 대신 기기 멘트: ${it.message}") }
                .getOrNull()
            if (player.currentMediaItem?.mediaId != item.mediaId) return@launch
            if (brief == null) {
                val line = DjScript.intro(song, drive, now, seed)
                app.dj.update { it.say(line, now).copy(problem = null) }
                app.voice.speak(line)
                return@launch
            }
            val mentions = song.title.isNotBlank() && song.title.trim() in brief.script
            val line = if (mentions) brief.script else "${brief.script} ${DjScript.play(song, seed)}"
            app.dj.update { it.say(line, now).copy(problem = null).suggest(SongRequest.of(brief.song)) }
            val voiceUrl = brief.voiceUrl
            if (voiceUrl != null && mentions) playClip(app.api.absolute(voiceUrl)) else app.voice.speak(line)
        }
    }

    /** 오래 멈췄다 같은 곡을 이어 틀면 신청곡 제안만 새로 받는다. 곡 중간이라 DJ는 말하지 않는다. */
    private fun refreshSuggestion(item: MediaItem) {
        if (app.dj.value.freshSuggestion() != null || briefJob?.isActive == true) return
        val nowPlaying = item.mediaMetadata.nowPlaying()
        val drive = app.car.state.value
        briefJob = scope.launch {
            runCatching { app.api.brief(drive.toInput(LocalTime.now()), voice = false, nowPlaying = nowPlaying) }
                .onSuccess { brief -> app.dj.update { it.suggest(SongRequest.of(brief.song)) } }
                .onFailure { Log.i(TAG, "신청곡 제안을 새로 받지 못했어요: ${it.message}") }
        }
    }

    /** DJ에게 알릴 지금 곡. 내가 만든 노래면 신청곡으로 소개하게 ID를 붙인다. */
    private fun MediaMetadata.nowPlaying(): NowPlaying {
        val made = extras?.takeIf { it.getBoolean(Catalog.EXTRA_MADE) }
        return NowPlaying(title?.toString().orEmpty(), artist?.toString().orEmpty(), made?.getString(Catalog.EXTRA_SONG_ID))
    }

    /** 서버 멘트 음성 — 음악을 줄이고 얹었다가 끝나면 되돌린다. */
    private fun playClip(url: String) {
        val voice = clip ?: ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(authorizedSource()))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), false)
            .build()
            .also { created ->
                clip = created
                created.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) player.volume = 1f
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        player.volume = 1f
                        app.dj.value.line?.let(app.voice::speak)
                    }
                })
            }
        player.volume = DUCKED
        voice.setMediaItem(MediaItem.fromUri(url))
        voice.prepare()
        voice.play()
    }

    /**
     * 드라이브 노래 음원(`/songs/{id}/audio`)은 로그인 토큰이 있어야 받는다. 그 주소에만 Bearer를 붙인다.
     * 🔑 재생 스레드에서 불린다 — 토큰이 만료됐으면 여기서 기다려 새로 받는다.
     */
    private fun authorizedSource(): DataSource.Factory = ResolvingDataSource.Factory(DefaultDataSource.Factory(this)) { spec ->
        if (!app.api.needsToken(spec.uri.toString())) return@Factory spec
        val token = runBlocking { runCatching { app.account.token() }.getOrNull() } ?: return@Factory spec
        spec.withAdditionalHeaders(mapOf("Authorization" to "Bearer $token"))
    }

    private inner class PlayerEvents : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) return
            failuresInRow = 0
            app.dj.update { it.copy(problem = null) }
            announce(player.currentMediaItem)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            app.orders.onStage(mediaItem?.mediaMetadata?.extras?.getString(Catalog.EXTRA_SONG_ID))
            if (player.isPlaying) announce(mediaItem)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) {
                clip?.stop()
                player.volume = 1f
            }
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                app.voice.stop()
                player.stop()
            }
        }

        /** 음원을 받지 못한 곡은 건너뛴다. 목록 전체가 안 되면 멈추고 화면에 알린다. */
        override fun onPlayerError(error: PlaybackException) {
            val current = player.currentMediaItem?.mediaId
            Log.w(TAG, "재생 실패 $current: ${error.errorCodeName}", error)
            current?.let { unplayable += it }
            failuresInRow += 1
            val remaining = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.filterNot { it in unplayable }
            if (failuresInRow < MAX_FAILURES_IN_ROW && remaining.isNotEmpty() && player.hasNextMediaItem()) {
                app.dj.update { it.copy(problem = "이 노래는 지금 들을 수 없어 다음 곡으로 넘어가요.") }
                player.seekToNextMediaItem()
                player.prepare()
            } else {
                app.dj.update { it.copy(problem = "지금은 노래를 불러오지 못했어요. 잠시 뒤 다시 눌러 주세요.") }
                player.stop()
            }
        }
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(catalog.root(), LibraryParams.Builder().setExtras(catalog.rootExtras()).build()))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            runCatching { catalog.children(parentId) }.fold(
                onSuccess = { LibraryResult.ofItemList(it.page(page, pageSize), params) },
                onFailure = { Log.w(TAG, "목록을 받지 못했어요: $parentId", it); LibraryResult.ofError(SessionError.ERROR_IO) },
            )
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            catalog.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            val count = runCatching { catalog.search(query).size }.getOrDefault(0)
            session.notifySearchResultChanged(browser, query, count, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            runCatching { catalog.search(query) }.fold(
                onSuccess = { LibraryResult.ofItemList(it.page(page, pageSize), params) },
                onFailure = { LibraryResult.ofError(SessionError.ERROR_IO) },
            )
        }

        /**
         * 🔑 재생 요청을 실제 재생 목록으로 바꾼다.
         * - 곡 하나 → 그 곡이 있던 목록 전체(그 곡부터)
         * - 폴더(예: "DJ 트롯 추천") → 폴더의 곡 전부
         * - 음성 검색("트롯 틀어줘") → 검색 결과, 없으면 추천
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            unplayable.clear()
            failuresInRow = 0
            announced = null
            val single = mediaItems.singleOrNull()
            val query = single?.requestMetadata?.searchQuery
            when {
                query != null -> {
                    val found = catalog.search(query).ifEmpty { catalog.children(Catalog.PICKS) }
                    MediaSession.MediaItemsWithStartPosition(found, 0, C.TIME_UNSET)
                }
                single != null && catalog.isFolder(single.mediaId) -> {
                    val songs = runCatching { catalog.children(single.mediaId) }.getOrDefault(emptyList()).filter { it.mediaMetadata.isPlayable == true }
                    // 서버가 아직 제공 목록을 내보내지 않았으면 빈 목록이다 — 조용히 멈추지 말고 알린다.
                    if (songs.isEmpty()) app.dj.update { it.copy(problem = "들려 드릴 노래를 준비하고 있어요. 곧 무대에 올라와요.") }
                    MediaSession.MediaItemsWithStartPosition(songs, 0, C.TIME_UNSET)
                }
                single != null -> {
                    val (queue, index) = catalog.queueFor(single.mediaId)
                    MediaSession.MediaItemsWithStartPosition(queue, index, startPositionMs)
                }
                else -> MediaSession.MediaItemsWithStartPosition(mediaItems.mapNotNull { catalog.resolve(it.mediaId) }, startIndex, startPositionMs)
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            mediaItems.flatMap { item ->
                when {
                    item.requestMetadata.searchQuery != null -> catalog.search(item.requestMetadata.searchQuery!!)
                    catalog.isFolder(item.mediaId) -> catalog.children(item.mediaId).filter { it.mediaMetadata.isPlayable == true }
                    else -> listOfNotNull(catalog.resolve(item.mediaId))
                }
            }.toMutableList()
        }

        /** 시스템 재개 요청(블루투스·시동 후 이어 듣기) — 추천 목록을 준비한다. 재생 여부는 시스템이 정한다. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            MediaSession.MediaItemsWithStartPosition(catalog.children(Catalog.PICKS), 0, C.TIME_UNSET)
        }
    }

    private companion object {
        const val TAG = "DjTrotPlayback"
        /** DJ가 말하는 동안 음악 크기. */
        const val DUCKED = 0.3f
        const val MAX_FAILURES_IN_ROW = 3
    }
}

private fun List<MediaItem>.page(page: Int, pageSize: Int): ImmutableList<MediaItem> {
    if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return ImmutableList.copyOf(this)
    return ImmutableList.copyOf(drop(page * pageSize).take(pageSize))
}
