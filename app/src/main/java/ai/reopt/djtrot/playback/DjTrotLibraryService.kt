package ai.reopt.djtrot.playback

import ai.reopt.djtrot.BriefTrace
import ai.reopt.djtrot.DjTrotApp
import ai.reopt.djtrot.api.Ad
import ai.reopt.djtrot.api.BriefRequest
import ai.reopt.djtrot.api.DjTrotApi
import ai.reopt.djtrot.api.NowPlaying
import ai.reopt.djtrot.dj.DjScript
import ai.reopt.djtrot.dj.SongInfo
import ai.reopt.djtrot.dj.SongRequest
import ai.reopt.djtrot.dj.VoiceLines
import ai.reopt.djtrot.dj.VoicePackStore
import ai.reopt.djtrot.dj.toInput
import ai.reopt.djtrot.ui.MainActivity
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
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
import kotlinx.serialization.json.Json

/**
 * DJ 트롯 재생 서비스 — Pleos Music/Audio 카테고리 요건을 이 한 곳에서 지킨다.
 * - Media3 MediaLibraryService + MediaSession: 미디어 센터·스티어링 휠·미디어 검색이 붙는다.
 * - 🔴 자동 재생 금지: 서비스가 뜨거나 연결돼도 재생하지 않는다. 사용자 동작·시스템 재개 요청에서만 재생한다.
 * - 🔴 오디오 포커스: 영구 손실이면 정지해 포커스를 돌려준다. 일시 손실은 ExoPlayer가 멈췄다 이어 튼다.
 *   DJ 목소리는 음악과 따로 나오므로 음악이 눌리는 순간(일시 손실, 사용자 정지) 함께 멈춘다 — Gleo·통화 위로 말하지 않는다.
 * - 🔑 DJ는 내비 안내 직전과 방향지시등이 켜진 동안 말을 미룬다(DriveContext.holdTalk).
 * - 🔴 앱 종료(onTaskRemoved): 재생 정지 → stopSelf → super 순서.
 * - 🔑 Pleos 서비스 수명 요건: onCreate에서 startService로 Started 상태를 만든다. 포그라운드 전환은 재생 시 Media3가 한다.
 * - 🔴 쓰지 않으면 내려놓는다(Pleos 미사용 앱 BG→CCH 전환 가이드): 노래가 나오지 않은 채 [IDLE_STOP_MS](오래 멈춤)·[ENDED_STOP_MS](끝남·시작 전)가
 *   지나면 재생기를 멈춰 오디오 포커스를 돌려주고 Started 상태를 푼다([retire]). 화면이 붙어 있지 않으면 서비스가 사라지고 프로세스는 cached로 간다.
 *   차량 신호도 서비스가 사는 동안만 잡는다(`CarSignals.hold`).
 * - 🔑 DJ의 목소리는 서버가 만든 음성이 먼저다 — 브리프의 조각 → 받아 둔 음성 팩 → 그래도 없을 때만 기기 TTS(Pleos TTS, 안 되면 Android TTS).
 * - 🔑 음성 앞에 [PREROLL_US]의 묵음을 둔다(Pleos TTS·음성 안내 선행 묵음 가이드 — 첫 음절이 잘리지 않게, 데이터 단계에서).
 * - 🔴 음악이 멈춘 채 조각만 들려줄 때(멘트 살펴보기)는 그 소리의 오디오 포커스를 따로 받는다([focusForClip]) — 포커스 없이 소리를 내지 않는다.
 */
@OptIn(UnstableApi::class)
class DjTrotLibraryService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private val scope: CoroutineScope = MainScope()
    private val app get() = application as DjTrotApp
    private val catalog get() = app.catalog

    private var announced: String? = null

    /** 대기열에 끼워 둔 광고의 ID — 나오기 전에 같은 광고를 또 끼우지 않는다. */
    private var queuedAd: String? = null

    /** 지금 나오는 광고. 끝까지 들으면 서버에 알린다. */
    private var playingAd: Ad? = null

    /** 광고 바로 다음에 나오는 곡의 ID — 이 곡은 DJ가 소개하지 않는다. */
    private var afterAd: String? = null
    /** 이번 재생 목록에서 DJ가 소개한 곡 수 — 설정의 간격([DjTrotApp.fullEvery])마다만 길게 말한다. */
    private var introduced = 0
    private var briefJob: Job? = null
    /** 서버가 만든 DJ 멘트 음성을 트는 작은 플레이어. 오디오 포커스는 본 플레이어가 쥐고, 말하는 동안 음악을 줄인다. */
    private var clip: ExoPlayer? = null
    private var clipOn = false
    /** 기기 TTS가 입을 떼기 전, 음악을 먼저 줄여 두는 동안. */
    private var leadIn = false
    private var fade: Job? = null
    private var fadeTarget = 1f
    /** 노래가 나오지 않는 채로 기다리는 중 — 다 지나면 서비스를 내려놓는다. */
    private var idle: Job? = null
    /** 이미 내려놓았다 — 다시 노래가 나올 때까지 기다리기를 되풀이하지 않는다. */
    private var retired = false
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    /** 음악 없이 조각만 틀 때 받은 오디오 포커스. */
    private var clipFocus: AudioFocusRequest? = null
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
        app.car.hold(this)
        // 🔑 광고 — 광고 조건에 맞는 장소에 가까워지면 광고 음악을 지금 곡 다음에 끼운다. 장소 판단은 기기 안에서 한다.
        //    노래가 나오는 중일 때만 끼우고(자동 재생 금지), 어린이보호구역에서는 끼우지 않는다.
        scope.launch {
            app.car.state.collect { drive ->
                if (!player.isPlaying || drive.schoolZone || queuedAd != null) return@collect
                queueAd(app.ads.due(drive) ?: return@collect)
            }
        }
        // 시뮬레이션의 광고 버튼 — 쉬는 시간을 보지 않고 그 광고를 바로 다음 곡으로 끼운다.
        scope.launch {
            app.adCues.collect { cue ->
                if (!player.isPlaying) return@collect
                val ad = app.ads.list.value.firstOrNull { it.id == cue.id } ?: return@collect
                queueAd(ad)
                // 자동 시연 — 지금 곡을 넘겨 광고를 바로 튼다. 넘긴 곡은 광고가 아니라 건너뜀으로 세지 않는다.
                if (cue.now && queuedAd == ad.id) player.seekToNextMediaItem()
            }
        }
        // 신청한 노래가 완성되면 지금 곡 다음에 넣고 DJ가 알린다.
        scope.launch {
            app.orders.ready.collect { song ->
                val item = catalog.resolve(MediaPath.Song(song.id).id) ?: return@collect
                if (player.mediaItemCount == 0) player.setMediaItem(item) else player.addMediaItem(player.currentMediaItemIndex + 1, item)
                // 화면에는 제목을 넣은 글을, 소리는 받아 둔 조각(제목 없이 말한다)을 먼저 쓴다. 조각이 없을 때만 기기 TTS가 글을 읽는다.
                val line = "신청하신 노래 '${song.title}'가 완성됐어요. 다음 곡으로 들려 드릴게요."
                app.dj.update { it.say(line) }
                if (app.djTalks) launch { talk(line, clips = listOfNotNull(app.voicePack.fragment(VoiceLines.SONG_READY_ID)?.file?.toUri()?.toString())) }
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
        // 시연 탭의 버튼 — 지금 곡 위에서, 덧씌운 상황으로 다시 소개한다. 노래가 멈춰 있으면 말하지 않는다.
        scope.launch {
            app.demoCues.collect {
                if (!app.djTalks || !player.isPlaying) return@collect
                announced = null
                announce(player.currentMediaItem, demo = true)
            }
        }
        // 시연 탭의 멘트 살펴보기 — 고른 조각 하나를 바로 들려준다. 노래가 멈춰 있어도 들려준다(조각만 나온다).
        scope.launch {
            app.demoLines.collect { id ->
                val fragment = app.voicePack.fragment(id) ?: return@collect
                briefJob?.cancel()
                app.voice.stop()
                app.dj.update { it.say(fragment.text) }
                playClips(listOf(fragment.file.toUri().toString()))
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
        // 시스템이 목록만 보려고 띄운 경우에도 Started로 남지 않게 — 노래를 틀지 않으면 곧 내려놓는다.
        idleCheck()
    }

    /**
     * 노래가 나오는지 다시 본다 — 나오지 않으면 기다렸다가 내려놓고([retire]), 나오면 기다리기를 그만둔다.
     * 🔑 잠깐 멈춘 것(일시 정지)은 [IDLE_STOP_MS]까지 기다린다 — 곧 이어 들을 수 있다. 끝났거나 아직 틀지 않았으면 [ENDED_STOP_MS]만 기다린다.
     */
    private fun idleCheck() {
        idle?.cancel()
        idle = null
        if (player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)) {
            retired = false
            return
        }
        if (retired) return
        val paused = player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING
        idle = scope.launch {
            delay(if (paused) IDLE_STOP_MS else ENDED_STOP_MS)
            if (!player.isPlaying) retire()
        }
    }

    /**
     * 쓰지 않는 서비스를 내려놓는다 — DJ를 멈추고, 재생기를 멈춰(IDLE) 오디오 포커스를 돌려주고, 멘트 재생기를 풀고, Started 상태를 푼다.
     * 🔑 재생 목록과 위치는 재생기에 남는다. 다시 누르면 화면과 미디어 세션이 준비(prepare)부터 다시 한다.
     *    화면이 아직 붙어 있으면 서비스는 살아 있고(바인딩), 화면도 떠나면 사라진다.
     */
    private fun retire() {
        Log.i(TAG, "노래가 나오지 않아 재생 서비스를 내려놓아요")
        retired = true
        hush()
        player.stop()
        clip?.release()
        clip = null
        stopSelf()
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
        dropClipFocus()
        app.car.release(this)
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
     * 🔑 길게 말하는 것은 첫 곡과 그 뒤 설정의 간격([DjTrotApp.fullEvery], 기본 세 곡)마다다. 나머지와 어린이보호구역에서는 곡 소개만 한다.
     * 🔑 [demo] — 시뮬레이션 버튼이 불렀다. 노래가 이미 나오는 중이라 곡 소개는 하지 않고 바뀐 상황의 말만 하며, 분위기 장면도 곡을 가리지 않고 쓴다.
     *    어린이보호구역만 예외로 곡 이름만 말한다(그 규칙을 보여 주려는 것이다).
     * 🔑 여는 말을 받아 둔 조각으로 낼 수 있으면 서버에는 곡 소개만 청한다 — 집·회사·동승자처럼 차만 아는 것으로 고른 말을 쓰려는 것이다.
     */
    /**
     * 광고 음악을 지금 곡 다음에 끼운다. 같은 광고가 이미 대기 중이면 다시 넣지 않는다.
     * 🔑 지금 곡을 끊지 않는다 — 곡이 끝나면 광고가 나오고, 광고가 끝나면 하던 목록으로 돌아간다.
     */
    private fun queueAd(ad: Ad) {
        if (queuedAd == ad.id || player.mediaItemCount == 0) return
        val item = catalog.ad(ad.song, ad.maxSeconds)
        player.addMediaItem(player.currentMediaItemIndex + 1, item)
        queuedAd = ad.id
        app.ads.queued(ad)
        app.dj.update { it.say("이 근처 소식이 있어요. 다음 곡으로 '${ad.title}' 광고를 들려 드릴게요.") }
    }

    /**
     * 곡이 바뀌었다 — 광고가 시작됐는지, 방금 광고를 끝까지 들었는지 본다.
     * 🔑 끝까지 들은 것만 센다: 광고에서 다음 곡으로 **저절로** 넘어갔을 때([Player.MEDIA_ITEM_TRANSITION_REASON_AUTO]). 건너뛰면 세지 않는다.
     */
    private fun trackAd(next: MediaItem?, reason: Int) {
        val finished = playingAd
        val nextAd = app.ads.adOf(next?.mediaMetadata?.extras?.getString(Catalog.EXTRA_SONG_ID))
        playingAd = nextAd
        if (nextAd != null && queuedAd == nextAd.id) queuedAd = null
        if (finished == null) return
        // 🔑 광고 바로 다음 곡은 DJ가 길게 소개하지 않는다 — 광고를 듣고 난 뒤의 한마디(생성권 진행)와 겹치지 않게.
        afterAd = next?.mediaId
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) heardAd(finished)
        else app.dj.update { it.say("광고를 끝까지 듣지 않아 횟수에 넣지 않았어요.") }
    }

    /** 광고를 끝까지 들었다 — 서버에 알리고, 생성권이 생겼으면 화면에 알린다. */
    private fun heardAd(ad: Ad) {
        app.ads.played(ad, "completed")
        scope.launch {
            val progress = app.ads.heard(ad) ?: return@launch
            if (progress.granted) app.orders.refreshTokens()
            // DJ가 받아 둔 조각으로 진행을 알린다. 조각이 없거나 DJ 멘트가 꺼져 있으면 글로만 알린다.
            val (fragmentId, line) = when {
                progress.granted -> "ad.outro.granted" to "광고를 ${progress.needed}번 들어서 생성권 한 장이 생겼어요!"
                progress.limited -> VoiceLines.AD_LIMITED_ID to "오늘 광고로 받을 수 있는 생성권을 다 받았어요. 내일 다시 받을 수 있어요."
                progress.capped -> "ad.outro.capped" to "생성권이 가득 찼어요. 노래를 만들면 광고로 다시 받을 수 있어요."
                progress.progress == 1 -> "ad.outro.1" to "광고 1/${progress.needed} · 두 번 더 들으면 생성권 한 장이에요."
                else -> "ad.outro.2" to "광고 ${progress.progress}/${progress.needed} · 한 번만 더 들으면 생성권 한 장이에요."
            }
            // 🔑 받아 둔 조각이 있을 때만 소리로 알린다 — 없으면 글로만 알린다(기기 TTS로 읽지 않는다).
            if (app.djTalks && player.isPlaying && app.voicePack.fragment(fragmentId) != null) moment(fragmentId, line) else app.dj.update { it.say(line) }
        }
    }

    private fun announce(item: MediaItem?, demo: Boolean = false) {
        if (item == null || !app.djTalks) return
        // 광고 음악 — 곡 소개 대신 "이 근처 소식" 한마디만 한다(받아 둔 조각이 있으면 DJ 목소리로).
        app.ads.adOf(item.mediaMetadata.extras?.getString(Catalog.EXTRA_SONG_ID))?.let { ad ->
            if (item.mediaId != announced) {
                announced = item.mediaId
                app.ads.played(ad, "started")
                briefJob?.cancel()
                briefJob = scope.launch { moment("ad.intro.${Math.floorMod(ad.id.hashCode(), 2) + 1}", "광고 · ${ad.title}") }
            }
            return
        }
        // 광고 바로 다음 곡 — 길게 소개하지 않고 넘어간다(광고 뒤의 한마디가 나오는 자리다).
        if (item.mediaId == afterAd) {
            afterAd = null
            announced = item.mediaId
            return
        }
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
        // 🔑 시연이 방금 덧씌운 상황은 신호 흐름에 아직 닿지 않았을 수 있다 — 여기서 한 번 더 덧씌운다(같은 값이라 해롭지 않다).
        val drive = app.car.state.value.let { real -> app.demo.value?.apply(real) ?: real }
        val now = app.clock()
        val seed = item.mediaId.hashCode()
        // 시연 버튼은 늘 길게 말한다(어린이보호구역만 빼고) — 누른 상황의 말을 바로 들려주려는 것이다. 곡 수는 세지 않는다.
        // 길게 말하는 간격은 설정에서 고른다 — 0이면 늘 곡 소개만.
        val every = app.fullEvery
        val full = (demo || (every > 0 && introduced % every == 0)) && !drive.schoolZone
        if (!demo) introduced += 1
        app.syncVoicePack()
        val pick = VoiceLines.seedOf(song)
        // 받아 둔 조각으로 낼 여는 말. 장면의 조각을 아직 받지 못했으면(새로 더한 장면) 장면 없이 시간대·날씨 인사로 고른다.
        // 그래도 하나라도 빠졌으면 서버가 고른 것을 쓴다.
        val pack = app.voicePack
        val leadIds = if (!full) emptyList() else VoiceLines.leadIds(drive, now.hour, pick, always = demo).takeIf { ids -> ids.all { pack.fragment(it) != null } }
            ?: VoiceLines.leadIds(drive, now.hour, pick, always = demo, plain = true)
        val lead = leadIds.map(pack::fragment)
        val ownLead = lead.isNotEmpty() && lead.all { it != null }
        // 🔑 시뮬레이션 버튼 — 노래는 이미 나오는 중이다. 곡 소개를 되풀이하지 않고 바뀐 상황의 말만 한다.
        //    받아 둔 조각으로 낼 수 있으면 서버에 묻지 않고, 아니면 서버에 상황의 말만 청한다(ment=lead).
        if (demo && ownLead) {
            val parts = lead.filterNotNull()
            val line = parts.joinToString(" ") { it.text }
            app.dj.update { it.say(line, now).copy(problem = null) }
            app.lastBrief.value = BriefTrace(LocalTime.now(), request = null, response = line, local = leadIds)
            briefJob?.cancel()
            briefJob = scope.launch { talk(line, item.mediaId, clips = parts.map { it.file.toUri().toString() }) }
            return
        }
        briefJob?.cancel()
        briefJob = scope.launch {
            val ment = when {
                demo && full -> "lead"
                full && !ownLead -> "full"
                else -> "short"
            }
            val input = drive.toInput(now, app.spoken, pick, always = demo)
            val attempt = runCatching { app.api.brief(input, voice = true, nowPlaying = nowPlaying, ment = ment, token = app.account.cachedToken(), dj = app.djId.value) }
                .onFailure { Log.i(TAG, "DJ 브리프 대신 기기 멘트: ${it.message}") }
            val brief = attempt.getOrNull()
            // 개발자 모드가 보여 주는 기록 — 무엇을 보내고 무엇을 받았는가.
            app.lastBrief.value = BriefTrace(
                at = LocalTime.now(),
                request = PRETTY.encodeToString(BriefRequest.serializer(), BriefRequest(input, voice = true, nowPlaying = nowPlaying, ment = ment, dj = app.djId.value)),
                response = brief?.let { "${it.script}\n음성 조각 ${it.voiceClips.size}개 · ${if (it.voiceUrl != null) "통문장 음성 있음 · " else ""}출처 ${it.source}" }
                    ?: "받지 못했어요 · ${attempt.exceptionOrNull()?.message ?: "알 수 없는 오류"}",
                local = leadIds.takeIf { ownLead }.orEmpty(),
            )
            if (player.currentMediaItem?.mediaId != item.mediaId) return@launch
            // 🔑 브리프를 기다리는 사이 멈췄으면 말하지 않는다. 다시 틀 때 새로 소개한다.
            if (!player.isPlaying) {
                announced = null
                return@launch
            }
            val mine = lead.filterNotNull().takeIf { ownLead }.orEmpty()
            // 받아 둔 음성 팩의 제목 없는 곡 소개 — 서버가 음성을 주지 못할 때 같은 DJ 목소리로 잇는 조각.
            val play = pack.fragment(VoiceLines.genericPlayId(song, pick))
            // 받아 둔 조각만으로 말한다(여는 말 + 곡 소개). 브리프가 준 신청곡 제안이 있으면 함께 올린다.
            suspend fun ownVoice(play: VoicePackStore.Fragment, request: SongRequest? = null) {
                val parts = mine + play
                val line = parts.joinToString(" ") { it.text }
                app.dj.update { state -> state.say(line, now).copy(problem = null).let { if (request != null) it.suggest(request) else it } }
                talk(line, item.mediaId, clips = parts.map { it.file.toUri().toString() })
            }
            if (brief == null) {
                // 서버가 닿지 않는다 — 받아 둔 조각이 있으면 같은 목소리로, 없으면 기기 템플릿으로.
                if (play != null) return@launch ownVoice(play)
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
            val voiceUrl = brief.voiceUrl?.let(app.api::absolute)
            // 🔑 서버가 음성을 주지 못했다(팩이 꺼져 있다, 오늘 합성 상한, 곡 소개 조각이 아직 없다) — 기기 TTS로 읽기 전에 받아 둔 음성 팩으로 말한다.
            if (voiceUrl == null && play != null) return@launch ownVoice(play, SongRequest.of(brief.song))
            // 통문장 음성이 곡을 말하지 않으면, 받아 둔 곡 소개 조각을 뒤에 이어 같은 DJ 목소리로 끝낸다.
            if (voiceUrl != null && !mentions && play != null) {
                val line = "${brief.script} ${play.text}"
                app.dj.update { it.say(line, now).copy(problem = null).suggest(SongRequest.of(brief.song)) }
                talk(line, item.mediaId, clips = listOf(voiceUrl, play.file.toUri().toString()))
                return@launch
            }
            val line = if (mentions) brief.script else "${brief.script} ${DjScript.play(song, seed)}"
            app.dj.update { it.say(line, now).copy(problem = null).suggest(SongRequest.of(brief.song)) }
            talk(line, item.mediaId, clips = listOfNotNull(voiceUrl?.takeIf { mentions }))
        }
    }

    /**
     * 멘트를 소리 내어 읽는다. 화면에는 이미 보이고 있다.
     * 🔑 내비 안내 직전·차선 변경 중·말로 사연을 듣는 중이면 풀릴 때까지 기다리고, [HOLD_MAX_MS]가 넘으면 읽지 않는다.
     *    풀린 뒤에도 [SETTLE_MS] 쉬고 말한다 — 안내 음성이 끝나자마자 끼어들지 않는다.
     *    기다리는 사이 곡이 바뀌었거나 멈췄으면 읽지 않는다.
     * 🔑 [clips](서버가 만든 음성 — 브리프의 조각, 받아 둔 음성 팩)가 있으면 그것을 튼다. 하나도 없을 때만 기기 TTS가 [line]을 읽는다.
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
        val target = if (clipOn || leadIn || app.voice.speaking.value || app.mic.state.value.listening) app.duckLevel else 1f
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
        dropClipFocus()
        duck()
    }

    /**
     * 멘트 조각을 틀어도 되는가 — 노래가 나오는 중이면 음악이 오디오 포커스를 쥐고 있어 그 위에 얹는다.
     * 🔴 노래가 멈춰 있으면(멘트 살펴보기) 이 소리의 포커스를 잠깐 받는다. 받지 못하면(통화 중 등) 틀지 않는다(Pleos 오디오 포커스 가이드: 모든 스트림은 재생 전에 포커스를 청한다).
     */
    private fun focusForClip(): Boolean {
        if (player.isPlaying || clipFocus != null) return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            // 다른 소리가 포커스를 가져가면 멘트를 바로 멈춘다.
            .setOnAudioFocusChangeListener { change -> if (change < 0) hush() }
            .build()
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        clipFocus = request
        return true
    }

    private fun dropClipFocus() {
        clipFocus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        clipFocus = null
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
                            dropClipFocus()
                            duck()
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        clipOn = false
                        dropClipFocus()
                        duck()
                        // 음성을 받지 못했다 — 노래가 나오는 중이면 같은 글을 기기 TTS가 읽는다.
                        if (player.isPlaying) app.dj.value.line?.let(app.voice::speak)
                    }
                })
            }
        if (!focusForClip()) return
        clipOn = true
        duck()
        // 🔑 조각은 끝의 쉼이 짧다(실측 중앙값 0.15초) — 그대로 이으면 문장이 붙어 들린다. 조각 사이에 [CLIP_GAP_US]만큼 숨을 넣는다.
        val sources = DefaultMediaSourceFactory(authorizedSource())
        // 🔑 맨 앞에는 선행 묵음([PREROLL_US])을 둔다 — 출력 경로가 열리는 사이 첫 음절이 잘리지 않게.
        voice.setMediaSources(urls.flatMapIndexed { index, url ->
            listOf(SilenceMediaSource(if (index > 0) CLIP_GAP_US else PREROLL_US), sources.createMediaSource(MediaItem.fromUri(url)))
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
        // 연결한 차는 세션, 아니면 시연 손님 ID — 시연으로 만든 곡도 같은 주소로 받는다.
        spec.withAdditionalHeaders(runBlocking { app.songAccess.headers() })
    }

    private inner class PlayerEvents : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            // 광고가 목록의 마지막 곡이었으면 다음 곡으로 넘어가는 일이 없다 — 재생이 끝난 것으로 끝까지 들었다고 본다.
            if (playbackState == Player.STATE_ENDED) playingAd?.let { ad ->
                playingAd = null
                heardAd(ad)
            }
            idleCheck()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            idleCheck()
            if (!isPlaying) return
            failuresInRow = 0
            app.dj.update { it.copy(problem = null) }
            announce(player.currentMediaItem)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            trackAd(mediaItem, reason)
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

        /** 개발자 모드의 요청 기록을 읽기 쉽게 쓰는 JSON. */
        val PRETTY = Json(DjTrotApi.json) { prettyPrint = true }
        /** 멘트 조각 사이의 숨(마이크로초). */
        const val CLIP_GAP_US = 350_000L
        /** 멘트 음성 맨 앞의 선행 묵음(마이크로초) — Pleos 권장 150~200ms. */
        const val PREROLL_US = 180_000L
        /** 멈춰 둔 채 이만큼 지나면 서비스를 내려놓는다. */
        const val IDLE_STOP_MS = 5 * 60_000L
        /** 재생이 끝났거나 아직 틀지 않은 채 이만큼 지나면 서비스를 내려놓는다. */
        const val ENDED_STOP_MS = 60_000L

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
    }
}

private fun List<MediaItem>.page(page: Int, pageSize: Int): ImmutableList<MediaItem> {
    if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return ImmutableList.copyOf(this)
    return ImmutableList.copyOf(drop(page * pageSize).take(pageSize))
}
