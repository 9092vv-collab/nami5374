package space.sgu.next.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import space.sgu.next.R

/** Claude app look (light only): ink is the action colour, orange is only an accent. */
object Sgu {
    val Ink = Color(0xFF141413)
    val Bg = Color(0xFFFAF9F7)
    val Drawer = Color(0xFFF3F2EE)
    val Card = Color(0xFFFFFFFF)
    val Line = Color(0xFFE6E5E0)
    val Sub = Color(0xFF8A8984)
    val Bubble = Color(0xFFF0EFEA)
    val Accent = Color(0xFFD97757)
    val AccentSoft = Color(0xFFF7E3DA)
    val Danger = Color(0xFFC0392B)
}

private val Scheme = lightColorScheme(
    primary = Sgu.Ink,
    onPrimary = Color.White,
    primaryContainer = Sgu.Bubble,
    onPrimaryContainer = Sgu.Ink,
    secondary = Sgu.Accent,
    onSecondary = Color.White,
    secondaryContainer = Sgu.AccentSoft,
    onSecondaryContainer = Color(0xFF5A2414),
    tertiary = Sgu.Accent,
    background = Sgu.Bg,
    onBackground = Sgu.Ink,
    surface = Sgu.Bg,
    onSurface = Sgu.Ink,
    surfaceVariant = Sgu.Bubble,
    onSurfaceVariant = Sgu.Sub,
    surfaceContainerLowest = Sgu.Card,
    surfaceContainerLow = Sgu.Card,
    surfaceContainer = Sgu.Bg,
    surfaceContainerHigh = Sgu.Drawer,
    surfaceContainerHighest = Color(0xFFECEBE6),
    outline = Color(0xFFD9D8D2),
    outlineVariant = Sgu.Line,
    error = Sgu.Danger,
)

val Pretendard = FontFamily(
    Font(R.font.pretendardgov_regular, FontWeight.Normal),
    Font(R.font.pretendardgov_medium, FontWeight.Medium),
    Font(R.font.pretendardgov_semibold, FontWeight.SemiBold),
    Font(R.font.pretendardgov_bold, FontWeight.Bold),
    Font(R.font.pretendardgov_extrabold, FontWeight.ExtraBold),
)

private fun TextStyle.p() = copy(fontFamily = Pretendard)

private val SguTypography = Typography().run {
    copy(
        displayLarge = displayLarge.p(), displayMedium = displayMedium.p(), displaySmall = displaySmall.p(),
        headlineLarge = headlineLarge.p(), headlineMedium = headlineMedium.p().copy(fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.p().copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.p().copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
        titleMedium = titleMedium.p().copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
        titleSmall = titleSmall.p().copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.p().copy(fontSize = 17.sp, lineHeight = 26.sp),
        bodyMedium = bodyMedium.p().copy(fontSize = 15.sp, lineHeight = 22.sp),
        bodySmall = bodySmall.p().copy(fontSize = 13.sp),
        labelLarge = labelLarge.p().copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
        labelMedium = labelMedium.p(), labelSmall = labelSmall.p(),
    )
}

@Composable
fun SguTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = SguTypography, content = content)
}
