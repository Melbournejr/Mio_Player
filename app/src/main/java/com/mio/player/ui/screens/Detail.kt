
package com.mio.player.ui.screens
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.mio.player.data.log.MioLog
import com.mio.player.data.ota.OtaUpdateChecker
import com.mio.player.data.prefs.SettingsStore
import com.mio.player.ui.components.*
import com.mio.player.ui.navigation.Routes
import com.mio.player.ui.theme.*
import com.mio.player.ui.viewmodel.LibraryViewModel
import com.mio.player.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch
// Landscape (v1.1.5): the screens in this file are scrolling lists /
// columns, so they already rotate — only the side margin adapts via
// screenHorizontalPadding() (20 dp portrait, 48 dp landscape).
@Composable fun AlbumDetailScreen(libVm: LibraryViewModel, playerVm: PlayerViewModel, nav: NavHostController) {
    val tracks by libVm.tracks.collectAsState()
    val queue by playerVm.queue.collectAsState(); val idx by playerVm.index.collectAsState()
    val currentId = queue.getOrNull(idx)?.id
    val first = tracks.firstOrNull()
    Column(Modifier.fillMaxSize().background(MioBg).padding(horizontal = screenHorizontalPadding(), vertical = 20.dp)) {
        if (first != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AlbumArt(first.albumId, Modifier.size(120.dp), CardLarge, coverPath = first.coverPath)
                Column(Modifier.padding(start = 16.dp)) {
                    Text(first.album, color = MioText, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    Text(first.artist, color = MioTextSecondary, fontSize = 14.sp)
                    Text("${tracks.size} songs", color = MioTextSecondary, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
        } else {
            SectionTitle("Album")
            Text("Your library, grouped by album and artist.", color = MioTextSecondary)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GradientButton("Play all", modifier = Modifier.weight(1f)) { if (tracks.isNotEmpty()) { playerVm.playQueue(tracks, 0); nav.navigate(Routes.NOW_PLAYING) } }
            GradientButton("Shuffle", modifier = Modifier.weight(1f)) { if (tracks.isNotEmpty()) { playerVm.pm.setShuffle(true); playerVm.playQueue(tracks.shuffled(), 0); nav.navigate(Routes.NOW_PLAYING) } }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn { items(tracks) { t -> TrackRow(t, onClick = { playerVm.playQueue(tracks, tracks.indexOf(t)); nav.navigate(Routes.NOW_PLAYING) }, isPlaying = t.id == currentId) } }
    }
}
@Composable fun ArtistScreen(libVm: LibraryViewModel, playerVm: PlayerViewModel, nav: NavHostController) {
    val tracks by libVm.tracks.collectAsState()
    val artists = tracks.groupBy { it.artist }
    Column(Modifier.fillMaxSize().background(MioBg).padding(horizontal = screenHorizontalPadding(), vertical = 20.dp)) {
        SectionTitle("Artists")
        LazyColumn { items(artists.entries.toList()) { (name, list) ->
            Row(Modifier.fillMaxWidth().clickable { nav.navigate("artist_detail/${android.net.Uri.encode(name)}") }.padding(vertical = 10.dp)) {
                AlbumArt(list.first().albumId, Modifier.size(52.dp), coverPath = list.first().coverPath)
                Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(name, color = MioText, fontSize = 18.sp); Text("${list.size} songs", color = MioTextSecondary) }
                androidx.compose.material3.TextButton(onClick = { playerVm.playQueue(list, 0); nav.navigate(Routes.NOW_PLAYING) }) { Text("Play", color = MioCyan) }
            }
        } }
    }
}
@Composable fun ArtistDetailScreen(name: String, libVm: LibraryViewModel, playerVm: PlayerViewModel, nav: NavHostController) {
    val tracks by libVm.tracks.collectAsState()
    val list = tracks.filter { it.artist == name }
    val popular = list.sortedByDescending { it.playCount }
    val ctx = LocalContext.current
    val settings = (ctx.applicationContext as com.mio.player.MioApp).container.settings
    val followed by settings.flow(com.mio.player.data.prefs.SettingsStore.KEY_FOLLOWED_ARTISTS, emptySet<String>()).collectAsState(initial = emptySet())
    val scope = rememberCoroutineScope()
    val isFollowed = name in followed
    Column(Modifier.fillMaxSize().background(MioBg).padding(horizontal = screenHorizontalPadding(), vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, null, tint = MioText) }
            Text("Artist", color = MioTextSecondary, fontSize = 14.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            AlbumArt(list.firstOrNull()?.albumId ?: 0, Modifier.size(120.dp), CardLarge, coverPath = list.firstOrNull()?.coverPath)
            Column(Modifier.padding(start = 16.dp)) {
                Text(name, color = MioText, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                Text("${list.size} songs", color = MioTextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.Button(onClick = {
                    scope.launch {
                        settings.set(com.mio.player.data.prefs.SettingsStore.KEY_FOLLOWED_ARTISTS, if (isFollowed) followed - name else followed + name)
                    }
                }, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = if (isFollowed) MioSurfaceRaised else MioTeal), shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp)) {
                    Text(if (isFollowed) "Following" else "Follow", color = MioText)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        SectionTitle("Popular songs")
        LazyColumn { items(popular) { t -> TrackRow(t, onClick = { playerVm.playQueue(list, list.indexOf(t)); nav.navigate(Routes.NOW_PLAYING) }) } }
    }
}
@Composable fun LyricsScreen(vm: PlayerViewModel, nav: NavHostController) {
    val queue by vm.queue.collectAsState(); val idx by vm.index.collectAsState()
    val t = queue.getOrNull(idx)
    var lines by remember { mutableStateOf(listOf<com.mio.player.data.model.LyricLine>()) }
    var plain by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf("Searching…") }
    // v1.1.6: true while the fetch runs, so the screen shows a
    // loading state instead of "No synced lyrics found" before the
    // lookup has even finished.
    var loadingLyrics by remember { mutableStateOf(true) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(t?.id) {
        loadingLyrics = true
        t?.let {
            val repo = (ctx.applicationContext as com.mio.player.MioApp).container.lyricsRepo
            // v1.1.6: pass the track's content Uri so EmbeddedLyrics
            // can open SAF tracks (their path is folder-relative).
            runCatching {
                val r = repo.getLyrics(it.artist, it.title, it.album, it.durationMs, it.path, vm.pm.player.duration.takeIf { d -> d > 0 } ?: 0, it.uri.toString())
                lines = r?.synced ?: emptyList(); plain = r?.plain; source = r?.source ?: "No lyrics found"
            }
            loadingLyrics = false
        } ?: run { loadingLyrics = false }
    }
    // v1.1.11: plain (unsynced) lyrics stay STATIC by design — no
    // estimated timestamps, no auto-scroll, no highlight. The repo
    // only fills `lines` when it has real timestamps; when it
    // doesn't, `plain` is shown as-is below. Do not synthesize
    // timing here.
    // Live sync: follow the player's real position, highlight the line
    // being sung in teal, and auto-scroll to it (the PDF requires this;
    // 1.0.8 showed all lines plain white and static).
    var pos by remember { mutableStateOf(0L) }
    val playing by vm.isPlaying.collectAsState()
    LaunchedEffect(playing, t?.id) { while (playing) { pos = vm.pm.player.currentPosition; kotlinx.coroutines.delay(250) } }
    val currentIndex = lines.indexOfLast { it.timeMs <= pos }.coerceAtLeast(0)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // v1.1.6: same centre-scroll as NowPlaying — the old
    // animateScrollToItem(currentIndex - 2) parked the current line
    // near the top edge and re-animated on every line change. The
    // current line is now scrolled to the CENTRE, only when it leaves
    // the middle third of the viewport; a small move animates, a big
    // jump snaps. And the highlight no longer requires `playing`.
    LaunchedEffect(currentIndex, lines.size) {
        if (lines.isEmpty()) return@LaunchedEffect
        runCatching {
            val info = listState.layoutInfo
            val viewportH = info.viewportSize.height
            val visible = info.visibleItemsInfo
            if (viewportH <= 0 || visible.isEmpty()) {
                listState.scrollToItem(currentIndex)
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
                listState.animateScrollToItem(targetFirst)
            } else {
                listState.scrollToItem(targetFirst)
            }
        }
    }
    Column(Modifier.fillMaxSize().background(MioBg).padding(horizontal = screenHorizontalPadding(), vertical = 20.dp)) {
        Text(t?.title ?: "Lyrics", color = MioText, fontSize = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text("Source: $source", color = MioTextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.weight(1f), state = listState) {
            if (lines.isNotEmpty()) items(lines.size) { i ->
                val l = lines[i]
                val isCurrent = i == currentIndex
                Text(l.text, color = if (isCurrent) MioCyan else MioText, fontSize = if (isCurrent) 20.sp else 18.sp, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(vertical = 8.dp))
            }
            else item { Text(if (loadingLyrics) "Đang tìm lời…" else (plain ?: "No synced lyrics found. LRCLIB, embedded lyrics and sidecar .lrc files are checked automatically."), color = MioTextSecondary) }
        }
    }
}
/** v1.1.25 — Settings (was "Profile"), split into sub-screens.
 *  The research (top-music-apps-menu-settings, 2026-11-23) found
 *  7/9 top apps put Settings in grouped sub-screens, never one long
 *  scroll, and all 6 offline apps have NO profile/account screen —
 *  Settings IS the top-level destination. So the old single-scroll
 *  ProfileScreen (5 sections, ~30 controls) becomes:
 *   - SettingsScreen: the tab destination, a header (avatar + name +
 *     library stats) + 5 navigation rows (icon + title + supporting
 *     text + chevron), each opening a sub-screen.
 *   - SettingsSoundScreen / SettingsDisplayScreen /
 *     SettingsLibraryScreen / SettingsPlaybackScreen /
 *     SettingsAboutScreen: one section each, with a back button +
 *     title and the section's existing controls.
 *  Every control, DataStore key, range, label and player call is
 *  unchanged — only the navigation changed. The bottom-nav tab is
 *  renamed "Hồ sơ" -> "Cài đặt" (MainActivity) and the route stays
 *  Routes.PROFILE so saved navigation state keeps working. */
@Composable fun SettingsScreen(libVm: LibraryViewModel, nav: NavHostController) {
    val tracks by libVm.tracks.collectAsState()
    val ctx = LocalContext.current
    val settings = (ctx.applicationContext as com.mio.player.MioApp).container.settings
    val userName by settings.flow(SettingsStore.KEY_USER_NAME, "").collectAsState(initial = "")
    val favCount = tracks.count { it.isFavorite }
    Column(Modifier.fillMaxSize().background(MioBg).padding(horizontal = screenHorizontalPadding(), vertical = 20.dp)) {
        SectionTitle("Cài đặt")
        // Header: avatar + name + library stats (the stats used to
        // live buried in the Thư viện section).
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).background(MioSurfaceRaised, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Person, null, tint = MioCyan, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.padding(start = 16.dp)) {
                Text(if (userName.isBlank()) "Local listener" else userName, color = MioText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("${tracks.size} bài hát • $favCount yêu thích", color = MioTextSecondary, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        SettingsNavRow(Icons.Default.Tune, "Âm thanh", "Equalizer, hiệu ứng, chất lượng nhạc") { nav.navigate(Routes.SETTINGS_SOUND) }
        SettingsNavRow(Icons.Default.Palette, "Hiển thị", "Giao diện sáng hay tối") { nav.navigate(Routes.SETTINGS_DISPLAY) }
        SettingsNavRow(Icons.Default.LibraryMusic, "Thư viện", "Nhạc trên máy, metadata, quét lại") { nav.navigate(Routes.SETTINGS_LIBRARY) }
        SettingsNavRow(Icons.Default.PlayArrow, "Phát lại", "ReplayGain, crossfade, tốc độ, gapless") { nav.navigate(Routes.SETTINGS_PLAYBACK) }
        SettingsNavRow(Icons.Default.Info, "Giới thiệu", "Phiên bản, cập nhật, nhật ký lỗi") { nav.navigate(Routes.SETTINGS_ABOUT) }
    }
}

/** One navigation row on the main Settings screen: leading icon,
 *  title + supporting text, trailing chevron. */
@Composable private fun SettingsNavRow(icon: ImageVector, title: String, supporting: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MioCyan, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, color = MioText, fontSize = 16.sp)
            Text(supporting, color = MioTextSecondary, fontSize = 13.sp)
        }
        Icon(Icons.Default.ChevronRight, null, tint = MioTextSecondary)
    }
}

/** Sub-screen shell: back button + title (same pattern as
 *  EqualizerScreen / LogScreen), then the section content. */
@Composable private fun SettingsSubScreen(nav: NavHostController, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(MioBg).padding(horizontal = screenHorizontalPadding(), vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, null, tint = MioText) }
            Text(title, color = MioText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            content()
        }
    }
}

/** Âm thanh sub-screen: EQ entry, headphone correction link, music
 *  quality note, DSD fallback cache. Same content as the old
 *  ProfileScreen Âm thanh section, unchanged. */
@Composable fun SettingsSoundScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val settings = (ctx.applicationContext as com.mio.player.MioApp).container.settings
    val scope = rememberCoroutineScope()
    SettingsSubScreen(nav, "Âm thanh") {
        Text("Chỉnh EQ, hiệu ứng và chất lượng phát nhạc.", color = MioTextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        GradientButton("Equalizer & sound") { nav.navigate(Routes.EQ) }
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.TextButton(onClick = { nav.navigate(Routes.EQ) }) { Text("Chỉnh âm tai nghe (Harman target)", color = MioCyan) }
        Spacer(Modifier.height(16.dp))
        Text("Chất lượng nhạc", color = MioText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("Original files play as-is. DSD (.dsf/.dff) plays natively; only files the native decoder can't handle (e.g. DST-compressed .dff) are decoded once to a 176.4 kHz PCM WAV cache as a fallback, then played from cache.", color = MioTextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        // v1.1.37 — USB bit-perfect (Android 14+): when a USB DAC is
        // connected, the mixer runs bit-perfect at the track's native
        // rate instead of resampling to 48 kHz. While it is active the
        // EQ and ReplayGain are bypassed (the signal must be untouched).
        // The status line reports what the framework really engaged.
        val pm = (ctx.applicationContext as com.mio.player.MioApp).container.playerManager
        val bpOn by settings.flow(com.mio.player.data.prefs.SettingsStore.KEY_BIT_PERFECT_USB, false).collectAsState(initial = false)
        val bpState by pm.bitPerfectState.collectAsState()
        Text("Bit-perfect USB (Android 14+)", color = MioText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("Cắm USB DAC rồi bật: nhạc ra đúng tần số gốc của file, không qua bộ trộn 48 kHz. Khi bật, EQ và ReplayGain tạm tắt vì tín hiệu phải nguyên bản.", color = MioTextSecondary, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (bpOn) "Đang bật" else "Đang tắt", color = MioText, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(
                checked = bpOn,
                onCheckedChange = { v -> scope.launch { pm.applyBitPerfectEnabled(v) } },
                enabled = com.mio.player.data.audio.UsbBitPerfectManager.isApiSupported
            )
        }
        val bpStatus = when {
            !com.mio.player.data.audio.UsbBitPerfectManager.isApiSupported -> "Máy này chưa có Android 14, không dùng được."
            bpState.active -> "Đang chạy bit-perfect: ${bpState.deviceName} · ${bpState.sampleRateHz / 1000} kHz"
            bpOn && pm.bitPerfect.findUsbDevice() == null -> "Đã bật — cắm USB DAC vào để dùng."
            bpOn -> "Đã bật — DAC này không hỗ trợ bit-perfect ở tần số của bài đang phát, đang phát thường."
            else -> "Tắt — đang phát qua bộ trộn thường (48 kHz)."
        }
        Text(bpStatus, color = MioTextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        // v1.1.39 — Hi-Res AAudio (internal DAC): bypasses the 48 kHz
        // mixer with an exclusive AAudio stream at the file's native
        // rate, so a 96/192 kHz FLAC reaches the internal DAC without
        // resampling. Only the Parametric EQ still applies (it runs in
        // the sink chain); the Graphic EQ / Bass / Virtualizer /
        // Loudness need an AudioTrack session, which AAudio has none
        // of, so they do not apply while this is active. If the phone
        // refuses exclusive mode or the requested rate, the app falls
        // back to the mixer and says so — it never claims hi-res it
        // did not get.
        val hiResOn by settings.flow(com.mio.player.data.prefs.SettingsStore.KEY_HIRES_AAUDIO, false).collectAsState(initial = false)
        val hiResState by pm.hiResState.collectAsState()
        Text("Hi-Res (AAudio, DAC trong máy)", color = MioText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("Bật để nhạc ra đúng tần số gốc của file qua DAC trong máy, không qua bộ trộn 48 kHz. Chỉ EQ Parametric còn tác dụng; EQ Graphic, Bass, Virtualizer, Loudness tạm không áp dụng ở chế độ này. Nếu máy không cho mở luồng exclusive ở tần số đó, app tự về mixer.", color = MioTextSecondary, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (hiResOn) "Đang bật" else "Đang tắt", color = MioText, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(
                checked = hiResOn,
                onCheckedChange = { v -> scope.launch { pm.applyHiResAaudioEnabled(v) } }
            )
        }
        val hiResStatus = when (val s = hiResState) {
            is com.mio.player.data.audio.AaudioOutputProvider.AaudioState.Active ->
                "Đang chạy AAudio exclusive: ${s.sampleRate / 1000} kHz${if (s.lowLatency) " · low-latency" else ""}"
            is com.mio.player.data.audio.AaudioOutputProvider.AaudioState.Fallback -> s.reason
            is com.mio.player.data.audio.AaudioOutputProvider.AaudioState.Idle ->
                if (hiResOn) "Đã bật — phát một bài để mở luồng AAudio." else "Tắt — đang phát qua bộ trộn thường (48 kHz)."
        }
        Text(hiResStatus, color = MioTextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        val cacheSizeMb = remember {
            val dir = java.io.File(ctx.cacheDir, "dsd-cache")
            (dir.listFiles()?.sumOf { it.length() } ?: 0L) / (1024 * 1024)
        }
        Text("DSD fallback decode cache: $cacheSizeMb MB. Clear it to free space — fallback files will re-decode on next play.", color = MioTextSecondary, fontSize = 13.sp)
        androidx.compose.material3.TextButton(onClick = {
            scope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    java.io.File(ctx.cacheDir, "dsd-cache").listFiles()?.forEach { it.delete() }
                }
            }
        }) { Text("Clear DSD cache", color = MioCyan) }
    }
}

/** Hiển thị sub-screen: theme choice (Deep Wave / Sunset / system).
 *  Same radio rows as the old ProfileScreen Hiển thị section. */
@Composable fun SettingsDisplayScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val settings = (ctx.applicationContext as com.mio.player.MioApp).container.settings
    val scope = rememberCoroutineScope()
    SettingsSubScreen(nav, "Hiển thị") {
        Text("Giao diện sáng hay tối, theo ý bạn.", color = MioTextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        val themeMode by settings.themeMode.collectAsState(initial = "deep_wave")
        listOf("deep_wave" to "Deep Wave (tối)", "sunset" to "Sunset (sáng)", "system" to "Theo hệ thống").forEach { (value, label) ->
            Row(Modifier.fillMaxWidth().clickable { scope.launch { settings.setThemeMode(value) } }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (themeMode == value) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked, null, tint = MioCyan)
                Text(label, color = MioText, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

/** Thư viện sub-screen: song count, auto-metadata toggle, rescan.
 *  Same content as the old ProfileScreen Thư viện section. */
@Composable fun SettingsLibraryScreen(libVm: LibraryViewModel, nav: NavHostController) {
    val tracks by libVm.tracks.collectAsState()
    val ctx = LocalContext.current
    val settings = (ctx.applicationContext as com.mio.player.MioApp).container.settings
    val scope = rememberCoroutineScope()
    SettingsSubScreen(nav, "Thư viện") {
        Text("Nhạc trên máy và thông tin bài hát.", color = MioTextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Text("${tracks.size} songs • ${tracks.count { it.isFavorite }} favorites", color = MioTextSecondary)
        Spacer(Modifier.height(12.dp))
        Text("Metadata bài hát", color = MioText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        val autoMetadata by settings.flow(SettingsStore.KEY_AUTO_METADATA, true).collectAsState(initial = true)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Tự tải metadata", color = MioText, fontSize = 16.sp)
                Text("Tự tìm nghệ sĩ, album và bìa cho bài thiếu thông tin, lưu vào máy để nghe offline.", color = MioTextSecondary, fontSize = 13.sp)
            }
            androidx.compose.material3.Switch(checked = autoMetadata, onCheckedChange = { scope.launch { settings.set(SettingsStore.KEY_AUTO_METADATA, it) } })
        }
        Spacer(Modifier.height(16.dp))
        androidx.compose.material3.TextButton(onClick = { libVm.scan() }) { Text("Rescan library", color = MioCyan) }
    }
}

/** Phát lại sub-screen: ReplayGain, crossfade, speed, gapless.
 *  Same keys, ranges and player calls as the old ProfileScreen
 *  Phát lại section (moved there from EqualizerScreen in v1.1.13). */
@Composable fun SettingsPlaybackScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val settings = (ctx.applicationContext as com.mio.player.MioApp).container.settings
    val pm = (ctx.applicationContext as com.mio.player.MioApp).container.playerManager
    val scope = rememberCoroutineScope()
    val gapless by settings.flow(SettingsStore.KEY_GAPLESS, true).collectAsState(initial = true)
    val crossfadeMs by settings.flow(SettingsStore.KEY_CROSSFADE_MS, 0).collectAsState(initial = 0)
    val replayGain by settings.flow(SettingsStore.KEY_REPLAYGAIN, "track").collectAsState(initial = "track")
    val speed by settings.flow(SettingsStore.KEY_SPEED, 1f).collectAsState(initial = 1f)
    LaunchedEffect(crossfadeMs, replayGain, speed) {
        pm.crossfadeMs = crossfadeMs.toLong()
        pm.replayGainMode = replayGain
        pm.setSpeed(speed)
    }
    SettingsSubScreen(nav, "Phát lại") {
        Text("Cách các bài nối tiếp nhau, chuẩn hoá âm lượng và tốc độ phát.", color = MioTextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Text("ReplayGain: $replayGain", color = MioText)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("off", "track", "album").forEach { m ->
                FilterChip(selected = replayGain == m, onClick = { scope.launch { settings.set(SettingsStore.KEY_REPLAYGAIN, m) } }, label = { Text(m) })
            }
        }
        Spacer(Modifier.height(8.dp))
        val crossfadeSec = crossfadeMs / 1000f
        Text("Crossfade: ${crossfadeSec.toInt()}s", color = MioText)
        ThinSlider(value = crossfadeSec, onValueChange = { scope.launch { settings.set(SettingsStore.KEY_CROSSFADE_MS, (it * 1000).toInt()) } }, valueRange = 0f..12f)
        Spacer(Modifier.height(8.dp))
        Text("Playback speed: ${speed}x", color = MioText)
        ThinSlider(value = speed, onValueChange = { scope.launch { settings.set(SettingsStore.KEY_SPEED, it) } }, valueRange = 0.5f..2f)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Gapless playback", color = MioText, modifier = Modifier.weight(1f))
            Switch(checked = gapless, onCheckedChange = { scope.launch { settings.set(SettingsStore.KEY_GAPLESS, it) } })
        }
    }
}

/** Giới thiệu sub-screen: version, update check, error log.
 *  Same content as the old ProfileScreen Giới thiệu section. */
@Composable fun SettingsAboutScreen(nav: NavHostController) {
    val update = rememberUpdateState()
    SettingsSubScreen(nav, "Giới thiệu") {
        Text("Phiên bản, cập nhật và nhật ký lỗi.", color = MioTextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Text(OtaUpdateChecker.currentVersionLabel(), color = MioTextSecondary, fontSize = 13.sp)
        Text("Updates come from GitHub Releases — no Play Store needed. A new release is published when a tag v* is pushed.", color = MioTextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        GradientButton(if (update.checking) "Checking…" else "Check for updates") { update.check(true) }
        update.available?.let { info -> UpdateDialog(info) { update.available = null } }
        Spacer(Modifier.height(16.dp))
        Text("Nhật ký lỗi", color = MioText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("Nếu app tự thoát đột ngột, mở đây xem log rồi chụp màn hình gửi lại — trong này ghi rõ app crash vì sao và đang làm gì lúc chết.", color = MioTextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        GradientButton("Xem nhật ký lỗi") { nav.navigate(Routes.LOG) }
        Spacer(Modifier.height(16.dp))
        Text("Local profile only — no server, no account. Your library stays on your device.", color = MioTextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        Text("Hẹn giờ ngủ (sleep timer) nằm ở menu ⋮ trên màn Đang phát. Không tài khoản, không cloud — mọi thứ đều lưu trên máy.", color = MioTextSecondary, fontSize = 13.sp)
    }
}

/** Album detail by name (v1.1.25): the "Đi tới album" target for the
 *  Now Playing overflow menu. The old Routes.ALBUM screen shows the
 *  FIRST album in the library, not a chosen one — navigating there
 *  from Now Playing would show the wrong album. This one filters by
 *  the album name (same pattern as ArtistDetailScreen). */
@Composable fun AlbumDetailByNameScreen(album: String, libVm: LibraryViewModel, playerVm: PlayerViewModel, nav: NavHostController) {
    val tracks by libVm.tracks.collectAsState()
    val queue by playerVm.queue.collectAsState(); val idx by playerVm.index.collectAsState()
    val currentId = queue.getOrNull(idx)?.id
    val list = tracks.filter { it.album == album }
    val first = list.firstOrNull()
    Column(Modifier.fillMaxSize().background(MioBg).padding(horizontal = screenHorizontalPadding(), vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, null, tint = MioText) }
            Text("Album", color = MioTextSecondary, fontSize = 14.sp)
        }
        if (first != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AlbumArt(first.albumId, Modifier.size(120.dp), CardLarge, coverPath = first.coverPath)
                Column(Modifier.padding(start = 16.dp)) {
                    Text(album, color = MioText, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    Text(first.artist, color = MioTextSecondary, fontSize = 14.sp)
                    Text("${list.size} songs", color = MioTextSecondary, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GradientButton("Play all", modifier = Modifier.weight(1f)) { if (list.isNotEmpty()) { playerVm.playQueue(list, 0); nav.navigate(Routes.NOW_PLAYING) } }
                GradientButton("Shuffle", modifier = Modifier.weight(1f)) { if (list.isNotEmpty()) { playerVm.pm.setShuffle(true); playerVm.playQueue(list.shuffled(), 0); nav.navigate(Routes.NOW_PLAYING) } }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn { items(list) { t -> TrackRow(t, onClick = { playerVm.playQueue(list, list.indexOf(t)); nav.navigate(Routes.NOW_PLAYING) }, isPlaying = t.id == currentId) } }
        } else {
            Text("Không tìm thấy album \"$album\".", color = MioTextSecondary)
        }
    }
}

/** Crash + event log viewer. The log is the only way to stop guessing
 *  why the app exits: a Java crash lands here with its stack trace,
 *  and the event log shows the last thing the app was doing. */
@Composable fun LogScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("Đang tải…") }
    LaunchedEffect(Unit) {
        text = "=== CRASH LOG ===\n${MioLog.readCrash(ctx)}\n\n=== EVENT LOG ===\n${MioLog.readLog(ctx)}"
    }
    Column(Modifier.fillMaxSize().background(MioBg).padding(horizontal = screenHorizontalPadding(), vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, null, tint = MioText) }
            Text("Nhật ký lỗi", color = MioText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text("Nếu app tự thoát, chụp màn hình trang này gửi lại — trong này ghi rõ app crash vì sao và đang làm gì lúc chết.", color = MioTextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(text, color = MioText, fontSize = 11.sp)
        }
        Spacer(Modifier.height(12.dp))
        GradientButton("Chia sẻ log") {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Mio Player log")
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(Intent.createChooser(intent, "Chia sẻ log"))
        }
    }
}
