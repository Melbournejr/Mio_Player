
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
    Column(Modifier.fillMaxSize().background(MioBg).padding(20.dp)) {
        SectionTitle("Album")
        Text("Your library, grouped by album and artist.", color = MioTextSecondary)
        Spacer(Modifier.height(12.dp))
        GradientButton("Play all") { if (tracks.isNotEmpty()) { playerVm.playQueue(tracks, 0); nav.navigate(Routes.NOW_PLAYING) } }
        Spacer(Modifier.height(12.dp))
        LazyColumn { items(tracks) { t -> TrackRow(t, onClick = { playerVm.playQueue(tracks, tracks.indexOf(t)); nav.navigate(Routes.NOW_PLAYING) }) } }
    }
}
@Composable fun ArtistScreen(libVm: LibraryViewModel, playerVm: PlayerViewModel, nav: NavHostController) {
    val tracks by libVm.tracks.collectAsState()
    val artists = tracks.groupBy { it.artist }
    Column(Modifier.fillMaxSize().background(MioBg).padding(20.dp)) {
        SectionTitle("Artists")
        LazyColumn { items(artists.entries.toList()) { (name, list) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Column(Modifier.weight(1f)) { Text(name, color = MioText, fontSize = 18.sp); Text("${list.size} songs", color = MioTextSecondary) }
                androidx.compose.material3.TextButton(onClick = { playerVm.playQueue(list, 0); nav.navigate(Routes.NOW_PLAYING) }) { Text("Play", color = MioCyan) }
            }
        } }
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
    Column(Modifier.fillMaxSize().background(MioBg).padding(24.dp)) {
        Text(t?.title ?: "Lyrics", color = MioText, fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text("Source: $source", color = MioTextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.weight(1f)) {
            if (lines.isNotEmpty()) items(lines) { l -> Text(l.text, color = MioText, fontSize = 18.sp, modifier = Modifier.padding(vertical = 8.dp)) }
            else item { Text(plain ?: "No synced lyrics found. LRCLIB, embedded lyrics and sidecar .lrc files are checked automatically.", color = MioTextSecondary) }
        }
    }
}
@Composable fun ProfileScreen(libVm: LibraryViewModel, nav: NavHostController) {
    val tracks by libVm.tracks.collectAsState()
    val update = rememberUpdateState()
    Column(Modifier.fillMaxSize().background(MioBg).padding(20.dp)) {
        SectionTitle("Profile")
        Text("Local listener", color = MioText, fontSize = 20.sp)
        Text("${tracks.size} songs • ${tracks.count { it.isFavorite }} favorites", color = MioTextSecondary)
        Spacer(Modifier.height(16.dp))
        // Design system: theme switch lives in Profile / Settings >
        // Interface — Deep Wave / Sunset / Follow system. Deep Wave is
        // the first-install default.
        SectionTitle("Giao diện")
        val settings = (LocalContext.current.applicationContext as com.mio.player.MioApp).container.settings
        val themeMode by settings.themeMode.collectAsState(initial = "deep_wave")
        val scope = rememberCoroutineScope()
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
