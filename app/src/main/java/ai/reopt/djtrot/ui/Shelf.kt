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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.blur
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

/** 오른쪽 열 — 위에는 DJ([top]), 아래는 노래 고르기. 주행 중에는 노래 고르기 대신 짧은 안내. */
@Composable
fun RightRail(app: DjTrotApp, shelf: ShelfMemory, driving: Boolean, ui: PlayerUi, player: Player?, modifier: Modifier, top: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        top()
        LockableShelf(app, shelf, driving, ui, player, Modifier.weight(1f))
    }
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
    val guestOpen by app.creationGuest.collectAsStateWithLifecycle()
    // 🔑 시연 모드면 연결하지 않아도 이 차가 시연으로 만든 곡이 내 노래에 보인다.
    val connected = connection is Connection.Connected || guestOpen

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
        if (player.isPlaying) player.pause() else {
            // 오래 멈춰 두면 서비스가 재생기를 내려놓는다(IDLE) — 다시 준비하고 튼다.
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
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

/** 주행 중 잠시 열어 두는 시간. 지나면 저절로 다시 잠근다. */
private const val PEEK_MS = 20_000L

/**
 * 노래 고르기 — 주행 중에는 목록 위에 반투명 잠금 막을 덮는다. 목록은 흐릿하게 비쳐 "여기에 노래가 있고 지금은 잠겨 있다"가 한눈에 보인다.
 * 🔑 "잠시 열기"를 누르면 [PEEK_MS] 동안 목록을 쓸 수 있고, 시간이 지나거나 "다시 잠그기"를 누르면 다시 덮는다. 정차하면 잠금이 저절로 풀린다.
 * ⚠️ 주행 중 목록 고르기는 운전자의 선택으로만 연다 — 기본은 잠금이고, 열어 둔 채로 남지 않는다.
 */
@Composable
private fun LockableShelf(app: DjTrotApp, shelf: ShelfMemory, driving: Boolean, ui: PlayerUi, player: Player?, modifier: Modifier) {
    var peekAt by remember { mutableLongStateOf(0L) }
    val peeking = driving && peekAt > 0L
    LaunchedEffect(peekAt, driving) {
        if (!driving) peekAt = 0L
        if (peekAt == 0L) return@LaunchedEffect
        delay((peekAt + PEEK_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        peekAt = 0L
    }
    val locked = driving && !peeking
    val blur by animateDpAsState(if (locked) 14.dp else 0.dp, tween(380), label = "shelfBlur")
    Box(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxSize()) {
            if (peeking) {
                PeekBar(onLock = { peekAt = 0L })
                Spacer(Modifier.height(14.dp))
            }
            SongShelf(app, shelf, ui, player, Modifier.weight(1f).fillMaxWidth().blur(blur))
        }
        AnimatedVisibility(locked, enter = fadeIn(tween(380)), exit = fadeOut(tween(220)), modifier = Modifier.matchParentSize()) {
            LockVeil(ui, onPeek = { peekAt = SystemClock.elapsedRealtime() })
        }
    }
}

/** 잠금 막 — 가운데 금빛 자물쇠, 안내, "잠시 열기". 아래에는 다음 곡 한 줄. 막 아래 목록은 누를 수 없다. */
@Composable
private fun LockVeil(ui: PlayerUi, onPeek: () -> Unit) {
    val shape = RoundedCornerShape(30.dp)
    val swallow = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .background(Brush.verticalGradient(0f to Color(0x99090806), 0.45f to Color(0xD9090806), 1f to Color(0xF2090806)))
            .border(1.5.dp, DjColors.Line, shape)
            .clickable(interactionSource = swallow, indication = null, onClick = {})
            .padding(horizontal = 40.dp, vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        // 금빛 자물쇠 — 바깥 고리는 은은한 빛, 안은 새틴 금.
        Box(
            Modifier
                .size(150.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(DjColors.Gold.copy(alpha = 0.28f), Color.Transparent))),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(104.dp)
                    .shadow(20.dp, CircleShape, ambientColor = DjColors.Gold, spotColor = DjColors.GoldDeep)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(DjColors.Gold2, DjColors.Gold, DjColors.GoldDeep)))
                    .border(2.dp, DjColors.SatinEdge, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(DjIcons.Lock, contentDescription = null, tint = DjColors.OnGold, modifier = Modifier.size(50.dp))
            }
        }
        Spacer(Modifier.height(22.dp))
        Text("주행 중 목록 잠금", style = headline(DjType.TitleM, DjColors.Ink), maxLines = 1)
        Spacer(Modifier.height(10.dp))
        Text("곡 넘기기는 스티어링 휠 버튼이나 “트롯 틀어줘”로 해 주세요.", style = body(DjType.BodyS, DjColors.Muted), textAlign = TextAlign.Center)
        Spacer(Modifier.height(26.dp))
        Pill(DjIcons.Lock, "잠시 열기 · 20초", onClick = onPeek)
        Spacer(Modifier.weight(1f))
        if (ui.nextTitle != null) {
            Row(
                Modifier.fillMaxWidth().djCard(radius = 24.dp, background = DjColors.Card.copy(alpha = 0.9f)).padding(horizontal = 24.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(ui.nextArtworkUri, ui.nextStyle, size = 84.dp, radius = 16.dp, seed = ui.nextSongId)
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (ui.nextMade) {
                            Icon(DjIcons.Sparkle, contentDescription = null, tint = DjColors.Gold, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (ui.nextMade) "다음은 생성곡" else "다음 곡", style = body(DjType.LabelM, DjColors.Gold, FontWeight.Bold))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(ui.nextTitle, style = headline(DjType.BodyL, DjColors.Ink, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 잠시 열어 둔 동안 목록 위에 붙는 띠 — 곧 다시 잠근다는 안내와 바로 잠그는 버튼. 주행 중이라 줄어드는 숫자는 쓰지 않는다. */
@Composable
private fun PeekBar(onLock: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().djCard(radius = 22.dp, background = DjColors.Selected, line = DjColors.Gold.copy(alpha = 0.6f)).padding(start = 24.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(DjIcons.Car, contentDescription = null, tint = DjColors.Gold, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(12.dp))
        Text("목록 잠시 열림 · 20초 뒤 다시 잠금", style = body(DjType.LabelM, DjColors.Gold2, FontWeight.SemiBold), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Pill(DjIcons.Lock, "다시 잠그기", onClick = onLock)
    }
}
