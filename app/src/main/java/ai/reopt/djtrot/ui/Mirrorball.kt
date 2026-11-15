package ai.reopt.djtrot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 미러볼 — 마스터트롯의 "작곡 중" 무대에 걸린 것과 같은 그림이다(거울 조각을 구면에 놓고 세로축으로 돌려 매 프레임 비춘다).
 * 줄에 매달려 있고, 뒤로 은은한 빛무리가 번진다. 폭은 [size], 높이는 줄 길이만큼 더 길다(폭의 1.3배).
 * 🔑 [spinning]이 거짓이면(주행 중) 돌지 않고 멈춘 그림만 보인다 — 움직이는 장식으로 시선을 끌지 않는다.
 * 🔑 조각 색과 빛의 식은 마스터트롯 것을 그대로 옮겼다. 그쪽을 바꾸면 여기도 맞춘다.
 */
@Composable
fun Mirrorball(size: Dp, spinning: Boolean, modifier: Modifier = Modifier) {
    var angle by remember { mutableFloatStateOf(0.6f) }
    LaunchedEffect(spinning) {
        if (!spinning) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                // 초당 30장이면 충분하다 — 그보다 자주 그리지 않는다.
                if (last == 0L) last = now
                val elapsed = (now - last) / 1_000_000f
                if (elapsed >= FRAME_MS) {
                    angle += min(elapsed, 100f) * RADIANS_PER_MS
                    last = now
                }
            }
        }
    }
    val tile = remember { Path() }
    Canvas(modifier.size(width = size, height = size * HEIGHT_RATIO)) {
        val radius = this.size.width * 0.44f
        val center = Offset(this.size.width / 2f, this.size.height - this.size.width / 2f)
        // 빛무리 — 공 뒤로 번지는 보랏빛.
        drawCircle(Brush.radialGradient(listOf(HALO, Color.Transparent), center, radius * 1.9f), radius * 1.9f, center)
        // 줄과 고리.
        drawLine(Brush.verticalGradient(listOf(Color(0x15FFFFFF), Color(0xA0DAD2E5)), 0f, center.y - radius), Offset(center.x, 0f), Offset(center.x, center.y - radius), strokeWidth = max(1f, radius * 0.012f))
        drawRect(Color(0xFFB9B2C6), Offset(center.x - radius * 0.06f, center.y - radius * 1.06f), Size(radius * 0.12f, radius * 0.1f))
        drawCircle(BASE, radius, center)
        for (row in 0 until ROWS) {
            val latitude = (-PI / 2 + (row + 0.5) * PI / ROWS).toFloat()
            val columns = max(8, (COLUMNS * cos(latitude)).roundToInt())
            val dLat = (PI / ROWS * 0.45).toFloat()
            val dLon = (PI * 2 / columns * 0.45).toFloat()
            for (col in 0 until columns) {
                val longitude = (col * PI * 2 / columns).toFloat() + angle
                val nx = cos(latitude) * sin(longitude)
                val ny = sin(latitude)
                val nz = cos(latitude) * cos(longitude)
                if (nz <= 0.025f) continue
                val variation = ((sin(row * 127.1 + col * 311.7) * 43758.5453) % 1).toFloat()
                val highlight = max(0f, nx * -0.45f + ny * -0.4f + nz * 0.8f).pow(20)
                val glow = max(0f, nx * 0.7f + ny * 0.1f + nz * 0.7f).pow(30)
                val brightness = min(98f, 26f + nz * 37f + variation * 24f + highlight * 65f + glow * 45f).coerceAtLeast(0f)
                tile.reset()
                corner(tile, center, radius, latitude - dLat, longitude - dLon, first = true)
                corner(tile, center, radius, latitude - dLat, longitude + dLon)
                corner(tile, center, radius, latitude + dLat, longitude + dLon)
                corner(tile, center, radius, latitude + dLat, longitude - dLon)
                tile.close()
                // 금빛 조명이 닿는 조각은 따뜻한 색, 나머지는 차가운 보랏빛 은색.
                drawPath(tile, Color.hsl(if (glow > 0.15f) 40f else 245f, if (highlight > 0.3f) 0.08f else 0.12f, brightness / 100f))
            }
        }
        // 공 전체의 윤기와 가장자리 그늘.
        drawCircle(
            Brush.radialGradient(0f to Color(0x18FFFFFF), 0.7f to Color.Transparent, 1f to Color(0x50000000), center = Offset(center.x - radius * 0.34f, center.y - radius * 0.34f), radius = radius * 1.34f),
            radius, center,
        )
    }
}

private fun corner(path: Path, center: Offset, radius: Float, latitude: Float, longitude: Float, first: Boolean = false) {
    val x = center.x + cos(latitude) * sin(longitude) * radius
    val y = center.y + sin(latitude) * radius
    if (first) path.moveTo(x, y) else path.lineTo(x, y)
}

private const val ROWS = 26
private const val COLUMNS = 54
private const val FRAME_MS = 1000f / 30f
private const val RADIANS_PER_MS = 0.00044f
/** 줄 길이까지 넣은 높이 — 폭의 몇 배인가. */
private const val HEIGHT_RATIO = 1.3f
private val BASE = Color(0xFF33333E)
private val HALO = Color(0x30C1B0FF)
