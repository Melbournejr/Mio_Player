
package com.mio.player.ui.screens
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
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
import com.mio.player.data.model.Track
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
    // Landscape (v1.1.5): lists scroll the same way, but the 20 dp
    // portrait margin leaves content stretched edge to edge on a wide
    // screen — the shared screenHorizontalPadding() widens it to 48 dp.
    LazyColumn(Modifier.fillMaxSize().background(MioBg).padding(horizontal = screenHorizontalPadding())) {
        item { Spacer(Modifier.height(16.dp)); Text("Good evening", color = MioText, fontSize = 26.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold); Text("${tracks.size} songs in your library", color = MioTextSecondary, fontSize = 13.sp) }
        if (featured != null) {
            item {
                SectionTitle("Featured")
                Box(Modifier.fillMaxWidth().mioGradientBackground(CardLarge).padding(16.dp)) {
                    Row {
                        AlbumArt(featured.value.first().albumId, Modifier.size(96.dp), CardSmall, coverPath = featured.value.first().coverPath)
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
                                AlbumArt(list.first().albumId, Modifier.size(64.dp), coverPath = list.first().coverPath)
                                Spacer(Modifier.height(8.dp))
                                Text(name, color = MioText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2); Text("${list.size} songs", color = MioTextSecondary, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
        item { SectionTitle("Recently played"); LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(tracks.take(6)) { t -> androidx.compose.material3.Card(Modifier.size(140.dp).clickable { playerVm.playQueue(tracks, tracks.indexOf(t)); nav.navigate(Routes.NOW_PLAYING) }, colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MioSurface), shape = CardLarge) { Column(Modifier.padding(12.dp)) { AlbumArt(t.albumId, Modifier.size(64.dp), coverPath = t.coverPath); Spacer(Modifier.height(8.dp)); Text(t.title, color = MioText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2); Text(t.artist, color = MioTextSecondary, fontSize = 13.sp) } } } } }
        item { SectionTitle("All songs") }
        items(tracks) { t -> TrackRow(t, onClick = { playerVm.playQueue(tracks, tracks.indexOf(t)); nav.navigate(Routes.NOW_PLAYING) }, onFav = { libVm.toggleFavorite(t) }, isPlaying = t.id == currentId) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
val GENRES = listOf("Pop", "Chill", "Lo-Fi", "Rock", "Jazz", "EDM", "Classical", "Hip-Hop", "R&B", "Acoustic")
@Composable fun SearchScreen(libVm: LibraryViewModel, playerVm: PlayerViewModel, nav: NavHostController) {
    var q by remember { mutableStateOf("") }
    // Search results are a separate flow (v1.1.9 TASK 3): reading
    // libVm.tracks here used to let search() overwrite the full
    // library list, so Home/Profile then showed the filtered count.
    val results by libVm.searchResults.collectAsState()
    val queue by playerVm.queue.collectAsState(); val idx by playerVm.index.collectAsState()
    val currentId = queue.getOrNull(idx)?.id
    // Landscape (v1.1.5): 4 genre columns instead of 2 — a 2-column grid
    // on a wide screen leaves each card half the screen wide.
    val landscape = isLandscape()
    Column(Modifier.fillMaxSize().background(MioBg).padding(horizontal = screenHorizontalPadding(), vertical = 20.dp)) {
        OutlinedTextFieldLocal("Search songs, artists, albums", q) { q = it; if (q.isBlank()) libVm.search("") else libVm.search(q) }
        Spacer(Modifier.height(12.dp))
        if (q.isBlank()) {
            SectionTitle("Browse genres")
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(if (landscape) 4 else 2), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                items(GENRES.size) { i ->
                    val g = GENRES[i]
                    androidx.compose.material3.Card(Modifier.fillMaxWidth().height(64.dp).clickable { q = g; libVm.search(g) }, colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MioSurfaceRaised), shape = CardLarge) {
                        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = androidx.compose.ui.Alignment.CenterStart) { Text(g, color = MioText, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) }
                    }
                }
            }
        } else {
            LazyColumn { items(results) { t -> TrackRow(t, onClick = { playerVm.playQueue(results, results.indexOf(t)); nav.navigate(Routes.NOW_PLAYING) }, isPlaying = t.id == currentId) } }
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
    // Tabs: Songs (flat list, per spec p.4 screen 3: "Danh sách bài hát")
    // and Folders (real folders from Track.path). v1.0.1 claimed a
    // Folders tab, but this screen never had one — only the flat list —
    // so the user could never see or open the folder holding their
    // music. Tapping a folder opens its songs; tapping a song plays it
    // (from that folder's queue when inside a folder, so Next stays in
    // the folder).
    var tab by remember { mutableStateOf(0) }
    var openFolderKey by remember { mutableStateOf<String?>(null) }
    val folders = remember(tracks) {
        tracks.groupBy(::folderOf)
            .map { (key, list) -> FolderGroup(key.first, key.second, list) }
            .sortedBy { it.name.lowercase() }
    }
    val openFolder = folders.firstOrNull { it.key == openFolderKey }
    // Landscape (v1.1.5): the portrait header stacks Scan + Add-folder +
    // Albums vertically (~200 dp) — on a ~360 dp-tall landscape screen
    // that leaves almost no room for the song list. Side by side instead.
    val landscape = isLandscape()
    Column(Modifier.fillMaxSize().background(MioBg).padding(horizontal = screenHorizontalPadding(), vertical = 20.dp)) {
        SectionTitle("Library")
        if (landscape) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                GradientButton("Scan music folders", modifier = Modifier.weight(1f)) { libVm.scan() }
                androidx.compose.material3.TextButton(onClick = { folderPicker.launch(null) }) { Text("Add a music folder", color = MioCyan, maxLines = 1) }
                androidx.compose.material3.TextButton(onClick = { nav.navigate(Routes.ALBUM) }) { Text("Albums & artists", color = MioCyan, maxLines = 1) }
            }
        } else {
            GradientButton("Scan music folders") { libVm.scan() }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.TextButton(onClick = { folderPicker.launch(null) }) { Text("Add a music folder", color = MioCyan) }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.TextButton(onClick = { nav.navigate(Routes.ALBUM) }) { Text("Albums & artists", color = MioCyan) }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            LibraryTab("Songs", selected = tab == 0) { tab = 0; openFolderKey = null }
            LibraryTab("Folders", selected = tab == 1) { tab = 1; openFolderKey = null }
        }
        Spacer(Modifier.height(8.dp))
        if (tracks.isEmpty()) {
            Text("No songs yet. Tap \"Scan music folders\" or add a folder with your music.", color = MioTextSecondary, fontSize = 14.sp)
        } else if (tab == 0) {
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(tracks) { i, t ->
                    TrackRow(t, onClick = { playerVm.playQueue(tracks, i); nav.navigate(Routes.NOW_PLAYING) }, onFav = { libVm.toggleFavorite(t) }, isPlaying = t.id == currentId)
                }
            }
        } else if (openFolder == null) {
            // Landscape (v1.1.5): folders in 2 columns — a single-column
            // list of full-width cards wastes the wide screen.
            if (landscape) {
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(folders.size) { i -> FolderRow(folders[i]) { openFolderKey = folders[i].key } }
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(folders) { f -> FolderRow(f) { openFolderKey = f.key } }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { openFolderKey = null }) { Icon(Icons.Default.ArrowBack, "Back to folders", tint = MioText) }
                Column(Modifier.weight(1f)) {
                    Text(openFolder.name, color = MioText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("${openFolder.tracks.size} songs", color = MioTextSecondary, fontSize = 13.sp)
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(openFolder.tracks) { i, t ->
                    TrackRow(t, onClick = { playerVm.playQueue(openFolder.tracks, i); nav.navigate(Routes.NOW_PLAYING) }, onFav = { libVm.toggleFavorite(t) }, isPlaying = t.id == currentId)
                }
            }
        }
    }
}
/** One real folder in the Library Folders tab. `key` is unique per
 *  physical folder; `name` is the display name (last path segment,
 *  e.g. "Adele 25-2015" or "NhacCuaTui.Com"). */
private data class FolderGroup(val key: String, val name: String, val tracks: List<Track>)
/** The folder a track lives in, as (unique key, display name).
 *  Both scan sources store the folder in [Track.path], but in different
 *  forms — see MusicScanner: MediaStore tracks carry the absolute file
 *  path ("/storage/emulated/0/Music/Adele 25-2015/A1.Hello.dsf"), SAF
 *  tracks the path relative to the picked folder root
 *  ("Adele 25-2015/A1.Hello.dsf", or just "song.dsf" at the root).
 *  The parent segment is the folder in both cases. The key is the full
 *  parent path for MediaStore tracks (already unique) and the SAF tree
 *  root + relative parent for SAF tracks — two picked folders can share
 *  a relative name, so the root disambiguates them. A SAF track sitting
 *  at the root of its picked folder has no parent segment, so the folder
 *  is the picked folder itself, named from its document-tree URI. */
private fun folderOf(t: Track): Pair<String, String> {
    val parent = t.path.substringBeforeLast('/', "")
    if (parent.isNotEmpty()) {
        val name = parent.substringAfterLast('/')
        val key = if (t.path.startsWith("/")) parent else "${safTreeRoot(t.uri)}/$parent"
        return key to name
    }
    val root = safTreeRoot(t.uri)
    return root to root.substringAfterLast('/').ifEmpty { "Music folder" }
}
/** Decoded root of a SAF document-tree URI
 *  ("content://.../tree/primary%3AMusic/document/..." -> "primary:Music").
 *  Uri.pathSegments are already percent-decoded, so no extra decoding.
 *  Anything that is not a tree URI (e.g. a MediaStore content URI, which
 *  should never reach here) falls back to a generic name. */
private fun safTreeRoot(uri: Uri): String {
    val segments = uri.pathSegments
    val i = segments.indexOf("tree")
    if (i >= 0 && i + 1 < segments.size) return segments[i + 1]
    return "Music folder"
}
/** One tab of the Library tab row. Selected tab: text in primary color
 *  with a theme-gradient indicator; unselected: secondary text, no bar. */
@Composable private fun RowScope.LibraryTab(text: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.weight(1f).clickable { onClick() }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = if (selected) MioText else MioTextSecondary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        val indicator = if (selected) Modifier.mioGradientBackground(RoundedCornerShape(999.dp)) else Modifier
        Box(indicator.fillMaxWidth(0.6f).height(3.dp))
    }
}
/** One folder row in the Folders tab: folder icon, name, song count,
 *  chevron. Tapping opens the folder's songs. */
@Composable private fun FolderRow(folder: FolderGroup, onClick: () -> Unit) {
    androidx.compose.material3.Card(
        Modifier.fillMaxWidth().clickable { onClick() },
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MioSurface),
        shape = CardSmall
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Folder, null, tint = MioCyan, modifier = Modifier.size(40.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(folder.name, color = MioText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("${folder.tracks.size} songs", color = MioTextSecondary, fontSize = 13.sp)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MioTextSecondary)
        }
    }
}
