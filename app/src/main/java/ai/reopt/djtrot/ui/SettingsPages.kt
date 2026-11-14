package ai.reopt.djtrot.ui

import ai.reopt.djtrot.BuildConfig
import ai.reopt.djtrot.DjTrotApp
import ai.reopt.djtrot.account.Connection
import ai.reopt.djtrot.car.DriveContext
import ai.reopt.djtrot.car.SignalBoard
import ai.reopt.djtrot.car.SignalSource
import ai.reopt.djtrot.dj.AutoDemo
import ai.reopt.djtrot.dj.Demo
import ai.reopt.djtrot.dj.DemoScenario
import ai.reopt.djtrot.dj.pauseSeconds
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * 설정 메뉴의 한 줄 — 아이콘, 이름과 작은 배지, 한 줄 설명. 메뉴는 한 줄에 하나씩 쌓는다.
 */
@Composable
internal fun SettingsTile(icon: ImageVector, title: String, description: String, badge: String?, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier
            .height(128.dp)
            .pressScale(source, depth = 0.98f)
            .clip(shape)
            .background(Brush.horizontalGradient(if (active) listOf(DjColors.Selected, DjColors.Cream) else listOf(DjColors.CardRaised, DjColors.Card)))
            .border(1.5.dp, if (active) DjColors.Gold else DjColors.LineStrong, shape)
            .tap(source, label = "$title 열기", onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 아이콘 받침 — 테 없이 은은한 원만 둔다.
        Box(Modifier.size(76.dp).clip(CircleShape).background(DjColors.Cream), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = DjColors.Gold2, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = headline(DjType.TitleS, DjColors.Ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (badge != null) {
                    Spacer(Modifier.width(10.dp))
                    Text(badge, style = body(DjType.LabelM, if (active) DjColors.Gold2 else DjColors.Gold, FontWeight.Bold), maxLines = 1)
                }
            }
            Text(description, style = body(DjType.LabelM, if (active) DjColors.Gold2 else DjColors.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(DjIcons.ChevronRight, contentDescription = null, tint = DjColors.GoldRing, modifier = Modifier.size(34.dp))
    }
}

/**
 * 자동 시연 — 정해 둔 길을 골라 누르면 버튼을 차례로 눌러 준다. 도는 동안은 몇 번째인지와 DJ가 한 말을 보여 준다.
 * 🔑 노래가 멈춰 있으면 먼저 튼다([onStartMusic]) — 사용자가 "시작"을 누른 동작이라 자동 재생이 아니다.
 */
@Composable
internal fun ColumnScope.AutoDemoPage(auto: AutoDemo?, playing: Boolean, line: String?, onStart: (DemoScenario) -> Unit, onStop: () -> Unit) {
    if (auto != null) {
        Column(Modifier.fillMaxWidth().djCard(radius = 24.dp, line = DjColors.Gold).padding(horizontal = 28.dp, vertical = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Equalizer(playing = true, height = 30.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(auto.title, style = headline(DjType.TitleS, DjColors.Gold2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${auto.index + 1} / ${auto.total} · ${auto.label}", style = body(DjType.BodyM, DjColors.Ink, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(12.dp))
                PillButton("멈추기", "자동 시연을 멈춰요", selected = true, Modifier.width(170.dp), onClick = onStop)
            }
            Spacer(Modifier.height(20.dp))
            // 진행 막대 — 지금 버튼까지 금빛으로 찬다.
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(DjColors.Track)) {
                Box(Modifier.fillMaxWidth((auto.index + 1f) / auto.total).fillMaxHeight().clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(DjColors.GoldDeep, DjColors.Gold, DjColors.Gold2))))
            }
            Spacer(Modifier.height(18.dp))
            Text(
                line?.let { "“$it”" } ?: "DJ가 곧 말해요.",
                style = body(DjType.BodyM, if (line != null) DjColors.Ink else DjColors.Muted),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(22.dp))
    }
    Text(
        if (playing) "길을 고르면 버튼을 차례로 눌러 드려요." else "길을 고르면 노래를 틀고, 버튼을 차례로 눌러 드려요.",
        style = body(DjType.BodyM, DjColors.Muted),
    )
    Spacer(Modifier.height(16.dp))
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Demo.SCENARIOS.forEach { scenario ->
            SettingsTile(
                if (auto?.scenarioId == scenario.id) DjIcons.Pause else DjIcons.Play,
                scenario.title,
                scenario.description,
                badge = "${scenario.steps.size}단계 · 약 ${(scenario.steps.sumOf { it.pauseSeconds } + 30) / 60}분",
                active = auto?.scenarioId == scenario.id,
                modifier = Modifier.fillMaxWidth(),
            ) { if (auto?.scenarioId == scenario.id) onStop() else onStart(scenario) }
        }
    }
}

/**
 * 차량 신호 — 차가 지금 알려 주는 값을 어느 SDK에서 오는지에 따라 묶어 보여 준다.
 * 🔑 DJ 멘트의 재료가 실제 차량 신호임을 화면으로 보여 주려는 것이다. 시뮬레이션이 덧씌운 값이면 그렇다고 알린다.
 */
@Composable
internal fun ColumnScope.SignalsPage(drive: DriveContext) {
    val rows = SignalBoard.rows(drive)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SignalSource.entries.forEach { source ->
            GroupTitle(source.label)
            rows.filter { it.source == source }.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { signal ->
                        Row(
                            Modifier.weight(1f).height(76.dp).djCard(radius = 16.dp).padding(horizontal = 22.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(signal.label, style = body(DjType.LabelM, DjColors.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                signal.value ?: "값 없음",
                                style = body(DjType.BodyM, if (signal.value != null) DjColors.Gold2 else DjColors.Faint, FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** DJ 설정 — 멘트 켜기·끄기, 길게 말하는 간격, 멘트 중 음악 크기. 값은 기기에만 저장한다. */
@Composable
internal fun ColumnScope.DjSettingsPage(app: DjTrotApp, talks: Boolean, onToggleTalks: () -> Unit) {
    var every by remember { mutableIntStateOf(app.fullEvery) }
    var duck by remember { mutableFloatStateOf(app.duckLevel) }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GroupTitle("DJ 멘트", DjIcons.Mic)
        Choices(listOf(true to "켜기", false to "끄기"), talks) { if (it != talks) onToggleTalks() }
        Note(if (talks) "곡이 시작될 때 DJ가 소개해요." else "DJ가 말하지 않고 노래만 이어져요.")
        GroupTitle("길게 말하기", DjIcons.Sparkle)
        Choices(listOf(1 to "매 곡", DjTrotApp.DEFAULT_FULL_EVERY to "세 곡마다", 0 to "곡 소개만"), every) {
            every = it
            app.fullEvery = it
        }
        Note(
            when (every) {
                0 -> "여는 말 없이 곡 제목만 소개해요."
                1 -> "곡마다 여는 말과 남은 길을 함께 이야기해요."
                else -> "첫 곡과 그 뒤 세 곡마다 길게, 사이에는 곡 소개만 해요."
            },
        )
        GroupTitle("멘트 중 음악", DjIcons.VolumeOn)
        Choices(listOf(0.5f to "조금 줄이기", DjTrotApp.DEFAULT_DUCK to "보통", 0.15f to "많이 줄이기"), duck) {
            duck = it
            app.duckLevel = it
        }
        Note("DJ가 말하는 동안 노래 소리를 얼마나 낮출지 골라요. 다음 멘트부터 적용돼요.")
    }
}

/** 연결·계정 — 휴대폰 연결 상태와 남은 토큰. 연결은 정차 중에만 열 수 있다(Pleos 정책). */
@Composable
internal fun ColumnScope.AccountPage(app: DjTrotApp, drive: DriveContext, driving: Boolean) {
    val connection by app.account.connection.collectAsStateWithLifecycle()
    val tokens by app.orders.tokens.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val connected = connection is Connection.Connected
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GroupTitle("휴대폰 연결", DjIcons.Phone)
        InfoRow("상태", if (connected) "연결됨" else "연결 안 됨", strong = connected)
        if (!connected) {
            val blocked = driving || drive.noSetup
            PillButton(if (blocked) "정차하면 연결할 수 있어요" else "휴대폰 연결하기", "QR로 휴대폰과 연결해요", selected = !blocked, Modifier.fillMaxWidth(), height = 84.dp) {
                if (!blocked) context.startActivity(Intent(context, ConnectActivity::class.java))
            }
            Note("휴대폰을 연결하면 이 길의 사연으로 노래를 만들 수 있어요.")
        } else {
            Note("연결을 끊으려면 휴대폰의 DJ 트롯 화면에서 이 차를 지워 주세요.")
        }
        GroupTitle("드라이브 토큰", DjIcons.Sparkle)
        InfoRow("남은 토큰", if (!connected) "연결하면 보여요" else tokens?.let { "${it}개" } ?: "확인 중", strong = connected && tokens != null)
        if (connected) {
            PillButton("토큰 다시 확인", "서버에서 남은 토큰을 다시 받아요", selected = false, Modifier.fillMaxWidth(), height = 80.dp, icon = DjIcons.Refresh) { app.orders.refreshTokens() }
        }
        Note("노래 한 곡에 토큰 1개를 써요. 토큰은 휴대폰에서 받아요.")
    }
}

/** 앱 정보 — 버전, 서버, 받아 둔 DJ 멘트(다시 받기), 쓰고 있는 오픈소스. */
@Composable
internal fun ColumnScope.AboutPage(app: DjTrotApp) {
    val scope = rememberCoroutineScope()
    var lineCount by remember { mutableIntStateOf(app.voicePack.fragments().size) }
    var syncing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GroupTitle("DJ 트롯", DjIcons.Disc)
        InfoRow("버전", BuildConfig.VERSION_NAME, strong = true)
        InfoRow("서버", runCatching { java.net.URI(BuildConfig.DJ_TROT_API).host }.getOrNull() ?: "알 수 없음", strong = false)
        InfoRow("플랫폼", "Pleos Connect · Android Automotive", strong = false)
        GroupTitle("DJ 멘트", DjIcons.Mic)
        InfoRow("받아 둔 멘트", if (lineCount > 0) "${lineCount}개" else "아직 없음", strong = lineCount > 0)
        PillButton(if (syncing) "받는 중…" else "멘트 다시 받기", "서버에서 DJ 멘트를 다시 받아요", selected = false, Modifier.fillMaxWidth(), height = 80.dp, icon = DjIcons.Refresh) {
            if (syncing) return@PillButton
            syncing = true
            result = null
            scope.launch {
                val done = runCatching { app.voicePack.sync(force = true) }.getOrDefault(false)
                lineCount = app.voicePack.fragments().size
                result = if (done) "DJ 멘트를 모두 받았어요." else "다 받지 못했어요. 서버에 연결되면 이어 받아요."
                syncing = false
            }
        }
        result?.let { Note(it) }
        GroupTitle("오픈소스", DjIcons.Note)
        Note("Jetpack Compose · AndroidX Media3 · Kotlin Coroutines · kotlinx.serialization · OkHttp (Apache License 2.0)")
        Note("아이콘 — Material Symbols (Apache License 2.0)")
        Note("차량 연동 — Pleos Playground SDK (Vehicle · NaviHelper · TextToSpeech · SpeechToText)")
    }
}

/** 고르는 버튼 줄 — 지금 고른 것이 금빛이다. */
@Composable
private fun <T> Choices(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        options.forEach { (value, label) ->
            PillButton(label, label, selected = value == selected, Modifier.weight(1f), height = 80.dp) { onSelect(value) }
        }
    }
}

/** 이름과 값 한 줄. */
@Composable
private fun InfoRow(label: String, value: String, strong: Boolean) {
    Row(Modifier.fillMaxWidth().height(80.dp).djCard(radius = 16.dp).padding(horizontal = 26.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = body(DjType.BodyM, DjColors.Muted), maxLines = 1)
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            style = body(DjType.BodyM, if (strong) DjColors.Gold2 else DjColors.Ink, FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

/** 작은 설명 글. */
@Composable
private fun Note(text: String) {
    Text(text, style = body(DjType.LabelM, DjColors.Muted), modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 4.dp))
}
