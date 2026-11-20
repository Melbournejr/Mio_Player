package com.mio.player.ui.components
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mio.player.data.model.Track
import com.mio.player.ui.theme.*
import kotlin.math.PI
import kotlin.math.sin
@Composable fun GradientButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
        contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(999.dp)) {
        // 135° diagonal gradient from the design system (not horizontal).
        Box(Modifier.mioGradientBackground(RoundedCornerShape(999.dp)).fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
    }
}
/** An icon painted with the theme gradient (design system: the selected
 *  bottom-nav icon uses the gradient). The icon is drawn in white first,
 *  then the gradient is masked onto it with SrcIn. */
@Composable fun GradientIcon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier) {
    val gradient = MioGradient
    Icon(imageVector, contentDescription, tint = Color.White,
        modifier = modifier.drawWithCache {
            onDrawWithContent {
                drawContent()
                drawRect(brush = gradient, blendMode = BlendMode.SrcIn)
            }
        })
}
@Composable fun SectionTitle(text: String) { Text(text, color = MioText, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp)) }
@Composable fun TrackRow(t: Track, onClick: () -> Unit, onFav: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(CardSmall).background(MioSurfaceRaised), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, null, tint = MioCyan)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(t.title, color = MioText, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("${t.artist} • ${t.displayFormat}", color = MioTextSecondary, fontSize = 12.sp, maxLines = 1)
        }
        if (onFav != null) IconButton(onClick = onFav) { Icon(if (t.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (t.isFavorite) MioCyan else MioTextSecondary) }
        trailing?.invoke()
        Text(formatDuration(t.durationMs), color = MioTextSecondary, fontSize = 12.sp)
    }
}
fun formatDuration(ms: Long): String { val s = (ms/1000).toInt(); return "%d:%02d".format(s/60, s%60) }
@Composable fun MiniPlayer(title: String, artist: String, isPlaying: Boolean, onToggle: () -> Unit, onNext: () -> Unit, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(12.dp).clickable { onOpen() }, colors = CardDefaults.cardColors(containerColor = MioSurfaceRaised), shape = CardSmall) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(MioGradient), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.MusicNote, null, tint = Color.White)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(title, color = MioText, maxLines = 1); Text(artist, color = MioTextSecondary, fontSize = 12.sp, maxLines = 1) }
            IconButton(onClick = onToggle) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = MioText) }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, null, tint = MioText) }
        }
    }
}
@Composable fun WaveformBar(progress: Float, modifier: Modifier = Modifier) {
    // Deterministic pseudo-waveform (no fake audio analysis): bars are visual only.
    Row(modifier.height(36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        val heights = listOf(10,18,26,14,30,22,12,28,18,10,24,16,30,20,12,26,14,22,10,18)
        heights.forEachIndexed { i, h ->
            Box(Modifier.width(3.dp).height(h.dp).clip(CircleShape)
                .background(if (i.toFloat()/heights.size <= progress) MioCyan else MioDivider))
        }
    }
}
/** Live audio wave for Now Playing. Bars follow the real FFT levels
 *  from the player's output while playing. If the device refuses the
 *  Visualizer tap, levels stay empty and the bars animate on their own
 *  instead — moving while playing, almost still when paused. */
@Composable fun LiveWaveform(levels: FloatArray, playing: Boolean, progress: Float, modifier: Modifier = Modifier) {
    val live = levels.any { it > 0.02f }
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(0f, (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Row(modifier.height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        val n = levels.size
        for (i in 0 until n) {
            val h = if (live) {
                8f + levels[i] * 48f
            } else {
                val amp = if (playing) 1f else 0.25f
                val s = 0.5f + 0.5f * sin((phase + i * 0.85f).toDouble()).toFloat()
                10f + s * 34f * amp
            }
            Box(Modifier.width(4.dp).height(h.dp).clip(CircleShape)
                .background(if (i.toFloat() / n <= progress) MioCyan else MioDivider))
        }
    }
}
