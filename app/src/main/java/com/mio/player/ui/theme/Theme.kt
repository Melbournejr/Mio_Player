package com.mio.player.ui.theme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
/** Design tokens from mio-player-deep-wave-design.pdf (20.11.2026).
 *  One palette per theme; screens only ever read the tokens below, so
 *  switching theme changes colors, never layout, spacing or radii. */
data class MioColors(
    val background: Color, val surface: Color, val surfaceRaised: Color,
    val text: Color, val textSecondary: Color, val divider: Color,
    /** Gradient stops, in order. Deep Wave: teal -> cyan. Sunset: coral -> orange -> amber. */
    val gradientColors: List<Color>,
    /** Single accent for icons / links / active states. Deep Wave: cyan.
     *  Sunset: coral (never amber for text — the design says amber is an
     *  accent end only, not for copy). */
    val accent: Color, val isDark: Boolean
)
/** Theme 1 — Mio Deep Wave (default): dark, elegant, minimal. */
val DeepWaveColors = MioColors(
    background = Color(0xFF07181D), surface = Color(0xFF0E2A32), surfaceRaised = Color(0xFF16363F),
    text = Color.White, textSecondary = Color(0xFF9FB8BE), divider = Color(0xFF1E3A44),
    gradientColors = listOf(Color(0xFF14B8A6), Color(0xFF22D3EE)),
    accent = Color(0xFF22D3EE), isDark = true
)
/** Theme 2 — Mio Sunset: light, warm, friendly. Same layout, only the
 *  color tokens change. */
val SunsetColors = MioColors(
    background = Color(0xFFFFF7ED), surface = Color.White, surfaceRaised = Color(0xFFFFF1E3),
    text = Color(0xFF3B2314), textSecondary = Color(0xFF8A6A55), divider = Color(0xFFE8D9C8),
    gradientColors = listOf(Color(0xFFFF6B4A), Color(0xFFFF8A5B), Color(0xFFFFB347)),
    accent = Color(0xFFFF6B4A), isDark = false
)
val LocalMioColors = staticCompositionLocalOf { DeepWaveColors }
/** Token accessors — these are the only colors screens should use. */
val MioBg @Composable get() = LocalMioColors.current.background
val MioSurface @Composable get() = LocalMioColors.current.surface
val MioSurfaceRaised @Composable get() = LocalMioColors.current.surfaceRaised
val MioText @Composable get() = LocalMioColors.current.text
val MioTextSecondary @Composable get() = LocalMioColors.current.textSecondary
val MioDivider @Composable get() = LocalMioColors.current.divider
/** Gradient endpoints (first / accent stop). */
val MioTeal @Composable get() = LocalMioColors.current.gradientColors.first()
val MioCyan @Composable get() = LocalMioColors.current.accent
val MioGradient @Composable get() = Brush.horizontalGradient(LocalMioColors.current.gradientColors)
val CardLarge = RoundedCornerShape(20.dp); val CardSmall = RoundedCornerShape(16.dp); val InputShape = RoundedCornerShape(16.dp)
/** 135° diagonal gradient (top-left -> bottom-right), as in the design
 *  system. Size-aware, so it fills any button or surface correctly. */
@Composable
fun Modifier.mioGradientBackground(shape: Shape = RectangleShape): Modifier {
    val colors = LocalMioColors.current.gradientColors
    return clip(shape).drawBehind {
        drawRect(
            brush = Brush.linearGradient(colors, start = Offset.Zero, end = Offset(size.width, size.height))
        )
    }
}
@Composable fun MioPlayerTheme(themeMode: String = "deep_wave", content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "sunset" -> false
        "system" -> isSystemInDarkTheme()
        else -> true // deep_wave (also the first-install default)
    }
    val colors = if (dark) DeepWaveColors else SunsetColors
    val scheme = if (dark) darkColorScheme(primary = colors.accent, secondary = colors.gradientColors.first(), background = colors.background, surface = colors.surface, onBackground = colors.text, onSurface = colors.text)
    else lightColorScheme(primary = colors.accent, secondary = colors.gradientColors.first(), background = colors.background, surface = colors.surface, onBackground = colors.text, onSurface = colors.text)
    CompositionLocalProvider(LocalMioColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}
