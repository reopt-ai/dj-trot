package ai.reopt.djtrot.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

/**
 * 마스터트롯 콘서트 테마의 블랙·샴페인 골드.
 * 🔑 무대 그림만 화려하게, 글자와 조작 영역은 평평한 검정 위에 또렷하게 둔다.
 */
object DjColors {
    val Black = Color(0xFF000000)
    val Paper = Color(0xFF060605)
    val Card = Color(0xFF14120F)
    val CardRaised = Color(0xFF19150E)
    val Cream = Color(0xFF241E13)
    val Selected = Color(0xFF352A19)
    val Segment = Color(0xFF191714)
    val Line = Color(0xFF3A3020)
    val LineStrong = Color(0xFF665033)
    val Gold = Color(0xFFE8C779)
    val Gold2 = Color(0xFFF8E5B1)
    val GoldDeep = Color(0xFFB58C45)
    val GoldRing = Color(0xFFD4AE67)
    val SatinEdge = Color(0xFFF3D791)
    val OnGold = Color(0xFF171108)
    val Ink = Color(0xFFF8F4E9)
    val Muted = Color(0xFFB6B3AE)
    val Faint = Color(0xFF99958E)
    val Track = Color(0xFF302B22)
    val Pink = Color(0xFFEFADB9)
    val Ok = Color(0xFFA1D5B7)
    val ErrorInk = Color(0xFFFFD5DD)
    val ErrorBg = Color(0xFF2A1116)
    val ErrorLine = Color(0xFFA25163)
}

/**
 * Pleos Connect 글자 단계(UI Design Guide › Typography). 기준 창(1682×1210dp, mdpi)에서 sp = px.
 * 🔑 이 단계 밖의 크기는 쓰지 않는다 — 기본 앱들과 위계가 맞아야 한눈에 읽힌다. 가장 작은 글자는 20.
 */
object DjType {
    val HeadlineL = 60.sp
    val HeadlineM = 56.sp
    val HeadlineS = 48.sp
    val TitleL = 40.sp
    val TitleM = 36.sp
    val TitleS = 32.sp
    val BodyL = 30.sp
    val BodyM = 28.sp
    val BodyS = 26.sp
    val LabelM = 24.sp
    val LabelS = 20.sp
}

@Composable
fun DjTrotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = DjColors.Gold,
            onPrimary = DjColors.OnGold,
            background = DjColors.Black,
            surface = DjColors.Card,
            onSurface = DjColors.Ink,
            onBackground = DjColors.Ink,
        ),
        content = content,
    )
}
