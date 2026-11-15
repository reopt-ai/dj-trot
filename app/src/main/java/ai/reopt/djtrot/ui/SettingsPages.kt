package ai.reopt.djtrot.ui

import ai.reopt.djtrot.BuildConfig
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.fillMaxSize
import ai.reopt.djtrot.DjTrotApp
import ai.reopt.djtrot.Server
import ai.reopt.djtrot.account.Connection
import ai.reopt.djtrot.api.DjInfo
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
import androidx.compose.foundation.clickable
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
internal fun SettingsTile(icon: ImageVector, title: String, description: String, badge: String?, active: Boolean, modifier: Modifier = Modifier, @DrawableRes avatar: Int? = null, onClick: () -> Unit) {
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
        // 아이콘 받침 — 테 없이 은은한 원만 둔다. DJ처럼 얼굴 그림이 있으면 원을 그림으로 채운다.
        Box(Modifier.size(76.dp).clip(CircleShape).background(DjColors.Cream), contentAlignment = Alignment.Center) {
            if (avatar != null) Image(painterResource(avatar), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(icon, contentDescription = null, tint = DjColors.Gold2, modifier = Modifier.size(40.dp))
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

/**
 * DJ 설정 — DJ 고르기, 멘트 켜기·끄기, 길게 말하는 간격, 멘트 중 음악 크기. 값은 기기에만 저장한다.
 * 🔑 DJ마다 목소리와 말투가 다르다. 서버에 음성 팩이 준비된 DJ만 고를 수 있다.
 */
@Composable
internal fun ColumnScope.DjSettingsPage(app: DjTrotApp, djs: List<DjInfo>, djId: String, talks: Boolean, onToggleTalks: () -> Unit) {
    var every by remember { mutableIntStateOf(app.fullEvery) }
    var duck by remember { mutableFloatStateOf(app.duckLevel) }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GroupTitle("DJ 고르기", DjIcons.Disc)
        djs.forEach { dj ->
            SettingsTile(
                DjIcons.Mic,
                dj.name,
                if (dj.ready) dj.description else "${dj.description} · 준비 중이에요",
                badge = if (dj.id == djId) "진행 중" else null,
                active = dj.id == djId,
                modifier = Modifier.fillMaxWidth(),
                avatar = Art.dj(dj.id),
            ) { app.selectDj(dj.id) }
        }
        Note("다음 멘트부터 고른 DJ가 말해요. 처음 고르면 그 DJ의 멘트를 받아 둬요.")
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
    val demoLinked by app.demoLinked.collectAsStateWithLifecycle()
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GroupTitle("휴대폰 연결", DjIcons.Phone)
        InfoRow("상태", if (connected) "연결됨" else if (demoLinked) "시연 연결됨" else "연결 안 됨", strong = connected || demoLinked)
        if (!connected && demoLinked) {
            // 🔑 시연 연결 — 휴대폰 없이 흐름만 체험한 상태다. 실제 계정은 묶여 있지 않다.
            PillButton("시연 연결 끊기", "시연 연결을 지우고 연결 전으로 돌아가요", selected = false, Modifier.fillMaxWidth(), height = 84.dp, icon = DjIcons.Refresh) { app.linkDemo(false) }
            Note("시연 연결이에요. 실제 계정은 연결되지 않았고, 실제 서비스에서는 휴대폰 로그인으로 연결해요.")
        } else if (!connected) {
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
    // 개발자 모드 — 버전을 [DEVELOPER_TAPS]번 누르면 켜진다. 서버를 바꾸고 연결을 확인한다.
    var developer by remember { mutableStateOf(app.developer) }
    var taps by remember { mutableIntStateOf(0) }
    var chosen by remember { mutableStateOf(app.server) }
    var checking by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf<String?>(null) }
    var resetting by remember { mutableStateOf(false) }
    val trace by app.lastBrief.collectAsStateWithLifecycle()
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GroupTitle("DJ 트롯", DjIcons.Disc)
        InfoRow("버전", BuildConfig.VERSION_NAME, strong = true) {
            if (developer) return@InfoRow
            taps += 1
            if (taps >= DEVELOPER_TAPS) {
                developer = true
                app.developer = true
            }
        }
        if (!developer && taps in 3 until DEVELOPER_TAPS) Note("개발자 모드까지 ${DEVELOPER_TAPS - taps}번 남았어요.")
        InfoRow("서버", "${app.server.label} · ${hostOf(app.server.url)}", strong = false)
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
        if (developer) {
            GroupTitle("개발자 모드", DjIcons.Settings)
            Choices(Server.entries.sortedBy { it.production }.map { it to it.label }, chosen) { chosen = it }
            Note("${chosen.label} 서버 · ${chosen.url}")
            // 🔑 로컬 서버는 평문(HTTP)이라 디버그 빌드에서만 닿는다.
            val blocked = !BuildConfig.DEBUG && chosen.url.startsWith("http://")
            if (blocked) Note("이 빌드에서는 평문(HTTP) 로컬 서버에 연결할 수 없어요. 디버그 빌드에서 써 주세요.")
            if (chosen != app.server && !blocked) {
                PillButton("${chosen.label} 서버로 다시 시작", "서버를 바꾸고 앱을 다시 시작해요", selected = true, Modifier.fillMaxWidth(), height = 84.dp, icon = DjIcons.Refresh) { app.switchServer(chosen) }
                Note("앱이 다시 시작돼요. 재생 중인 노래는 멈추고, 휴대폰 연결과 DJ 멘트는 서버마다 따로 보관해요.")
            }
            PillButton(if (checking) "확인하는 중…" else "지금 서버 연결 확인", "지금 보는 서버가 답하는지 확인해요", selected = false, Modifier.fillMaxWidth(), height = 80.dp) {
                if (checking) return@PillButton
                checking = true
                checked = null
                scope.launch {
                    val started = System.currentTimeMillis()
                    checked = runCatching { app.api.config() }.fold(
                        onSuccess = { config -> "연결됨 · ${System.currentTimeMillis() - started}ms · 준비된 DJ ${config.djs.count { it.ready }}명" },
                        onFailure = { "연결하지 못했어요 · ${it.message ?: it.javaClass.simpleName}" },
                    )
                    checking = false
                }
            }
            checked?.let { Note(it) }
            GroupTitle("마지막 DJ 요청", DjIcons.Mic)
            trace.let { last ->
                if (last == null) Note("아직 없어요. 노래가 시작되거나 시뮬레이션 버튼을 누르면 여기에 남아요.")
                else Column(Modifier.fillMaxWidth().djCard(radius = 16.dp).padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TraceBlock("언제", last.at.format(TRACE_TIME))
                    TraceBlock("서버에 보낸 것", last.request ?: "서버에 묻지 않았어요 — 받아 둔 조각으로 말했어요.")
                    TraceBlock("받은 것", last.response)
                    if (last.local.isNotEmpty()) TraceBlock("기기가 고른 조각", last.local.joinToString(" → "))
                }
            }
            GroupTitle("빌드", DjIcons.Note)
            InfoRow("커밋", BuildConfig.GIT_COMMIT.ifEmpty { "알 수 없음" }, strong = false)
            InfoRow("빌드한 때", BuildConfig.BUILD_TIME, strong = false)
            InfoRow("빌드 종류", if (BuildConfig.DEBUG) "디버그" else "릴리스", strong = false)
            GroupTitle("초기화", DjIcons.Refresh)
            if (!resetting) {
                PillButton("받아 둔 데이터 지우기", "DJ 멘트 팩과 DJ 설정을 지우고 처음 상태로 돌아가요", selected = false, Modifier.fillMaxWidth(), height = 80.dp) { resetting = true }
            } else {
                Note("받아 둔 DJ 멘트와 DJ 설정(멘트 켜기·간격·음악 크기·고른 DJ)을 지우고 앱을 다시 시작해요. 휴대폰 연결은 그대로예요.")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("지우고 다시 시작", "지우고 앱을 다시 시작해요", selected = true, Modifier.weight(1f), height = 80.dp) { app.resetData() }
                    PillButton("취소", "지우지 않아요", selected = false, Modifier.weight(1f), height = 80.dp) { resetting = false }
                }
            }
            GroupTitle("개발자 모드 닫기", DjIcons.Settings)
            PillButton("개발자 모드 끄기", "개발자 모드를 숨겨요", selected = false, Modifier.fillMaxWidth(), height = 72.dp) {
                developer = false
                app.developer = false
                taps = 0
            }
        }
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

private val TRACE_TIME: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")

/** 요청 기록의 한 덩이 — 작은 이름과 그 아래 내용(줄바꿈을 그대로 보인다). */
@Composable
private fun TraceBlock(label: String, value: String) {
    Column {
        Text(label, style = body(DjType.LabelM, DjColors.Gold, FontWeight.Bold))
        Text(value, style = body(DjType.LabelM, DjColors.Ink))
    }
}

/** 개발자 모드를 켜려면 버전을 눌러야 하는 횟수. */
private const val DEVELOPER_TAPS = 7

private fun hostOf(url: String): String = runCatching { java.net.URI(url).let { uri -> if (uri.port > 0) "${uri.host}:${uri.port}" else uri.host } }.getOrNull() ?: "알 수 없음"

/** 이름과 값 한 줄. [onClick]이 있으면 누를 수 있다(누르는 표시는 내지 않는다 — 숨은 동작용). */
@Composable
private fun InfoRow(label: String, value: String, strong: Boolean, onClick: (() -> Unit)? = null) {
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().height(80.dp).djCard(radius = 16.dp)
            .then(if (onClick != null) Modifier.clickable(interactionSource = source, indication = null, onClick = onClick) else Modifier)
            .padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
