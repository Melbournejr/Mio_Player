package com.mio.player.ui.screens
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.navigation.NavHostController
import com.mio.player.data.audio.DsdSupport
import com.mio.player.data.model.LyricLine
import com.mio.player.ui.components.AlbumArt
import com.mio.player.ui.components.MetadataEditDialog
import com.mio.player.ui.visualizer.VIS_HERO_HEIGHT_DP
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
 *  v1.1.6 (lyrics): the inline lyrics finally get room — the cover
 *  shrinks to 160 dp once lyrics load (was 240 dp always, leaving
 *  ~2 lines visible); v1.1.12 shrinks it further to 120 dp, aiming
 *  for ~5–6 visible lines; the current line scrolls to the CENTRE of the
 *  viewport and only when it leaves the middle third, so it settles
 *  instead of re-animating per line; the highlight no longer needs
 *  `playing`; and a loading state shows while fetching, with the
 *  lines appearing during DSD converting as soon as they are fetched.
 *  The separate Lyrics screen stays reachable via "Xem đầy đủ", and
 *  the transport row stays pinned at the bottom.
 *  v1.1.17 (hero visualizer): the square cover is gone from this
 *  screen — the visualizer is the protagonist now. A 320 dp hero
 *  visualizer sits directly under the top bar (Circular's disc is
 *  ~128 dp there — 40%, v1.1.18, was ~180 dp at 56% — Ferrofluid's
 *  blob/spikes scale up), the cover survives only as a 40 dp
 *  circular thumb beside the title/artist and as the art inside
 *  Circular's disc. A fullscreen icon in the top bar opens an
 *  edge-to-edge expanded visualizer (Dialog + BackHandler); the
 *  transport row repeats there. Lyrics lose room (~3–4 lines
 *  instead of ~5) — accepted trade-off.
 *  v1.1.21 — portrait like landscape (user's 1.1.20 test): the
 *  "Tìm metadata" text button moves into a MoreVert overflow menu
 *  in the top bar and "Xem đầy đủ" becomes a 20 dp Fullscreen
 *  icon (both as landscape v1.1.20), and the format line is ONE
 *  centred line with a concise rate ("MP3 · 48 kHz", was two
 *  lines "MP3" + "Thiết bị phát tối đa 48 kHz") — the freed
 *  vertical room goes to the lyrics list.
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
    // v1.1.21 — overflow menu for the header actions that used to
    // be labelled text (mirrors landscape v1.1.20): "Tìm metadata"
    // moves in here, so the portrait screen reads like landscape.
    var showOverflow by remember { mutableStateOf(false) }
    // v1.1.17: expanded (full-screen) visualizer Dialog.
    var expandedVisualizer by remember { mutableStateOf(false) }
    // v1.1.12: the manual metadata action now opens the edit dialog
    // (MetadataEditDialog) instead of fetching immediately — the user
    // types the search info first. Reset on track change with the
    // lyrics state below.
    var showMetadataDialog by remember { mutableStateOf(false) }
    // Inline synced lyrics — same source and call as LyricsScreen
    // (Detail.kt): LyricsRepository via the app container, LRCLIB /
    // sidecar .lrc / embedded, cached in Room.
    var lines by remember { mutableStateOf(listOf<LyricLine>()) }
    var plain by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf("Đang tìm lời…") }
    // v1.1.6: true while the lyrics fetch for this track is running,
    // so the lyrics area shows a loading state instead of a blank
    // box — and once fetched, the lines show DURING converting too
    // (the old code reset the lines on track change and the cramped
    // area left nothing visible even after the LRCLIB HIT landed).
    var loadingLyrics by remember { mutableStateOf(true) }
    val ctx = LocalContext.current
    // Reset on track change, and sync to the player's real position instead
    // of counting up blindly: the old loop kept counting after a track
    // change, so pos could exceed a shorter track's duration and the
    // slider/progress went out of range.
    LaunchedEffect(t?.id) {
        pos = 0L
        currentUri = vm.pm.player.currentMediaItem?.localConfiguration?.uri
        lines = emptyList(); plain = null; source = "Đang tìm lời…"; loadingLyrics = true
        showMetadataDialog = false
        t?.let {
            val repo = (ctx.applicationContext as com.mio.player.MioApp).container.lyricsRepo
            // v1.1.6: pass the track's content Uri so EmbeddedLyrics
            // can open SAF tracks (their path is folder-relative).
            runCatching {
                val r = repo.getLyrics(it.artist, it.title, it.album, it.durationMs, it.path, vm.pm.player.duration.takeIf { d -> d > 0 } ?: 0, it.uri.toString())
                lines = r?.synced ?: emptyList(); plain = r?.plain; source = r?.source ?: "Không tìm thấy lời"
            }
            loadingLyrics = false
        } ?: run { loadingLyrics = false }
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
        // v1.1.21 — concise: just the rate ("48 kHz"), not the
        // sentence ("Thiết bị phát tối đa 48 kHz") — the user wants
        // the track-info tail short to save space, and the portrait
        // format line is now ONE line (see below), so "MP3 · 48 kHz".
        else -> "${vm.pm.deviceSampleRateHz / 1000} kHz"
    }
    // Live lyric sync: same rule as LyricsScreen — the line whose time
    // has passed, highlighted teal, list auto-scrolled to it.
    // v1.1.6: the highlight no longer requires `playing` — a paused
    // track still has a current line, and the old `&& playing` left
    // the visible line dark whenever playback was paused.
    val currentIndex = lines.indexOfLast { it.timeMs <= pos }.coerceAtLeast(0)
    val lyricListState = rememberLazyListState()
    // v1.1.6: scroll the current line to the CENTRE of the viewport,
    // not the top edge. The old animateScrollToItem(currentIndex - 1)
    // parked the current line at the very top, where it was clipped —
    // the user's screenshot showed one visible line, un-highlighted,
    // because the highlighted line was scrolled just off the top.
    // scrollToItem/animateScrollToItem only take a NON-NEGATIVE
    // offset, so centring is done by choosing the first visible index
    // instead: currentIndex minus the number of lines in half a
    // viewport. And the list only scrolls when the current line would
    // leave the middle third of the viewport (or is not visible at
    // all), so it settles instead of re-animating on every line
    // change; a small move animates, a big jump (seek / track change)
    // snaps instantly.
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
    // v1.1.17: the square cover is GONE from this screen (the old
    // coverSize/hasLyrics shrink logic with it). The visualizer is
    // the protagonist: a 320 dp hero directly under the top bar.
    // The cover survives only as a 40 dp circular thumb beside the
    // title/artist, and as the art inside Circular's disc.
    Column(Modifier.fillMaxSize().background(MioBg).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.KeyboardArrowDown, null, tint = MioText) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // v1.1.21 — "Tìm metadata" leaves the header (same as
                // landscape v1.1.20): on the user's 1.1.20 portrait
                // frame the labelled button is one more line of text
                // crowding the screen. It now lives in the overflow
                // menu (same dialog, v1.1.12) behind a standard
                // MoreVert icon — the action is unchanged, only its
                // affordance shrinks.
                Box {
                    IconButton(onClick = { showOverflow = true }) { Icon(Icons.Default.MoreVert, "Thêm", tint = MioText) }
                    DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                        DropdownMenuItem(
                            text = { Text("Tìm metadata") },
                            onClick = { showOverflow = false; showMetadataDialog = true },
                            leadingIcon = { Icon(Icons.Default.Download, null, tint = MioCyan) }
                        )
                    }
                }
                IconButton(onClick = { showQueue = true }) { Icon(Icons.Default.QueueMusic, "Hàng đợi", tint = MioText) }
                // v1.1.17: fullscreen visualizer (edge-to-edge Dialog).
                IconButton(onClick = { expandedVisualizer = true }) { Icon(Icons.Default.Fullscreen, "Toàn màn hình", tint = MioText) }
                // The ONLY Equalizer entry point on this screen (Fix 5).
                IconButton(onClick = { nav.navigate(Routes.EQ) }) { Icon(Icons.Default.Tune, "Equalizer", tint = MioText) }
            }
        }
        // v1.1.17 — HERO visualizer, 320 dp, full width, directly
        // under the top bar. Circular's disc is ~128 dp here (40%
        // of the 320 dp canvas, v1.1.18 — was ~180 dp at 56%),
        // Ferrofluid's blob/spikes scale by 320/140 ≈ 2.29x;
        // Bars/Wave/Mirror fill the taller canvas.
        VisualizerPanel(levels, pcmFrame, playing, Modifier.fillMaxWidth(), albumId = t?.albumId ?: 0L, coverPath = t?.coverPath, heightDp = VIS_HERO_HEIGHT_DP)
        Spacer(Modifier.height(8.dp))
        // v1.1.17 — title/artist row: a small circular art thumb
        // (40 dp) + the title/artist beside it, left-aligned. The
        // big square cover is gone (see above).
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (t != null) AlbumArt(t.albumId, Modifier.size(40.dp), CircleShape, coverPath = t.coverPath) else Box(Modifier.size(40.dp).background(MioSurfaceRaised, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = MioCyan, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t?.title ?: "No song", color = MioText, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t?.artist ?: "", color = MioTextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(8.dp))
        // v1.1.7: the DSD native state line is identical to displayFormat
        // ("DSD128 · Native decode"), so show the format once — the
        // state line is only rendered when it says something different.
        // v1.1.21 — format + state on ONE centred line (was two
        // lines, like landscape v1.1.18): with the concise state
        // ("48 kHz", see above) this reads "MP3 · 48 kHz" and frees
        // a whole line of vertical room for the lyrics list.
        val formatText = t?.displayFormat ?: ""
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(formatText, color = MioCyan, fontSize = 12.sp, maxLines = 1)
            if (stateText.isNotEmpty() && stateText != formatText) {
                Text(" · $stateText", color = MioTextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
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
        // v1.1.21 — the "Tìm metadata" text button is GONE from this
        // screen (it moved into the MoreVert overflow menu in the
        // top bar, see above — same as landscape v1.1.20). The
        // button still opens the metadata edit dialog (v1.1.12,
        // rendered at the bottom of this file); only its affordance
        // changed. Removing this row frees ~36 dp for the lyrics list.
        // v1.1.17: the visualizer moved to the hero slot directly
        // under the top bar — it is no longer down here.
        Spacer(Modifier.height(4.dp))
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
            // v1.1.21 — "Xem đầy đủ" becomes a small icon (was a
            // text line, same as landscape v1.1.20): a 20 dp
            // Fullscreen icon with 4 dp padding, same destination
            // (the full Lyrics screen). No IconButton — its 48 dp
            // min touch target would steal the height the icon is
            // meant to save.
            Icon(
                Icons.Default.Fullscreen, "Xem đầy đủ", tint = MioCyan,
                modifier = Modifier.size(20.dp).clickable { nav.navigate(Routes.LYRICS) }.padding(4.dp)
            )
        }
        if (loadingLyrics) {
            // Fetching (incl. while DSD is converting — the fetch is
            // independent of the transcode, and the lines appear here
            // as soon as they land instead of leaving a blank area).
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = MioCyan, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("  Đang tìm lời…", color = MioTextSecondary, fontSize = 14.sp)
                }
            }
        } else if (lines.isNotEmpty()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = lyricListState) {
                items(lines.size) { i ->
                    val l = lines[i]
                    val isCurrent = i == currentIndex
                    Text(l.text, color = if (isCurrent) MioCyan else MioText, fontSize = if (isCurrent) 18.sp else 16.sp, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(vertical = 6.dp))
                }
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(plain ?: source, color = MioTextSecondary, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
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
    // v1.1.17 — expanded (full-screen) visualizer. Edge-to-edge
    // Dialog (no platform default width), dark background, top bar
    // with a close button + the track title, the visualizer filling
    // the remaining height, and a transport row at the bottom. Back
    // (system/gesture) exits via BackHandler. Tap/swipe on the
    // visualizer still cycles styles; Ferrofluid's freq chips still
    // show top-right.
    BackHandler(enabled = expandedVisualizer) { expandedVisualizer = false }
    if (expandedVisualizer) {
        Dialog(
            onDismissRequest = { expandedVisualizer = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(Modifier.fillMaxSize().background(MioBg)) {
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { expandedVisualizer = false }) { Icon(Icons.Default.Close, "Đóng", tint = MioText) }
                        Text(t?.title ?: "Đang phát", color = MioText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                        Spacer(Modifier.size(48.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        VisualizerPanel(levels, pcmFrame, playing, Modifier.fillMaxWidth(), albumId = t?.albumId ?: 0L, coverPath = t?.coverPath, heightDp = maxHeight.value)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.pm.previous() }) { Icon(Icons.Default.SkipPrevious, null, tint = MioText, modifier = Modifier.size(40.dp)) }
                        Box(Modifier.size(72.dp).mioGradientBackground(CircleShape), contentAlignment = Alignment.Center) {
                            IconButton(onClick = { vm.toggle() }, modifier = Modifier.size(72.dp)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(32.dp)) }
                        }
                        IconButton(onClick = { vm.pm.next() }) { Icon(Icons.Default.SkipNext, null, tint = MioText, modifier = Modifier.size(40.dp)) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
    // v1.1.12: metadata edit dialog — opened by the "Tìm metadata"
    // button above; Tìm / Lưu inside it swap the queue item live via
    // onTrackUpdated so Now Playing / MiniPlayer show the new
    // artist/album/cover immediately.
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
