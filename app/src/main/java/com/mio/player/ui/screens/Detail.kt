
package com.mio.player.ui.screens
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.mio.player.ui.components.*
import com.mio.player.ui.navigation.Routes
import com.mio.player.ui.theme.*
import com.mio.player.ui.viewmodel.LibraryViewModel
import com.mio.player.ui.viewmodel.PlayerViewModel
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
    Column(Modifier.fillMaxSize().background(MioBg).padding(20.dp)) {
        SectionTitle("Profile")
        Text("Local listener", color = MioText, fontSize = 20.sp)
        Text("${tracks.size} songs • ${tracks.count { it.isFavorite }} favorites", color = MioTextSecondary)
        Spacer(Modifier.height(16.dp))
        GradientButton("Equalizer & sound") { nav.navigate(Routes.EQ) }
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.TextButton(onClick = { libVm.scan() }) { Text("Rescan library", color = MioCyan) }
        Text("Settings: gapless, crossfade, ReplayGain, sleep timer and playback speed are in the Equalizer & Sound screen and Now Playing. No account, no cloud — everything is local.", color = MioTextSecondary, fontSize = 13.sp)
    }
}
