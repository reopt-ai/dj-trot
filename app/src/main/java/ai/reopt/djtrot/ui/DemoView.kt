package ai.reopt.djtrot.ui

import ai.reopt.djtrot.BuildConfig
import ai.reopt.djtrot.DjTrotApp
import ai.reopt.djtrot.car.DriveContext
import ai.reopt.djtrot.dj.Demo
import ai.reopt.djtrot.dj.DemoDrive
import ai.reopt.djtrot.dj.DemoStep
import ai.reopt.djtrot.dj.LineGroup
import ai.reopt.djtrot.dj.VoicePackStore
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 설정 칸의 화면 — 처음에는 메뉴, 버튼을 누르면 그 안(상세)으로 들어간다. */
internal enum class SettingsPage(val title: String) {
    HOME("설정"),
    AUTO("자동 시연"),
    SIMULATION("시뮬레이션"),
    LINES("DJ 멘트 살펴보기"),
    SIGNALS("차량 신호"),
    DJ("DJ 설정"),
    ACCOUNT("연결·계정"),
    ABOUT("앱 정보"),
}

/**
 * 설정 칸 — 메뉴 버튼 두 개(시뮬레이션, DJ 멘트 살펴보기)에서 시작하고, 누르면 그 안으로 들어간다(공모전 심사·전시용).
 * 🔑 이 칸에서는 무대의 진행 막대와 재생 조작부를 그리지 않는다 — 버튼이 한 화면에 다 들어오게 자리를 내준다. 재생은 재생 칸·스티어링 휠로 한다.
 * 🔑 상세로 들어가면 무대가 뒤에서 어두워지고([page]를 무대가 함께 본다) 상세 화면이 옆에서 밀려 들어온다. 주행 중([LocalCalm])에는 밀지 않고 짧게 바꾼다.
 * 🔑 시뮬레이션이 "주행"을 덧씌우면 화면의 주행 제한(목록·가사 숨김)도 실제처럼 걸린다. 이 칸은 그때도 열려 있다 — 차가 서 있는 자리에서 쓰는 도구다.
 */
@Composable
internal fun DemoView(
    app: DjTrotApp,
    drive: DriveContext,
    driving: Boolean,
    talks: Boolean,
    onToggleTalks: () -> Unit,
    /** 노래가 멈춰 있을 때 트는 일 — 자동 시연을 시작할 때 쓴다. */
    onStartMusic: () -> Unit,
    page: SettingsPage,
    onPage: (SettingsPage) -> Unit,
    demo: DemoDrive?,
    playing: Boolean,
    line: String?,
    columns: Int,
    onStep: (DemoStep) -> Unit,
    onEnd: () -> Unit,
    /** DJ가 받아 둔 멘트 조각 — 살펴보기 목록. 아직 받지 못했으면 빈 목록. */
    lines: () -> List<VoicePackStore.Fragment>,
    onLine: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var last by rememberSaveable { mutableStateOf<String?>(null) }
    val auto by app.autoDemo.collectAsStateWithLifecycle()
    val calm = LocalCalm.current
    BackHandler(enabled = page != SettingsPage.HOME) { onPage(SettingsPage.HOME) }
    AnimatedContent(
        targetState = page,
        modifier = modifier,
        transitionSpec = {
            if (calm) {
                fadeIn(tween(120)) togetherWith fadeOut(tween(120))
            } else {
                // 안으로 들어갈 때는 오른쪽에서, 메뉴로 돌아올 때는 왼쪽에서 밀려 들어온다.
                val forward = targetState != SettingsPage.HOME
                val enter = slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { width -> if (forward) width / 5 else -width / 5 } + fadeIn(tween(260, delayMillis = 60))
                val exit = slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { width -> if (forward) -width / 8 else width / 8 } + fadeOut(tween(160))
                enter togetherWith exit
            }
        },
        label = "settings",
    ) { shown ->
        Column(Modifier.fillMaxSize()) {
            when (shown) {
                SettingsPage.HOME -> {
                    val lineCount = remember { lines().size }
                    SettingsHero(lineCount = lineCount, simulating = demo != null)
                    Spacer(Modifier.height(16.dp))
                    // 메뉴 — 한 줄에 하나씩 쌓고, 넘치면 스크롤한다. 시연에 쓰는 것이 먼저, 평소 설정이 다음이다.
                    val tiles: List<@Composable (Modifier) -> Unit> = listOf(
                        { SettingsTile(DjIcons.Play, "자동 시연", auto?.let { "${it.index + 1}/${it.total} · ${it.label}" } ?: "정해 둔 길을 버튼 없이 차례로 보여 줘요", null, auto != null, it) { onPage(SettingsPage.AUTO) } },
                        { SettingsTile(DjIcons.Car, "시뮬레이션", demo?.summary() ?: "출발·비·정체·도착 같은 길 위의 상황을 버튼으로 흉내 내요", null, demo != null, it) { onPage(SettingsPage.SIMULATION) } },
                        { SettingsTile(DjIcons.Mic, "DJ 멘트 살펴보기", "DJ가 준비해 둔 멘트를 보고 하나씩 들어 봐요", null, false, it) { onPage(SettingsPage.LINES) } },
                        { SettingsTile(DjIcons.Flag, "차량 신호", "차가 지금 알려 주는 값을 SDK별로 보여 줘요", null, false, it) { onPage(SettingsPage.SIGNALS) } },
                        { SettingsTile(DjIcons.VolumeOn, "DJ 설정", "멘트 켜기, 길게 말하는 간격, 멘트 중 음악 크기", null, false, it) { onPage(SettingsPage.DJ) } },
                        { SettingsTile(DjIcons.Phone, "연결·계정", "휴대폰 연결 상태와 남은 드라이브 토큰", null, false, it) { onPage(SettingsPage.ACCOUNT) } },
                        { SettingsTile(DjIcons.Note, "앱 정보", "버전, DJ 멘트 다시 받기, 오픈소스 표기", null, false, it) { onPage(SettingsPage.ABOUT) } },
                    )
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        tiles.forEach { tile -> tile(Modifier.fillMaxWidth()) }
                    }
                    Spacer(Modifier.height(10.dp))
                    // 바닥 — 어떤 빌드인지. 심사·전시 자리에서 버전을 바로 확인한다.
                    Text(
                        "DJ 트롯 ${BuildConfig.VERSION_NAME} · Pleos Connect",
                        style = body(DjType.LabelM, DjColors.Faint),
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
                SettingsPage.SIMULATION -> {
                    DetailHeader(DjIcons.Car, shown.title, "버튼을 누르면 DJ가 그 상황으로 말해요", onBack = { onPage(SettingsPage.HOME) }) {
                        if (demo != null) {
                            PillButton("실제 신호로", "시뮬레이션을 끝내고 실제 차량 신호로 돌아가요", selected = false, Modifier.width(230.dp), icon = DjIcons.Refresh) {
                                last = null
                                onEnd()
                            }
                        }
                    }
                    Spacer(Modifier.height(DetailGap))
                    SimulationStatus(demo, playing, line)
                    Spacer(Modifier.height(6.dp))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Demo.GROUPS.forEach { group ->
                            GroupTitle(group.title, groupIcon(group.title))
                            val perRow = group.columns ?: columns
                            group.steps.chunked(perRow).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    row.forEach { step ->
                                        PillButton(step.label, step.hint, selected = step.id == last, Modifier.weight(1f), height = 80.dp) {
                                            last = step.id
                                            onStep(step)
                                        }
                                    }
                                    // 마지막 줄의 빈 칸 — 버튼 너비를 다른 줄과 맞춘다.
                                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
                SettingsPage.AUTO -> {
                    DetailHeader(DjIcons.Play, shown.title, "정해 둔 길을 버튼 없이 보여 줘요", onBack = { onPage(SettingsPage.HOME) }) {
                        if (demo != null && auto == null) PillButton("실제 신호로", "시연을 끝내고 실제 차량 신호로 돌아가요", selected = false, Modifier.width(230.dp), icon = DjIcons.Refresh, onClick = onEnd)
                    }
                    Spacer(Modifier.height(DetailGap))
                    AutoDemoPage(auto, playing, line, onStart = { scenario ->
                        if (!playing) onStartMusic()
                        // 노래를 방금 틀었으면 첫 곡 소개가 끝날 때까지 기다렸다가 시작한다.
                        app.startAuto(scenario, leadMs = if (playing) 600 else 16_000)
                    }, onStop = app::stopAuto)
                }
                SettingsPage.SIGNALS -> {
                    DetailHeader(DjIcons.Flag, shown.title, "DJ 멘트의 재료가 되는 차량 신호예요", onBack = { onPage(SettingsPage.HOME) }) {
                        StatusPill(DjIcons.Car, if (demo != null) "시뮬레이션 값" else "실시간", on = demo != null)
                    }
                    SignalsPage(drive)
                }
                SettingsPage.DJ -> {
                    DetailHeader(DjIcons.VolumeOn, shown.title, "DJ가 언제, 얼마나 말할지 정해요", onBack = { onPage(SettingsPage.HOME) })
                    DjSettingsPage(app, talks, onToggleTalks)
                }
                SettingsPage.ACCOUNT -> {
                    DetailHeader(DjIcons.Phone, shown.title, "휴대폰 연결과 드라이브 토큰", onBack = { onPage(SettingsPage.HOME) })
                    AccountPage(app, drive, driving)
                }
                SettingsPage.ABOUT -> {
                    DetailHeader(DjIcons.Note, shown.title, "DJ 트롯 ${BuildConfig.VERSION_NAME}", onBack = { onPage(SettingsPage.HOME) })
                    AboutPage(app)
                }
                SettingsPage.LINES -> {
                    val all = remember { lines() }
                    DetailHeader(DjIcons.Mic, shown.title, "누르면 DJ 목소리로 들려 드려요", onBack = { onPage(SettingsPage.HOME) }) {
                        StatusPill(DjIcons.VolumeOn, "${all.size}개", on = all.isNotEmpty())
                    }
                    LineBrowser(all, onLine, Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 상세 화면의 머리 — 금빛 고리의 뒤로 가기, 아이콘과 금빛 제목, 오른쪽 끝의 덧붙임([trailing]), 그 아래 금빛 선.
 * 🔑 메뉴의 머리([SettingsHero])와 같은 금빛 말투를 한 단계 작게 쓴다.
 */
@Composable
internal fun DetailHeader(icon: ImageVector, title: String, subtitle: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val gold = Brush.verticalGradient(listOf(DjColors.Gold2, DjColors.SatinEdge, DjColors.Gold, DjColors.GoldDeep))
    Row(verticalAlignment = Alignment.CenterVertically) {
        val source = remember { MutableInteractionSource() }
        Box(
            Modifier
                .size(68.dp)
                .pressScale(source)
                .clip(CircleShape)
                .background(DjColors.Card)
                .border(2.dp, gold, CircleShape)
                .tap(source, label = "설정 메뉴로 돌아가기", onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(DjIcons.ChevronRight, contentDescription = "뒤로", tint = DjColors.Gold2, modifier = Modifier.size(40.dp).rotate(180f))
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = DjColors.Gold2, modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    style = headline(DjType.TitleM, DjColors.Gold2, FontWeight.Black).copy(
                        brush = gold,
                        shadow = Shadow(color = DjColors.Gold.copy(alpha = 0.4f), offset = Offset(0f, 2f), blurRadius = 14f),
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(subtitle, style = body(DjType.BodyM, DjColors.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        trailing()
    }
    Spacer(Modifier.height(20.dp))
    Box(Modifier.fillMaxWidth().height(2.dp).background(Brush.horizontalGradient(listOf(DjColors.Gold, DjColors.Gold2, DjColors.Gold.copy(alpha = 0.4f), Color.Transparent))))
    // 🔑 선 아래 여백은 묶음 이름([GroupTitle])의 위 여백과 합쳐진다 — 묶음으로 시작하지 않는 화면은 [DetailGap]을 더 둔다.
    Spacer(Modifier.height(8.dp))
}

/**
 * 시뮬레이션의 지금 — 덧씌운 상황을 알약으로 늘어놓고, 그 아래 DJ가 방금 한 말을 자막처럼 보여 준다.
 * 🔑 버튼을 누른 결과(상황이 바뀌고 DJ가 말한다)가 이 한 자리에서 보인다.
 */
@Composable
private fun SimulationStatus(demo: DemoDrive?, playing: Boolean, line: String?) {
    val spoke = playing && line != null
    Column(Modifier.fillMaxWidth().djCard(radius = 22.dp, line = if (spoke) DjColors.GoldRing else DjColors.Line).padding(horizontal = 24.dp, vertical = 20.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("지금", style = body(DjType.LabelM, DjColors.Faint, FontWeight.Bold))
            if (demo == null) StatusPill(DjIcons.Car, "실제 차량 신호", on = false)
            else demo.parts().forEach { part -> StatusPill(null, part, on = true) }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.Top) {
            Icon(DjIcons.Mic, contentDescription = null, tint = if (spoke) DjColors.Gold2 else DjColors.Pink, modifier = Modifier.padding(top = 2.dp).size(30.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    !playing -> "재생 탭에서 노래를 틀면, DJ가 누른 상황으로 말해요."
                    line != null -> "“$line”"
                    else -> "버튼을 누르면 DJ가 지금 곡 위에서 말해요."
                },
                style = body(DjType.BodyM, if (spoke) DjColors.Ink else DjColors.Pink, if (spoke) FontWeight.Medium else FontWeight.Normal),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 시뮬레이션 묶음의 아이콘 — 떠나기는 깃발, 길 위는 차, 시간대는 반짝임, 닿기는 핀. */
private fun groupIcon(title: String): ImageVector? = when (title) {
    "길 떠나기" -> DjIcons.Flag
    "길 위에서" -> DjIcons.Car
    "시간대" -> DjIcons.Sparkle
    "멈추고 닿기" -> DjIcons.Place
    else -> null
}

/** 상세 머리의 선과 첫 내용 사이 — 묶음 이름으로 시작하지 않는 화면이 쓴다. */
internal val DetailGap = 16.dp

/** 버튼 묶음의 이름 — 금빛 아이콘(없으면 점)과 이름, 오른쪽으로 흐려지는 선. */
@Composable
internal fun GroupTitle(title: String, icon: ImageVector? = null) {
    // 묶음 사이는 넉넉히 띄우고, 이름과 그 아래 줄은 가깝게 둔다 — 어디서 묶음이 바뀌는지 한눈에 보인다.
    Row(Modifier.padding(top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, contentDescription = null, tint = DjColors.Gold, modifier = Modifier.size(22.dp))
        else Box(Modifier.size(8.dp).clip(CircleShape).background(DjColors.Gold))
        Spacer(Modifier.width(10.dp))
        Text(title, style = body(DjType.LabelM, DjColors.Gold, FontWeight.Bold))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(DjColors.LineStrong, Color.Transparent))))
    }
}

/**
 * 설정 첫 화면의 머리 — 금빛 고리 안의 톱니바퀴, 간판처럼 큰 금빛 제목, 지금 상태를 알리는 알약 두 개, 금빛 선.
 * 🔑 재생 칸의 무대 간판과 같은 말투로 꾸민다 — 설정도 무대의 한 칸이다.
 */
@Composable
private fun SettingsHero(lineCount: Int, simulating: Boolean) {
    val gold = Brush.verticalGradient(listOf(DjColors.Gold2, DjColors.SatinEdge, DjColors.Gold, DjColors.GoldDeep))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(92.dp)
                .shadow(18.dp, CircleShape, ambientColor = DjColors.Gold, spotColor = DjColors.Gold)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(DjColors.Selected, DjColors.Card)))
                .border(2.5.dp, gold, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(DjIcons.Settings, contentDescription = null, tint = DjColors.Gold2, modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.width(22.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "설정",
                style = headline(DjType.HeadlineS, DjColors.Gold2, FontWeight.Black).copy(
                    brush = gold,
                    shadow = Shadow(color = DjColors.Gold.copy(alpha = 0.45f), offset = Offset(0f, 2f), blurRadius = 18f),
                ),
                maxLines = 1,
            )
            Text("DJ 트롯의 무대 뒤를 살펴봐요", style = body(DjType.BodyM, DjColors.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusPill(DjIcons.Mic, if (lineCount > 0) "DJ 멘트 ${lineCount}개 준비됨" else "DJ 멘트 받는 중", on = lineCount > 0)
        StatusPill(DjIcons.Car, if (simulating) "시뮬레이션 진행 중" else "실제 차량 신호", on = simulating)
    }
    Spacer(Modifier.height(18.dp))
    // 금빛 선 — 가운데가 밝고 양끝으로 사라진다.
    Box(Modifier.fillMaxWidth().height(2.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, DjColors.Gold, DjColors.Gold2, DjColors.Gold, Color.Transparent))))
    Spacer(Modifier.height(2.dp))
}

/** 상태 알약 — 아이콘과 짧은 글. 켜진 상태는 금빛이다. */
@Composable
internal fun StatusPill(icon: ImageVector?, text: String, on: Boolean) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(if (on) DjColors.Selected else DjColors.Card)
            .border(1.5.dp, if (on) DjColors.GoldRing else DjColors.Line, shape)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (on) DjColors.Gold2 else DjColors.Muted, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = body(DjType.LabelM, if (on) DjColors.Gold2 else DjColors.Muted, FontWeight.Bold), maxLines = 1)
    }
}

/**
 * DJ 멘트 살펴보기 — DJ가 받아 둔 멘트 조각을 묶음별로 보여 주고, 누르면 그 조각을 바로 들려준다.
 * 🔑 화면의 글과 들리는 말이 같은 조각이다 — 곡 사이의 멘트가 이 조각들을 이어 붙인 것임을 보여 준다.
 */
@Composable
private fun LineBrowser(lines: List<VoicePackStore.Fragment>, onLine: (String) -> Unit, modifier: Modifier = Modifier) {
    var group by rememberSaveable { mutableStateOf(LineGroup.OPEN) }
    var heard by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier) {
        Spacer(Modifier.height(DetailGap))
        // 묶음 버튼은 글자 폭만큼 차지하고, 넘치면 옆으로 민다 — 이름과 개수가 잘리지 않게.
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LineGroup.entries.forEach { item ->
                val count = lines.count { item.has(it.id) }
                PillButton(item.title, "${count}개", selected = item == group, Modifier.padding(vertical = 2.dp), height = 72.dp, count = count, fit = true) { group = item }
            }
        }
        Spacer(Modifier.height(4.dp))
        val shown = lines.filter { group.has(it.id) }
        if (shown.isNotEmpty()) {
            GroupTitle("${group.title} · ${shown.size}개")
            Spacer(Modifier.height(8.dp))
        }
        if (shown.isEmpty()) {
            Text(
                if (lines.isEmpty()) "DJ 멘트를 아직 받지 못했어요. 서버에 연결되면 받아 둬요." else "이 묶음에는 받아 둔 멘트가 없어요.",
                style = body(DjType.BodyM, DjColors.Muted),
                modifier = Modifier.padding(top = 12.dp),
            )
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(shown, key = { _, line -> line.id }) { index, line ->
                val source = remember { MutableInteractionSource() }
                val shape = RoundedCornerShape(16.dp)
                val on = line.id == heard
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(if (on) Brush.horizontalGradient(listOf(DjColors.Selected, DjColors.Cream)) else Brush.horizontalGradient(listOf(DjColors.CardRaised, DjColors.Card)))
                        .border(1.5.dp, if (on) DjColors.Gold else DjColors.Line, shape)
                        .pressScale(source, depth = 0.985f)
                        .tap(source, label = "들어 보기") {
                            heard = line.id
                            onLine(line.id)
                        }
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 번호 — 묶음 안의 순서. 방금 들은 줄은 금빛이다.
                    // 방금 들은 줄은 번호 대신 금빛 막대가 선다.
                    if (on) Equalizer(playing = true, height = 24.dp, modifier = Modifier.width(40.dp))
                    else Text("%02d".format(index + 1), style = body(DjType.LabelM, DjColors.Faint, FontWeight.Bold), modifier = Modifier.width(40.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(line.text, style = body(DjType.BodyM, if (on) DjColors.Gold2 else DjColors.Ink), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(18.dp))
                    Box(Modifier.size(48.dp).clip(CircleShape).background(if (on) DjColors.Gold else DjColors.Cream).border(1.5.dp, DjColors.GoldRing, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(DjIcons.VolumeOn, contentDescription = null, tint = if (on) DjColors.OnGold else DjColors.Gold2, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

/**
 * 설정 칸의 버튼 — 이름만 보이고 설명([hint])은 화면 낭독기가 읽는다. 앞에 아이콘([icon]), 뒤에 작은 수([count])를 붙일 수 있다.
 * 🔑 방금 누른 버튼은 금빛 바탕과 테, 은은한 빛으로 남는다 — 지금 어떤 상황을 골랐는지 한눈에 보인다.
 */
@Composable
internal fun PillButton(
    label: String,
    hint: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 68.dp,
    icon: ImageVector? = null,
    count: Int? = null,
    /** 글자 폭만큼만 차지한다(옆으로 미는 줄 안에서 쓴다). 거짓이면 주어진 폭에 맞춰 넘치는 글을 줄인다. */
    fit: Boolean = false,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)
    val ink = if (selected) DjColors.Gold2 else DjColors.Ink
    Row(
        modifier
            .height(height)
            .pressScale(source, depth = 0.96f)
            .then(if (selected) Modifier.shadow(14.dp, shape, ambientColor = DjColors.Gold, spotColor = DjColors.Gold) else Modifier)
            .clip(shape)
            .background(if (selected) Brush.verticalGradient(listOf(DjColors.Selected, DjColors.Cream)) else Brush.verticalGradient(listOf(DjColors.CardRaised, DjColors.Card)))
            .border(1.5.dp, if (selected) DjColors.Gold else DjColors.Line, shape)
            .tap(source, label = "$label, $hint", onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (selected) DjColors.Gold2 else DjColors.Gold, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = body(DjType.BodyS, ink, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = if (fit) Modifier else Modifier.weight(1f, fill = false))
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text("$count", style = body(DjType.LabelM, if (selected) DjColors.Gold else DjColors.Faint, FontWeight.Bold), maxLines = 1)
        }
    }
}
