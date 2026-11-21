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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.vector.ImageVector
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
/** v1.1.13 — LiveWaveform is GONE (deleted, not moved): the Bars
 *  visualizer is now native in ui/visualizer/VisualizerStyles.kt
 *  (Monstercat-style peak caps, fast attack / slow release, same gain
 *  calibration and idle shape as this function had). It was the only
 *  caller — verified by grep — so nothing else in this file changes,
 *  and the WAVEFORM_* constants and STATIC_IDLE below go with it.
 *  The KDoc math it carried (gain 4, level 0.25 = full height on the
 *  88 dp row) now lives on VisualizerBars. */
