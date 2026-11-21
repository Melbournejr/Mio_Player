package com.mio.player.ui.screens
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.mio.player.ui.components.isLandscape
import com.mio.player.ui.navigation.Routes
import com.mio.player.ui.theme.*
import com.mio.player.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
/**
 * Landscape Now Playing (v1.1.5). The portrait screen (NowPlaying.kt)
 * is a single vertical column with a fixed 240 dp cover — in landscape
 * (~360 dp tall) it clips. This is a SEPARATE landscape layout for the
 * same screen, not a rewrite of the portrait one:
 *
 *   - left: cover (sized to the available height) + live waveform
 *   - right: title / artist / format / real playback-state line,
 *     inline synced lyrics, seek slider, compact transport row
 *
 * State is the SAME as portrait, collected from the same PlayerViewModel
 * and the same LyricsRepository call — no new ViewModel, no new audio
 * code. The queue bottom sheet, the single EQ entry point (top-right
 * Tune icon) and the actual-state line (native vs WAV-fallback, never
 * claimed from the file type alone) are identical to portrait.
 *
 * WIRING (in MainActivity.kt, Routes.NOW_PLAYING destination):
 *   `composable(Routes.NOW_PLAYING) { NowPlayingRoute(playerVm, nav) }`
 * `NowPlayingRoute` picks this screen in landscape and the existing
 * `NowPlayingScreen` in portrait. NowPlaying.kt itself is untouched.
 */
@Composable fun NowPlayingRoute(vm: PlayerViewModel, nav: NavHostController) {
    if (isLandscape()) NowPlayingLandscapeScreen(vm, nav) else NowPlayingScreen(vm, nav)
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NowPlayingLandscapeScreen(vm: PlayerViewModel, nav: NavHostController) {
    // Same state as the portrait screen (NowPlaying.kt) — same flows,
    // same 250 ms position poll, same lyrics fetch, same state text.
    val queue by vm.queue.collectAsState(); val idx by vm.index.collectAsState(); val playing by vm.isPlaying.collectAsState()
    val levels by vm.pm.waveform.levels.collectAsState()
    val pcmFrame by vm.pm.waveform.pcmFrame.collectAsState()
    val shuffleOn by vm.pm.shuffleEnabled.collectAsState()
    val repeatMode by vm.pm.repeatMode.collectAsState()
    val converting by vm.pm.isConvertingDsd.collectAsState()
    val dsdError by vm.pm.dsdError.collectAsState()
    val t = queue.getOrNull(idx)
    val playerDuration = vm.pm.player.duration
    val totalMs = if (playerDuration != C.TIME_UNSET && playerDuration > 0) playerDuration else (t?.durationMs ?: 0L)
    val totalF = if (totalMs > 0) totalMs.toFloat() else 1f
    var pos by remember { mutableStateOf(0L) }
    var currentUri by remember { mutableStateOf<Uri?>(null) }
    var showQueue by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf(listOf<LyricLine>()) }
    var plain by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf("Đang tìm lời…") }
    val ctx = LocalContext.current
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
    // Actual per-track playback state — same rule as portrait: the
    // fallback is proven exactly when the playing media item's URI is
    // no longer the track's raw URI; native only once the raw URI is
    // playing AND the player reached STATE_READY.
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
    // v1.1.6 (coordinator, mirroring portrait): scroll the current
    // line to the CENTRE of the viewport, not the top edge, and only
    // scroll when it would leave the middle third — the old
    // animateScrollToItem(currentIndex - 1) parked the highlighted
    // line at the top edge where it was clipped.
    val currentIndex = lines.indexOfLast { it.timeMs <= pos }.coerceAtLeast(0)
    val lyricListState = rememberLazyListState()
    LaunchedEffect(currentIndex, lines.size) {
        if (lines.isEmpty()) return@LaunchedEffect
        runCatching {
            val info = lyricListState.layoutInfo
            val viewportH = info.viewportSize.height
            val visible = info.visibleItemsInfo
            if (viewportH <= 0 || visible.isEmpty()) {
                lyricListState.scrollToItem(currentIndex)
                return@runCatching
            }
            val current = visible.firstOrNull { it.index == currentIndex }
            val inCentre = current != null &&
                current.offset >= viewportH / 3 && current.offset <= 2 * viewportH / 3
            if (inCentre) return@runCatching
            val avgItem = visible.map { it.size }.average().takeIf { it > 0 }?.toInt() ?: 0
            val halfLines = if (avgItem > 0) (viewportH / 2) / avgItem else 2
            val targetFirst = (currentIndex - halfLines).coerceAtLeast(0)
            val firstVisible = visible.firstOrNull()?.index ?: currentIndex
            if (targetFirst == firstVisible) return@runCatching
            if (kotlin.math.abs(targetFirst - firstVisible) <= 3) {
                lyricListState.animateScrollToItem(targetFirst)
            } else {
                lyricListState.scrollToItem(targetFirst)
            }
        }
    }
    Column(Modifier.fillMaxSize().background(MioBg).padding(horizontal = 20.dp, vertical = 12.dp)) {
        // Header — same actions as portrait: back, queue sheet, the ONLY
        // EQ entry point (top-right Tune icon).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.KeyboardArrowDown, null, tint = MioText) }
            Row {
                IconButton(onClick = { showQueue = true }) { Icon(Icons.Default.QueueMusic, "Hàng đợi", tint = MioText) }
                IconButton(onClick = { nav.navigate(Routes.EQ) }) { Icon(Icons.Default.Tune, "Equalizer", tint = MioText) }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // LEFT — cover sized to the available height (never the
            // portrait fixed 240 dp, which overflows a ~360 dp-tall
            // landscape screen) + the live waveform underneath it.
            Column(Modifier.weight(0.42f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val coverSize = minOf(maxWidth, maxHeight)
                        if (t != null) AlbumArt(t.albumId, Modifier.size(coverSize), CardLarge)
                        else Box(Modifier.size(coverSize).background(MioSurfaceRaised, CardLarge), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = MioCyan, modifier = Modifier.size(64.dp)) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                VisualizerPanel(levels, pcmFrame, playing, Modifier.fillMaxWidth())
            }
            // RIGHT — track info, inline synced lyrics, seek, transport.
            // Compact transport (56 dp play button vs 72 dp portrait)
            // so the lyrics list keeps usable height on short screens.
            Column(Modifier.weight(0.58f).fillMaxHeight()) {
                Text(t?.title ?: "No song", color = MioText, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t?.artist ?: "", color = MioTextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // v1.1.7: same dedupe as portrait NowPlaying — the DSD
                // native state line is identical to displayFormat, so
                // show the format once; the state line only renders
                // when it says something different.
                val formatText = t?.displayFormat ?: ""
                Text(formatText, color = MioCyan, fontSize = 12.sp)
                if (stateText.isNotEmpty() && stateText != formatText) {
                    Text(stateText, color = MioTextSecondary, fontSize = 12.sp)
                }
                if (converting) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = MioCyan, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("  Converting DSD — please wait, do not tap again…", color = MioCyan, fontSize = 12.sp)
                    }
                }
                dsdError?.let { err ->
                    Spacer(Modifier.height(4.dp))
                    Text(err, color = androidx.compose.ui.graphics.Color(0xFFFF8A80), fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Lời bài hát", color = MioText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { nav.navigate(Routes.LYRICS) }) { Text("Xem đầy đủ", color = MioCyan, fontSize = 13.sp) }
                }
                if (lines.isNotEmpty()) {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = lyricListState) {
                        items(lines.size) { i ->
                            val l = lines[i]
                            // v1.1.6: highlight no longer requires
                            // `playing` — same fix as portrait.
                            val isCurrent = i == currentIndex
                            Text(l.text, color = if (isCurrent) MioCyan else MioText, fontSize = if (isCurrent) 18.sp else 16.sp, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(vertical = 6.dp))
                        }
                    }
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(plain ?: source, color = MioTextSecondary, fontSize = 14.sp)
                    }
                }
                Slider(value = pos.toFloat().coerceIn(0f, totalF), onValueChange = { pos = it.toLong(); vm.pm.seekTo(it.toLong()) }, valueRange = 0f..totalF, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatDuration(pos), color = MioTextSecondary, fontSize = 12.sp)
                    Text(formatDuration(totalMs), color = MioTextSecondary, fontSize = 12.sp)
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.pm.setShuffle(!shuffleOn) }) { Icon(Icons.Default.Shuffle, null, tint = if (shuffleOn) MioCyan else MioTextSecondary) }
                    IconButton(onClick = { vm.pm.previous() }) { Icon(Icons.Default.SkipPrevious, null, tint = MioText, modifier = Modifier.size(32.dp)) }
                    Box(Modifier.size(56.dp).mioGradientBackground(androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                        IconButton(onClick = { vm.toggle() }, modifier = Modifier.size(56.dp)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(28.dp)) }
                    }
                    IconButton(onClick = { vm.pm.next() }) { Icon(Icons.Default.SkipNext, null, tint = MioText, modifier = Modifier.size(32.dp)) }
                    IconButton(onClick = { vm.pm.cycleRepeat() }) { Icon(if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, null, tint = if (repeatMode == Player.REPEAT_MODE_OFF) MioTextSecondary else MioCyan) }
                }
            }
        }
    }
    // Play queue — same bottom sheet as portrait.
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
