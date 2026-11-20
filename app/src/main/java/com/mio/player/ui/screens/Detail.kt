
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.mio.player.ui.components.*
import com.mio.player.ui.navigation.Routes
import com.mio.player.ui.theme.*
import com.mio.player.ui.viewmodel.LibraryViewModel
import com.mio.player.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch
@Composable fun AlbumDetailScreen(libVm: LibraryViewModel, playerVm: PlayerViewModel, nav: NavHostController) {
    val tracks by libVm.tracks.collectAsState()
    val queue by playerVm.queue.collectAsState(); val idx by playerVm.index.collectAsState()
    val currentId = queue.getOrNull(idx)?.id
    val first = tracks.firstOrNull()
    Column(Modifier.fillMaxSize().background(MioBg).padding(20.dp)) {
        if (first != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AlbumArt(first.albumId, Modifier.size(120.dp), CardLarge)
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
    Column(Modifier.fillMaxSize().background(MioBg).padding(20.dp)) {
        SectionTitle("Artists")
        LazyColumn { items(artists.entries.toList()) { (name, list) ->
            Row(Modifier.fillMaxWidth().clickable { nav.navigate("artist_detail/${android.net.Uri.encode(name)}") }.padding(vertical = 10.dp)) {
                AlbumArt(list.first().albumId, Modifier.size(52.dp))
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
    Column(Modifier.fillMaxSize().background(MioBg).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, null, tint = MioText) }
            Text("Artist", color = MioTextSecondary, fontSize = 14.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            AlbumArt(list.firstOrNull()?.albumId ?: 0, Modifier.size(120.dp), CardLarge)
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
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(t?.id) {
        t?.let {
            val repo = (ctx.applicationContext as com.mio.player.MioApp).container.lyricsRepo
            val r = repo.getLyrics(it.artist, it.title, it.album, it.durationMs, it.path)
            lines = r?.synced ?: emptyList(); plain = r?.plain; source = r?.source ?: "No lyrics found"
        }
    }
    // Live sync: follow the player's real position, highlight the line
    // being sung in teal, and auto-scroll to it (the PDF requires this;
    // 1.0.8 showed all lines plain white and static).
    var pos by remember { mutableStateOf(0L) }
    val playing by vm.isPlaying.collectAsState()
    LaunchedEffect(playing, t?.id) { while (playing) { pos = vm.pm.player.currentPosition; kotlinx.coroutines.delay(250) } }
    val currentIndex = lines.indexOfLast { it.timeMs <= pos }.coerceAtLeast(0)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(currentIndex) {
        if (lines.isNotEmpty()) runCatching { listState.animateScrollToItem((currentIndex - 2).coerceAtLeast(0)) }
    }
    Column(Modifier.fillMaxSize().background(MioBg).padding(24.dp)) {
        Text(t?.title ?: "Lyrics", color = MioText, fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text("Source: $source", color = MioTextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.weight(1f), state = listState) {
            if (lines.isNotEmpty()) items(lines.size) { i ->
                val l = lines[i]
                val isCurrent = i == currentIndex && playing
                Text(l.text, color = if (isCurrent) MioCyan else MioText, fontSize = if (isCurrent) 20.sp else 18.sp, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(vertical = 8.dp))
            }
            else item { Text(plain ?: "No synced lyrics found. LRCLIB, embedded lyrics and sidecar .lrc files are checked automatically.", color = MioTextSecondary) }
        }
    }
}
@Composable fun ProfileScreen(libVm: LibraryViewModel, nav: NavHostController) {
    val tracks by libVm.tracks.collectAsState()
    val update = rememberUpdateState()
    val ctx = LocalContext.current
    val settings = (ctx.applicationContext as com.mio.player.MioApp).container.settings
    val userName by settings.flow(com.mio.player.data.prefs.SettingsStore.KEY_USER_NAME, "").collectAsState(initial = "")
    val scope = rememberCoroutineScope()
    // 1.0.9: the old plain Column cut off the bottom on small phones.
    // LazyColumn scrolls, and adds the PDF's Account / Music quality /
    // Downloads rows that were missing.
    LazyColumn(Modifier.fillMaxSize().background(MioBg).padding(20.dp)) {
        item {
            SectionTitle("Profile")
            Text(if (userName.isBlank()) "Local listener" else userName, color = MioText, fontSize = 20.sp)
            Text("${tracks.size} songs • ${tracks.count { it.isFavorite }} favorites", color = MioTextSecondary)
            Spacer(Modifier.height(16.dp))
        }
        item {
            SectionTitle("Account")
            Text("Local profile only — no server, no account. Your library stays on your device.", color = MioTextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            SectionTitle("Music quality")
            Text("Original files play as-is. DSD (.dsf/.dff) plays natively; only files the native decoder can't handle (e.g. DST-compressed .dff) are decoded once to a 176.4 kHz PCM WAV cache as a fallback, then played from cache.", color = MioTextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            SectionTitle("Downloads")
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
            SectionTitle("Giao diện")
            val themeMode by settings.themeMode.collectAsState(initial = "deep_wave")
            listOf("deep_wave" to "Deep Wave (tối)", "sunset" to "Sunset (sáng)", "system" to "Theo hệ thống").forEach { (value, label) ->
                Row(Modifier.fillMaxWidth().clickable { scope.launch { settings.setThemeMode(value) } }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (themeMode == value) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked, null, tint = MioCyan)
                    Text(label, color = MioText, modifier = Modifier.padding(start = 12.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            GradientButton("Equalizer & sound") { nav.navigate(Routes.EQ) }
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.TextButton(onClick = { libVm.scan() }) { Text("Rescan library", color = MioCyan) }
            Spacer(Modifier.height(8.dp))
            SectionTitle("Updates (OTA)")
            Text(OtaUpdateChecker.currentVersionLabel(), color = MioTextSecondary, fontSize = 13.sp)
            Text("Updates come from GitHub Releases — no Play Store needed. A new release is published when a tag v* is pushed.", color = MioTextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            GradientButton(if (update.checking) "Checking…" else "Check for updates") { update.check(true) }
            update.available?.let { info -> UpdateDialog(info) { update.available = null } }
            Spacer(Modifier.height(16.dp))
            SectionTitle("Nhật ký lỗi")
            Text("Nếu app tự thoát đột ngột, mở đây xem log rồi chụp màn hình gửi lại — trong này ghi rõ app crash vì sao và đang làm gì lúc chết.", color = MioTextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            GradientButton("Xem nhật ký lỗi") { nav.navigate(Routes.LOG) }
            Text("Settings: gapless, crossfade, ReplayGain, sleep timer and playback speed are in the Equalizer & Sound screen and Now Playing. No account, no cloud — everything is local.", color = MioTextSecondary, fontSize = 13.sp)
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
    Column(Modifier.fillMaxSize().background(MioBg).padding(20.dp)) {
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
