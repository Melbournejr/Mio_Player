package com.mio.player.ui.components
import android.content.ContentUris
import android.provider.MediaStore
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
/** v1.1.15 — GradientIcon is DELETED, not moved: the SrcIn blend that
 *  painted the selected tab icon with the gradient caused the solid
 *  square icons of v1.1.9–v1.1.11. The selected tab now uses the
 *  standard Material 3 treatment — accent tint + a tonal indicator
 *  pill (see MainScaffold) — no custom drawing at all. Do not
 *  re-add a gradient-masked icon: a gradient fill is only safe as a
 *  plain background (see mioGradientBackground), never as a blend
 *  over icon pixels. */
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
/** A thin, refined horizontal slider (v1.1.33) — the shared
 *  replacement for every remaining thick Material3 Expressive
 *  Slider in the app: the Now Playing time bar, the EQ Preamp /
 *  effects sliders, the Parametric Freq/Gain/Q sliders, and the
 *  Settings Crossfade / Speed sliders. The user called those
 *  "to, thô" (big, crude) after the EQ band faders went thin in
 *  v1.1.31, and asked for the time bar and the other sliders to
 *  be neat too. Same look as the vertical band fader: a 4dp track
 *  (dark unfilled, teal filled) and a small 16dp round thumb
 *  (white with a teal centre). Drag horizontally to change the
 *  value; tap the track to jump to that value. When [enabled] is
 *  false (the Parametric Q slider for a Shelf band) it renders
 *  dimmed and ignores input. The drag snapshots the value in
 *  onDragStart so incremental deltas never lose a step to a stale
 *  read, and consumes its moves so a horizontal drag on a slider
 *  does not scroll the screen behind it. */
@Composable fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true
) {
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    Box(
        modifier
            .height(32.dp)
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .pointerInput(enabled, valueRange) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    val frac = (offset.x / size.width).coerceIn(0f, 1f)
                    currentOnValueChange(valueRange.start + frac * (valueRange.endInclusive - valueRange.start))
                }
            }
            .pointerInput(enabled, valueRange) {
                if (!enabled) return@pointerInput
                var dragValue = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragValue = currentValue },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val span = valueRange.endInclusive - valueRange.start
                        dragValue = (dragValue + dragAmount / size.width * span)
                            .coerceIn(valueRange.start, valueRange.endInclusive)
                        currentOnValueChange(dragValue)
                    }
                )
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val trackWidth = 4.dp.toPx()
            val thumbRadius = 8.dp.toPx()
            val centerY = size.height / 2f
            val left = thumbRadius
            val right = size.width - thumbRadius
            val frac = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
            val valueX = left + frac * (right - left)
            // Unfilled track (full width, dark).
            drawLine(
                color = Color(0xFF3A3A44),
                start = Offset(left, centerY),
                end = Offset(right, centerY),
                strokeWidth = trackWidth,
                cap = StrokeCap.Round
            )
            // Filled track (teal, from the left up to the thumb).
            drawLine(
                color = Color(0xFF14B8A6),
                start = Offset(left, centerY),
                end = Offset(valueX, centerY),
                strokeWidth = trackWidth,
                cap = StrokeCap.Round
            )
            // Thumb: white disc with a teal centre.
            drawCircle(color = Color.White, radius = thumbRadius, center = Offset(valueX, centerY))
            drawCircle(color = Color(0xFF14B8A6), radius = thumbRadius - 3.dp.toPx(), center = Offset(valueX, centerY))
        }
    }
}
// v1.1.15 — WaveformBar DELETED: a fake pseudo-waveform (20 hard-coded
// bar heights coloured by progress), and dead code — no screen used
// it. A waveform that does not come from the audio has no place in
// the app; the live FFT bars live in ui/visualizer/VisualizerStyles.kt.
/** v1.1.13 — LiveWaveform is GONE (deleted, not moved): the Bars
 *  visualizer is now native in ui/visualizer/VisualizerStyles.kt
 *  (Monstercat-style peak caps, fast attack / slow release, same gain
 *  calibration and idle shape as this function had). It was the only
 *  caller — verified by grep — so nothing else in this file changes,
 *  and the WAVEFORM_* constants and STATIC_IDLE below go with it.
 *  The KDoc math it carried (gain 4, level 0.25 = full height on the
 *  88 dp row) now lives on VisualizerBars. */
