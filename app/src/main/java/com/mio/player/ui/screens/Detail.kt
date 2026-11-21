
package com.mio.player.ui.screens
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
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
/** v1.1.13 — Profile / Settings, reorganized into grouped sections
 *  (Âm thanh / Hiển thị / Thư viện / Phát lại / Giới thiệu), each with
 *  a short description under its header. The playback controls
 *  (Gapless / Crossfade / ReplayGain / speed) moved here from
 *  EqualizerScreen — same DataStore keys, ranges and labels, same
 *  player calls. The sleep timer stays in Now Playing. */
@Composable fun ProfileScreen(libVm: LibraryViewModel, nav: NavHostController) {
    val tracks by libVm.tracks.collectAsState()
    val update = rememberUpdateState()
    val ctx = LocalContext.current
    val settings = (ctx.applicationContext as com.mio.player.MioApp).container.settings
    val pm = (ctx.applicationContext as com.mio.player.MioApp).container.playerManager
    val userName by settings.flow(SettingsStore.KEY_USER_NAME, "").collectAsState(initial = "")
    val scope = rememberCoroutineScope()
    // v1.1.13 — playback settings, moved from EqualizerScreen.
    // Same keys and defaults as before: gapless = true, crossfade 0 ms,
    // ReplayGain "track", speed 1x. MainActivity.restoreSoundSettings
    // reads KEY_CROSSFADE_MS, KEY_REPLAYGAIN and KEY_SPEED at startup;
    // KEY_GAPLESS is stored but has no player call to restore —
    // ExoPlayer handles gapless natively, and EqualizerScreen never
    // applied it either.
    val gapless by settings.flow(SettingsStore.KEY_GAPLESS, true).collectAsState(initial = true)
    val crossfadeMs by settings.flow(SettingsStore.KEY_CROSSFADE_MS, 0).collectAsState(initial = 0)
    val replayGain by settings.flow(SettingsStore.KEY_REPLAYGAIN, "track").collectAsState(initial = "track")
    val speed by settings.flow(SettingsStore.KEY_SPEED, 1f).collectAsState(initial = 1f)
    // v1.1.13 — apply the playback values to the player whenever they
    // change (the same calls EqualizerScreen.apply() used to make).
    LaunchedEffect(crossfadeMs, replayGain, speed) {
        pm.crossfadeMs = crossfadeMs.toLong()
        pm.replayGainMode = replayGain
        pm.setSpeed(speed)
    }
    // 1.0.9: the old plain Column cut off the bottom on small phones.
    // LazyColumn scrolls — keep it one list, one section per item.
    LazyColumn(Modifier.fillMaxSize().background(MioBg).padding(horizontal = screenHorizontalPadding(), vertical = 20.dp)) {
        item {
            SectionTitle("Profile")
            Text(if (userName.isBlank()) "Local listener" else userName, color = MioText, fontSize = 20.sp)
            Spacer(Modifier.height(16.dp))
        }
        item {
            SectionTitle("Âm thanh")
            Text("Chỉnh EQ, hiệu ứng và chất lượng phát nhạc.", color = MioTextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            GradientButton("Equalizer & sound") { nav.navigate(Routes.EQ) }
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.TextButton(onClick = { nav.navigate(Routes.EQ) }) { Text("Chỉnh âm tai nghe (Harman target)", color = MioCyan) }
            Spacer(Modifier.height(16.dp))
            Text("Chất lượng nhạc", color = MioText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("Original files play as-is. DSD (.dsf/.dff) plays natively; only files the native decoder can't handle (e.g. DST-compressed .dff) are decoded once to a 176.4 kHz PCM WAV cache as a fallback, then played from cache.", color = MioTextSecondary, fontSize = 13.sp)
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
            Spacer(Modifier.height(16.dp))
        }
        item {
            // Design system: theme switch lives in Profile / Settings >
            // Interface — Deep Wave / Sunset / Follow system. Deep Wave is
            // the first-install default.
            SectionTitle("Hiển thị")
            Text("Giao diện sáng hay tối, theo ý bạn.", color = MioTextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            val themeMode by settings.themeMode.collectAsState(initial = "deep_wave")
            listOf("deep_wave" to "Deep Wave (tối)", "sunset" to "Sunset (sáng)", "system" to "Theo hệ thống").forEach { (value, label) ->
                Row(Modifier.fillMaxWidth().clickable { scope.launch { settings.setThemeMode(value) } }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (themeMode == value) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked, null, tint = MioCyan)
                    Text(label, color = MioText, modifier = Modifier.padding(start = 12.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        item {
            SectionTitle("Thư viện")
            Text("Nhạc trên máy và thông tin bài hát.", color = MioTextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Text("${tracks.size} songs • ${tracks.count { it.isFavorite }} favorites", color = MioTextSecondary)
            Spacer(Modifier.height(12.dp))
            // v1.1.10: auto metadata — fetch missing artist/album/cover
            // in the background (iTunes / MusicBrainz, free, no key)
            // and save it on the device. Off = no network lookup at all.
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
            Spacer(Modifier.height(16.dp))
        }
        item {
            SectionTitle("Phát lại")
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
            Slider(value = crossfadeSec, onValueChange = { scope.launch { settings.set(SettingsStore.KEY_CROSSFADE_MS, (it * 1000).toInt()) } }, valueRange = 0f..12f)
            Spacer(Modifier.height(8.dp))
            Text("Playback speed: ${speed}x", color = MioText)
            Slider(value = speed, onValueChange = { scope.launch { settings.set(SettingsStore.KEY_SPEED, it) } }, valueRange = 0.5f..2f)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Gapless playback", color = MioText, modifier = Modifier.weight(1f))
                Switch(checked = gapless, onCheckedChange = { scope.launch { settings.set(SettingsStore.KEY_GAPLESS, it) } })
            }
            Spacer(Modifier.height(16.dp))
        }
        item {
            SectionTitle("Giới thiệu")
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
        }
        item {
            Spacer(Modifier.height(16.dp))
            Text("Hẹn giờ ngủ (sleep timer) nằm ở màn Đang phát. Không tài khoản, không cloud — mọi thứ đều lưu trên máy.", color = MioTextSecondary, fontSize = 13.sp)
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
