package ai.reopt.djtrot.ui

import ai.reopt.djtrot.DjTrotApp
import ai.reopt.djtrot.account.Connection
import ai.reopt.djtrot.car.DriveContext
import ai.reopt.djtrot.dj.Order
import ai.reopt.djtrot.dj.toInput
import android.content.Intent
import android.os.SystemClock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import ai.reopt.djtrot.dj.SongRequest
import ai.reopt.djtrot.playback.Catalog
import ai.reopt.djtrot.playback.Shelf
import androidx.media3.common.C
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/** 오른쪽 열 — 신청곡 사연 위, 노래 고르기 아래. 주행 중에는 노래 고르기 대신 짧은 안내. */
@Composable
fun RightRail(app: DjTrotApp, shelf: ShelfMemory, request: SongRequest, drive: DriveContext, driving: Boolean, ui: PlayerUi, player: Player?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        RequestCard(app, request, drive, driving, ui.songId)
        if (driving) {
            DrivingCard(ui, Modifier.weight(1f))
        } else {
            SongShelf(app, shelf, ui, player, Modifier.weight(1f))
        }
    }
}

// ── 신청곡 사연 ─────────────────────────────────────────

/**
 * 이 길의 신청곡 — DJ 브리프(AI)가 제안한 사연과 장르. 휴대폰을 연결하면 토큰 1개로 이 사연의 노래를 만든다.
 * 🔴 휴대폰 연결(QR 로그인)과 만들기는 정차 중에만 연다 — Pleos 정책상 QR 로그인은 모든 지역에서 주행 중 금지.
 * 🔑 작곡은 약 2분. 앱 범위에서 지켜보다 완성되면 재생 서비스가 지금 곡 다음에 넣는다.
 * 🔑 맡긴 뒤로는 맡긴 사연을 보인다 — 그 사이 곡이 바뀌어 DJ가 새 사연을 제안해도 카드가 다른 노래를 말하지 않게.
 */
@Composable
fun RequestCard(app: DjTrotApp, suggestion: SongRequest, drive: DriveContext, driving: Boolean, nowSongId: String?) {
    val connection by app.account.connection.collectAsStateWithLifecycle()
    val tokens by app.orders.tokens.collectAsStateWithLifecycle()
    val order by app.orders.current.collectAsStateWithLifecycle()
    val hearing by app.mic.state.collectAsStateWithLifecycle()
    val dj by app.dj.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 화면을 떠나면 그만 듣는다. 그때까지 들은 말은 사연으로 남는다.
    DisposableEffect(Unit) { onDispose { app.mic.stop() } }
    val connected = connection is Connection.Connected
    val parked = !driving && !drive.noSetup
    val request = order?.request ?: suggestion
    Column(
        Modifier
            .fillMaxWidth()
            .djCard(radius = 30.dp)
            .background(Brush.verticalGradient(listOf(DjColors.Cream.copy(alpha = 0.55f), Color.Transparent)))
            .padding(horizontal = 34.dp, vertical = 30.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(DjIcons.Sparkle, contentDescription = null, tint = DjColors.Gold, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(12.dp))
            Text("이 길의 생성곡", style = body(DjType.BodyS, DjColors.Gold, FontWeight.Bold))
            Spacer(Modifier.weight(1f))
            Tag(request.topicLabel)
            Spacer(Modifier.width(10.dp))
            Tag(request.styleLabel)
        }
        Spacer(Modifier.height(18.dp))
        val current = order
        when {
            hearing.listening && (driving || hearing.heard.isBlank()) -> Text("듣고 있어요. 사연을 말씀해 주세요", style = body(DjType.BodyM, DjColors.Gold2))
            hearing.listening -> Text("“${hearing.heard}”", style = headline(DjType.TitleS, DjColors.Gold2, FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            driving -> Text("정차하면 사연을 보여 드릴게요", style = body(DjType.BodyM, DjColors.Muted))
            else -> Text("“${request.story}”", style = headline(DjType.TitleS, DjColors.Ink, FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        // 🔑 말로 넣는 사연(Pleos SpeechToText) — 주행 중에도 손 대신 말로 넣을 수 있다. 맡긴 뒤에는 사연을 바꾸지 않는다.
        if (current == null) {
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(
                    icon = DjIcons.Mic,
                    text = when {
                        hearing.listening -> "다 말했어요"
                        dj.freshSpoken() != null -> "사연 다시 말하기"
                        else -> "말로 사연 넣기"
                    },
                    active = hearing.listening,
                    onClick = { if (hearing.listening) app.mic.stop() else app.mic.listen() },
                )
                hearing.problem?.let { problem ->
                    Spacer(Modifier.width(16.dp))
                    Text(problem, style = body(DjType.LabelS, DjColors.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        when {
            current != null && current.working -> OrderProgress(current)
            current != null && current.state == "ready" -> {
                val title = current.song?.title.orEmpty()
                val text = if (current.song?.id == nowSongId) "‘$title’ 지금 들려 드리고 있어요" else "‘$title’ 완성! 다음 곡으로 들려 드려요"
                OrderNote(DjIcons.Sparkle, text, onClose = app.orders::dismiss)
            }
            current != null && current.state == "failed" -> ErrorNotice(current.problem ?: "노래를 만들지 못했어요.", action = "닫기", onAction = app.orders::dismiss)
            !connected -> RequestAction(
                icon = DjIcons.Phone,
                text = "휴대폰 연결하고 만들기",
                hint = if (parked) "PIN·QR로 1분" else "정차하면 연결할 수 있어요",
                enabled = parked,
            ) { context.startActivity(Intent(context, ConnectActivity::class.java)) }
            else -> RequestAction(
                icon = DjIcons.Sparkle,
                text = "이 사연으로 노래 만들기",
                hint = when {
                    !parked -> "정차하면 만들 수 있어요"
                    tokens == 0 -> "토큰이 없어요 · 휴대폰에서 받아 주세요"
                    tokens != null -> "토큰 1개 · ${tokens}개 남음"
                    else -> "토큰 1개"
                },
                enabled = parked && tokens != 0,
                gold = true,
            ) {
                app.orders.create(request, drive.toInput(LocalTime.now(), app.spoken))
                app.dj.update { it.forget() }
            }
        }
    }
}

/** 신청곡 카드의 큰 버튼. 금빛은 만들기, 테두리는 연결. 누를 수 없을 때는 이유를 오른쪽에 적는다. */
@Composable
private fun RequestAction(icon: ImageVector, text: String, hint: String, enabled: Boolean, gold: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val source = remember { MutableInteractionSource() }
    val active = enabled && gold
    Row(
        Modifier
            .then(if (enabled) Modifier.pressScale(source, depth = 0.98f) else Modifier)
            .fillMaxWidth()
            .height(86.dp)
            .clip(shape)
            .background(if (active) DjColors.Selected else DjColors.CardRaised)
            .border(1.5.dp, if (active) DjColors.Gold.copy(alpha = 0.8f) else DjColors.LineStrong, shape)
            .tap(source, enabled = enabled, label = text, onClick = onClick)
            .padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (enabled) icon else DjIcons.Lock, contentDescription = null, tint = if (enabled) DjColors.Gold else DjColors.Faint, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, style = body(DjType.BodyS, if (enabled) DjColors.Gold2 else DjColors.Muted, FontWeight.Bold), modifier = Modifier.weight(1f), maxLines = 1)
        Text(hint, style = body(DjType.LabelS, DjColors.Muted), maxLines = 1)
    }
}

/**
 * 작곡 진행 — 맡김 → 차례 → 작곡. 기다린 시간을 초 단위로 센다(약 2분).
 * 🔑 주행 중에는 바뀌는 숫자 대신 "약 2분"으로 둔다.
 */
@Composable
private fun OrderProgress(order: Order) {
    val calm = LocalCalm.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(order.since, calm) {
        while (!calm) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val waited = ((now - order.since) / 1000).coerceAtLeast(0)
    val label = when {
        order.state == "sending" -> "작곡실에 맡기는 중"
        calm -> "작곡 중이에요 · 약 2분"
        order.state == "queued" -> "작곡 차례를 기다려요 · %d:%02d".format(waited / 60, waited % 60)
        else -> "작곡 중이에요 · %d:%02d".format(waited / 60, waited % 60)
    }
    Row(
        Modifier.fillMaxWidth().height(86.dp).djCard(radius = 20.dp, background = DjColors.Selected, line = DjColors.Gold.copy(alpha = 0.6f)).padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(color = DjColors.Gold, strokeWidth = 4.dp, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, style = body(DjType.BodyS, DjColors.Gold2, FontWeight.Bold), modifier = Modifier.weight(1f), maxLines = 1)
        Text("완성되면 바로 틀어요", style = body(DjType.LabelS, DjColors.Muted), maxLines = 1)
    }
}

@Composable
private fun OrderNote(icon: ImageVector, text: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(86.dp).djCard(radius = 20.dp, background = DjColors.Selected, line = DjColors.Gold.copy(alpha = 0.8f)).padding(start = 26.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = DjColors.Gold, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, style = body(DjType.BodyS, DjColors.Gold2, FontWeight.Bold), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Pill(null, "닫기", onClick = onClose)
    }
}

@Composable
private fun Tag(text: String) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        text,
        style = body(DjType.LabelS, DjColors.Gold2, FontWeight.SemiBold),
        maxLines = 1,
        modifier = Modifier.clip(shape).background(DjColors.Cream).border(1.dp, DjColors.LineStrong, shape).padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

// ── 노래 고르기 ────────────────────────────────────────

sealed interface ShelfState {
    data object Loading : ShelfState
    data class Ready(val items: List<MediaItem>) : ShelfState
    data object Failed : ShelfState
}

/**
 * 노래 고르기의 고른 탭과 받아 둔 목록. 탭은 브라우즈 트리 루트의 네 폴더([Shelf])와 같다.
 * 화면 밖으로 나갔다 와도(좁은 창의 탭 전환) 다시 받지 않게 위에서 들고 있는다.
 */
class ShelfMemory {
    var tab by mutableStateOf(Shelf.PICKS)
    val states = mutableStateMapOf<Shelf, ShelfState>()
}

@Composable
fun rememberShelf() = remember { ShelfMemory() }

@Composable
fun SongShelf(app: DjTrotApp, shelf: ShelfMemory, ui: PlayerUi, player: Player?, modifier: Modifier) {
    val tab = shelf.tab
    val states = shelf.states
    var retry by remember { mutableIntStateOf(0) }
    val connection by app.account.connection.collectAsStateWithLifecycle()
    val order by app.orders.current.collectAsStateWithLifecycle()
    val connected = connection is Connection.Connected

    // 새 노래가 완성되거나 연결이 바뀌면 내 노래를 다시 받는다.
    LaunchedEffect(order?.state == "ready", connected) { states.remove(Shelf.MINE) }
    LaunchedEffect(tab, retry, connected, states[Shelf.MINE] == null) {
        if (tab == Shelf.MINE && !connected) return@LaunchedEffect
        if (states[tab] is ShelfState.Ready) return@LaunchedEffect
        states[tab] = ShelfState.Loading
        states[tab] = runCatching { app.catalog.children(tab) }
            .fold({ ShelfState.Ready(it) }, { ShelfState.Failed })
    }

    Column(modifier) {
        Segmented(Shelf.entries, tab, { it.label }, { shelf.tab = it })
        Spacer(Modifier.height(18.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val state = if (tab == Shelf.MINE && !connected) ShelfState.Ready(emptyList()) else states[tab] ?: ShelfState.Loading) {
                ShelfState.Loading -> LoadingRows()
                ShelfState.Failed -> ErrorNotice("노래 목록을 받지 못했어요.", action = "다시 받기", onAction = { retry++ })
                is ShelfState.Ready -> when {
                    tab == Shelf.MINE && !connected -> EmptyShelf("휴대폰을 연결하면 길에서 만든 노래가 여기 모여요")
                    state.items.isEmpty() && tab == Shelf.MINE -> EmptyShelf("아직 만든 노래가 없어요. 이 길의 생성곡을 만들어 보세요")
                    state.items.isEmpty() -> EmptyShelf("노래 목록을 준비하고 있어요. 곧 무대에 올라와요")
                    tab == Shelf.STYLES -> StyleGrid(state.items) { id -> player?.let { playList(it, id) } }
                    else -> SongList(state.items, ui) { index -> playFrom(player, ui, state.items, index) }
                }
            }
        }
    }
}

/**
 * 고른 곡부터 보이는 목록을 이어 튼다. 지금 곡을 다시 누르면 처음부터 다시 틀지 않고 멈춤·재생만 바꾼다.
 * 🔑 목록을 통째로 큐로 보낸다 — 같은 곡이 여러 탭에 있어도 누른 탭의 순서대로 이어진다.
 */
private fun playFrom(player: Player?, ui: PlayerUi, items: List<MediaItem>, index: Int) {
    player ?: return
    val picked = items.getOrNull(index) ?: return
    if (picked.mediaId == ui.mediaId) {
        if (player.isPlaying) player.pause() else player.play()
        return
    }
    player.setMediaItems(items.map { MediaItem.Builder().setMediaId(it.mediaId).build() }, index, C.TIME_UNSET)
    player.prepare()
    player.play()
}

/** 장르 카드나 "DJ 트롯 시작"처럼 목록 자체를 누르면 그 목록을 처음부터 튼다. 곡은 서비스가 채운다. */
internal fun playList(player: Player, listId: String) {
    player.setMediaItem(MediaItem.Builder().setMediaId(listId).build())
    player.prepare()
    player.play()
}

/**
 * 좁은 창 신청곡 칸 아래 — 연결했으면 길에서 만든 노래, 아직이면 만드는 순서.
 * 🔑 넓은 창은 오른쪽 열의 '내 노래' 탭이 같은 일을 한다.
 */
@Composable
fun MadeSongs(app: DjTrotApp, ui: PlayerUi, player: Player?, modifier: Modifier) {
    val connection by app.account.connection.collectAsStateWithLifecycle()
    val order by app.orders.current.collectAsStateWithLifecycle()
    val connected = connection is Connection.Connected
    // 새 노래가 완성되거나 연결이 바뀌면 다시 받는다.
    val songs by produceState<List<MediaItem>?>(null, connected, order?.state == "ready") {
        value = if (connected) runCatching { app.catalog.children(Shelf.MINE) }.getOrDefault(emptyList()) else emptyList()
    }
    Column(modifier.fillMaxWidth().djCard(radius = 30.dp).padding(start = 34.dp, end = 20.dp, top = 30.dp)) {
        Text(if (connected) "길에서 만든 노래" else "이렇게 만들어져요", style = body(DjType.BodyS, DjColors.Gold, FontWeight.Bold))
        Spacer(Modifier.height(12.dp))
        val list = songs
        when {
            !connected -> Column(Modifier.padding(end = 14.dp)) {
                Step(1, "정차 중에 휴대폰을 연결해요 · PIN으로 1분")
                Step(2, "이 길의 사연으로 노래를 맡겨요 · 토큰 1개")
                Step(3, "약 2분 뒤 다음 곡으로 들려 드려요")
            }
            list == null -> Unit
            list.isEmpty() -> Text("아직 만든 노래가 없어요. 위 사연으로 첫 노래를 만들어 보세요", style = body(DjType.BodyS, DjColors.Muted), modifier = Modifier.padding(end = 14.dp))
            else -> Box(Modifier.weight(1f)) {
                SongList(list, ui, fade = DjColors.Card) { index -> playFrom(player, ui, list, index) }
            }
        }
    }
}

@Composable
private fun SongList(items: List<MediaItem>, ui: PlayerUi, fade: Color = DjColors.Black, onPlay: (index: Int) -> Unit) {
    val list = rememberLazyListState()
    // 지금 곡이 목록 밖에 있으면 한 줄 위까지 보이게 굴린다.
    LaunchedEffect(ui.mediaId, items) {
        val index = items.indexOfFirst { it.mediaId == ui.mediaId }
        if (index < 0) return@LaunchedEffect
        val visible = list.layoutInfo.visibleItemsInfo
        val fullyVisible = visible.any { it.index == index && it.offset >= 0 && it.offset + it.size <= list.layoutInfo.viewportEndOffset }
        if (!fullyVisible) list.animateScrollToItem((index - 1).coerceAtLeast(0))
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.scrollbar(list), state = list, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(end = 18.dp, bottom = 60.dp)) {  // 오른쪽 18dp는 스크롤 막대 자리
            itemsIndexed(items, key = { _, item -> item.mediaId }) { index, item ->
                SongRow(item, current = item.mediaId == ui.mediaId, playing = ui.playing) { onPlay(index) }
                if (index < items.lastIndex) Box(Modifier.padding(start = 124.dp, end = 16.dp).fillMaxWidth().height(1.dp).background(DjColors.Line))
            }
        }
        // 목록이 아래로 이어진다는 표시 — 잘린 줄 대신 검정으로 스며든다.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(90.dp).background(Brush.verticalGradient(listOf(Color.Transparent, fade))))
    }
}

/**
 * 곡 한 줄 — 줄 전체가 누르는 자리다.
 * 지금 곡은 금빛 바탕에 금빛 원 버튼(멈춤·재생)과 막대, 나머지는 어두운 테의 원 재생 버튼.
 * 🔑 금빛은 지금 곡에만 — 줄마다 금테가 서면 무대의 재생 버튼보다 목록이 먼저 눈에 든다.
 * 🔑 부제는 주제 · 장르. 제공 곡은 모두 같은 악단이라 가수 이름은 고르는 데 쓸모가 없다.
 */
@Composable
private fun SongRow(item: MediaItem, current: Boolean, playing: Boolean, onPlay: () -> Unit) {
    val meta = item.mediaMetadata
    val style = meta.extras?.getString(Catalog.EXTRA_STYLE)
    val topic = meta.extras?.getString(Catalog.EXTRA_TOPIC_LABEL)
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .pressScale(source, depth = 0.985f)
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (current) DjColors.Cream else Color.Transparent)
            .then(if (current) Modifier.border(1.5.dp, DjColors.LineStrong, RoundedCornerShape(22.dp)) else Modifier)
            .tap(source, label = if (current && playing) "일시정지" else "재생", onClick = onPlay)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(meta.artworkUri, style, size = 88.dp, radius = 16.dp, seed = meta.extras?.getString(Catalog.EXTRA_SONG_ID))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (current) {
                    Equalizer(playing, height = 22.dp)
                    Spacer(Modifier.width(12.dp))
                }
                Text(meta.title?.toString().orEmpty(), style = headline(DjType.BodyL, if (current) DjColors.Gold2 else DjColors.Ink, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                listOfNotNull(topic ?: meta.artist?.toString(), meta.albumTitle?.toString()).joinToString(" · "),
                style = body(DjType.LabelM, DjColors.Muted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        meta.durationMs?.takeIf { it > 0 }?.let {
            Spacer(Modifier.width(12.dp))
            Text(clock(it), style = digits(DjType.LabelM, DjColors.Faint))
        }
        Spacer(Modifier.width(18.dp))
        if (current) {
            Box(
                Modifier.size(74.dp).clip(CircleShape).background(Brush.linearGradient(listOf(DjColors.Gold2, DjColors.Gold, DjColors.GoldDeep))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (playing) DjIcons.Pause else DjIcons.Play, contentDescription = null, tint = DjColors.OnGold, modifier = Modifier.size(36.dp))
            }
        } else {
            Box(
                Modifier.size(74.dp).clip(CircleShape).border(1.5.dp, DjColors.LineStrong, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(DjIcons.Play, contentDescription = null, tint = DjColors.Gold, modifier = Modifier.size(34.dp))
            }
        }
    }
}

/** 테마 선곡 — 장르 공연 장면을 깐 카드. 누르면 그 목록을 처음부터 튼다. */
@Composable
private fun StyleGrid(items: List<MediaItem>, onPlay: (String) -> Unit) = BoxWithConstraints {
    // 두 줄이면 남는 높이를 나눠 채운다. 그보다 많으면 고정 높이로 굴린다.
    val rows = (items.size + 1) / 2
    val cardHeight = if (rows <= 2) ((maxHeight - 18.dp) / 2).coerceAtLeast(250.dp) else 250.dp
    LazyVerticalGrid(GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        items(items, key = { it.mediaId }) { item ->
            val title = item.mediaMetadata.title?.toString().orEmpty()
            val shape = RoundedCornerShape(24.dp)
            val source = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .pressScale(source, depth = 0.97f)
                    .fillMaxWidth()
                    .height(cardHeight)
                    .clip(shape)
                    .border(1.5.dp, DjColors.LineStrong, shape)
                    .tap(source, label = "$title 틀기") { onPlay(item.mediaId) },
            ) {
                Image(painterResource(Art.stage(item.mediaMetadata.extras?.getString(Catalog.EXTRA_STYLE))), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x10000000), 0.45f to Color(0x66000000), 1f to Color(0xF2050505))))
                item.mediaMetadata.totalTrackCount?.takeIf { it > 0 }?.let { count ->
                    val chip = RoundedCornerShape(10.dp)
                    Row(
                        Modifier.padding(18.dp).align(Alignment.TopStart).clip(chip).background(Color(0xB8090806)).border(1.dp, DjColors.LineStrong, chip).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(DjIcons.Note, contentDescription = null, tint = DjColors.Gold, modifier = Modifier.size(20.dp))
                        Text("${count}곡", style = digits(DjType.LabelS, DjColors.Gold2).copy(fontWeight = FontWeight.SemiBold))
                    }
                }
                Box(
                    Modifier.padding(18.dp).size(64.dp).align(Alignment.TopEnd).clip(CircleShape).background(Color(0xB8090806)).border(1.5.dp, DjColors.Gold.copy(alpha = 0.65f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(DjIcons.Play, contentDescription = "재생", tint = DjColors.Gold2, modifier = Modifier.size(34.dp))
                }
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 22.dp, vertical = 20.dp)) {
                    Text(title, style = headline(DjType.BodyL), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    item.mediaMetadata.subtitle?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it.toString(), style = body(DjType.LabelS, DjColors.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingRows() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(4) {
            Row(Modifier.fillMaxWidth().height(120.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(88.dp).clip(RoundedCornerShape(16.dp)).background(DjColors.Card))
                Spacer(Modifier.width(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.width(260.dp).height(26.dp).clip(RoundedCornerShape(8.dp)).background(DjColors.Card))
                    Box(Modifier.width(170.dp).height(20.dp).clip(RoundedCornerShape(8.dp)).background(DjColors.Card))
                }
            }
        }
    }
}

@Composable
private fun EmptyShelf(text: String) {
    Column(
        Modifier.fillMaxWidth().djCard(radius = 22.dp, background = DjColors.Card).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(DjIcons.Note, contentDescription = null, tint = DjColors.Gold, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(text, style = body(DjType.BodyS, DjColors.Ink, FontWeight.SemiBold), textAlign = TextAlign.Center)
    }
}

// ── 주행 중 ────────────────────────────────────────────

/** 주행 중 — 목록 대신 큰 글씨 안내와 다음 곡 한 줄만. */
@Composable
fun DrivingCard(ui: PlayerUi, modifier: Modifier) {
    Column(modifier.fillMaxWidth().djCard(radius = 30.dp).padding(40.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(84.dp).clip(CircleShape).background(DjColors.Cream).border(1.5.dp, DjColors.LineStrong, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(DjIcons.Car, contentDescription = null, tint = DjColors.Gold, modifier = Modifier.size(46.dp))
            }
            Spacer(Modifier.width(24.dp))
            Text("운전에 집중하세요", style = headline(DjType.TitleL))
        }
        Spacer(Modifier.height(22.dp))
        Text("곡 넘기기는 스티어링 휠 버튼이나\n“트롯 틀어줘”로 해 주세요.", style = body(DjType.BodyM, DjColors.Muted))
        Spacer(Modifier.weight(1f))
        if (ui.nextTitle != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(DjColors.Line))
            Spacer(Modifier.height(30.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ui.nextMade) {
                    Icon(DjIcons.Sparkle, contentDescription = null, tint = DjColors.Gold, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (ui.nextMade) "다음은 생성곡" else "다음 곡", style = body(DjType.LabelM, DjColors.Gold, FontWeight.Bold))
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(ui.nextArtworkUri, ui.nextStyle, size = 120.dp, radius = 20.dp, seed = ui.nextSongId)
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(ui.nextTitle, style = headline(DjType.TitleM, DjColors.Ink, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ui.nextSinger?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = body(DjType.BodyS, DjColors.Gold2, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
