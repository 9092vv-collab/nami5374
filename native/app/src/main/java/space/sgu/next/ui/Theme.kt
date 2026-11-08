package space.sgu.next.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import space.sgu.next.R
import space.sgu.next.data.AppState
import space.sgu.next.data.ThemeMode

object SguColors {
    val Accent = Color(0xFFD97757)
    val AccentDark = Color(0xFFE08A6D)
}

private val Light = lightColorScheme(
    primary = SguColors.Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6DED4),
    onPrimaryContainer = Color(0xFF5A2414),
    secondary = Color(0xFF73726C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEBE3),
    onSecondaryContainer = Color(0xFF141413),
    background = Color(0xFFF5F4ED),
    onBackground = Color(0xFF141413),
    surface = Color(0xFFF5F4ED),
    onSurface = Color(0xFF141413),
    surfaceVariant = Color(0xFFEDEBE3),
    onSurfaceVariant = Color(0xFF73726C),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFAF9F5),
    surfaceContainerHigh = Color(0xFFF0EEE6),
    surfaceContainerHighest = Color(0xFFE8E6DC),
    outline = Color(0xFFD9D6CB),
    outlineVariant = Color(0xFFE5E3DA),
    error = Color(0xFFC0392B),
)

private val Dark = darkColorScheme(
    primary = SguColors.AccentDark,
    onPrimary = Color(0xFF1F1E1D),
    primaryContainer = Color(0xFF5A2E20),
    onPrimaryContainer = Color(0xFFF6DED4),
    secondary = Color(0xFFA6A39A),
    onSecondary = Color(0xFF1F1E1D),
    secondaryContainer = Color(0xFF34332F),
    onSecondaryContainer = Color(0xFFF5F4ED),
    background = Color(0xFF1F1E1D),
    onBackground = Color(0xFFF5F4ED),
    surface = Color(0xFF1F1E1D),
    onSurface = Color(0xFFF5F4ED),
    surfaceVariant = Color(0xFF30302E),
    onSurfaceVariant = Color(0xFFA6A39A),
    surfaceContainerLowest = Color(0xFF1A1918),
    surfaceContainerLow = Color(0xFF262624),
    surfaceContainer = Color(0xFF262624),
    surfaceContainerHigh = Color(0xFF30302E),
    surfaceContainerHighest = Color(0xFF3A3936),
    outline = Color(0xFF4A4945),
    outlineVariant = Color(0xFF3A3936),
    error = Color(0xFFE57368),
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
        headlineLarge = headlineLarge.p(), headlineMedium = headlineMedium.p(), headlineSmall = headlineSmall.p(),
        titleLarge = titleLarge.p().copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.p().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.p().copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.p(), bodyMedium = bodyMedium.p(), bodySmall = bodySmall.p(),
        labelLarge = labelLarge.p().copy(fontWeight = FontWeight.SemiBold),
        labelMedium = labelMedium.p(), labelSmall = labelSmall.p(),
    )
}

@Composable
fun isDark(): Boolean = when (AppState.themeMode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

@Composable
fun SguTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isDark()) Dark else Light,
        typography = SguTypography,
        content = content,
    )
}
