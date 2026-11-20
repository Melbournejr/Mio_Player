
package com.mio.player.ui.screens
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
@Composable fun HomeScreen(libVm: LibraryViewModel, playerVm: PlayerViewModel, nav: NavHostController) {
    val tracks by libVm.tracks.collectAsState()
    LazyColumn(Modifier.fillMaxSize().background(MioBg).padding(horizontal = 20.dp)) {
        item { Spacer(Modifier.height(16.dp)); Text("Good evening", color = MioText, fontSize = 26.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold); Text("${tracks.size} songs in your library", color = MioTextSecondary) }
        item { SectionTitle("Recently played"); LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(tracks.take(6)) { t -> androidx.compose.material3.Card(Modifier.size(140.dp).clickable { playerVm.playQueue(tracks, tracks.indexOf(t)); nav.navigate(Routes.NOW_PLAYING) }, colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MioSurface), shape = CardLarge) { Column(Modifier.padding(12.dp)) { Text(t.title, color = MioText, maxLines = 2); Text(t.artist, color = MioTextSecondary) } } } } }
        item { SectionTitle("All songs") }
        items(tracks) { t -> TrackRow(t, onClick = { playerVm.playQueue(tracks, tracks.indexOf(t)); nav.navigate(Routes.NOW_PLAYING) }, onFav = { libVm.toggleFavorite(t) }) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
@Composable fun SearchScreen(libVm: LibraryViewModel, playerVm: PlayerViewModel, nav: NavHostController) {
    var q by remember { mutableStateOf("") }
    val tracks by libVm.tracks.collectAsState()
    Column(Modifier.fillMaxSize().background(MioBg).padding(20.dp)) {
        OutlinedTextFieldLocal("Search songs, artists, albums", q) { q = it; if (q.isBlank()) libVm.load() else libVm.search(q) }
        Spacer(Modifier.height(12.dp))
        LazyColumn { items(tracks) { t -> TrackRow(t, onClick = { playerVm.playQueue(tracks, tracks.indexOf(t)); nav.navigate(Routes.NOW_PLAYING) }) } }
    }
}
@Composable fun LibraryScreen(libVm: LibraryViewModel, playerVm: PlayerViewModel, nav: NavHostController) {
    val tracks by libVm.tracks.collectAsState()
    Column(Modifier.fillMaxSize().background(MioBg).padding(20.dp)) {
        SectionTitle("Library")
        GradientButton("Scan music folders") { libVm.scan() }
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.TextButton(onClick = { nav.navigate(Routes.ALBUM) }) { Text("Albums & artists", color = MioCyan) }
        LazyColumn { items(tracks) { t -> TrackRow(t, onClick = { playerVm.playQueue(tracks, tracks.indexOf(t)); nav.navigate(Routes.NOW_PLAYING) }, onFav = { libVm.toggleFavorite(t) }) } }
    }
}
