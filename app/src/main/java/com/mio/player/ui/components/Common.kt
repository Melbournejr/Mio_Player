package com.mio.player.ui.components
import android.content.ContentUris
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sqrt
import coil.compose.AsyncImage
import com.mio.player.data.model.Track
import com.mio.player.ui.theme.*
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
 *  then the gradient is masked onto it with SrcIn.
 *
 *  v1.1.11: the SrcIn rect MUST run inside its own saved layer. Without
 *  one, SrcIn blends against everything already on the canvas — including
 *  the opaque NavigationBar background behind the icon — so the gradient
 *  rect shows as a solid square instead of being masked to the icon
 *  shape. It only sometimes looked right before (when a layer happened
 *  to exist, e.g. during the indicator animation). The layer isolates
 *  the blend destination to just the white icon pixels, so the mask is
 *  the icon shape on all four tabs. */
@Composable fun GradientIcon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier) {
    val gradient = MioGradient
    Icon(imageVector, contentDescription, tint = Color.White,
        modifier = modifier.drawWithCache {
            onDrawWithContent {
                drawIntoCanvas { canvas ->
                    // Compose Canvas has no restoreToCount (that is
                    // android.graphics.Canvas): saveLayer returns Unit
                    // and restore() pops the layer it opened.
                    canvas.saveLayer(size.toRect(), Paint())
                    drawContent()
                    drawRect(brush = gradient, blendMode = BlendMode.SrcIn)
                    canvas.restore()
                }
            }
        })
}
@Composable fun SectionTitle(text: String) { Text(text, color = MioText, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp)) }
/** Real album art from MediaStore (content://media/external/audio/albumart/<albumId>).
 *  Falls back to the teal MusicNote box when there is no art — the PDF
 *  mockups show cover art everywhere, and 1.0.8 showed only placeholders.
 *  v1.1.10: a downloaded cover file (auto-metadata fetch) wins over
 *  MediaStore — it is a local file, so it works offline, and it is the
 *  only art most tracks have (their albumId is 0). */
@Composable fun AlbumArt(albumId: Long, modifier: Modifier = Modifier, corner: androidx.compose.foundation.shape.RoundedCornerShape = CardSmall, coverPath: String? = null) {
    val ctx = LocalContext.current
    val coverFile = coverPath?.let { java.io.File(it) }?.takeIf { it.exists() }
    if (coverFile != null) {
        Box(modifier.clip(corner).background(MioSurfaceRaised), contentAlignment = Alignment.Center) {
            AsyncImage(model = coverFile, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    } else if (albumId > 0) {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId)
        Box(modifier.clip(corner).background(MioSurfaceRaised), contentAlignment = Alignment.Center) {
            AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    } else {
        Box(modifier.clip(corner).background(MioSurfaceRaised), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, null, tint = MioCyan)
        }
    }
}
@Composable fun TrackRow(t: Track, onClick: () -> Unit, onFav: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null, isPlaying: Boolean = false) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AlbumArt(t.albumId, Modifier.size(52.dp), coverPath = t.coverPath)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(t.title, color = if (isPlaying) MioCyan else MioText, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("${t.artist} • ${t.displayFormat}", color = MioTextSecondary, fontSize = 12.sp, maxLines = 1)
        }
        if (isPlaying) Icon(Icons.Default.Equalizer, "Playing", tint = MioCyan, modifier = Modifier.size(20.dp))
        if (onFav != null) IconButton(onClick = onFav) { Icon(if (t.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (t.isFavorite) MioCyan else MioTextSecondary) }
        trailing?.invoke()
        Text(formatDuration(t.durationMs), color = MioTextSecondary, fontSize = 12.sp)
    }
}
fun formatDuration(ms: Long): String { val s = (ms/1000).toInt(); return "%d:%02d".format(s/60, s%60) }
@Composable fun MiniPlayer(title: String, artist: String, isPlaying: Boolean, onToggle: () -> Unit, onNext: () -> Unit, onOpen: () -> Unit, albumId: Long = 0, coverPath: String? = null) {
    Card(Modifier.fillMaxWidth().padding(12.dp).clickable { onOpen() }, colors = CardDefaults.cardColors(containerColor = MioSurfaceRaised), shape = CardSmall) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AlbumArt(albumId, Modifier.size(44.dp), RoundedCornerShape(10.dp), coverPath = coverPath)
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
/** Live audio wave for Now Playing. Bars follow ONLY the real FFT levels
 *  from the in-process tap in WaveformTap (v1.1.2, Fix 2) while playing.
 *  The Visualizer source is retired (it cannot initialise on this phone,
 *  error -3) and there is NO time-based animation of any kind here any
 *  more: the old idle "breathing" swell is gone too, because any motion
 *  not driven by the audio reads as fake movement. `progress` is kept in
 *  the signature only so the existing call site in NowPlaying keeps
 *  compiling; it is intentionally ignored here and never drives bar
 *  height or colour. When no live FFT data is available (silence /
 *  paused) the bars sit low and still: fixed per-bar heights determined
 *  by the bar index only.
 *
 *  SCALING (v1.1.4, Fix 3 — was v1.1.3, GAIN = 12): the v1.1.3 gain was
 *  calibrated on the tap's first-frame log (maxLevel ≈ 0.042), but that
 *  frame is the first 5.8 ms of a track — decoder settling / near
 *  silence — not loud playback. Steady-state low-band levels are
 *  >= 0.083 (measured from the user's v1.1.3 video: bars 0-14 pinned
 *  at the full 112 dp for 30 s straight), so GAIN = 12 pinned every
 *  loud bar at full height and hid all variation between bars. GAIN
 *  is now 4: only a level >= 0.25 (bin peak >= 0.0625, about -12 dB)
 *  reaches full height, so loud bars stay tall but differ from each
 *  other, and quiet bars stay visible. The raw values are boosted in
 *  the UI layer only (WaveformTap's levels are untouched):
 *      norm   = sqrt(min(level * WAVEFORM_GAIN, 1f))   // GAIN = 4
 *      height = WAVEFORM_MIN_HEIGHT_DP + norm * (ROW_HEIGHT_DP - MIN)
 *  Computed heights on the 88 dp row (min stub 10 dp, v1.1.12 —
 *  was the 112 dp row):
 *      level 0.00  -> norm 0.000 ->  10.0 dp (short stub, never invisible)
 *      level 0.01  -> norm 0.200 ->  25.6 dp
 *      level 0.042 -> norm 0.410 ->  42.0 dp
 *      level 0.10  -> norm 0.632 ->  59.3 dp
 *      level 0.20  -> norm 0.894 ->  79.7 dp
 *      level 0.25  -> norm 1.000 ->  88.0 dp (full height)
 *  Bars are painted with the theme gradient (Deep Wave:
 *  teal #14B8A6 -> cyan #22D3EE, vertical, bottom -> top), rounded,
 *  5 dp wide. */
// Waveform scaling constants (v1.1.4, Fix 3 — was v1.1.3 GAIN = 12) —
// see the LiveWaveform KDoc for the math and the computed heights.
// v1.1.12: row height 112 -> 88 dp, matching VIS_ROW_HEIGHT_DP in
// VisualizerStyles.kt — all five visualizer styles now share the
// same 88 dp slot, so switching styles no longer jumps the layout
// and the lyrics area gains 24 dp.
private const val WAVEFORM_ROW_HEIGHT_DP = 88f
private const val WAVEFORM_MIN_HEIGHT_DP = 10f
private const val WAVEFORM_GAIN = 4f
private const val WAVEFORM_LIVE_THRESHOLD = 0.005f
@Suppress("UNUSED_PARAMETER")
@Composable fun LiveWaveform(levels: FloatArray, playing: Boolean, progress: Float, modifier: Modifier = Modifier) {
    val live = levels.any { it > WAVEFORM_LIVE_THRESHOLD }
    // Theme gradient as a vertical brush, so each bar runs teal at the
    // bottom to cyan at the top (Deep Wave). Uses the token list, not
    // hardcoded colours, so the Sunset theme gets its own gradient.
    val barBrush: Brush = Brush.verticalGradient(LocalMioColors.current.gradientColors)
    Row(modifier.height(WAVEFORM_ROW_HEIGHT_DP.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        val n = levels.size
        for (i in 0 until n) {
            val h = if (live) {
                val norm = sqrt((levels[i] * WAVEFORM_GAIN).coerceIn(0f, 1f))
                WAVEFORM_MIN_HEIGHT_DP + norm * (WAVEFORM_ROW_HEIGHT_DP - WAVEFORM_MIN_HEIGHT_DP)
            } else {
                // Low fixed height per bar (18..30 dp, deterministic from
                // the bar index only). Nothing here depends on time,
                // position, duration or progress.
                18f + 12f * (0.5f + 0.5f * STATIC_IDLE[i % STATIC_IDLE.size])
            }
            // Live bars use the theme gradient; idle bars the same cyan
            // at low alpha. No bar is ever coloured by playback progress.
            val brush: Brush = if (live) barBrush else SolidColor(MioCyan.copy(alpha = 0.4f))
            Box(Modifier.width(5.dp).height(h.dp).background(brush, CircleShape))
        }
    }
}

/** Fixed per-bar idle shape (values in -1..1, symmetric) so the idle
 *  bars are not all the same flat height, without any animation. */
private val STATIC_IDLE = floatArrayOf(
    0.0f, 0.6f, 1.0f, 0.4f, -0.3f, -0.8f, -0.2f, 0.7f,
    0.9f, 0.1f, -0.6f, -1.0f, -0.4f, 0.5f, 0.8f, 0.0f
)
