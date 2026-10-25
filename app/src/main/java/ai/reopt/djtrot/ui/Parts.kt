package ai.reopt.djtrot.ui

import ai.reopt.djtrot.R
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ripple
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import java.util.Locale

/**
 * 한국어는 어절(띄어쓰기) 단위로 줄을 바꾼다 — "악/단의"처럼 낱말 중간에서 끊기면 한눈에 읽히지 않는다.
 * Android 13부터 적용되고, 그 아래에서는 기본 줄바꿈이다.
 */
private val KoreanLines = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)

/** 제목류 글자 — 마스터트롯처럼 자간을 좁혀 묵직하게. */
fun headline(size: TextUnit, color: Color = DjColors.Ink, weight: FontWeight = FontWeight.ExtraBold) =
    TextStyle(color = color, fontSize = size, fontWeight = weight, letterSpacing = (-0.035).em, lineHeight = size * 1.22f, lineBreak = KoreanLines)

fun body(size: TextUnit, color: Color = DjColors.Ink, weight: FontWeight = FontWeight.Normal) =
    TextStyle(color = color, fontSize = size, fontWeight = weight, letterSpacing = (-0.02).em, lineHeight = size * 1.4f, lineBreak = KoreanLines)

/** 시간 숫자 — 자리가 흔들리지 않게 고정폭 숫자. */
fun digits(size: TextUnit, color: Color = DjColors.Muted) =
    TextStyle(color = color, fontSize = size, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")

/** 곡 길이·재생 위치 — 2:58. */
fun clock(ms: Long): String {
    val seconds = ms / 1000
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

/** 검정 바탕에 가는 테두리를 두른 카드. */
fun Modifier.djCard(radius: Dp = 28.dp, background: Color = DjColors.Card, line: Color = DjColors.Line) =
    clip(RoundedCornerShape(radius)).background(background).border(1.5.dp, line, RoundedCornerShape(radius))

/**
 * 누르면 살짝 눌려 들어가는 조작. 차 화면은 손끝이 화면을 가려 눌렸는지 알기 어렵다.
 * 🔑 크기는 그리기 단계(graphicsLayer)에서만 바뀌어 배치가 흔들리지 않는다. 체인 맨 앞에 두고, 같은 [source]를 [tap]에 넘긴다.
 */
@Composable
fun Modifier.pressScale(source: InteractionSource, depth: Float = 0.94f): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) depth else 1f, spring(dampingRatio = 0.55f, stiffness = 700f), label = "press")
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** 금빛 물결이 번지는 누르기. */
fun Modifier.tap(source: MutableInteractionSource, enabled: Boolean = true, label: String? = null, onClick: () -> Unit) =
    clickable(interactionSource = source, indication = ripple(color = DjColors.Gold2), enabled = enabled, onClickLabel = label, onClick = onClick)

/**
 * 곡 표지. 커버를 올린 곡은 서버 그림을, 아니면 장르 그림을 [seed](곡 ID)에 따라 잘라 쓴다([Art.look]).
 * 🔑 서버 그림을 받지 못해도 장르 그림으로 남는다.
 */
@Composable
fun Cover(artworkUri: Uri?, style: String?, size: Dp, radius: Dp, modifier: Modifier = Modifier, seed: String? = null) {
    val fallback = painterResource(Art.cover(style))
    val shape = RoundedCornerShape(radius)
    val frame = modifier.size(size).clip(shape).background(DjColors.Cream).border(1.5.dp, DjColors.LineStrong, shape)
    if (artworkUri != null) {
        AsyncImage(model = artworkUri.toString(), contentDescription = null, placeholder = fallback, error = fallback, fallback = fallback, contentScale = ContentScale.Crop, modifier = frame)
    } else {
        val look = Art.look(seed)
        Box(frame) {
            Image(
                fallback,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().graphicsLayer {
                    scaleX = if (look.mirror) -look.zoom else look.zoom
                    scaleY = look.zoom
                    translationX = look.dx * this.size.width
                    translationY = look.dy * this.size.height
                },
            )
        }
    }
}

/** 번호 붙은 순서 한 줄 — 휴대폰 연결 안내, 노래 만드는 순서. */
@Composable
fun Step(number: Int, text: String) {
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(DjColors.Selected).border(1.5.dp, DjColors.LineStrong, CircleShape), contentAlignment = Alignment.Center) {
            Text("$number", style = digits(DjType.LabelM, DjColors.Gold2).copy(fontWeight = FontWeight.Bold))
        }
        Spacer(Modifier.width(20.dp))
        Text(text, style = body(DjType.BodyM, DjColors.Ink))
    }
}

/** 금빛 새틴 버튼 — 화면에서 가장 중요한 동작 하나에만 쓴다. */
@Composable
fun SatinButton(text: String, modifier: Modifier = Modifier, height: Dp = 112.dp, icon: ImageVector? = DjIcons.ChevronRight, onClick: () -> Unit) {
    val shape = RoundedCornerShape(height * 0.24f)
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(source, depth = 0.97f)
            .height(height)
            .shadow(18.dp, shape, ambientColor = DjColors.Gold, spotColor = DjColors.GoldDeep)
            .clip(shape)
            .border(1.5.dp, DjColors.SatinEdge, shape)
            .tap(source, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.art_gold_satin), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text, style = headline(if (height >= 100.dp) DjType.TitleL else DjType.BodyL, DjColors.OnGold, FontWeight.ExtraBold))
            if (icon != null) Icon(icon, contentDescription = null, tint = DjColors.OnGold, modifier = Modifier.size(height * 0.36f))
        }
    }
}

/** 재생 버튼 — 금빛 새틴 원. 불러오는 중에는 원 안에서 돈다. */
@Composable
fun PlayButton(playing: Boolean, loading: Boolean, size: Dp, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .pressScale(source)
            .size(size)
            .drawBehind { drawCircle(Brush.radialGradient(listOf(DjColors.Gold.copy(alpha = 0.35f), Color.Transparent), radius = this.size.minDimension * 0.85f), radius = this.size.minDimension * 0.85f) }
            .shadow(20.dp, CircleShape, ambientColor = DjColors.Gold, spotColor = DjColors.GoldDeep)
            .clip(CircleShape)
            .border(2.dp, DjColors.SatinEdge, CircleShape)
            .tap(source, label = if (playing) "일시정지" else "재생", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.art_gold_satin), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        if (loading) {
            CircularProgressIndicator(color = DjColors.OnGold, strokeWidth = 5.dp, modifier = Modifier.size(size * 0.42f))
        } else {
            Icon(if (playing) DjIcons.Pause else DjIcons.Play, contentDescription = if (playing) "일시정지" else "재생", tint = DjColors.OnGold, modifier = Modifier.size(size * 0.5f))
        }
    }
}

/** 이전·다음 같은 둥근 보조 버튼 — 금테 검정 원. */
@Composable
fun RingButton(icon: ImageVector, label: String, size: Dp, enabled: Boolean = true, onClick: () -> Unit) {
    val ink = if (enabled) DjColors.Gold2 else DjColors.Faint.copy(alpha = 0.5f)
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .pressScale(source)
            .size(size)
            .clip(CircleShape)
            .background(DjColors.CardRaised)
            .border(2.dp, if (enabled) DjColors.GoldRing.copy(alpha = 0.8f) else DjColors.Line, CircleShape)
            .tap(source, enabled = enabled, label = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = ink, modifier = Modifier.size(size * 0.46f))
    }
}

/**
 * 주행 중이면 참 — 반복해서 움직이는 장식(레코드 회전·막대·깜빡임)을 멈춘다.
 * 🔑 Pleos Driver Distraction Guideline: "Minimize automatic animations", 장식으로 반복해 움직이는 요소를 피한다.
 */
val LocalCalm = compositionLocalOf { false }

/** 재생 중 표시 — 금빛 막대 네 개. 멈추면 낮게 서고, 주행 중에는 움직이지 않는 모양으로 선다. */
@Composable
fun Equalizer(playing: Boolean, height: Dp, modifier: Modifier = Modifier, color: Color = DjColors.Gold) {
    if (LocalCalm.current || !playing) {
        Bars(if (playing) listOf(0.55f, 1f, 0.75f, 0.4f) else List(4) { 0.25f }, height, color, modifier)
        return
    }
    val transition = rememberInfiniteTransition(label = "equalizer")
    val bars = listOf(520, 380, 460, 300).mapIndexed { i, period ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(period, delayMillis = i * 70), RepeatMode.Reverse),
            label = "bar$i",
        )
    }
    Bars(bars.map { it.value }, height, color, modifier)
}

@Composable
private fun Bars(levels: List<Float>, height: Dp, color: Color, modifier: Modifier) {
    Row(modifier.height(height), horizontalArrangement = Arrangement.spacedBy(height * 0.14f), verticalAlignment = Alignment.Bottom) {
        levels.forEach { level ->
            Box(
                Modifier
                    .width(height * 0.16f)
                    .fillMaxHeight(level)
                    .clip(RoundedCornerShape(50))
                    .background(Brush.verticalGradient(listOf(DjColors.Gold2, color, DjColors.GoldDeep))),
            )
        }
    }
}

/**
 * 목록 오른쪽 가장자리의 스크롤 막대 — Pleos Scroll bar. 목록이 창보다 길 때만 그린다.
 * 줄 높이가 거의 같다는 가정으로 전체 길이를 어림한다.
 */
fun Modifier.scrollbar(state: LazyListState, width: Dp = 6.dp): Modifier = drawWithContent {
    drawContent()
    val info = state.layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty() || !(state.canScrollForward || state.canScrollBackward)) return@drawWithContent
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    val row = visible.sumOf { it.size + info.mainAxisItemSpacing }.toFloat() / visible.size
    val content = row * info.totalItemsCount
    if (content <= viewport) return@drawWithContent
    val scrolled = state.firstVisibleItemIndex * row + state.firstVisibleItemScrollOffset
    val bar = width.toPx()
    val thumb = (viewport * viewport / content).coerceIn(56.dp.toPx(), viewport)
    val top = (viewport - thumb) * (scrolled / (content - viewport)).coerceIn(0f, 1f)
    val x = size.width - bar
    drawRoundRect(DjColors.Line, topLeft = Offset(x, 0f), size = Size(bar, viewport), cornerRadius = CornerRadius(bar / 2))
    drawRoundRect(DjColors.Gold.copy(alpha = 0.7f), topLeft = Offset(x, top), size = Size(bar, thumb), cornerRadius = CornerRadius(bar / 2))
}

/** 탭 — 둥근 칸 안에서 고른 칸만 금빛. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(80.dp)
            .djCard(radius = 20.dp, background = DjColors.Segment)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(15.dp))
                    .background(if (on) DjColors.Selected else Color.Transparent)
                    .then(if (on) Modifier.border(1.5.dp, DjColors.LineStrong, RoundedCornerShape(15.dp)) else Modifier)
                    .clickable(interactionSource = null, indication = ripple(color = DjColors.Gold2)) { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), style = body(DjType.BodyS, if (on) DjColors.Gold2 else DjColors.Muted, if (on) FontWeight.Bold else FontWeight.Medium))
            }
        }
    }
}

/** 작은 알약 — 아이콘 + 글자. */
@Composable
fun Pill(icon: ImageVector?, text: String, modifier: Modifier = Modifier, active: Boolean = false, onClick: (() -> Unit)? = null) {
    PillFrame(modifier, active, onClick) {
        if (icon != null) Icon(icon, contentDescription = null, tint = if (active) DjColors.Gold2 else DjColors.Gold, modifier = Modifier.size(28.dp))
        Text(text, style = body(DjType.LabelM, if (active) DjColors.Gold2 else DjColors.Ink, FontWeight.SemiBold), maxLines = 1)
    }
}

/** 배터리 알약 — 남은 양만큼 채운 전지 그림. 20% 아래는 분홍으로 경고한다. */
@Composable
fun BatteryPill(percent: Int, modifier: Modifier = Modifier) {
    val level = (percent / 100f).coerceIn(0f, 1f)
    val fill = if (percent <= 20) DjColors.Pink else DjColors.Ok
    PillFrame(modifier, active = false, onClick = null) {
        Canvas(Modifier.size(width = 38.dp, height = 20.dp)) {
            val stroke = 2.dp.toPx()
            val cap = 3.dp.toPx()
            val body = Size(size.width - cap - 1.dp.toPx(), size.height)
            drawRoundRect(DjColors.Gold, size = body, cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(stroke))
            drawRoundRect(DjColors.Gold, topLeft = Offset(body.width + 1.dp.toPx(), size.height * 0.3f), size = Size(cap, size.height * 0.4f), cornerRadius = CornerRadius(1.dp.toPx()))
            val inset = stroke + 1.5.dp.toPx()
            drawRoundRect(fill, topLeft = Offset(inset, inset), size = Size((body.width - inset * 2) * level, body.height - inset * 2), cornerRadius = CornerRadius(2.dp.toPx()))
        }
        Text("$percent%", style = digits(DjType.LabelM, DjColors.Ink).copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
    }
}

@Composable
private fun PillFrame(modifier: Modifier, active: Boolean, onClick: (() -> Unit)?, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(50)
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .then(if (onClick != null) Modifier.pressScale(source, depth = 0.96f) else Modifier)
            .height(64.dp)
            .clip(shape)
            .background(if (active) DjColors.Selected else DjColors.Card)
            .border(1.5.dp, if (active) DjColors.Gold.copy(alpha = 0.75f) else DjColors.Line, shape)
            .then(if (onClick != null) Modifier.tap(source, onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** 문제 안내. 필요하면 다시 해 볼 동작 하나를 붙인다. */
@Composable
fun ErrorNotice(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .djCard(radius = 20.dp, background = DjColors.ErrorBg, line = DjColors.ErrorLine)
            .padding(horizontal = 26.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = body(DjType.LabelM, DjColors.ErrorInk), modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Spacer(Modifier.width(16.dp))
            Pill(DjIcons.Refresh, action, onClick = onAction)
        }
    }
}

/** 앱 표식 — 런처 아이콘과 같은 그림. */
@Composable
fun AppMark(size: Dp) {
    Box(
        Modifier.size(size).djCard(radius = size * 0.24f, background = DjColors.Paper, line = DjColors.LineStrong),
        contentAlignment = Alignment.Center,
    ) {
        // 런처 그림은 안전 영역 여백이 있어 키워서 채운다.
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(size * 1.32f))
    }
}
