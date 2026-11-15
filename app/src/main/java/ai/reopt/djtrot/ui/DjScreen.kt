package ai.reopt.djtrot.ui

import ai.reopt.djtrot.BuildConfig
import ai.reopt.djtrot.DjState
import ai.reopt.djtrot.DjTrotApp
import ai.reopt.djtrot.Said
import ai.reopt.djtrot.api.Ad
import ai.reopt.djtrot.api.DjInfo
import ai.reopt.djtrot.api.Labels
import ai.reopt.djtrot.api.LyricLine
import ai.reopt.djtrot.car.DriveContext
import ai.reopt.djtrot.dj.DemoDrive
import ai.reopt.djtrot.dj.DemoStep
import ai.reopt.djtrot.dj.SongRequest
import ai.reopt.djtrot.dj.StoryBuilder
import ai.reopt.djtrot.dj.VoicePackStore
import ai.reopt.djtrot.playback.Shelf
import androidx.compose.animation.AnimatedContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlinx.coroutines.delay

/** 기준 창 — Pleos Connect 앱 영역 두 칸(가로)은 1682×1210dp(mdpi), 한 칸(세로)은 그 절반 폭이다. */
private val WideWidth = 1682.dp
private val CompactWidth = 840.dp
private val DesignHeight = 1210.dp

/** 앱 화면 전체의 모서리 — 안쪽 무대 카드(36dp)보다 살짝 작게 둥글린다. */
private val WindowCorner = 32.dp

/**
 * DJ 트롯 차량 화면 — 마스터트롯 블랙·골드 콘서트 무대.
 * 🔑 Pleos 창은 한 칸(세로) 또는 두 칸(가로)이다. 두 칸이면 2열, 한 칸이면 1열에 탭으로 다시 짠다
 *    — 2열을 그대로 줄이면 글자를 읽을 수 없다(Pleos App Structure: 세로는 핵심 기능, 가로는 추가 정보).
 * 🔑 각 배치의 기준 창 크기로 그리고, 창이 그보다 작거나 크면 같은 비율로 맞춘다(글자·간격 모두).
 * 🔑 주행 중(CarUxRestrictions)에는 목록·사연 본문을 숨기고, 재생 조작과 짧은 안내만 남긴다.
 */
@Composable
fun DjScreen(app: DjTrotApp, player: Player?) {
    // 🔑 앱 화면 전체의 모서리를 둥글린다 — 창 바탕이 비어 있어(Theme.DjTrot.Stage) 모서리 밖으로 시스템 바탕이 보인다.
    BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(WindowCorner)).background(DjColors.Black)) {
        val compact = maxWidth < maxHeight || maxWidth < 1200.dp
        val scale = minOf(maxWidth / (if (compact) CompactWidth else WideWidth), maxHeight / DesignHeight).coerceIn(0.6f, 1.25f)
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
            DjContent(app, player, compact)
        }
    }
}

/**
 * 화면 칸. 넓은 창은 무대 열 아래에 [재생 | 트롯생성 | 설정]만 두고 DJ는 오른쪽 열 위에 늘 보인다. 좁은 창은 모든 칸을 아래 탭으로 오간다.
 * 🔑 탭은 화면 아래에 둔다 — 운전석에서 손이 닿기 쉬운 쪽이고, Pleos GNB와 같은 높이 흐름이다.
 */
private enum class Section(val label: String, val icon: ImageVector) {
    PLAY("재생", DjIcons.Disc),

    /** 트롯생성 — 이 길의 사연으로 노래를 만든다(이 길의 생성곡 카드, 사연의 재료, 만든 노래). */
    CREATE("트롯생성", DjIcons.Sparkle),
    SONGS("노래", DjIcons.Queue),

    /** DJ — 좁은 창에서만 탭이다. 넓은 창에서는 오른쪽 열 위에 DJ가 늘 보인다([DjCard]). */
    DJ("DJ", DjIcons.Mic),

    /** 설정 — 길 위의 상황을 버튼으로 흉내 내는 시연과 DJ 멘트 살펴보기(공모전·전시용, `BuildConfig.DEMO`). */
    DEMO("설정", DjIcons.Settings),
}

/** 무대(재생·DJ 칸)가 그리는 것과 할 수 있는 일. */
private class StageModel(
    val app: DjTrotApp,
    /** 지금 말하는 DJ의 이름 — DJ 칸의 머리에 보인다. */
    val djName: String,
    /** 지금 DJ의 ID — 얼굴 그림을 고른다. */
    val djId: String,
    /** 이 길의 생성곡 제안 — 트롯생성 칸이 보여 준다. */
    val request: SongRequest,
    /** 지금 나오는 곡이 광고면 그 광고. */
    val ad: Ad?,
    /** 광고 옆에 붙는 한마디 — 생성권까지의 진행. 휴대폰을 연결하지 않았으면 null. */
    val adNote: String?,
    val ui: PlayerUi,
    val progress: Progress,
    val dj: DjState,
    val lyrics: List<LyricLine>,
    val drive: DriveContext,
    val driving: Boolean,
    /** 넓은 창 — 무대가 커서 가사를 한 단계 크게 쓴다. */
    val roomy: Boolean,
    val talks: Boolean,
    val onToggleTalks: () -> Unit,
    val onReplay: () -> Unit,
    /** 시연이 덧씌운 상황. 시연 중이 아니면 null. */
    val demo: DemoDrive?,
    val onDemo: (DemoStep) -> Unit,
    val onEndDemo: () -> Unit,
    val demoLines: () -> List<VoicePackStore.Fragment>,
    val onDemoLine: (String) -> Unit,
)

@Composable
private fun DjContent(app: DjTrotApp, player: Player?, compact: Boolean) {
    val drive by app.car.state.collectAsStateWithLifecycle()
    val dj by app.dj.collectAsStateWithLifecycle()
    val demo by app.demo.collectAsStateWithLifecycle()
    val djId by app.djId.collectAsStateWithLifecycle()
    val djs by app.djs.collectAsStateWithLifecycle()
    val ads by app.ads.list.collectAsStateWithLifecycle()
    val adProgress by app.ads.progress.collectAsStateWithLifecycle()
    val ui = rememberPlayerUi(player)
    var now by remember { mutableStateOf(LocalTime.now()) }
    var talks by remember { mutableStateOf(app.djTalks) }
    val shelf = rememberShelf()
    var section by rememberSaveable { mutableStateOf(Section.PLAY) }
    var seenLine by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(60_000)
        }
    }
    // 가사 시각은 노래(trot-song/1)에 들어 있다. 정렬되지 않은 곡은 빈 목록이다.
    val lyrics = remember(ui.songId) { app.catalog.lyrics(ui.songId) }
    // 🔑 이 길의 신청곡은 서버 DJ 브리프(AI)의 제안. 아직 없거나 오프라인이면 기기에서 쓴다.
    val spoken = dj.freshSpoken()
    val fallback = remember(drive, now, spoken) { StoryBuilder.build(drive, now, spoken) }
    val request = dj.freshSuggestion() ?: fallback
    val driving = !drive.stopped || drive.restricted
    // 가사를 칠하는 동안은 더 자주 읽어 글자가 매끄럽게 채워지게 한다.
    val progress = rememberProgress(player, ui, intervalMs = if (lyrics.isEmpty() || driving) 250 else 80)

    // 🔑 넓은 창의 노래·신청곡은 오른쪽 열에 늘 있다. 좁은 창도 주행 중에는 무대 두 칸만 연다.
    // 시연 칸은 무대 칸이라 주행 중(시연이 덧씌운 주행 포함)에도 열려 있다.
    val available = Section.entries.filter { it != Section.DEMO || BuildConfig.DEMO }
    // 🔑 넓은 창 — 무대 탭은 재생·트롯생성·설정. 노래 고르기와 DJ는 오른쪽 열에 늘 있다.
    //    좁은 창 — 다섯 칸을 모두 탭으로 오가고, 주행 중에는 재생·DJ·설정만 연다(트롯생성·노래 고르기는 정차 중에).
    val sections = when {
        !compact -> available.filter { it == Section.PLAY || it == Section.CREATE || it == Section.DEMO }
        driving -> available.filter { it == Section.PLAY || it == Section.DJ || it == Section.DEMO }
        else -> available
    }
    val shown = section.takeIf { it in sections } ?: Section.PLAY
    BackHandler(enabled = shown != Section.PLAY) { section = Section.PLAY }
    // DJ가 새로 한 말은 DJ 탭을 열 때까지 점으로 알린다.
    LaunchedEffect(shown, dj.line) { if (shown == Section.DJ) seenLine = dj.line }
    val fresh = dj.line != null && dj.line != seenLine && shown != Section.DJ

    val model = StageModel(
        app = app,
        djName = djs.firstOrNull { it.id == djId }?.name ?: DjInfo.DEFAULT.name,
        djId = djId,
        request = request,
        ad = ads.firstOrNull { it.song.id == ui.songId },
        adNote = adProgress?.let { progress ->
            when {
                progress.limited -> "오늘 받을 생성권을 다 받았어요"
                progress.capped -> "생성권이 가득 찼어요"
                else -> "끝까지 들으면 ${progress.progress + 1}/${progress.needed} · 생성권까지 ${progress.needed - progress.progress}번"
            }
        },
        ui = ui,
        progress = progress,
        dj = dj,
        lyrics = lyrics,
        drive = drive,
        driving = driving,
        roomy = !compact,
        talks = talks,
        onToggleTalks = {
            talks = !talks
            app.djTalks = talks
            if (!talks) app.voice.stop()
        },
        onReplay = { dj.line?.let(app.voice::speak) },
        demo = demo,
        onDemo = app::demo,
        onEndDemo = app::endDemo,
        demoLines = app.voicePack::fragments,
        onDemoLine = { app.demoLines.tryEmit(it) },
    )

    Box(Modifier.fillMaxSize()) {
        // 위쪽에서 번지는 금빛 조명 — 무대 밖은 평평한 검정이다.
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(Brush.radialGradient(listOf(DjColors.Gold.copy(alpha = 0.10f), Color.Transparent), center = Offset(size.width * 0.3f, 0f), radius = size.width * 0.55f))
            },
        )
        CompositionLocalProvider(LocalCalm provides driving) {
            if (compact) {
                Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp)) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (shown) {
                            Section.PLAY, Section.CREATE, Section.DJ, Section.DEMO -> StagePanel(shown, model, player, Modifier.fillMaxSize())
                            Section.SONGS -> SongShelf(app, shelf, ui, player, Modifier.fillMaxSize())
                        }
                    }
                    if (shown == Section.SONGS) {
                        MiniPlayer(ui, player, onOpen = { section = Section.PLAY }, Modifier.padding(top = 16.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    BottomTabs(sections, shown, fresh, onSelect = { section = it })
                }
            } else {
                Row(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Column(Modifier.weight(1.14f).fillMaxHeight()) {
                        StagePanel(shown, model, player, Modifier.weight(1f).fillMaxWidth())
                        Spacer(Modifier.height(18.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Brand()
                            Spacer(Modifier.width(24.dp))
                            BottomTabs(sections, shown, fresh, onSelect = { section = it }, Modifier.weight(1f))
                        }
                    }
                    RightRail(app, shelf, driving, ui, player, Modifier.weight(1f).fillMaxHeight()) { DjCard(model) }
                }
            }
        }
    }
}

// ── 아래 탭 ──────────────────────────────────────────

/** 앱 표식과 이름 — 넓은 창의 탭 왼쪽. 이름은 금빛 새틴 글자로 크게 쓴다(무대 간판처럼). */
@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppMark(80.dp)
        Spacer(Modifier.width(16.dp))
        Text(
            "DJ 트롯",
            style = headline(DjType.HeadlineS, DjColors.Gold2, FontWeight.Black).copy(
                brush = Brush.verticalGradient(listOf(DjColors.Gold2, DjColors.SatinEdge, DjColors.Gold, DjColors.GoldDeep)),
                shadow = Shadow(color = DjColors.Gold.copy(alpha = 0.45f), offset = Offset(0f, 2f), blurRadius = 18f),
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** 아래 탭 — 아이콘과 이름. DJ 탭에는 아직 보지 않은 멘트가 있으면 금빛 점이 붙는다. */
@Composable
private fun BottomTabs(sections: List<Section>, selected: Section, fresh: Boolean, onSelect: (Section) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(96.dp)
            .djCard(radius = 24.dp, background = DjColors.Segment)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        sections.forEach { section ->
            val on = section == selected
            val source = remember { MutableInteractionSource() }
            val shape = RoundedCornerShape(18.dp)
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pressScale(source, depth = 0.97f)
                    .clip(shape)
                    .background(if (on) DjColors.Selected else Color.Transparent)
                    .then(if (on) Modifier.border(1.5.dp, DjColors.LineStrong, shape) else Modifier)
                    .tap(source, label = section.label) { onSelect(section) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    Icon(section.icon, contentDescription = null, tint = if (on) DjColors.Gold2 else DjColors.Muted, modifier = Modifier.size(34.dp))
                    if (section == Section.DJ && fresh) {
                        Box(Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp).size(14.dp).clip(CircleShape).background(DjColors.Gold).border(2.dp, DjColors.Segment, CircleShape))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(section.label, style = body(DjType.BodyS, if (on) DjColors.Gold2 else DjColors.Muted, if (on) FontWeight.Bold else FontWeight.Medium))
            }
        }
    }
}

/** 좁은 창에서 노래·신청곡 칸을 보는 동안 아래에 남는 지금 곡. 누르면 재생 칸으로 간다. */
@Composable
private fun MiniPlayer(ui: PlayerUi, player: Player?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    if (player == null || !ui.hasItems) return
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .height(116.dp)
            .djCard(radius = 24.dp, background = DjColors.CardRaised, line = DjColors.LineStrong)
            .tap(source, label = "재생 칸 열기", onClick = onOpen)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(ui.artworkUri, ui.style, size = 84.dp, radius = 16.dp, seed = ui.songId)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Equalizer(ui.playing, height = 22.dp)
                Spacer(Modifier.width(12.dp))
                Text(ui.title.orEmpty(), style = headline(DjType.BodyL, DjColors.Ink, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
            Text(ui.singer.orEmpty(), style = body(DjType.LabelM, DjColors.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        PlayButton(ui.playing, ui.loading, size = 84.dp) { togglePlay(player, ui) }
        Spacer(Modifier.width(14.dp))
        RingButton(DjIcons.Next, "다음 곡", size = 76.dp, enabled = ui.hasNext) { player.seekToNextMediaItem() }
    }
}

private fun togglePlay(player: Player, ui: PlayerUi) {
    if (ui.playing) {
        player.pause()
    } else {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }
}

/** 지금 동네·남은 길·기어·배터리. */
@Composable
private fun DriveFactPills(drive: DriveContext) {
    Pill(DjIcons.Place, drive.region ?: "위치 확인 중")
    drive.destination?.let { d ->
        val rest = listOfNotNull(d.remainMinutes?.let { "${it}분" }, d.remainKm?.let { String.format(Locale.KOREA, "%.1fkm", it) })
        Pill(DjIcons.Flag, rest.joinToString(" · ").ifEmpty { d.name })
    }
    Pill(DjIcons.Car, listOfNotNull(drive.gear.label, drive.drivingMode?.let(::modeLabel)).joinToString(" · "))
    drive.batteryPercent?.let { BatteryPill(it) }
}

/** 방송 중 표시 — DJ가 진행하고 노래가 나오는 동안 금빛 점이 숨 쉰다. */
@Composable
private fun OnAirBadge(onAir: Boolean) {
    val pulse by rememberInfiniteTransition(label = "onAir").animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot")
    val calm = LocalCalm.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .height(44.dp)
            .clip(shape)
            .border(1.5.dp, if (onAir) DjColors.Gold else DjColors.Line, shape)
            .background(if (onAir) DjColors.Selected else Color.Transparent)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(12.dp).alpha(if (onAir && !calm) pulse else 1f).clip(CircleShape).background(if (onAir) DjColors.Gold2 else DjColors.Faint))
        Text("ON AIR", style = body(DjType.LabelS, if (onAir) DjColors.Gold2 else DjColors.Faint, FontWeight.ExtraBold).copy(letterSpacing = 2.sp))
    }
}

// ── 무대 ────────────────────────────────────────────

/**
 * 무대 — 장르 공연 장면 위에 재생 칸 또는 DJ 칸, 그 아래 늘 같은 자리의 진행 막대와 조작부.
 * 🔑 그림은 위쪽에만 보이고 아래로 갈수록 검정에 묻힌다. 조작부는 평평한 검정 위다.
 * 🔑 재생·DJ 칸을 오가도 조작부는 움직이지 않는다 — 손이 기억한 자리에 늘 있어야 한다. 설정 칸에는 조작부가 없다.
 */
@Composable
private fun StagePanel(view: Section, m: StageModel, player: Player?, modifier: Modifier) {
    val shape = RoundedCornerShape(36.dp)
    val live = player != null && m.ui.hasItems
    Box(modifier.clip(shape).background(DjColors.Paper).border(1.5.dp, DjColors.LineStrong, shape)) {
        Crossfade(targetState = if (m.ui.hasItems) Art.stage(m.ui.style) else Art.stage(null), animationSpec = tween(700), label = "stage") { art ->
            // 살짝 흐려 글자 뒤로 물린다(무대의 심도). 640px 그림을 키운 거친 결도 감춘다.
            Image(painterResource(art), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(3.dp))
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0x80090806),
                    0.42f to Color(0xE6090806),
                    0.62f to Color(0xFA090806),
                    1f to Color(0xFF090806),
                ),
            ),
        )
        // 🔑 설정의 상세(시뮬레이션·멘트 살펴보기)로 들어가면 무대가 뒤에서 어두워진다 — 버튼과 글이 무대 조명에 묻히지 않게.
        var settingsPage by rememberSaveable { mutableStateOf(SettingsPage.HOME) }
        val dim by animateFloatAsState(if (view == Section.DEMO && settingsPage != SettingsPage.HOME) 0.72f else 0f, tween(if (LocalCalm.current) 120 else 420), label = "dim")
        if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        Column(Modifier.fillMaxSize().padding(start = 44.dp, end = 44.dp, top = 40.dp, bottom = 32.dp)) {
            Crossfade(targetState = view, animationSpec = tween(250), label = "view", modifier = Modifier.weight(1f).fillMaxWidth()) { shown ->
                when {
                    shown == Section.DJ -> DjView(m, Modifier.fillMaxSize())
                    shown == Section.CREATE -> CreateView(m, player, Modifier.fillMaxSize())
                    shown == Section.DEMO -> DemoView(
                        m.app, m.drive, m.driving, m.talks, m.onToggleTalks,
                        onStartMusic = { player?.let { if (m.ui.hasItems) { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } else playList(it, Shelf.PICKS.id) } },
                        settingsPage, { settingsPage = it }, m.demo, m.ui.playing, m.dj.line, columns = if (m.roomy) 4 else 3, onStep = m.onDemo, onEnd = m.onEndDemo, lines = m.demoLines, onLine = m.onDemoLine, modifier = Modifier.fillMaxSize())
                    live -> PlayView(m, Modifier.fillMaxSize())
                    else -> StageIdle(player, Modifier.fillMaxSize())
                }
            }
            m.dj.problem?.let {
                Spacer(Modifier.height(16.dp))
                ErrorNotice(it)
            }
            // 🔑 설정 칸에서는 진행 막대와 조작부를 그리지 않는다 — 메뉴와 시뮬레이션 버튼에 자리를 내준다.
            if (live && view != Section.DEMO) {
                Spacer(Modifier.height(24.dp))
                Transport(m.ui, m.progress, player)
            }
        }
    }
}

@Composable
private fun StageIdle(player: Player?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.Bottom) {
        Text("이 길에 어울리는\n트로트를 틀어 드릴게요", style = headline(DjType.HeadlineL))
        Spacer(Modifier.height(16.dp))
        Text("곡 사이사이 DJ가 지금 지나는 동네와 남은 길을 이야기해요.", style = body(DjType.BodyS, DjColors.Muted))
        Spacer(Modifier.height(40.dp))
        if (player == null) {
            Text("재생 서비스에 연결하는 중…", style = body(DjType.BodyS, DjColors.Muted))
        } else {
            SatinButton("DJ 트롯 시작", Modifier.fillMaxWidth(), height = 120.dp) {
                playList(player, Shelf.PICKS.id)
            }
            Spacer(Modifier.height(16.dp))
            Text("추천곡부터 이어서 틀어요", style = body(DjType.LabelM, DjColors.Muted), modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

/** 재생 칸 — 표지와 곡 정보, 노래방 가사, 다음 곡. */
@Composable
private fun PlayView(m: StageModel, modifier: Modifier) {
    val ui = m.ui
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverWithRecord(ui)
            Spacer(Modifier.width(26.dp))
            Column(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = ui.title.orEmpty(),
                    transitionSpec = { (fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 4 }) togetherWith fadeOut(tween(200)) },
                    label = "title",
                ) { title ->
                    Text(title.ifEmpty { "노래 준비 중" }, style = headline(DjType.HeadlineS), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(10.dp))
                Text(ui.singer.orEmpty(), style = body(DjType.BodyL, DjColors.Gold2, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val style = Labels.style(ui.style)
                if (m.ad != null) {
                    // 광고가 나오는 중 — 광고임과 광고주, 생성권까지 남은 횟수를 곡 정보 자리에서 알린다.
                    Spacer(Modifier.height(18.dp))
                    AdBanner(m.ad.title, m.adNote)
                } else if (style != null || ui.made) {
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (ui.made) MadeTag()
                        style?.let { StyleTag(it) }
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        // 🔴 주행 중에는 가사를 띄우지 않는다 — 움직이며 바뀌는 글자는 눈을 붙잡는다. 시각은 계속 받아 두어 정차하면 바로 이어진다.
        if (!m.driving) LyricsBlock(m.lyrics, m.progress.positionMs / 1000.0, big = m.roomy)
        Spacer(Modifier.weight(1f))

        ui.nextTitle?.let { next ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ui.nextMade) {
                    // 신청해 만든 노래가 다음 차례면 금빛으로 미리 알린다.
                    Icon(DjIcons.Sparkle, contentDescription = null, tint = DjColors.Gold, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("다음은 생성곡", style = body(DjType.LabelM, DjColors.Gold, FontWeight.Bold))
                    Spacer(Modifier.width(16.dp))
                    Text("‘$next’", style = body(DjType.BodyS, DjColors.Gold2, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Text("다음 곡", style = body(DjType.LabelM, DjColors.Gold, FontWeight.Bold))
                    Spacer(Modifier.width(16.dp))
                    Text(listOfNotNull(next, ui.nextSinger).joinToString(" · "), style = body(DjType.BodyS, DjColors.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * DJ 칸 — 방금 한 말을 크게, 다시 듣기와 멘트 켜기·끄기, DJ가 아는 이 드라이브.
 * 🔑 주행 중에는 멘트를 한 줄로 줄이고, 글 길이 제한(LIMIT_STRING_LENGTH)이 걸리면 그 글자 수로 자른다. 전체는 음성으로 듣는다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DjView(m: StageModel, modifier: Modifier) {
    val line = m.dj.line
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val avatar = Art.dj(m.djId)
            Box(
                Modifier.size(132.dp).clip(CircleShape).background(Brush.linearGradient(listOf(DjColors.Gold2, DjColors.Gold, DjColors.GoldDeep))).border(2.5.dp, DjColors.GoldRing, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (avatar != null) Image(painterResource(avatar), contentDescription = m.djName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(DjIcons.Mic, contentDescription = null, tint = DjColors.OnGold, modifier = Modifier.size(64.dp))
            }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.djName, style = headline(DjType.TitleM, DjColors.Gold2))
                    Spacer(Modifier.width(18.dp))
                    OnAirBadge(m.ui.playing && m.talks)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (m.talks) "곡 사이에 지나는 동네와 남은 길을 이야기해요" else "멘트를 꺼 두었어요. 노래만 이어서 틀어요",
                    style = body(DjType.LabelM, DjColors.Muted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(28.dp))
        Column(Modifier.fillMaxWidth().djCard(radius = 28.dp, background = Color(0xF015120E), line = Color(0xFF56432A)).padding(horizontal = 32.dp, vertical = 28.dp)) {
            Text("방금 DJ가 한 말", style = body(DjType.LabelM, DjColors.Gold, FontWeight.Bold))
            Spacer(Modifier.height(12.dp))
            Text(
                line?.let { "“${m.drive.brief(it)}”" } ?: "다음 곡을 틀 때 DJ가 인사해요",
                style = headline(DjType.TitleS, if (line != null) DjColors.Ink else DjColors.Muted, FontWeight.Bold),
                maxLines = if (m.driving) 1 else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(28.dp))
        Text("DJ가 아는 이 드라이브", style = body(DjType.LabelM, DjColors.Gold, FontWeight.Bold))
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DriveFactPills(m.drive)
        }
        // 앞서 한 말은 남는 자리만큼만 보인다. 주행 중에는 숨긴다(긴 글). 누르는 것은 아래로 — 조작부·탭과 같은 손 높이.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            // 줄이 반쯤 잘리지 않게 들어가는 만큼만 그린다.
            val fits = ((maxHeight - EARLIER_HEAD) / EARLIER_ROW).toInt()
            if (!m.driving && fits > 0 && m.dj.earlier.isNotEmpty()) Earlier(m.dj.earlier.take(fits), Modifier.padding(top = 24.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ActionButton(DjIcons.Replay, "다시 듣기", Modifier.weight(1f), enabled = line != null && m.talks, onClick = m.onReplay)
            ActionButton(
                if (m.talks) DjIcons.VolumeOn else DjIcons.VolumeOff,
                if (m.talks) "DJ 멘트 켜짐" else "DJ 멘트 꺼짐",
                Modifier.weight(1f),
                active = m.talks,
                onClick = m.onToggleTalks,
            )
        }
    }
}

/** 트롯생성 칸 — 트롯 제작실([CreateStudio]). 사연 신청 → 제작 중계 → 첫 방송. */
@Composable
private fun CreateView(m: StageModel, player: Player?, modifier: Modifier) {
    CreateStudio(m.app, m.request, m.djId, m.djName, m.drive, m.driving, m.ui.songId, m.ui.playing, player, modifier)
}

/**
 * DJ 카드 — 넓은 창의 오른쪽 열 위에 늘 보인다. 큰 얼굴, 이름과 ON AIR, 방금 한 말. 다시 듣기와 멘트 켜기는 설정 버튼([DjMenu]) 뒤에 있다.
 * 🔑 DJ가 이 앱의 얼굴이라 어느 탭에 있어도 보이게 한다. 얼굴 그림이 없는 DJ는 금빛 마이크로 대신한다.
 */
@Composable
private fun DjCard(m: StageModel) {
    val line = m.dj.line
    val onAir = m.ui.playing && m.talks
    Row(
        Modifier.fillMaxWidth().djCard(radius = 36.dp, background = DjColors.Paper, line = if (onAir) DjColors.GoldRing else DjColors.LineStrong).padding(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 큰 얼굴 — 금빛 고리, 말하는 중에는 은은하게 빛난다.
        val avatar = Art.dj(m.djId)
        Box(
            Modifier
                .size(196.dp)
                .then(if (onAir) Modifier.shadow(26.dp, CircleShape, ambientColor = DjColors.Gold, spotColor = DjColors.Gold) else Modifier)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(DjColors.Gold2, DjColors.Gold, DjColors.GoldDeep)))
                .border(3.dp, Brush.verticalGradient(listOf(DjColors.Gold2, DjColors.SatinEdge, DjColors.Gold, DjColors.GoldDeep)), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (avatar != null) Image(painterResource(avatar), contentDescription = m.djName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(DjIcons.Mic, contentDescription = null, tint = DjColors.OnGold, modifier = Modifier.size(96.dp))
        }
        Spacer(Modifier.width(28.dp))
        Column(Modifier.weight(1f).height(196.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 이름과 ON AIR가 남는 폭을 다 쓰고, 설정 버튼은 오른쪽 끝에 붙는다.
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(m.djName, style = headline(DjType.TitleL, DjColors.Gold2), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(16.dp))
                    OnAirBadge(onAir)
                }
                Spacer(Modifier.width(12.dp))
                DjMenu(m)
            }
            Spacer(Modifier.height(14.dp))
            // 방금 한 말 — 버튼이 있던 자리까지 넓게 쓴다. 자막처럼 크게 보인다.
            Text(
                when {
                    !m.talks -> "멘트를 꺼 두었어요. 노래만 이어서 틀어요."
                    line != null -> "“${m.drive.brief(line)}”"
                    else -> "다음 곡을 틀 때 인사할게요."
                },
                style = body(DjType.BodyM, if (line != null && m.talks) DjColors.Ink else DjColors.Muted, FontWeight.Medium),
                maxLines = if (m.driving) 2 else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * DJ 카드의 설정 버튼 — 누르면 작은 메뉴가 열린다(다시 듣기, 멘트 켜기·끄기). 바깥을 누르면 닫힌다.
 * 🔑 카드에는 DJ의 말만 크게 두고, 가끔 쓰는 조작은 이 버튼 뒤로 모은다.
 */
@Composable
private fun DjMenu(m: StageModel) {
    var open by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    Box {
        Box(
            Modifier
                .size(64.dp)
                .pressScale(source)
                .clip(CircleShape)
                .background(if (open) DjColors.Selected else DjColors.Card)
                .border(1.5.dp, if (open) DjColors.Gold else DjColors.LineStrong, CircleShape)
                .tap(source, label = "DJ 설정") { open = !open },
            contentAlignment = Alignment.Center,
        ) {
            Icon(DjIcons.Settings, contentDescription = "DJ 설정", tint = DjColors.Gold2, modifier = Modifier.size(34.dp))
        }
        if (open) {
            Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, with(LocalDensity.current) { 76.dp.roundToPx() }), onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
                Column(
                    Modifier.width(340.dp).shadow(24.dp, RoundedCornerShape(24.dp)).djCard(radius = 24.dp, background = DjColors.CardRaised, line = DjColors.GoldRing).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ActionButton(DjIcons.Replay, "다시 듣기", Modifier.fillMaxWidth(), enabled = m.dj.line != null && m.talks) {
                        open = false
                        m.onReplay()
                    }
                    ActionButton(
                        if (m.talks) DjIcons.VolumeOn else DjIcons.VolumeOff,
                        if (m.talks) "멘트 켜짐" else "멘트 꺼짐",
                        Modifier.fillMaxWidth(),
                        active = m.talks,
                        onClick = m.onToggleTalks,
                    )
                }
            }
        }
    }
}

/** 이번 드라이브에서 앞서 한 말 — 시각과 한 줄씩, 최근 것부터. */
@Composable
private fun Earlier(said: List<Said>, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text("앞서 한 말", style = body(DjType.LabelM, DjColors.Gold, FontWeight.Bold))
        said.forEach { s ->
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(DjColors.Line))
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.at.format(SAID_AT), style = digits(DjType.LabelM, DjColors.Faint), modifier = Modifier.width(130.dp))
                Text(s.text, style = body(DjType.BodyS, DjColors.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val SAID_AT: DateTimeFormatter = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)

/** [Earlier]의 위 여백과 제목, 한 줄(구분선·여백 포함)의 높이. */
private val EARLIER_HEAD = 62.dp
private val EARLIER_ROW = 78.dp

/** DJ 칸의 큰 버튼 — 켜진 상태는 금테. */
@Composable
private fun ActionButton(icon: ImageVector, text: String, modifier: Modifier, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(22.dp)
    val ink = when {
        !enabled -> DjColors.Faint
        active -> DjColors.Gold2
        else -> DjColors.Ink
    }
    Row(
        modifier
            .pressScale(source, depth = 0.97f)
            .height(88.dp)
            .clip(shape)
            .background(if (active) DjColors.Selected else DjColors.CardRaised)
            .border(1.5.dp, if (active) DjColors.Gold.copy(alpha = 0.75f) else DjColors.LineStrong, shape)
            .tap(source, enabled = enabled, label = text, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) DjColors.Gold else DjColors.Faint, modifier = Modifier.size(34.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = body(DjType.BodyS, ink, FontWeight.Bold))
    }
}

/** 진행 막대와 이전·재생·다음 — 재생 칸과 DJ 칸 모두 같은 자리. */
@Composable
private fun Transport(ui: PlayerUi, progress: Progress, player: Player) {
    Column {
        ProgressBar(progress)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(64.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            RingButton(DjIcons.Previous, "이전 곡", size = 108.dp, enabled = ui.hasPrevious || progress.positionMs > 3_000) { player.seekToPrevious() }
            PlayButton(ui.playing, ui.loading, size = 156.dp) { togglePlay(player, ui) }
            RingButton(DjIcons.Next, "다음 곡", size = 108.dp, enabled = ui.hasNext) { player.seekToNextMediaItem() }
        }
    }
}

/** 광고 표시 — 금빛 "광고" 딱지와 광고주 이름, 그 아래 줄에 생성권까지의 진행(옆에 두면 좁은 자리에서 잘린다). */
@Composable
private fun AdBanner(title: String, note: String?) {
    val shape = RoundedCornerShape(10.dp)
    Column {
        Row(
            Modifier.clip(shape).background(Brush.linearGradient(listOf(DjColors.Gold2, DjColors.Gold))).padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(DjIcons.VolumeOn, contentDescription = null, tint = DjColors.OnGold, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("광고 · $title", style = body(DjType.LabelS, DjColors.OnGold, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (note != null) {
            Spacer(Modifier.height(8.dp))
            Text(note, style = body(DjType.LabelS, DjColors.Gold2, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 이 드라이브에서 신청해 만든 노래 표시 — 금빛으로 채워 장르 태그와 구분한다. */
@Composable
private fun MadeTag() {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.clip(shape).background(Brush.linearGradient(listOf(DjColors.Gold2, DjColors.Gold))).padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(DjIcons.Sparkle, contentDescription = null, tint = DjColors.OnGold, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text("생성곡", style = body(DjType.LabelS, DjColors.OnGold, FontWeight.Bold))
    }
}

@Composable
private fun StyleTag(text: String) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        text,
        style = body(DjType.LabelS, DjColors.Gold2, FontWeight.SemiBold),
        modifier = Modifier.clip(shape).background(DjColors.Cream.copy(alpha = 0.9f)).border(1.dp, DjColors.LineStrong, shape).padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/**
 * 노래방 자막 — 지금 줄은 부른 만큼 금빛으로 칠하고, 다음 줄은 흐리게. 정차 중에만 그린다.
 * 쉬는 동안은 전주·간주를 알리고, 다음 줄 4초 전부터 노래방처럼 점을 하나씩 끈다.
 * 가사 시각이 없는 곡이면 아무것도 그리지 않는다.
 */
@Composable
private fun LyricsBlock(lines: List<LyricLine>, seconds: Double, big: Boolean) {
    if (lines.isEmpty()) return
    val moment = LyricTimeline.at(lines, seconds)
    Column(Modifier.fillMaxWidth().height(if (big) 190.dp else 150.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AnimatedContent(
            targetState = moment.current to moment.index,
            transitionSpec = { (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 2 }) togetherWith (fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it / 2 }) },
            contentAlignment = Alignment.Center,
            label = "lyric",
        ) { (line, index) ->
            if (line != null) {
                // 🔑 붓의 범위는 글자 폭이다 — 너비를 채우지 않고 가운데 둔다.
                Text(
                    line,
                    style = headline(if (big) DjType.HeadlineS else DjType.TitleL, weight = FontWeight.Bold).copy(brush = karaoke(moment.sung)),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Resting(moment.countdown, intro = index < 0, ending = moment.next == null)
            }
        }
        if (moment.next != null) {
            Spacer(Modifier.height(14.dp))
            Text(moment.next, style = body(if (big) DjType.BodyL else DjType.BodyM, DjColors.Muted), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** 부른 곳까지 금빛, 남은 곳은 흰빛 — 경계는 날카롭게. */
private fun karaoke(sung: Float): Brush = Brush.horizontalGradient(
    0f to DjColors.Gold,
    sung to DjColors.Gold,
    sung to DjColors.Ink,
    1f to DjColors.Ink,
)

@Composable
private fun Resting(countdown: Double?, intro: Boolean, ending: Boolean) {
    if (countdown != null && countdown <= COUNTDOWN_SECONDS) {
        val lit = ceil(countdown).toInt().coerceIn(1, COUNTDOWN_SECONDS.toInt())
        Row(Modifier.height(54.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(COUNTDOWN_SECONDS.toInt()) { dot ->
                Box(Modifier.size(20.dp).clip(CircleShape).background(if (dot < lit) DjColors.Gold2 else DjColors.Track))
            }
        }
    } else {
        Row(Modifier.height(54.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(DjIcons.Note, contentDescription = null, tint = DjColors.Gold, modifier = Modifier.size(34.dp))
            Text(
                when {
                    intro -> "전주"
                    ending -> "마무리 연주"
                    else -> "간주"
                },
                style = body(DjType.BodyL, DjColors.Gold, FontWeight.SemiBold),
            )
        }
    }
}

private const val COUNTDOWN_SECONDS = 4.0

/** 표지와 그 뒤에서 빠져나오는 레코드. 재생 중이면 더 많이 나와 돈다. */
@Composable
private fun CoverWithRecord(ui: PlayerUi) {
    val cover = 272.dp
    val record = 240.dp
    // 🔑 재생 중에는 금빛 라벨 가장자리가 보일 만큼 빠져나온다.
    val out by animateDpAsState(if (ui.playing) 116.dp else 44.dp, tween(700, easing = FastOutSlowInEasing), label = "record")
    Box(Modifier.size(width = (cover - record) / 2 + 116.dp + record, height = cover)) {
        Box(
            Modifier.size(cover + 40.dp).offset(x = (-20).dp, y = (-20).dp).drawBehind {
                drawCircle(Brush.radialGradient(listOf(DjColors.Gold.copy(alpha = 0.26f), Color.Transparent)), radius = size.minDimension * 0.62f)
            },
        )
        Vinyl(ui.playing, record, Modifier.align(Alignment.CenterStart).offset { IntOffset(((cover - record) / 2 + out).roundToPx(), 0) })
        Cover(
            ui.artworkUri,
            ui.style,
            size = cover,
            radius = 24.dp,
            modifier = Modifier.shadow(24.dp, RoundedCornerShape(24.dp), ambientColor = Color.Black, spotColor = Color.Black),
            seed = ui.songId,
        )
    }
}

@Composable
private fun ProgressBar(progress: Progress) {
    Column {
        BoxWithConstraints(Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.CenterStart) {
            val width = maxWidth
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(DjColors.Track))
            Box(
                Modifier
                    .width(width * progress.fraction)
                    .height(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(DjColors.GoldDeep, DjColors.Gold, DjColors.Gold2))),
            )
            Box(
                Modifier
                    .offset(x = (width - 22.dp) * progress.fraction)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(DjColors.Gold2),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(clock(progress.positionMs), style = digits(DjType.LabelM))
            Spacer(Modifier.weight(1f))
            Text(if (progress.durationMs > 0) clock(progress.durationMs) else "--:--", style = digits(DjType.LabelM))
        }
    }
}


private fun modeLabel(mode: String) = when (mode) {
    "ECO" -> "에코"
    "SPORT" -> "스포츠"
    "NORMAL" -> "일반"
    else -> mode
}
