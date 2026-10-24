package ai.reopt.djtrot.dj

import ai.reopt.djtrot.account.DeviceAccount
import ai.reopt.djtrot.api.CreateSong
import ai.reopt.djtrot.api.DjTrotApi
import ai.reopt.djtrot.api.DjTrotApiException
import ai.reopt.djtrot.api.DriveInput
import ai.reopt.djtrot.api.TrotSong
import ai.reopt.djtrot.playback.Catalog
import android.os.SystemClock
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 지금 만드는 신청곡. */
data class Order(
    val request: SongRequest,
    /** sending · queued · running · ready · failed */
    val state: String,
    val songId: String? = null,
    val song: TrotSong? = null,
    val problem: String? = null,
    /** 맡긴 때(`SystemClock.elapsedRealtime`). 화면이 기다린 시간을 센다. */
    val since: Long = 0L,
    /** 완성곡이 무대에 올랐는가. */
    val heard: Boolean = false,
) {
    val working: Boolean get() = state == "sending" || state == "queued" || state == "running"
}

/**
 * 드라이브 노래 주문 — `POST /songs`로 맡기고 5초마다 확인해, 완성되면 목록에 넣고 [ready]로 알린다.
 * 🔑 앱 범위에서 돈다 — 화면을 닫아도 작곡(약 2분)을 끝까지 지켜본다. 재생 서비스가 [ready]를 받아 다음 곡으로 넣는다.
 * 🔑 같은 주문은 같은 requestKey로 보낸다(멱등). 실패한 곡의 토큰은 서버가 돌려준다.
 */
class SongOrders(
    private val api: DjTrotApi,
    private val account: DeviceAccount,
    private val catalog: Catalog,
    private val scope: CoroutineScope,
) {
    private val _current = MutableStateFlow<Order?>(null)
    val current: StateFlow<Order?> = _current.asStateFlow()

    /** 드라이브 제작 토큰 잔액. 모르면 null. */
    private val _tokens = MutableStateFlow<Int?>(null)
    val tokens: StateFlow<Int?> = _tokens.asStateFlow()

    private val _ready = MutableSharedFlow<TrotSong>(extraBufferCapacity = 4)
    val ready: SharedFlow<TrotSong> = _ready.asSharedFlow()

    private var watching: Job? = null

    fun refreshTokens() {
        scope.launch {
            _tokens.value = runCatching { account.authorized { api.benefits(it) }.tokens }.getOrElse { _tokens.value }
        }
    }

    fun create(request: SongRequest, drive: DriveInput) {
        if (_current.value?.working == true) return
        val key = UUID.randomUUID().toString()
        val since = SystemClock.elapsedRealtime()
        _current.value = Order(request, "sending", since = since)
        watching?.cancel()
        watching = scope.launch {
            val body = CreateSong(key, request.topic, request.style, request.voice, request.story.take(StoryBuilder.MAX_STORY), drive)
            val created = runCatching { account.authorized { api.createSong(it, body) } }
                .getOrElse { error -> return@launch fail(request, error) }
            _current.value = Order(request, created.state, created.id, since = since)
            refreshTokens()
            var song = created
            while (song.state == "queued" || song.state == "running") {
                delay(POLL_MS)
                song = runCatching { account.authorized { api.song(it, created.id) } }.getOrElse { continue }
                _current.value = Order(request, song.state, song.id, since = since)
            }
            val made = song.song
            if (song.state == "ready" && made != null) {
                catalog.register(made)
                _current.value = Order(request, "ready", song.id, made)
                _ready.tryEmit(made)
            } else {
                _current.value = Order(request, "failed", song.id, problem = "노래를 끝내지 못했어요. 토큰은 돌려드렸어요.")
                refreshTokens()
            }
        }
    }

    /** 무대의 곡이 바뀔 때마다 — 완성곡을 듣고 다음 곡으로 넘어가면 안내를 저절로 닫는다. */
    fun onStage(songId: String?) {
        val order = _current.value ?: return
        val made = order.song?.id ?: return
        if (songId == made) _current.value = order.copy(heard = true) else if (order.heard) _current.value = null
    }

    /** 완성 안내를 닫는다. */
    fun dismiss() {
        if (_current.value?.working == false) _current.value = null
    }

    private fun fail(request: SongRequest, error: Throwable) {
        _current.value = Order(request, "failed", problem = message(error))
        refreshTokens()
    }

    companion object {
        private const val POLL_MS = 5_000L

        fun message(error: Throwable): String = when {
            error is DeviceAccount.NotConnected -> "휴대폰을 먼저 연결해 주세요."
            error is DjTrotApiException && error.code == "INSUFFICIENT_TOKENS" -> "토큰이 없어요. 휴대폰에서 토큰을 받은 뒤 다시 눌러 주세요."
            error is DjTrotApiException && error.code == "DAILY_LIMIT" -> error.message ?: "오늘은 더 만들 수 없어요."
            error is DjTrotApiException && error.status == 503 -> "지금은 작곡실이 쉬고 있어요. 잠시 뒤 다시 눌러 주세요."
            else -> "노래를 맡기지 못했어요. 잠시 뒤 다시 눌러 주세요."
        }
    }
}
