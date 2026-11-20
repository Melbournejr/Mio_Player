package com.mio.player.ui.theme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
// Mio Deep Wave — tokens (single source of truth; change theme here).
val MioBg = Color(0xFF0B1216); val MioSurface = Color(0xFF121A20); val MioSurfaceRaised = Color(0xFF1A252D)
val MioTeal = Color(0xFF14B8A6); val MioCyan = Color(0xFF22D3EE)
val MioText = Color.White; val MioTextSecondary = Color(0xFF94A3B8); val MioDivider = Color(0xFF24313A)
val MioGradient = Brush.horizontalGradient(listOf(MioTeal, MioCyan))
val CardLarge = RoundedCornerShape(20.dp); val CardSmall = RoundedCornerShape(16.dp); val InputShape = RoundedCornerShape(14.dp)
private val DarkScheme = darkColorScheme(primary = MioCyan, secondary = MioTeal, background = MioBg, surface = MioSurface, onBackground = MioText, onSurface = MioText)
@Composable fun MioPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, typography = Typography(), content = content)
}
