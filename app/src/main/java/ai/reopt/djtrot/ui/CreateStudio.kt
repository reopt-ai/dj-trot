package ai.reopt.djtrot.ui

import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import ai.reopt.djtrot.dj.StoryBuilder
import androidx.compose.foundation.horizontalScroll
import ai.reopt.djtrot.DjTrotApp
import ai.reopt.djtrot.account.Connection
import ai.reopt.djtrot.api.Choice
import ai.reopt.djtrot.api.Labels
import ai.reopt.djtrot.api.TrotSong
import ai.reopt.djtrot.car.DriveContext
import ai.reopt.djtrot.dj.Order
import ai.reopt.djtrot.dj.Relay
import ai.reopt.djtrot.dj.SongRequest
import ai.reopt.djtrot.dj.toInput
import ai.reopt.djtrot.playback.Catalog
import ai.reopt.djtrot.playback.MediaPath
import android.content.Intent
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import ai.reopt.djtrot.R
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import java.time.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update

/**
 * 트롯 제작실 — 트롯생성 칸의 전부. 한 곡이 태어나는 과정을 세 막으로 보여 준다.
 *  1막 사연 신청: DJ가 제안한 사연에 주제·가락·목소리를 골라 맡긴다.
 *  2막 제작 중계: 접수 → 작사 → 작곡 → 마무리. 가사가 나오면 제목과 몇 줄을 먼저 보여 준다.
 *  3막 첫 방송: 완성곡을 지금 곡 다음에 올리고, 바로 들을 수도 있다.
 * 🔴 휴대폰 연결(QR 로그인)과 만들기는 정차 중에만 연다 — Pleos 정책상 QR 로그인은 모든 지역에서 주행 중 금지.
 * 🔑 맡긴 뒤로는 맡긴 사연을 보인다 — 그 사이 곡이 바뀌어 DJ가 새 사연을 제안해도 화면이 다른 노래를 말하지 않게.
 * 🔑 주행 중([LocalCalm])에는 움직임을 멈추고 바뀌는 숫자 대신 "약 2분"으로 둔다.
 */
@Composable
fun CreateStudio(app: DjTrotApp, suggestion: SongRequest, djId: String, djName: String, drive: DriveContext, driving: Boolean, nowSongId: String?, nowPlaying: Boolean, player: Player?, modifier: Modifier) {
    val order by app.orders.current.collectAsStateWithLifecycle()
    // 화면을 떠나면 그만 듣는다. 그때까지 들은 말은 사연으로 남는다.
    DisposableEffect(Unit) { onDispose { app.mic.stop() } }
    val act = when {
        order?.working == true -> "relay"
        order?.state == "ready" -> "premiere"
        else -> "request"
    }
    AnimatedContent(
        targetState = act,
        transitionSpec = { fadeIn(tween(if (driving) 120 else 380)) togetherWith fadeOut(tween(if (driving) 120 else 220)) },
        label = "act",
        modifier = modifier,
    ) { shown ->
        val current = order
        when {
            shown == "relay" && current != null -> RelayAct(current, djId, djName)
            shown == "premiere" && current?.song != null -> PremiereAct(current.song, current.request, playing = nowPlaying && current.song.id == nowSongId, onListen = { player?.let { playMade(it, current.song.id) } }, onAgain = app.orders::dismiss)
            else -> RequestAct(app, suggestion, djName, drive, driving, failed = current?.takeIf { it.state == "failed" })
        }
    }
}

/** 완성곡을 지금 튼다 — 서비스가 다음 곡으로 넣어 뒀으면 그 곡으로 넘어가고, 아니면 그 곡만 새로 올린다. */
private fun playMade(player: Player, songId: String) {
    // 무대에 이미 올라 있으면(빈 무대에 완성곡만 올린 때) 그대로 튼다.
    if (player.currentMediaItem?.mediaMetadata?.extras?.getString(Catalog.EXTRA_SONG_ID) == songId) {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        return player.play()
    }
    val next = player.currentMediaItemIndex + 1
    val queued = next < player.mediaItemCount && player.getMediaItemAt(next).mediaMetadata.extras?.getString(Catalog.EXTRA_SONG_ID) == songId
    if (queued) {
        player.seekToNextMediaItem()
    } else {
        player.setMediaItem(MediaItem.Builder().setMediaId(MediaPath.Song(songId).id).build())
        player.prepare()
    }
    player.play()
}

// ── 1막 사연 신청 ───────────────────────────────────────

@Composable
private fun RequestAct(app: DjTrotApp, suggestion: SongRequest, djName: String, drive: DriveContext, driving: Boolean, failed: Order?) {
    val connection by app.account.connection.collectAsStateWithLifecycle()
    val tokens by app.orders.tokens.collectAsStateWithLifecycle()
    val open by app.creationOpen.collectAsStateWithLifecycle()
    // 광고 진행 — 광고가 있고 휴대폰을 연결했을 때만 보인다. "광고 1/3"
    val ads by app.ads.list.collectAsStateWithLifecycle()
    val adProgress by app.ads.progress.collectAsStateWithLifecycle()
    val adLine = adProgress?.takeIf { ads.isNotEmpty() }?.let { if (it.limited) "오늘 광고 생성권 끝" else if (it.capped) "광고 생성권 가득" else "광고 ${it.progress}/${it.needed}" }
    val hearing by app.mic.state.collectAsStateWithLifecycle()
    val dj by app.dj.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val guestOpen by app.creationGuest.collectAsStateWithLifecycle()
    val connected = connection is Connection.Connected
    // 🔑 시연 모드 — 휴대폰을 연결하지 않은 차도 생성권 없이 만든다. 연결한 차는 그대로 생성권을 쓴다.
    val guest = !connected && guestOpen
    val parked = !driving && !drive.noSetup
    // 🔑 고르지 않은 칸은 DJ의 제안을 따른다. 손으로 고른 칸은 제안이 바뀌어도 그대로 둔다.
    var topic by rememberSaveable { mutableStateOf<String?>(null) }
    var style by rememberSaveable { mutableStateOf<String?>(null) }
    var voice by rememberSaveable { mutableStateOf<String?>(null) }
    val choices = Labels.current.choices
    val request = suggestion.copy(
        topic = topic?.takeIf { id -> choices.topic.any { it.id == id } } ?: suggestion.topic,
        style = style?.takeIf { id -> choices.style.any { it.id == id } } ?: suggestion.style,
        voice = voice?.takeIf { id -> choices.voice.any { it.id == id } } ?: suggestion.voice,
    )
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(DjIcons.Sparkle, contentDescription = null, tint = DjColors.Gold, modifier = Modifier.size(34.dp))
            Spacer(Modifier.width(12.dp))
            Text("트롯 제작실", style = headline(DjType.TitleS, DjColors.Gold2), modifier = Modifier.weight(1f), maxLines = 1)
            if (guest) Pill(DjIcons.Sparkle, "시연 · 무료")
            else if (connected && tokens != null) Pill(DjIcons.Note, listOfNotNull("생성권 ${tokens}장", adLine).joinToString(" · "))
            // 알약이 없을 때는 이 칸에서 하는 일을 한 줄로 알린다.
            else Text("사연을 넣고 고르면 노래가 돼요", style = body(DjType.LabelS, DjColors.Muted, FontWeight.SemiBold), maxLines = 1)
        }
        Spacer(Modifier.height(18.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            // 사연 — 이 화면의 주인공이다. DJ가 이 길을 보고 먼저 써 두고, 말하거나 예시를 눌러 바꾼다.
            val mine = dj.freshSpoken() != null
            val listening = hearing.listening
            Column(
                Modifier
                    .fillMaxWidth()
                    .djCard(radius = 28.dp, background = DjColors.CardRaised, line = if (listening) DjColors.Gold else DjColors.GoldRing)
                    .background(Brush.verticalGradient(listOf(DjColors.Cream.copy(alpha = 0.85f), Color.Transparent)))
                    .padding(horizontal = 30.dp, vertical = 20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("이 길의 사연", style = headline(DjType.TitleS, DjColors.Gold2, FontWeight.ExtraBold), modifier = Modifier.weight(1f), maxLines = 1)
                    Pill(if (mine) DjIcons.Mic else DjIcons.Sparkle, if (mine) "내가 넣은 사연" else "$djName 추천", active = mine)
                }
                Spacer(Modifier.height(14.dp))
                when {
                    listening && (driving || hearing.heard.isBlank()) -> Text("듣고 있어요. 사연을 말씀해 주세요", style = headline(DjType.TitleS, DjColors.Gold2, FontWeight.Bold))
                    listening -> Text(quoted(hearing.heard), style = headline(DjType.TitleS, DjColors.Gold2, FontWeight.Bold), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    driving -> Text("정차하면 사연을 보여 드릴게요", style = body(DjType.BodyM, DjColors.Muted))
                    else -> Text(quoted(request.story), style = headline(DjType.TitleS, DjColors.Ink, FontWeight.Bold), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(16.dp))
                // 🔑 말로 넣는 사연(Pleos SpeechToText) — 주행 중에도 손 대신 말로 넣을 수 있다. 이 카드에서 가장 큰 버튼이다.
                MicButton(
                    listening = listening,
                    text = when {
                        listening -> "다 말했어요"
                        else -> "사연 음성 입력"
                    },
                    hint = when {
                        listening -> "말을 마치면 눌러 주세요"
                        mine -> hearing.problem ?: "다시 말하면 사연이 바뀌어요"
                        else -> hearing.problem ?: "눌러서 말해 주세요 · “어머니 뵈러 가는 길이에요”"
                    },
                    onClick = { if (listening) app.mic.stop() else app.mic.listen() },
                )
                // 🔑 예시 사연 — 말하기 어려울 때 한 번 눌러 넣는다(키보드 대신 프리셋). 고르기와 같이 정차 중에만 보인다.
                //    제작실 전체가 한 화면에 들어오게 이름표 없이 버튼만 둔다.
                if (parked && !listening) {
                    Spacer(Modifier.height(14.dp))
                    val ideas = rememberScrollState()
                    Box {
                        Row(Modifier.horizontalScroll(ideas), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            // 넣은 사연이 있으면 되돌리기를 맨 앞에 둔다 — 밀지 않아도 보이게.
                            if (mine) Pill(DjIcons.Refresh, "DJ 추천으로", onClick = { app.dj.update { it.forget().copy(suggestion = null, suggestedAt = null) } })
                            StoryBuilder.IDEAS.forEach { (label, story) ->
                                Pill(null, label, active = dj.freshSpoken() == story, onClick = { app.wish(story) })
                            }
                        }
                        // 🔑 옆으로 더 있다는 표시 — 잘린 쪽 가장자리를 카드 색으로 흐린다.
                        if (ideas.canScrollForward) {
                            Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0.8f to Color.Transparent, 0.95f to IDEA_FADE)), contentAlignment = Alignment.CenterEnd) {
                                Icon(DjIcons.ChevronRight, contentDescription = "옆으로 밀면 더 있어요", tint = DjColors.Gold, modifier = Modifier.size(36.dp))
                            }
                        }
                        if (ideas.canScrollBackward) Box(Modifier.align(Alignment.CenterStart).matchParentSize().background(Brush.horizontalGradient(0f to IDEA_FADE, 0.18f to Color.Transparent)))
                    }
                }
            }
            // 🔑 고르기는 정차 중에만 — 주행 중에는 DJ가 고른 대로 보여 주기만 한다.
            ChoiceGroup("어떤 이야기", choices.topic, request.topic, perRow = 4, enabled = parked) { topic = it }
            ChoiceGroup("어떤 가락", choices.style, request.style, perRow = 3, enabled = parked) { style = it }
            ChoiceGroup("누구 목소리", choices.voice, request.voice, perRow = 2, enabled = parked) { voice = it }
        }
        Spacer(Modifier.height(18.dp))
        failed?.let {
            ErrorNotice(it.problem ?: "노래를 만들지 못했어요.", action = "닫기", onAction = app.orders::dismiss)
            Spacer(Modifier.height(12.dp))
        }
        when {
            !open -> StudioAction(DjIcons.Lock, "지금은 제작실이 쉬고 있어요", "제공 노래를 들으며 기다려 주세요", enabled = false) {}
            guest -> StudioAction(
                icon = DjIcons.Sparkle,
                text = "새로운 트롯 만들기",
                hint = if (parked) "시연 · 무료 · 약 2분" else "정차하면 만들 수 있어요",
                enabled = parked,
                gold = true,
            ) {
                app.orders.create(request, drive.toInput(LocalTime.now(), app.spoken))
                app.dj.update { it.forget() }
            }
            !connected -> StudioAction(
                icon = DjIcons.Phone,
                text = "휴대폰 연결하고 만들기",
                hint = if (parked) "PIN·QR로 1분" else "정차하면 연결할 수 있어요",
                enabled = parked,
            ) { context.startActivity(Intent(context, ConnectActivity::class.java)) }
            else -> StudioAction(
                icon = DjIcons.Sparkle,
                text = "새로운 트롯 만들기",
                hint = when {
                    !parked -> "정차하면 만들 수 있어요"
                    tokens == 0 -> "생성권이 없어요 · 휴대폰에서 받아 주세요"
                    else -> "생성권 1장 · 약 2분"
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

/**
 * 사연 카드의 마이크 버튼 — 무대의 "DJ 트롯 시작"과 같은 금빛 새틴 버튼. 듣는 동안은 어두운 바탕에 금빛 테와 움직이는 막대로 "듣는 중"을 보인다.
 */
@Composable
private fun MicButton(listening: Boolean, text: String, hint: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(26.dp)
    val ink = if (listening) DjColors.Gold2 else DjColors.OnGold
    Box(
        Modifier
            .pressScale(source, depth = 0.97f)
            .fillMaxWidth()
            .height(96.dp)
            .then(if (listening) Modifier else Modifier.shadow(16.dp, shape, ambientColor = DjColors.Gold, spotColor = DjColors.GoldDeep))
            .clip(shape)
            .background(DjColors.Selected)
            .border(if (listening) 2.dp else 1.5.dp, if (listening) DjColors.Gold else DjColors.SatinEdge, shape)
            .tap(source, label = text, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (!listening) {
            Image(painterResource(R.drawable.art_gold_satin), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            // 위쪽 가장자리의 빛 — 금속판이 도톰해 보이게.
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.22f), 0.35f to Color.Transparent, 1f to DjColors.OnGold.copy(alpha = 0.12f))))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 30.dp), verticalAlignment = Alignment.CenterVertically) {
            if (listening) Equalizer(playing = !LocalCalm.current, height = 34.dp, modifier = Modifier.width(44.dp))
            else Icon(DjIcons.Mic, contentDescription = null, tint = ink, modifier = Modifier.size(44.dp))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(text, style = headline(DjType.TitleS, ink, FontWeight.ExtraBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(hint, style = body(DjType.LabelM, ink.copy(alpha = 0.8f), FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!listening) Icon(DjIcons.ChevronRight, contentDescription = null, tint = ink, modifier = Modifier.size(40.dp))
        }
    }
}

/** 예시 사연 줄의 가장자리를 흐리는 색 — 사연 카드의 바탕과 같다. */
private val IDEA_FADE = Color(0xFF1D180F)

/** 사연을 금빛 따옴표로 감싼다 — 따옴표가 글보다 먼저 눈에 들어와 "누군가의 말"임을 알린다. */
private fun quoted(story: String) = buildAnnotatedString {
    withStyle(SpanStyle(color = DjColors.Gold)) { append("“") }
    append(story)
    withStyle(SpanStyle(color = DjColors.Gold)) { append("”") }
}

/** 고르기 한 묶음 — 이름표와 같은 폭의 큰 칩들. 한 줄에 [perRow]개씩 놓는다. */
@Composable
private fun ChoiceGroup(label: String, options: List<Choice>, selected: String, perRow: Int, enabled: Boolean, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 이름표 — 금빛 점과 이름, 흐려지는 선, 오른쪽 끝에 지금 고른 값. 무엇을 골랐는지 묶음을 훑지 않아도 보인다.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(DjColors.Gold))
            Spacer(Modifier.width(10.dp))
            Text(label, style = body(DjType.LabelM, DjColors.Ink, FontWeight.Bold), maxLines = 1)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(DjColors.LineStrong, Color.Transparent))))
            options.firstOrNull { it.id == selected }?.let {
                Spacer(Modifier.width(12.dp))
                Text(it.label, style = body(DjType.LabelM, DjColors.Gold2, FontWeight.Bold), maxLines = 1)
            }
        }
        options.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { option -> ChoiceChip(option.label, option.id == selected, enabled, Modifier.weight(1f)) { onSelect(option.id) } }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ChoiceChip(text: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .then(if (enabled) Modifier.pressScale(source, depth = 0.96f) else Modifier)
            .height(64.dp)
            .clip(shape)
            .background(if (selected) DjColors.Selected else DjColors.Card)
            .border(if (selected) 2.dp else 1.5.dp, if (selected) DjColors.Gold else DjColors.Line, shape)
            .tap(source, enabled = enabled, label = text, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 고른 칩은 색만이 아니라 금빛 점으로도 알린다.
            if (selected) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(DjColors.Gold2))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text,
                style = body(DjType.LabelM, if (selected) DjColors.Gold2 else if (enabled) DjColors.Ink else DjColors.Faint, if (selected) FontWeight.Bold else FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 제작실의 큰 버튼. 금빛은 "DJ 트롯 시작"([SatinButton])과 같은 금속 새틴 판에 그림자를 드리운다 — 이 칸에서 가장 중요한 동작이다.
 * 테두리 버튼은 연결·다시 만들기. 누를 수 없을 때는 자물쇠와 이유를 적는다.
 */
@Composable
private fun StudioAction(icon: ImageVector, text: String, hint: String, enabled: Boolean, gold: Boolean = false, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    if (enabled && gold) {
        val shape = RoundedCornerShape(28.dp)
        Box(
            Modifier
                .pressScale(source, depth = 0.97f)
                .fillMaxWidth()
                .height(116.dp)
                .shadow(18.dp, shape, ambientColor = DjColors.Gold, spotColor = DjColors.GoldDeep)
                .clip(shape)
                .border(1.5.dp, DjColors.SatinEdge, shape)
                .tap(source, label = text, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.art_gold_satin), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            // 위쪽 가장자리의 빛 — 금속판이 도톰해 보이게.
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.22f), 0.35f to Color.Transparent, 1f to DjColors.OnGold.copy(alpha = 0.12f))))
            Row(Modifier.fillMaxWidth().padding(horizontal = 34.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = DjColors.OnGold, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(text, style = headline(DjType.TitleM, DjColors.OnGold, FontWeight.ExtraBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(hint, style = body(DjType.LabelM, DjColors.OnGold.copy(alpha = 0.78f), FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(DjIcons.ChevronRight, contentDescription = null, tint = DjColors.OnGold, modifier = Modifier.size(44.dp))
            }
        }
        return
    }
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .then(if (enabled) Modifier.pressScale(source, depth = 0.98f) else Modifier)
            .fillMaxWidth()
            .height(92.dp)
            .clip(shape)
            .background(DjColors.CardRaised)
            .border(1.5.dp, if (enabled) DjColors.GoldRing else DjColors.LineStrong, shape)
            .tap(source, enabled = enabled, label = text, onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 아이콘은 금빛 고리 안에, 이름 아래에 한 줄 설명, 누를 수 있으면 끝에 화살표 — 금빛 버튼과 같은 짜임이다.
        Box(Modifier.size(56.dp).clip(CircleShape).background(DjColors.Cream).border(1.5.dp, if (enabled) DjColors.GoldRing else DjColors.Line, CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (enabled) icon else DjIcons.Lock, contentDescription = null, tint = if (enabled) DjColors.Gold2 else DjColors.Faint, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = body(DjType.BodyM, if (enabled) DjColors.Gold2 else DjColors.Muted, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(hint, style = body(DjType.LabelS, DjColors.Muted, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (enabled) Icon(DjIcons.ChevronRight, contentDescription = null, tint = DjColors.Gold, modifier = Modifier.size(36.dp))
    }
}

// ── 2막 제작 중계 ───────────────────────────────────────

@Composable
private fun RelayAct(order: Order, djId: String, djName: String) {
    val calm = LocalCalm.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(order.since, calm) {
        while (!calm) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val waited = ((now - order.since) / 1000).coerceAtLeast(0)
    val stage = Relay.stage(order.state, order.progress, waited)
    val title = order.progress?.title?.takeIf { it.isNotBlank() }
    val lines = order.progress?.lines.orEmpty()
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("제작 중계", style = headline(DjType.TitleS, DjColors.Gold2), maxLines = 1)
            Spacer(Modifier.width(16.dp))
            LiveBadge()
            Spacer(Modifier.weight(1f))
            Text(if (calm) "약 2분" else "%d:%02d".format(waited / 60, waited % 60), style = digits(DjType.BodyM, DjColors.Muted))
        }
        Spacer(Modifier.height(26.dp))
        StageTrack(stage)
        Spacer(Modifier.height(26.dp))
        // DJ의 한마디 — 단계가 바뀔 때마다 바뀐다. 글로만 보인다.
        Row(Modifier.fillMaxWidth().djCard(radius = 28.dp, background = DjColors.Selected, line = DjColors.Gold.copy(alpha = 0.55f)).padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
            DjFace(djId, djName, 84.dp)
            Spacer(Modifier.width(22.dp))
            Column(Modifier.weight(1f)) {
                Text(djName, style = body(DjType.LabelS, DjColors.Gold, FontWeight.Bold))
                Spacer(Modifier.height(4.dp))
                AnimatedContent(Relay.comment(stage, djId, title), transitionSpec = { fadeIn(tween(if (calm) 0 else 350)) togetherWith fadeOut(tween(if (calm) 0 else 150)) }, label = "comment") { line ->
                    Text(line, style = body(DjType.BodyM, DjColors.Ink, FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        // 악보 — 가사가 나오기 전에는 맡긴 사연, 나온 뒤에는 제목과 가사 몇 줄.
        Column(Modifier.weight(1f).fillMaxWidth().djCard(radius = 28.dp).padding(horizontal = 32.dp, vertical = 26.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StudioTag(order.request.topicLabel)
                Spacer(Modifier.width(10.dp))
                StudioTag(order.request.styleLabel)
                Labels.voice(order.request.voice)?.let {
                    Spacer(Modifier.width(10.dp))
                    StudioTag(it)
                }
            }
            Spacer(Modifier.height(20.dp))
            if (title == null && lines.isEmpty()) {
                Text("맡긴 사연", style = body(DjType.LabelM, DjColors.Muted, FontWeight.SemiBold))
                Spacer(Modifier.height(8.dp))
                Text("“${order.request.story}”", style = body(DjType.BodyL, DjColors.Ink, FontWeight.SemiBold), maxLines = 4, overflow = TextOverflow.Ellipsis)
            } else {
                Text("방금 나온 가사", style = body(DjType.LabelM, DjColors.Muted, FontWeight.SemiBold))
                Spacer(Modifier.height(8.dp))
                title?.let { Text("‘$it’", style = headline(DjType.TitleL, DjColors.Gold2), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Spacer(Modifier.height(14.dp))
                LyricReveal(lines)
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("완성되면 지금 곡 다음에 바로 틀어 드려요. 다른 화면에 가 계셔도 돼요.", style = body(DjType.LabelM, DjColors.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 가사가 한 줄씩 떠오른다. 주행 중에는 한꺼번에 보인다. */
@Composable
private fun LyricReveal(lines: List<String>) {
    val calm = LocalCalm.current
    var shown by remember(lines) { mutableIntStateOf(if (calm) lines.size else 0) }
    LaunchedEffect(lines, calm) {
        if (calm) {
            shown = lines.size
            return@LaunchedEffect
        }
        while (shown < lines.size) {
            delay(550)
            shown++
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        lines.take(4).forEachIndexed { index, line ->
            AnimatedVisibility(visible = index < shown, enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { it / 3 }) {
                Text(line, style = body(DjType.BodyL, DjColors.Ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 네 단계 — 끝난 칸은 금빛으로 차고, 지금 칸은 금빛 고리가 숨 쉬고, 남은 칸은 어둡다. */
@Composable
private fun StageTrack(stage: Relay.Stage) {
    val calm = LocalCalm.current
    val pulse = if (calm) 1f else rememberInfiniteTransition(label = "stage").animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse").value
    val icons = listOf(DjIcons.Flag, DjIcons.Mic, DjIcons.Note, DjIcons.Sparkle)
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Relay.Stage.entries.forEachIndexed { index, item ->
                val done = index < stage.ordinal
                val active = index == stage.ordinal
                if (index > 0) {
                    val filled by animateFloatAsState(if (index <= stage.ordinal) 1f else 0f, tween(if (calm) 0 else 600), label = "line$index")
                    Box(Modifier.weight(1f).padding(horizontal = 10.dp).height(4.dp).clip(RoundedCornerShape(50)).background(DjColors.Track)) {
                        Box(Modifier.fillMaxWidth(filled).height(4.dp).background(DjColors.Gold))
                    }
                }
                Box(
                    Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(if (done) Brush.verticalGradient(listOf(DjColors.Gold2, DjColors.Gold, DjColors.GoldDeep)) else Brush.verticalGradient(listOf(DjColors.Card, DjColors.Card)))
                        .border(3.dp, if (done) DjColors.SatinEdge else if (active) DjColors.Gold.copy(alpha = pulse) else DjColors.Line, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icons[index],
                        contentDescription = null,
                        tint = if (done) DjColors.OnGold else if (active) DjColors.Gold2 else DjColors.Faint,
                        modifier = Modifier.size(38.dp).graphicsLayer { if (active) alpha = 0.55f + pulse * 0.45f },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Relay.Stage.entries.forEachIndexed { index, item ->
                Text(
                    item.label,
                    style = body(DjType.LabelM, if (index == stage.ordinal) DjColors.Gold2 else if (index < stage.ordinal) DjColors.Gold else DjColors.Faint, if (index == stage.ordinal) FontWeight.Bold else FontWeight.Medium),
                    modifier = Modifier.width(84.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(stage.doing, style = body(DjType.LabelM, DjColors.Muted), maxLines = 1)
    }
}

@Composable
private fun LiveBadge() {
    val calm = LocalCalm.current
    val glow = if (calm) 1f else rememberInfiniteTransition(label = "live").animateFloat(0.35f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "dot").value
    val shape = RoundedCornerShape(50)
    Row(Modifier.clip(shape).background(DjColors.ErrorBg).border(1.5.dp, DjColors.ErrorLine, shape).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).graphicsLayer { alpha = glow }.clip(CircleShape).background(DjColors.Pink))
        Spacer(Modifier.width(8.dp))
        Text("LIVE", style = body(DjType.LabelS, DjColors.ErrorInk, FontWeight.Bold))
    }
}

@Composable
private fun DjFace(djId: String, djName: String, size: androidx.compose.ui.unit.Dp) {
    val avatar = Art.dj(djId)
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(DjColors.Gold2, DjColors.Gold, DjColors.GoldDeep)))
            .border(2.dp, DjColors.SatinEdge, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (avatar != null) Image(painterResource(avatar), contentDescription = djName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(DjIcons.Mic, contentDescription = null, tint = DjColors.OnGold, modifier = Modifier.size(size / 2))
    }
}

// ── 3막 첫 방송 ────────────────────────────────────────

@Composable
private fun PremiereAct(song: TrotSong, request: SongRequest, playing: Boolean, onListen: () -> Unit, onAgain: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(DjIcons.Sparkle, contentDescription = null, tint = DjColors.Gold, modifier = Modifier.size(34.dp))
            Spacer(Modifier.width(12.dp))
            Text("첫 방송", style = headline(DjType.TitleS, DjColors.Gold2), maxLines = 1)
        }
        Spacer(Modifier.height(22.dp))
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .djCard(radius = 32.dp, background = DjColors.Selected, line = DjColors.Gold.copy(alpha = 0.7f))
                .background(Brush.verticalGradient(listOf(DjColors.Gold.copy(alpha = 0.14f), Color.Transparent)))
                .padding(horizontal = 36.dp, vertical = 32.dp),
        ) {
            Text(if (playing) "지금 방송 중" else "이 길에서 태어난 새 노래", style = body(DjType.LabelM, DjColors.Gold, FontWeight.Bold))
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("‘${song.title}’", style = headline(DjType.HeadlineS, DjColors.Gold2), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (playing) {
                    Spacer(Modifier.width(20.dp))
                    Equalizer(playing = true, height = 44.dp)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row {
                StudioTag(song.labels.topic.ifBlank { request.topicLabel })
                Spacer(Modifier.width(10.dp))
                StudioTag(song.labels.style.ifBlank { request.styleLabel })
            }
            Spacer(Modifier.height(24.dp))
            // 짧은 창에서는 넘치는 줄을 잘라 아래 사연과 겹치지 않게 한다.
            Column(Modifier.weight(1f).clipToBounds(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                song.lyrics.lines.take(4).forEach { line -> Text(line, style = body(DjType.BodyL, DjColors.Ink), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Text("“${request.story}”", style = body(DjType.LabelM, DjColors.Muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(18.dp))
        if (!playing) {
            StudioAction(DjIcons.Play, "지금 바로 듣기", "그대로 두면 다음 곡으로 나와요", enabled = true, gold = true, onClick = onListen)
            Spacer(Modifier.height(12.dp))
        }
        StudioAction(DjIcons.Sparkle, "한 곡 더 만들기", "새 사연으로", enabled = true, onClick = onAgain)
    }
}

@Composable
private fun StudioTag(text: String) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        text,
        style = body(DjType.LabelS, DjColors.Gold2, FontWeight.SemiBold),
        maxLines = 1,
        modifier = Modifier.clip(shape).background(DjColors.Cream).border(1.dp, DjColors.LineStrong, shape).padding(horizontal = 14.dp, vertical = 6.dp),
    )
}
