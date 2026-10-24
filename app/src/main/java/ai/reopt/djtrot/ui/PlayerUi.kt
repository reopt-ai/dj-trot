package ai.reopt.djtrot.ui

import ai.reopt.djtrot.playback.Catalog
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import kotlinx.coroutines.delay

data class PlayerUi(
    val mediaId: String? = null,
    val songId: String? = null,
    val title: String? = null,
    val singer: String? = null,
    val style: String? = null,
    val artworkUri: Uri? = null,
    /** 이 드라이브에서 신청해 만든 노래인가. */
    val made: Boolean = false,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val hasItems: Boolean = false,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    /** 대기열 안의 위치(1부터)와 전체 곡 수. */
    val position: Int = 0,
    val count: Int = 0,
    val nextSongId: String? = null,
    val nextTitle: String? = null,
    val nextSinger: String? = null,
    val nextStyle: String? = null,
    val nextArtworkUri: Uri? = null,
    val nextMade: Boolean = false,
)

private fun Player?.toUi(): PlayerUi {
    val player = this ?: return PlayerUi()
    val meta = player.mediaMetadata
    val extras = meta.extras
    val next = if (player.hasNextMediaItem()) player.getMediaItemAt(player.nextMediaItemIndex).mediaMetadata else null
    return PlayerUi(
        mediaId = player.currentMediaItem?.mediaId,
        songId = extras?.getString(Catalog.EXTRA_SONG_ID),
        title = meta.title?.toString(),
        singer = meta.artist?.toString(),
        style = extras?.getString(Catalog.EXTRA_STYLE),
        artworkUri = meta.artworkUri,
        made = extras?.getBoolean(Catalog.EXTRA_MADE) == true,
        playing = player.isPlaying,
        loading = player.playbackState == Player.STATE_BUFFERING,
        hasItems = player.mediaItemCount > 0,
        hasNext = player.hasNextMediaItem(),
        hasPrevious = player.hasPreviousMediaItem(),
        position = if (player.mediaItemCount > 0) player.currentMediaItemIndex + 1 else 0,
        count = player.mediaItemCount,
        nextSongId = next?.extras?.getString(Catalog.EXTRA_SONG_ID),
        nextTitle = next?.title?.toString(),
        nextSinger = next?.artist?.toString(),
        nextStyle = next?.extras?.getString(Catalog.EXTRA_STYLE),
        nextArtworkUri = next?.artworkUri,
        nextMade = next?.extras?.getBoolean(Catalog.EXTRA_MADE) == true,
    )
}

/** 컨트롤러의 상태를 Compose 상태로 옮긴다. */
@Composable
fun rememberPlayerUi(player: Player?): PlayerUi {
    var state by remember(player) { mutableStateOf(player.toUi()) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { state = player.toUi() }
        }
        player?.addListener(listener)
        state = player.toUi()
        onDispose { player?.removeListener(listener) }
    }
    return state
}

/** 재생 위치(ms)와 곡 길이(ms). 재생 중에만 자주 읽는다. */
data class Progress(val positionMs: Long = 0, val durationMs: Long = 0) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

@Composable
fun rememberProgress(player: Player?, ui: PlayerUi, intervalMs: Long = 250): Progress {
    var position by remember(player) { mutableLongStateOf(0L) }
    var duration by remember(player) { mutableLongStateOf(0L) }
    LaunchedEffect(player, ui.mediaId, ui.playing, intervalMs) {
        while (player != null) {
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
            if (!ui.playing) break
            delay(intervalMs)
        }
    }
    return Progress(position, duration)
}
