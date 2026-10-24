package ai.reopt.djtrot.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 표지 뒤에서 빠져나오는 레코드판 — DJ가 판을 거는 느낌.
 * 🔑 판(홈·반사광)은 그대로 두고 가운데 금빛 라벨만 돈다. 실제 판도 반사광은 조명 쪽에 머문다.
 *    멈추면 그 자리에서 멈추고, 다시 틀면 이어서 돈다. 주행 중([LocalCalm])에는 돌지 않는다.
 */
@Composable
fun Vinyl(playing: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val spin = remember { Animatable(0f) }
    val turning = playing && !LocalCalm.current
    LaunchedEffect(turning) {
        if (!turning) return@LaunchedEffect
        while (true) {
            spin.animateTo(spin.value + 360f, tween(REVOLUTION_MS, easing = LinearEasing))
            spin.snapTo(spin.value % 360f)
        }
    }
    Box(modifier.size(size)) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2
            drawCircle(Color(0xFF0B0A08), r)
            var groove = r * 0.96f
            while (groove > r * 0.4f) {
                drawCircle(Color(0xFF2A2419), groove, style = Stroke(1.dp.toPx()))
                groove -= r * 0.038f
            }
            drawCircle(
                Brush.sweepGradient(
                    0f to Color.Transparent,
                    0.08f to DjColors.Gold2.copy(alpha = 0.16f),
                    0.16f to Color.Transparent,
                    0.5f to Color.Transparent,
                    0.58f to DjColors.Gold2.copy(alpha = 0.10f),
                    0.66f to Color.Transparent,
                    1f to Color.Transparent,
                ),
                r,
            )
            drawCircle(DjColors.GoldDeep.copy(alpha = 0.7f), r - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        }
        Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = spin.value }) {
            val r = this.size.minDimension / 2
            val label = r * 0.34f
            drawCircle(Brush.linearGradient(listOf(DjColors.Gold2, DjColors.Gold, DjColors.GoldDeep), start = Offset(center.x - label, center.y - label), end = Offset(center.x + label, center.y + label)), label)
            // 라벨 위 반달 무늬와 점 — 이것이 돌아 판이 도는 게 보인다.
            drawArc(
                DjColors.OnGold.copy(alpha = 0.22f),
                startAngle = 200f,
                sweepAngle = 80f,
                useCenter = true,
                topLeft = Offset(center.x - label * 0.82f, center.y - label * 0.82f),
                size = androidx.compose.ui.geometry.Size(label * 1.64f, label * 1.64f),
            )
            drawCircle(DjColors.OnGold.copy(alpha = 0.55f), label * 0.07f, Offset(center.x + label * 0.62f, center.y))
            drawCircle(DjColors.OnGold.copy(alpha = 0.35f), label * 0.92f, style = Stroke(1.dp.toPx()))
            drawCircle(DjColors.Black, r * 0.035f)
        }
    }
}

private const val REVOLUTION_MS = 3_600
