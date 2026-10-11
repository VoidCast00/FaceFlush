package io.github.voidcast00.faceflush.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Muted olive and teal on near-black, in the spirit of Curbox
private val DarkColors = darkColorScheme(
    primary = Color(0xFFD8D48E),
    onPrimary = Color(0xFF2A2906),
    primaryContainer = Color(0xFF45431A),
    onPrimaryContainer = Color(0xFFF2EEA8),
    secondary = Color(0xFF9CC9B8),
    onSecondary = Color(0xFF0B3529),
    secondaryContainer = Color(0xFF1F5A4B),
    onSecondaryContainer = Color(0xFFC6EDDE),
    tertiary = Color(0xFFE9B9A2),
    onTertiary = Color(0xFF442A1A),
    background = Color(0xFF121210),
    onBackground = Color(0xFFE7E5D9),
    surface = Color(0xFF121210),
    onSurface = Color(0xFFE7E5D9),
    surfaceVariant = Color(0xFF2A2A24),
    onSurfaceVariant = Color(0xFFA9A796),
    surfaceContainerLowest = Color(0xFF0D0D0B),
    surfaceContainerLow = Color(0xFF181815),
    surfaceContainer = Color(0xFF1D1D19),
    surfaceContainerHigh = Color(0xFF26261F),
    surfaceContainerHighest = Color(0xFF303029),
    outline = Color(0xFF55544A),
    outlineVariant = Color(0xFF3A3A32),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5E5C1A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4E09A),
    onPrimaryContainer = Color(0xFF1C1B00),
    secondary = Color(0xFF2E6A5A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF1F6B58),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFF8A4F36),
    background = Color(0xFFF8F7F0),
    onBackground = Color(0xFF1B1B16),
    surface = Color(0xFFF8F7F0),
    onSurface = Color(0xFF1B1B16),
    surfaceVariant = Color(0xFFE6E4D6),
    onSurfaceVariant = Color(0xFF5C5B4E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F1E8),
    surfaceContainer = Color(0xFFEDEBE1),
    surfaceContainerHigh = Color(0xFFE7E5DA),
    surfaceContainerHighest = Color(0xFFE1DFD3),
    outline = Color(0xFF8C8A7C),
    outlineVariant = Color(0xFFCFCDBF),
)

private val base = Typography()

private val AppTypography = base.copy(
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp, lineHeight = 48.sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
)

/** Small, letter-spaced section label ("TECHNIQUE", "STRENGTH" …). */
val SectionLabel = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.5.sp)

@Composable
fun FaceFlushTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
