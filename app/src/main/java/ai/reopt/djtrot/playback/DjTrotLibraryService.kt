package ai.reopt.djtrot.playback

import ai.reopt.djtrot.DjTrotApp
import ai.reopt.djtrot.api.NowPlaying
import ai.reopt.djtrot.dj.DjScript
import ai.reopt.djtrot.dj.SongInfo
import ai.reopt.djtrot.dj.SongRequest
import ai.reopt.djtrot.dj.VoiceLines
import ai.reopt.djtrot.dj.toInput
import ai.reopt.djtrot.ui.MainActivity
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
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
import androidx.media3.exoplayer.source.SilenceMediaSource
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withTimeoutOrNull

/**
 * DJ 트롯 재생 서비스 — Pleos Music/Audio 카테고리 요건을 이 한 곳에서 지킨다.
 * - Media3 MediaLibraryService + MediaSession: 미디어 센터·스티어링 휠·미디어 검색이 붙는다.
 * - 🔴 자동 재생 금지: 서비스가 뜨거나 연결돼도 재생하지 않는다. 사용자 동작·시스템 재개 요청에서만 재생한다.
 * - 🔴 오디오 포커스: 영구 손실이면 정지해 포커스를 돌려준다. 일시 손실은 ExoPlayer가 멈췄다 이어 튼다.
 *   DJ 목소리는 음악과 따로 나오므로 음악이 눌리는 순간(일시 손실, 사용자 정지) 함께 멈춘다 — Gleo·통화 위로 말하지 않는다.
 * - 🔑 DJ는 내비 안내 직전과 방향지시등이 켜진 동안 말을 미룬다(DriveContext.holdTalk).
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
    /** 이번 재생 목록에서 DJ가 소개한 곡 수 — 첫 곡과 그 뒤 [FULL_EVERY]곡마다만 길게 말한다. */
    private var introduced = 0
    private var briefJob: Job? = null
    /** 서버가 만든 DJ 멘트 음성을 트는 작은 플레이어. 오디오 포커스는 본 플레이어가 쥐고, 말하는 동안 음악을 줄인다. */
    private var clip: ExoPlayer? = null
    private var clipOn = false
    /** 기기 TTS가 입을 떼기 전, 음악을 먼저 줄여 두는 동안. */
    private var leadIn = false
    private var fade: Job? = null
    private var fadeTarget = 1f
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
                val item = catalog.resolve(MediaPath.Song(song.id).id) ?: return@collect
                if (player.mediaItemCount == 0) player.setMediaItem(item) else player.addMediaItem(player.currentMediaItemIndex + 1, item)
                val line = "신청하신 노래 '${song.title}'가 완성됐어요. 다음 곡으로 들려 드릴게요."
                app.dj.update { it.say(line) }
                if (app.djTalks) launch { talk(line) }
            }
        }
        // 목적지에 닿으면 노래는 그대로 두고 이번 길을 마무리하는 말을 한다.
        scope.launch {
            app.car.arrivals.collect { arrival ->
                if (!app.djTalks || !player.isPlaying) return@collect
                launch { moment(VoiceLines.arrivalId(arrival), DjScript.arrival(arrival)) }
            }
        }
        // 길 안내를 시작하면 이번 길을 여는 말을, 경유지에 닿으면 짧은 인사를 한다.
        scope.launch {
            app.car.departures.collect { departure ->
                if (!app.djTalks || !player.isPlaying) return@collect
                launch { moment(VoiceLines.departureId(departure), DjScript.departure(departure)) }
            }
        }
        scope.launch {
            app.car.waypoints.collect { arrival ->
                if (!app.djTalks || !player.isPlaying) return@collect
                launch { moment(VoiceLines.WAYPOINT_ID, DjScript.waypoint(arrival)) }
            }
        }
        // 🔑 말로 사연을 듣는 동안은 DJ가 입을 닫고 음악도 줄인다 — 마이크에 노래가 섞이지 않게.
        scope.launch {
            app.mic.state.map { it.listening }.distinctUntilChanged().collect { listening -> if (listening) hush() else duck() }
        }
        // 🔑 DJ가 말하는 동안 음악을 줄인다 — 기기 TTS든 서버 음성이든.
        scope.launch { app.voice.speaking.collect { duck() } }
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
     * 🔑 멘트는 서버 DJ 브리프로 받는다. 브리프가 음성 조각(voiceClips)을 주면 순서대로 이어 틀고, 받아 둔 조각은 파일로 튼다.
     *    받지 못하면(8초·오프라인) 받아 둔 음성 팩에서 같은 규칙으로 조각을 고르고, 팩도 없으면 기기 템플릿을 TTS로 읽는다.
     *    통문장 음성(voiceUrl)은 멘트가 이 곡을 말할 때만 튼다 — 곡 소개를 기기 TTS로 덧붙이면 두 목소리가 섞인다.
     *    음성은 전주 위에 음악을 줄이고 얹는다(라디오 진행처럼). 곡을 미리 멈추면 시작이 늦어진다.
     * 🔑 길게 말하는 것은 첫 곡과 그 뒤 [FULL_EVERY]곡마다다. 나머지와 어린이보호구역에서는 곡 소개만 한다.
     * 🔑 여는 말을 받아 둔 조각으로 낼 수 있으면 서버에는 곡 소개만 청한다 — 집·회사·동승자처럼 차만 아는 것으로 고른 말을 쓰려는 것이다.
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
        val full = introduced % FULL_EVERY == 0 && !drive.schoolZone
        introduced += 1
        app.syncVoicePack()
        val pick = VoiceLines.seedOf(song)
        // 받아 둔 조각으로 낼 여는 말. 하나라도 빠졌으면 서버가 고른 것을 쓴다.
        val lead = if (full) VoiceLines.leadIds(drive, now.hour, pick).map(app.voicePack::fragment) else emptyList()
        val ownLead = lead.isNotEmpty() && lead.all { it != null }
        briefJob?.cancel()
        briefJob = scope.launch {
            val ment = if (full && !ownLead) "full" else "short"
            val brief = runCatching { app.api.brief(drive.toInput(now, app.spoken, pick), voice = true, nowPlaying = nowPlaying, ment = ment, token = app.account.cachedToken()) }
                .onFailure { Log.i(TAG, "DJ 브리프 대신 기기 멘트: ${it.message}") }
                .getOrNull()
            if (player.currentMediaItem?.mediaId != item.mediaId) return@launch
            // 🔑 브리프를 기다리는 사이 멈췄으면 말하지 않는다. 다시 틀 때 새로 소개한다.
            if (!player.isPlaying) {
                announced = null
                return@launch
            }
            val mine = lead.filterNotNull().takeIf { ownLead }.orEmpty()
            if (brief == null) {
                // 서버가 닿지 않는다 — 받아 둔 조각이 있으면 같은 목소리로, 없으면 기기 템플릿으로.
                val play = app.voicePack.fragment(VoiceLines.genericPlayId(song, pick))
                if (play != null) {
                    val parts = mine + play
                    val line = parts.joinToString(" ") { it.text }
                    app.dj.update { it.say(line, now).copy(problem = null) }
                    talk(line, item.mediaId, clips = parts.map { it.file.toUri().toString() })
                    return@launch
                }
                val line = DjScript.intro(song, drive, now, seed)
                app.dj.update { it.say(line, now).copy(problem = null) }
                talk(line, item.mediaId)
                return@launch
            }
            if (brief.voiceClips.isNotEmpty()) {
                val line = (mine.map { it.text } + brief.script).joinToString(" ")
                app.dj.update { it.say(line, now).copy(problem = null).suggest(SongRequest.of(brief.song)) }
                val clips = mine.map { it.file.toUri().toString() } + brief.voiceClips.map { url -> app.voicePack.local(url)?.toUri()?.toString() ?: app.api.absolute(url) }
                talk(line, item.mediaId, clips = clips)
                return@launch
            }
            val mentions = song.title.isNotBlank() && song.title.trim() in brief.script
            val line = if (mentions) brief.script else "${brief.script} ${DjScript.play(song, seed)}"
            app.dj.update { it.say(line, now).copy(problem = null).suggest(SongRequest.of(brief.song)) }
            val voiceUrl = brief.voiceUrl
            talk(line, item.mediaId, clips = listOfNotNull(voiceUrl?.takeIf { mentions }?.let(app.api::absolute)))
        }
    }

    /**
     * 멘트를 소리 내어 읽는다. 화면에는 이미 보이고 있다.
     * 🔑 내비 안내 직전·차선 변경 중·말로 사연을 듣는 중이면 풀릴 때까지 기다리고, [HOLD_MAX_MS]가 넘으면 읽지 않는다.
     *    풀린 뒤에도 [SETTLE_MS] 쉬고 말한다 — 안내 음성이 끝나자마자 끼어들지 않는다.
     *    기다리는 사이 곡이 바뀌었거나 멈췄으면 읽지 않는다.
     * 🔑 기기 TTS는 음악을 먼저 줄이고 [LEAD_IN_MS] 뒤에 말한다(라디오 진행처럼, Pleos 음성 앞 여백 권장).
     */
    private suspend fun talk(line: String, itemId: String? = null, clips: List<String> = emptyList()) {
        val quiet = withTimeoutOrNull(HOLD_MAX_MS) {
            while (app.car.state.value.holdTalk || app.mic.state.value.listening) {
                app.car.state.first { !it.holdTalk }
                app.mic.state.first { !it.listening }
                delay(SETTLE_MS)
            }
        }
        if (quiet == null || !stillOn(itemId)) return
        if (clips.isNotEmpty()) return playClips(clips)
        leadIn = true
        duck()
        try {
            delay(LEAD_IN_MS)
            if (!stillOn(itemId)) return
            app.voice.speak(line)
            withTimeoutOrNull(START_WAIT_MS) { app.voice.speaking.first { it } }
        } finally {
            leadIn = false
            duck()
        }
    }

    private fun stillOn(itemId: String?): Boolean =
        player.isPlaying && (itemId == null || player.currentMediaItem?.mediaId == itemId)

    /** 🔑 음악 크기는 끊기지 않게 [FADE_MS]에 걸쳐 바꾼다. */
    private fun duck() {
        val target = if (clipOn || leadIn || app.voice.speaking.value || app.mic.state.value.listening) DUCKED else 1f
        if (target == fadeTarget) return
        fadeTarget = target
        fade?.cancel()
        fade = scope.launch {
            val from = player.volume
            for (step in 1..FADE_STEPS) {
                delay(FADE_MS / FADE_STEPS)
                player.volume = from + (target - from) * step / FADE_STEPS
            }
        }
    }

    /** DJ 목소리를 모두 멈추고 음악 크기를 되돌린다. */
    private fun hush() {
        app.voice.stop()
        clip?.stop()
        clipOn = false
        leadIn = false
        duck()
    }

    /** 오래 멈췄다 같은 곡을 이어 틀면 신청곡 제안만 새로 받는다. 곡 중간이라 DJ는 말하지 않는다. */
    private fun refreshSuggestion(item: MediaItem) {
        if (app.dj.value.freshSuggestion() != null || briefJob?.isActive == true) return
        val nowPlaying = item.mediaMetadata.nowPlaying()
        val drive = app.car.state.value
        briefJob = scope.launch {
            runCatching { app.api.brief(drive.toInput(LocalTime.now(), app.spoken), voice = false, nowPlaying = nowPlaying) }
                .onSuccess { brief -> app.dj.update { it.suggest(SongRequest.of(brief.song)) } }
                .onFailure { Log.i(TAG, "신청곡 제안을 새로 받지 못했어요: ${it.message}") }
        }
    }

    /** DJ에게 알릴 지금 곡. 내가 만든 노래면 신청곡으로 소개하게 ID를 붙인다. */
    private fun MediaMetadata.nowPlaying(): NowPlaying {
        val made = extras?.takeIf { it.getBoolean(Catalog.EXTRA_MADE) }
        return NowPlaying(title?.toString().orEmpty(), artist?.toString().orEmpty(), made?.getString(Catalog.EXTRA_SONG_ID))
    }

    /**
     * 출발·도착·경유 인사 — 받아 둔 음성 조각이 있으면 그 목소리로, 없으면 기기 템플릿을 TTS로 읽는다.
     * 화면에는 실제로 말하는 글을 보인다.
     */
    private suspend fun moment(fragmentId: String, fallback: String) {
        val fragment = app.voicePack.fragment(fragmentId)
        val line = fragment?.text ?: fallback
        app.dj.update { it.say(line) }
        talk(line, clips = listOfNotNull(fragment?.file?.toUri()?.toString()))
    }

    /** 멘트 음성(조각 여럿이면 순서대로 이어서) — 음악을 줄이고 얹었다가 다 끝나면 되돌린다. 틀지 못하면 기기 TTS가 같은 글을 읽는다. */
    private fun playClips(urls: List<String>) {
        val voice = clip ?: ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(authorizedSource()))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), false)
            .build()
            .also { created ->
                clip = created
                created.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) {
                            clipOn = false
                            duck()
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        clipOn = false
                        duck()
                        app.dj.value.line?.let(app.voice::speak)
                    }
                })
            }
        clipOn = true
        duck()
        // 🔑 조각은 끝의 쉼이 짧다(실측 중앙값 0.15초) — 그대로 이으면 문장이 붙어 들린다. 조각 사이에 [CLIP_GAP_US]만큼 숨을 넣는다.
        val sources = DefaultMediaSourceFactory(authorizedSource())
        voice.setMediaSources(urls.flatMapIndexed { index, url ->
            listOfNotNull(if (index > 0) SilenceMediaSource(CLIP_GAP_US) else null, sources.createMediaSource(MediaItem.fromUri(url)))
        })
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
            if (!playWhenReady) hush()
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) player.stop()
        }

        /** 🔴 일시 손실(Gleo 음성, 통화)은 playWhenReady가 아니라 이 값으로 온다 — DJ도 바로 멈춘다(Pleos 오디오 포커스 가이드). */
        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            if (playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) hush()
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
         * - 곡 하나(미디어 센터) → 그 곡을 마지막으로 보여 준 목록 전체(그 곡부터)
         * - 곡 여럿(앱 화면) → 받은 목록 그대로
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
            introduced = 0
            val single = mediaItems.singleOrNull()
            val query = single?.requestMetadata?.searchQuery
            when {
                query != null -> {
                    val found = catalog.search(query).ifEmpty { catalog.children(Shelf.PICKS) }
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
                // 앱 화면은 목록을 통째로 보낸다. 풀리지 않는 곡이 빠져도 고른 곡에서 시작한다.
                else -> {
                    val picked = mediaItems.getOrNull(startIndex)?.mediaId
                    val queue = mediaItems.mapNotNull { catalog.resolve(it.mediaId) }
                    MediaSession.MediaItemsWithStartPosition(queue, queue.indexOfFirst { it.mediaId == picked }.coerceAtLeast(0), startPositionMs)
                }
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
            MediaSession.MediaItemsWithStartPosition(catalog.children(Shelf.PICKS), 0, C.TIME_UNSET)
        }
    }

    private companion object {
        const val TAG = "DjTrotPlayback"
        /** 멘트 조각 사이의 숨(마이크로초). */
        const val CLIP_GAP_US = 350_000L

        /** DJ가 말하는 동안 음악 크기. */
        const val DUCKED = 0.3f
        const val FADE_MS = 300L
        const val FADE_STEPS = 10
        /** 음악을 줄이고 기기 TTS가 입을 떼기까지. */
        const val LEAD_IN_MS = 400L
        /** 기기 TTS가 말을 시작하지 않을 때 음악을 되돌리기까지. */
        const val START_WAIT_MS = 3_000L
        /** 내비 안내·차선 변경이 끝나고 DJ가 말하기까지 쉬는 시간. */
        const val SETTLE_MS = 1_500L
        /** 내비 안내·차선 변경이 끝나기를 기다리는 가장 긴 시간. 넘으면 멘트는 화면에만 둔다. */
        const val HOLD_MAX_MS = 15_000L
        const val MAX_FAILURES_IN_ROW = 3
        /** DJ가 길게 말하는 간격(곡 수). 사이의 곡은 곡 소개만 한다. */
        const val FULL_EVERY = 3
    }
}

private fun List<MediaItem>.page(page: Int, pageSize: Int): ImmutableList<MediaItem> {
    if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return ImmutableList.copyOf(this)
    return ImmutableList.copyOf(drop(page * pageSize).take(pageSize))
}
