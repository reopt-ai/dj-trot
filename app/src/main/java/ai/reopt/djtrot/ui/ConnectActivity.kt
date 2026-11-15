package ai.reopt.djtrot.ui

import ai.reopt.djtrot.DjTrotApp
import ai.reopt.djtrot.api.PairingStarted
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 휴대폰 연결 — 차가 PIN과 QR을 보여 주고, 휴대폰(마스터트롯 로그인)이 승인하면 기기 자격을 받는다(AAOS 휴대폰 로그인 방식).
 * 🔴 이 화면은 distractionOptimized가 아니다 — 주행 중이면 시스템이 막는다. QR 로그인은 Pleos 정책상 모든 지역에서 주행 중 금지.
 * 🔑 자격은 claimed 때 한 번만 온다. 받는 즉시 [ai.reopt.djtrot.account.DeviceVault]에 암호화해 둔다.
 */
class ConnectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as DjTrotApp
        setContent {
            DjTrotTheme { ConnectScreen(app, onDone = ::finish) }
        }
    }
}

private enum class Step { STARTING, WAITING, APPROVED, CONNECTED, EXPIRED, FAILED }

@Composable
private fun ConnectScreen(app: DjTrotApp, onDone: () -> Unit) {
    var pairing by remember { mutableStateOf<PairingStarted?>(null) }
    var step by remember { mutableStateOf(Step.STARTING) }
    var attempt by remember { mutableIntStateOf(0) }
    var left by remember { mutableLongStateOf(0L) }

    LaunchedEffect(attempt) {
        step = Step.STARTING
        pairing = null
        val started = runCatching { app.api.startPairing(deviceName()) }.getOrNull()
        if (started == null) {
            step = Step.FAILED
            return@LaunchedEffect
        }
        pairing = started
        step = Step.WAITING
        val expires = runCatching { Instant.parse(started.expiresAt) }.getOrDefault(Instant.now().plusSeconds(600))
        var nextPoll = Instant.now().plusSeconds(POLL_SECONDS)
        while (isActive) {
            left = Duration.between(Instant.now(), expires).seconds.coerceAtLeast(0)
            if (left <= 0) {
                step = Step.EXPIRED
                return@LaunchedEffect
            }
            if (Instant.now().isAfter(nextPoll)) {
                nextPoll = Instant.now().plusSeconds(POLL_SECONDS)
                val result = runCatching { app.api.claimPairing(started.pairingId, started.claimSecret) }.getOrNull()
                when (result?.state) {
                    "approved" -> step = Step.APPROVED
                    "expired" -> {
                        step = Step.EXPIRED
                        return@LaunchedEffect
                    }
                    "claimed" -> {
                        val device = result.device
                        if (device == null) {
                            step = Step.FAILED
                            return@LaunchedEffect
                        }
                        app.account.connect(device)
                        step = Step.CONNECTED
                        delay(1_500)
                        onDone()
                        return@LaunchedEffect
                    }
                }
            }
            delay(1_000)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(DjColors.Black)) {
        val scale = minOf(maxWidth / 1682.dp, maxHeight / 1210.dp).coerceIn(0.5f, 1.25f)
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
            Row(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 64.dp), horizontalArrangement = Arrangement.spacedBy(72.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppMark(76.dp)
                        Spacer(Modifier.width(20.dp))
                        Text("DJ 트롯", style = headline(DjType.TitleL, DjColors.Gold2))
                    }
                    Spacer(Modifier.height(48.dp))
                    Text("휴대폰으로 연결하면\n이 길의 노래를 만들 수 있어요", style = headline(DjType.HeadlineS))
                    Spacer(Modifier.height(36.dp))
                    Step(1, "휴대폰 카메라로 오른쪽 QR을 찍어요")
                    Step(2, "마스터트롯에 로그인하고 아래 숫자를 확인해요")
                    Step(3, "승인하면 차가 바로 연결돼요")
                    Spacer(Modifier.weight(1f))
                    Pin(pairing?.pin)
                    Spacer(Modifier.height(28.dp))
                    Status(step, left)
                    Spacer(Modifier.height(28.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        BackButton(onDone)
                        if (step == Step.EXPIRED || step == Step.FAILED) Pill(DjIcons.Refresh, "새 번호 받기", active = true, onClick = { attempt++ })
                    }
                }
                Box(Modifier.width(560.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    QrCard(pairing?.connectUrl, dimmed = step == Step.EXPIRED || step == Step.FAILED)
                }
            }
        }
    }
}

/** PIN 여섯 자리 — 휴대폰 화면의 숫자와 맞춰 보는 용도라 크게. */
@Composable
private fun Pin(pin: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val digits = pin?.padEnd(6)?.take(6) ?: "      "
        digits.forEach { char ->
            Box(
                Modifier.size(width = 96.dp, height = 124.dp).djCard(radius = 20.dp, background = DjColors.CardRaised, line = DjColors.LineStrong),
                contentAlignment = Alignment.Center,
            ) {
                Text(char.toString().trim(), style = digits(DjType.HeadlineM, DjColors.Gold2).copy(fontWeight = FontWeight.ExtraBold))
            }
        }
    }
}

@Composable
private fun Status(step: Step, left: Long) {
    val (text, color) = when (step) {
        Step.STARTING -> "연결 번호를 받는 중…" to DjColors.Muted
        Step.WAITING -> "휴대폰 승인을 기다려요 · ${left / 60}:${(left % 60).toString().padStart(2, '0')} 남음" to DjColors.Muted
        Step.APPROVED -> "휴대폰에서 승인했어요. 연결하는 중…" to DjColors.Gold2
        Step.CONNECTED -> "연결됐어요! 이제 이 길의 노래를 만들 수 있어요" to DjColors.Gold2
        Step.EXPIRED -> "번호 시간이 지났어요. 새 번호를 받아 주세요" to DjColors.Pink
        Step.FAILED -> "서버에 연결하지 못했어요. 잠시 뒤 다시 받아 주세요" to DjColors.Pink
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (step == Step.STARTING || step == Step.APPROVED) {
            CircularProgressIndicator(color = DjColors.Gold, strokeWidth = 4.dp, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
        }
        if (step == Step.CONNECTED) {
            Icon(DjIcons.Sparkle, contentDescription = null, tint = DjColors.Gold, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(text, style = digits(DjType.BodyS, color).copy(fontWeight = FontWeight.SemiBold))
    }
}

/** QR — 잘 찍히도록 흰 바탕에 검은 점. */
@Composable
private fun QrCard(url: String?, dimmed: Boolean) {
    val bitmap = remember(url) { url?.let { qr(it, 720) } }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(520.dp).clip(RoundedCornerShape(36.dp)).background(if (dimmed) DjColors.Card else androidx.compose.ui.graphics.Color.White)
                .border(3.dp, DjColors.Gold.copy(alpha = 0.7f), RoundedCornerShape(36.dp)).padding(36.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                dimmed -> Icon(DjIcons.Refresh, contentDescription = null, tint = DjColors.Faint, modifier = Modifier.size(96.dp))
                bitmap == null -> CircularProgressIndicator(color = DjColors.GoldDeep, strokeWidth = 6.dp, modifier = Modifier.size(72.dp))
                else -> Image(bitmap.asImageBitmap(), contentDescription = "휴대폰 연결 QR", filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("휴대폰 카메라로 찍어 주세요", style = body(DjType.BodyS, DjColors.Muted), textAlign = TextAlign.Center)
    }
}

private fun qr(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

private fun deviceName(): String = "DJ 트롯 · ${Build.MANUFACTURER} ${Build.MODEL}".take(60)

private const val POLL_SECONDS = 3L

/**
 * 뒤로가기 — 이 화면을 닫고 DJ 트롯으로 돌아간다.
 * 🔑 차 안에서 한 번에 누를 수 있게 크게 둔다(높이 88dp). 금빛 테와 왼쪽 화살표로 "돌아가는 버튼"임을 알린다.
 */
@Composable
private fun BackButton(onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier
            .pressScale(source, depth = 0.96f)
            .height(88.dp)
            .clip(shape)
            .background(DjColors.CardRaised)
            .border(2.dp, DjColors.GoldRing, shape)
            .tap(source, label = "뒤로가기", onClick = onClick)
            .padding(start = 22.dp, end = 34.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(DjIcons.ChevronRight, contentDescription = null, tint = DjColors.Gold2, modifier = Modifier.size(44.dp).rotate(180f))
        Spacer(Modifier.width(8.dp))
        Text("뒤로가기", style = body(DjType.BodyM, DjColors.Gold2, FontWeight.Bold), maxLines = 1)
    }
}
