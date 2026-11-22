package com.mio.player.ui.screens
import android.app.Activity
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.navigation.NavHostController
import com.mio.player.data.audio.DsdSupport
import com.mio.player.data.model.LyricLine
import com.mio.player.ui.components.AlbumArt
import com.mio.player.ui.components.MetadataEditDialog
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
 *   - left: live visualizer (fills the column — v1.1.18, was
 *     cover + a 140 dp visualizer strip)
 *   - right: compact title row (40 dp art thumb + title/artist),
 *     format + state on one line, inline synced lyrics, seek
 *     slider, compact transport row
 *
 * State is the SAME as portrait, collected from the same PlayerViewModel
 * and the same LyricsRepository call — no new ViewModel, no new audio
 * code. The queue bottom sheet, the single EQ entry point (top-right
 * Tune icon) and the actual-state line (native vs WAV-fallback, never
 * claimed from the file type alone) are identical to portrait.
 *
 * v1.1.18 — landscape RESTRUCTURE (the v1.1.17 landscape was
 * broken on the user's screenshot, 2026-11-22 21:55: Circular
 * ~75 dp, 0 lyric lines, the play button squeezed to a pill).
 * (a) The large cover is GONE from the left column (portrait
 * v1.1.17 already removed it; the art lives in the Circular
 * disc + the 40 dp thumb in the title row) and the visualizer
 * fills the ENTIRE left column, so Circular is as big in
 * landscape as the portrait hero (~130 dp disc, ~92 dp rays).
 * (b) The right column is compacted: a 40 dp art thumb + title/
 * artist beside it (matching portrait), format + state on ONE
 * line, and the "Tìm metadata" button moved to the header — so
 * the lyrics list gets ~106 dp (~3 lines) and the transport
 * keeps its full 56 dp play button instead of being squeezed.
 * Nothing else in this screen changes.
 *
 * v1.1.19 — landscape goes FULL-SCREEN (immersive) and the right
 * column is re-budgeted for the lyrics list. Measured on the
 * user's 1.1.18 screenshot (2026-11-22 23:01): the lyrics
 * viewport was only ~57 dp and showed exactly 1 line — the
 * "~106 dp (~3 lines)" claimed in (b) was wrong. (a) The status
 * + navigation bars are hidden while this screen shows (restored
 * on dispose), freeing ~40 dp. (b) Outer vertical padding 12 ->
 * 8 dp. (c) The "Xem đầy đủ" TextButton (48 dp min touch target)
 * becomes a plain clickable Text, so the lyrics header row drops
 * from ~48 dp to ~24 dp. Together the lyrics viewport grows from
 * ~57 dp to ~130 dp (~4 lines, estimated — unproven until the
 * user tests). The visualizer, the transport (56 dp play button)
 * and "Tìm metadata" are unchanged.
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
    // v1.1.12: the manual metadata action now opens the edit dialog
    // (MetadataEditDialog) instead of fetching immediately — same as
    // portrait (NowPlaying.kt). Reset on track change with the
    // lyrics state below.
    var showMetadataDialog by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf(listOf<LyricLine>()) }
    var plain by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf("Đang tìm lời…") }
    val ctx = LocalContext.current
    // v1.1.19 — landscape is FULL-SCREEN (immersive): hide the status
    // + navigation bars while this screen is showing, so the lyrics
    // get the freed height (~40 dp). Restored on dispose (rotation
    // back to portrait / leaving the screen). Transient bars on swipe.
    DisposableEffect(ctx) {
        val activity = ctx as? Activity
        val window = activity?.window
        if (window == null) return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
    LaunchedEffect(t?.id) {
        pos = 0L
        currentUri = vm.pm.player.currentMediaItem?.localConfiguration?.uri
        lines = emptyList(); plain = null; source = "Đang tìm lời…"
        showMetadataDialog = false
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
    // v1.1.19: vertical padding 12 -> 8 dp (landscape full-screen:
    // every dp goes to the lyrics list).
    Column(Modifier.fillMaxSize().background(MioBg).padding(horizontal = 20.dp, vertical = 8.dp)) {
        // Header — same actions as portrait: back, queue sheet, the ONLY
        // EQ entry point (top-right Tune icon).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.KeyboardArrowDown, null, tint = MioText) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // v1.1.18: the "Tìm metadata" button lives here now
                // (was a full row in the right column) — it frees
                // ~36 dp so the lyrics list gets ~3 lines. Same
                // labelled action as portrait; opens the metadata
                // edit dialog (v1.1.12).
                TextButton(onClick = { showMetadataDialog = true }) {
                    Icon(Icons.Default.Download, null, tint = MioCyan, modifier = Modifier.size(16.dp))
                    Text("Tìm metadata", color = MioCyan, fontSize = 13.sp)
                }
                IconButton(onClick = { showQueue = true }) { Icon(Icons.Default.QueueMusic, "Hàng đợi", tint = MioText) }
                IconButton(onClick = { nav.navigate(Routes.EQ) }) { Icon(Icons.Default.Tune, "Equalizer", tint = MioText) }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // LEFT — v1.1.18: the visualizer fills the ENTIRE column
            // (was: a large cover + a 140 dp visualizer strip). The
            // large cover is gone — portrait v1.1.17 already removed
            // it; the art lives in the Circular disc + the 40 dp
            // thumb in the title row. `heightDp` comes from this
            // column's own constraints (same pattern as the portrait
            // full-screen dialog in NowPlaying.kt), so Circular's
            // disc is ~130 dp here, as big as the portrait hero.
            // v1.1.13: the panel gets the current track's cover so the
            // Circular style can ring the album art (other styles
            // ignore it); `t` is the playing track in scope here.
            BoxWithConstraints(Modifier.weight(0.42f).fillMaxHeight()) {
                VisualizerPanel(levels, pcmFrame, playing, Modifier.fillMaxWidth(), albumId = t?.albumId ?: 0L, coverPath = t?.coverPath, heightDp = maxHeight.value)
            }
            // RIGHT — track info, inline synced lyrics, seek, transport.
            // Compact transport (56 dp play button vs 72 dp portrait)
            // so the lyrics list keeps usable height on short screens.
            Column(Modifier.weight(0.58f).fillMaxHeight()) {
                // v1.1.18 — compact title row (was: separate 24 sp
                // title + 14 sp artist lines): a 40 dp circular art
                // thumb + the title/artist beside it, matching
                // portrait v1.1.17. Frees vertical room for the
                // lyrics list.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (t != null) AlbumArt(t.albumId, Modifier.size(40.dp), androidx.compose.foundation.shape.CircleShape, coverPath = t.coverPath) else Box(Modifier.size(40.dp).background(MioSurfaceRaised, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = MioCyan, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t?.title ?: "No song", color = MioText, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t?.artist ?: "", color = MioTextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // v1.1.7: same dedupe as portrait NowPlaying — the DSD
                // native state line is identical to displayFormat, so
                // show the format once; the state line only renders
                // when it says something different.
                // v1.1.18: format + state on ONE line (was two
                // lines) — frees ~16 dp for the lyrics list.
                val formatText = t?.displayFormat ?: ""
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatText, color = MioCyan, fontSize = 12.sp, maxLines = 1)
                    if (stateText.isNotEmpty() && stateText != formatText) {
                        Text(" · $stateText", color = MioTextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
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
                // v1.1.18: the "Tìm metadata" button moved to the
                // header (see above) — this row is gone, freeing
                // ~36 dp for the lyrics list. The button still opens
                // the metadata edit dialog (v1.1.12, rendered at the
                // bottom of this file).
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Lời bài hát", color = MioText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    // v1.1.19: a plain clickable Text, not a TextButton —
                    // TextButton enforces a 48 dp min touch target, which
                    // made this header row 48 dp tall and stole ~24 dp
                    // from the lyrics list. Same destination.
                    Text("Xem đầy đủ", color = MioCyan, fontSize = 13.sp, modifier = Modifier.clickable { nav.navigate(Routes.LYRICS) }.padding(8.dp))
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
                Spacer(Modifier.height(2.dp))
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
    // v1.1.12: metadata edit dialog — same as portrait: opened by
    // the "Tìm metadata" button above; Tìm / Lưu inside it swap the
    // queue item live via onTrackUpdated.
    val trackNow = t
    if (showMetadataDialog && trackNow != null) {
        val container = (ctx.applicationContext as com.mio.player.MioApp).container
        MetadataEditDialog(
            track = trackNow,
            repo = container.metadataRepo,
            lyricsRepo = container.lyricsRepo,
            onDismissRequest = { showMetadataDialog = false },
            onTrackUpdated = { vm.pm.updateQueueTrack(it) },
        )
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
