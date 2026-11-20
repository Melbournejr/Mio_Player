
package com.mio.player.ui.screens
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    val queue by playerVm.queue.collectAsState(); val idx by playerVm.index.collectAsState()
    val currentId = queue.getOrNull(idx)?.id
    // Featured album = the album with the most tracks; playlists = albums.
    val albums = tracks.groupBy { it.album }.entries.sortedByDescending { it.value.size }
    val featured = albums.firstOrNull()
    LazyColumn(Modifier.fillMaxSize().background(MioBg).padding(horizontal = 20.dp)) {
        item { Spacer(Modifier.height(16.dp)); Text("Good evening", color = MioText, fontSize = 26.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold); Text("${tracks.size} songs in your library", color = MioTextSecondary) }
        if (featured != null) {
            item {
                SectionTitle("Featured")
                Box(Modifier.fillMaxWidth().mioGradientBackground(CardLarge).padding(16.dp)) {
                    Row {
                        AlbumArt(featured.value.first().albumId, Modifier.size(96.dp), CardSmall)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(featured.key, color = androidx.compose.ui.graphics.Color.White, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 2)
                            Text("${featured.value.first().artist} • ${featured.value.size} songs", color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                            androidx.compose.material3.Button(onClick = { playerVm.playQueue(featured.value, 0); nav.navigate(Routes.NOW_PLAYING) },
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color.White),
                                shape = RoundedCornerShape(999.dp)) { Text("Play", color = MioTeal) }
                        }
                    }
                }
            }
        }
        if (albums.size > 1) {
            item {
                SectionTitle("Playlists")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(albums.drop(1).take(8)) { (name, list) ->
                        androidx.compose.material3.Card(Modifier.size(140.dp).clickable { playerVm.playQueue(list, 0); nav.navigate(Routes.NOW_PLAYING) }, colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MioSurface), shape = CardLarge) {
                            Column(Modifier.padding(12.dp)) {
                                AlbumArt(list.first().albumId, Modifier.size(64.dp))
                                Spacer(Modifier.height(8.dp))
                                Text(name, color = MioText, maxLines = 2); Text("${list.size} songs", color = MioTextSecondary)
                            }
                        }
                    }
                }
            }
        }
        item { SectionTitle("Recently played"); LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(tracks.take(6)) { t -> androidx.compose.material3.Card(Modifier.size(140.dp).clickable { playerVm.playQueue(tracks, tracks.indexOf(t)); nav.navigate(Routes.NOW_PLAYING) }, colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MioSurface), shape = CardLarge) { Column(Modifier.padding(12.dp)) { AlbumArt(t.albumId, Modifier.size(64.dp)); Spacer(Modifier.height(8.dp)); Text(t.title, color = MioText, maxLines = 2); Text(t.artist, color = MioTextSecondary) } } } } }
        item { SectionTitle("All songs") }
        items(tracks) { t -> TrackRow(t, onClick = { playerVm.playQueue(tracks, tracks.indexOf(t)); nav.navigate(Routes.NOW_PLAYING) }, onFav = { libVm.toggleFavorite(t) }, isPlaying = t.id == currentId) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
val GENRES = listOf("Pop", "Chill", "Lo-Fi", "Rock", "Jazz", "EDM", "Classical", "Hip-Hop", "R&B", "Acoustic")
@Composable fun SearchScreen(libVm: LibraryViewModel, playerVm: PlayerViewModel, nav: NavHostController) {
    var q by remember { mutableStateOf("") }
    val tracks by libVm.tracks.collectAsState()
    val queue by playerVm.queue.collectAsState(); val idx by playerVm.index.collectAsState()
    val currentId = queue.getOrNull(idx)?.id
    Column(Modifier.fillMaxSize().background(MioBg).padding(20.dp)) {
        OutlinedTextFieldLocal("Search songs, artists, albums", q) { q = it; if (q.isBlank()) libVm.load() else libVm.search(q) }
        Spacer(Modifier.height(12.dp))
        if (q.isBlank()) {
            SectionTitle("Browse genres")
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                items(GENRES.size) { i ->
                    val g = GENRES[i]
                    androidx.compose.material3.Card(Modifier.fillMaxWidth().height(64.dp).clickable { q = g; libVm.search(g) }, colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MioSurfaceRaised), shape = CardLarge) {
                        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = androidx.compose.ui.Alignment.CenterStart) { Text(g, color = MioText, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) }
                    }
                }
            }
        } else {
            LazyColumn { items(tracks) { t -> TrackRow(t, onClick = { playerVm.playQueue(tracks, tracks.indexOf(t)); nav.navigate(Routes.NOW_PLAYING) }, isPlaying = t.id == currentId) } }
        }
    }
}
@Composable fun LibraryScreen(libVm: LibraryViewModel, playerVm: PlayerViewModel, nav: NavHostController) {
    val tracks by libVm.tracks.collectAsState()
    val queue by playerVm.queue.collectAsState(); val idx by playerVm.index.collectAsState()
    val currentId = queue.getOrNull(idx)?.id
    val ctx = LocalContext.current
    // SAF folder picker: without this there was NO way to add a music
    // folder, so DSD files MediaStore doesn't index never entered the
    // library at all. The URI permission is persisted so the folder
    // stays readable after a restart.
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            libVm.addFolder(uri.toString())
        }
    }
    Column(Modifier.fillMaxSize().background(MioBg).padding(20.dp)) {
        SectionTitle("Library")
        GradientButton("Scan music folders") { libVm.scan() }
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.TextButton(onClick = { folderPicker.launch(null) }) { Text("Add a music folder", color = MioCyan) }
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.TextButton(onClick = { nav.navigate(Routes.ALBUM) }) { Text("Albums & artists", color = MioCyan) }
        LazyColumn { items(tracks) { t -> TrackRow(t, onClick = { playerVm.playQueue(tracks, tracks.indexOf(t)); nav.navigate(Routes.NOW_PLAYING) }, onFav = { libVm.toggleFavorite(t) }, isPlaying = t.id == currentId) } }
    }
}
