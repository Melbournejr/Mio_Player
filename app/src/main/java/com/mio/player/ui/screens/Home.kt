
package com.mio.player.ui.screens
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.mio.player.hasMusicPermission
import com.mio.player.musicPermission
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
/** Folder name for grouping: parent directory of the file path
 *  ("/storage/.../Music/AlbumX/song.flac" -> "AlbumX"). */
fun folderName(path: String): String {
    val parent = path.substringBeforeLast('/', "")
    if (parent.isEmpty()) return "Thư mục gốc"
    return parent.substringAfterLast('/').ifEmpty { "Thư mục gốc" }
}
@Composable fun LibraryScreen(libVm: LibraryViewModel, playerVm: PlayerViewModel, nav: NavHostController) {
    val tracks by libVm.tracks.collectAsState()
    val ctx = LocalContext.current
    var hasPerm by remember { mutableStateOf(hasMusicPermission(ctx)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPerm = granted
        if (granted) libVm.scan() else libVm.load()
    }
    var tab by remember { mutableStateOf(0) } // 0 = all songs, 1 = folders
    var expandedFolder by remember { mutableStateOf<String?>(null) }
    val folders = remember(tracks) { tracks.groupBy { folderName(it.path) }.entries.sortedBy { it.key } }
    Column(Modifier.fillMaxSize().background(MioBg).padding(20.dp)) {
        SectionTitle("Library")
        if (!hasPerm) {
            Text("Chưa cấp quyền truy cập nhạc nên thư viện đang trống.", color = MioTextSecondary)
            Spacer(Modifier.height(8.dp))
            GradientButton("Cấp quyền truy cập nhạc") { permissionLauncher.launch(musicPermission()) }
            Spacer(Modifier.height(4.dp))
            Text("Nếu đã chọn từ chối vĩnh viễn, vào Cài đặt > Quyền > Nhạc để bật lại.", color = MioTextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
        }
        GradientButton("Scan music folders") { libVm.scan() }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { nav.navigate(Routes.ALBUM) }) { Text("Albums & artists", color = MioCyan) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Tất cả (${tracks.size})") })
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Thư mục (${folders.size})") })
        }
        Spacer(Modifier.height(8.dp))
        if (tab == 0) {
            LazyColumn { items(tracks) { t -> TrackRow(t, onClick = { playerVm.playQueue(tracks, tracks.indexOf(t)); nav.navigate(Routes.NOW_PLAYING) }, onFav = { libVm.toggleFavorite(t) }) } }
        } else {
            LazyColumn {
                items(folders) { (name, list) ->
                    Column {
                        Row(Modifier.fillMaxWidth().clickable { expandedFolder = if (expandedFolder == name) null else name }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, null, tint = MioCyan)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(name, color = MioText, fontSize = 18.sp)
                                Text("${list.size} bài", color = MioTextSecondary)
                            }
                            TextButton(onClick = { playerVm.playQueue(list, 0); nav.navigate(Routes.NOW_PLAYING) }) { Text("Phát", color = MioCyan) }
                            IconButton(onClick = { expandedFolder = if (expandedFolder == name) null else name }) { Icon(if (expandedFolder == name) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MioTextSecondary) }
                        }
                        if (expandedFolder == name) {
                            list.forEach { t -> TrackRow(t, onClick = { playerVm.playQueue(list, list.indexOf(t)); nav.navigate(Routes.NOW_PLAYING) }, onFav = { libVm.toggleFavorite(t) }) }
                        }
                    }
                }
            }
        }
    }
}
