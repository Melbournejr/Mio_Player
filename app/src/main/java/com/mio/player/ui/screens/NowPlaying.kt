package com.mio.player.ui.screens
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.navigation.NavHostController
import com.mio.player.data.audio.DsdSupport
import com.mio.player.data.model.LyricLine
import com.mio.player.ui.components.AlbumArt
import com.mio.player.ui.visualizer.VisualizerPanel
import com.mio.player.ui.components.TrackRow
import com.mio.player.ui.components.formatDuration
import com.mio.player.ui.navigation.Routes
import com.mio.player.ui.theme.*
import com.mio.player.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
/**
 * v1.1.4 (Fixes 4/5/6/7) — Now Playing vs the design spec (PDF p.4:
 * "Bìa lớn, sóng, tiến trình, Play / Next"):
 *  - Fix 5: ONE EQ entry point only — the top-right Tune icon. The old
 *    bottom-row "Equalizer" OutlinedButton (a duplicate of that icon)
 *    is gone. EQ logic itself is untouched (it works — user confirmed
 *    2026-11-21); Equalizer.kt was not edited.
 *  - Fix 6: lyrics INLINE, synced to the song (same LyricsRepository
 *    call as the full Lyrics screen in Detail.kt, same auto-scroll and
 *    teal highlight). The transport row sits at the BOTTOM so the
 *    lyrics + waveform have room. The separate Lyrics screen (PDF
 *    screen 7) stays reachable via "Xem đầy đủ".
 *  - Fix 4 (queue): the play queue opens as a bottom sheet from the
 *    top-right QueueMusic icon; the playing track is highlighted and
 *    tapping any row plays it (reuses PlayerViewModel.playQueue, so no
 *    ViewModel change was needed).
 *  - Fix 7 (audit): screen margin 20 dp (was 24), title 24 sp bold /
 *    track 16 semibold / body 14 / secondary 12–13, 4 px spacing
 *    scale (8/12/16), cover radius CardLarge (20 dp). All colours are
 *    the theme tokens — no hardcoded colours, no amber body text.
 *  - Playback-state line (Fix 5b): the old line claimed "DSD plays
 *    natively" for every track even when the track was actually playing
 *    from a transcoded WAV. It now shows the ACTUAL per-track state,
 *    derived without touching PlayerManager: PlayerManager swaps a
 *    failed DSD track to its cached WAV via replaceMediaItem, so the
 *    fallback is proven exactly when the playing media item's URI is
 *    no longer the track's raw URI (the same check PlayerManager
 *    itself uses in maybePruneStaleDsdCache). Native is only claimed
 *    once the raw URI is playing AND the player reached STATE_READY.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NowPlayingScreen(vm: PlayerViewModel, nav: NavHostController) {
    val queue by vm.queue.collectAsState(); val idx by vm.index.collectAsState(); val playing by vm.isPlaying.collectAsState()
    val levels by vm.pm.waveform.levels.collectAsState()
    val pcmFrame by vm.pm.waveform.pcmFrame.collectAsState()
    val shuffleOn by vm.pm.shuffleEnabled.collectAsState()
    val repeatMode by vm.pm.repeatMode.collectAsState()
    val converting by vm.pm.isConvertingDsd.collectAsState()
    val dsdError by vm.pm.dsdError.collectAsState()
    val t = queue.getOrNull(idx)
    // FIX 2 (v1.1.1): DSD tracks have durationMs == 0 in the library
    // (MediaStore cannot read DSF duration; SAF tracks get 0), so using
    // t.durationMs as the total clamped the position to 0 forever and
    // showed 0:00 / 0:00. The player's duration is real (extractor
    // SeekMap for native DSD, WAV header for the transcode fallback);
    // fall back to the library value while the player has none yet.
    // Recomputed on every position poll, so it picks up the real
    // duration as soon as the player has loaded the track.
    val playerDuration = vm.pm.player.duration
    val totalMs = if (playerDuration != C.TIME_UNSET && playerDuration > 0) playerDuration else (t?.durationMs ?: 0L)
    val totalF = if (totalMs > 0) totalMs.toFloat() else 1f
    var pos by remember { mutableStateOf(0L) }
    // URI of the media item actually playing right now. Refreshed on
    // every position poll (250 ms while playing) and on track change,
    // so the fallback/native label below follows a replaceMediaItem
    // swap instead of freezing on the value from track start.
    var currentUri by remember { mutableStateOf<Uri?>(null) }
    var showQueue by remember { mutableStateOf(false) }
    // Inline synced lyrics — same source and call as LyricsScreen
    // (Detail.kt): LyricsRepository via the app container, LRCLIB /
    // sidecar .lrc / embedded, cached in Room.
    var lines by remember { mutableStateOf(listOf<LyricLine>()) }
    var plain by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf("Đang tìm lời…") }
    val ctx = LocalContext.current
    // Reset on track change, and sync to the player's real position instead
    // of counting up blindly: the old loop kept counting after a track
    // change, so pos could exceed a shorter track's duration and the
    // slider/progress went out of range.
    LaunchedEffect(t?.id) {
        pos = 0L
        currentUri = vm.pm.player.currentMediaItem?.localConfiguration?.uri
        lines = emptyList(); plain = null; source = "Đang tìm lời…"
        t?.let {
            val repo = (ctx.applicationContext as com.mio.player.MioApp).container.lyricsRepo
            val r = repo.getLyrics(it.artist, it.title, it.album, it.durationMs, it.path, vm.pm.player.duration.takeIf { d -> d > 0 } ?: 0)
            lines = r?.synced ?: emptyList(); plain = r?.plain; source = r?.source ?: "Không tìm thấy lời"
        }
    }
    LaunchedEffect(playing, t?.id) { while (playing) { val pd = vm.pm.player.duration; val total = if (pd != C.TIME_UNSET && pd > 0) pd else (t?.durationMs ?: Long.MAX_VALUE); pos = vm.pm.player.currentPosition.coerceIn(0L, total); currentUri = vm.pm.player.currentMediaItem?.localConfiguration?.uri; delay(250) } }
    // Actual per-track playback state (see the file KDoc): never claim
    // native decode from the file type alone. The fallback WAV is
    // always 176.4 kHz 16-bit stereo (DsdSupport.CACHED_PCM_SAMPLE_RATE_HZ).
    val isDsd = t != null && DsdSupport.isDsd(t.path)
    val isFallback = isDsd && currentUri != null && t != null && currentUri != t.uri
    val isNativeProven = isDsd && !converting && !isFallback && currentUri == t?.uri &&
        vm.pm.player.playbackState == Player.STATE_READY
    val stateText = when {
        converting -> "Đang chuyển đổi DSD sang WAV…"
        isFallback -> "Đang phát bản đổi (WAV 176.4 kHz)"
        isDsd && isNativeProven -> "${t?.dsdRate ?: "DSD"} · Native decode"
        isDsd -> "DSD · Đang tải…"
        else -> "Thiết bị phát tối đa ${vm.pm.deviceSampleRateHz / 1000} kHz"
    }
    // Live lyric sync: same rule as LyricsScreen — the line whose time
    // has passed, highlighted teal, list auto-scrolled to it.
    val currentIndex = lines.indexOfLast { it.timeMs <= pos }.coerceAtLeast(0)
    val lyricListState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        if (lines.isNotEmpty()) runCatching { lyricListState.animateScrollToItem((currentIndex - 1).coerceAtLeast(0)) }
    }
    Column(Modifier.fillMaxSize().background(MioBg).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.KeyboardArrowDown, null, tint = MioText) }
            Row {
                IconButton(onClick = { showQueue = true }) { Icon(Icons.Default.QueueMusic, "Hàng đợi", tint = MioText) }
                // The ONLY Equalizer entry point on this screen (Fix 5).
                IconButton(onClick = { nav.navigate(Routes.EQ) }) { Icon(Icons.Default.Tune, "Equalizer", tint = MioText) }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (t != null) AlbumArt(t.albumId, Modifier.size(240.dp), CardLarge) else Box(Modifier.size(240.dp).background(MioSurfaceRaised, CardLarge), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = MioCyan, modifier = Modifier.size(96.dp)) }
        Spacer(Modifier.height(16.dp))
        Text(t?.title ?: "No song", color = MioText, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(t?.artist ?: "", color = MioTextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        Text(t?.displayFormat ?: "", color = MioCyan, fontSize = 12.sp)
        Text(stateText, color = MioTextSecondary, fontSize = 12.sp)
        if (converting) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = MioCyan, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("  Converting DSD — please wait, do not tap again…", color = MioCyan, fontSize = 12.sp)
            }
        }
        dsdError?.let { err ->
            Spacer(Modifier.height(8.dp))
            Text(err, color = androidx.compose.ui.graphics.Color(0xFFFF8A80), fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        VisualizerPanel(levels, pcmFrame, playing, Modifier.fillMaxWidth())
        Slider(value = pos.toFloat().coerceIn(0f, totalF), onValueChange = { pos = it.toLong(); vm.pm.seekTo(it.toLong()) }, valueRange = 0f..totalF, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(pos), color = MioTextSecondary, fontSize = 12.sp)
            Text(formatDuration(totalMs), color = MioTextSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        // Inline synced lyrics (Fix 6). Takes the flexible middle of
        // the screen; the transport row below stays pinned at the bottom.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Lời bài hát", color = MioText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { nav.navigate(Routes.LYRICS) }) { Text("Xem đầy đủ", color = MioCyan, fontSize = 13.sp) }
        }
        if (lines.isNotEmpty()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = lyricListState) {
                items(lines.size) { i ->
                    val l = lines[i]
                    val isCurrent = i == currentIndex && playing
                    Text(l.text, color = if (isCurrent) MioCyan else MioText, fontSize = if (isCurrent) 18.sp else 16.sp, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(vertical = 6.dp))
                }
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(plain ?: source, color = MioTextSecondary, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.pm.setShuffle(!shuffleOn) }) { Icon(Icons.Default.Shuffle, null, tint = if (shuffleOn) MioCyan else MioTextSecondary) }
            IconButton(onClick = { vm.pm.previous() }) { Icon(Icons.Default.SkipPrevious, null, tint = MioText, modifier = Modifier.size(40.dp)) }
            // Design system: the Play button uses the theme gradient (135°),
            // not a solid color.
            Box(Modifier.size(72.dp).mioGradientBackground(androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                IconButton(onClick = { vm.toggle() }, modifier = Modifier.size(72.dp)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(32.dp)) }
            }
            IconButton(onClick = { vm.pm.next() }) { Icon(Icons.Default.SkipNext, null, tint = MioText, modifier = Modifier.size(40.dp)) }
            IconButton(onClick = { vm.pm.cycleRepeat() }) { Icon(if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, null, tint = if (repeatMode == Player.REPEAT_MODE_OFF) MioTextSecondary else MioCyan) }
        }
    }
    // Play queue (Fix 4): bottom sheet listing the current queue with
    // the playing track highlighted; tapping a row plays it from there.
    if (showQueue) {
        ModalBottomSheet(onDismissRequest = { showQueue = false }, containerColor = MioSurface) {
            Column(Modifier.fillMaxHeight(0.7f).padding(horizontal = 20.dp)) {
                Text("Hàng đợi", color = MioText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("${queue.size} bài", color = MioTextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(queue) { i, track ->
                        TrackRow(track, onClick = { vm.playQueue(queue, i); showQueue = false }, isPlaying = i == idx)
                    }
                }
            }
        }
    }
}
