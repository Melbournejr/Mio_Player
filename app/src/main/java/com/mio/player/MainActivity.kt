package com.mio.player
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.mio.player.ui.components.MiniPlayer
import com.mio.player.ui.navigation.Routes
import com.mio.player.ui.screens.*
import com.mio.player.ui.theme.MioPlayerTheme
import com.mio.player.ui.viewmodel.LibraryViewModel
import com.mio.player.ui.viewmodel.PlayerViewModel
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MioPlayerTheme { MioApp() } }
    }
}
@Composable fun MioApp() {
    val nav = rememberNavController()
    val libVm: LibraryViewModel = viewModel()
    val playerVm: PlayerViewModel = viewModel()
    LaunchedEffect(Unit) { libVm.load() }
    NavHost(nav, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) { SplashScreen { nav.navigate(Routes.ONBOARDING) { popUpTo(Routes.SPLASH) { inclusive = true } } } }
        composable(Routes.ONBOARDING) { OnboardingScreen(onDone = { nav.navigate(Routes.LOGIN) }, onSkip = { nav.navigate(Routes.HOME) }) }
        composable(Routes.LOGIN) { LoginScreen(onDone = { nav.navigate(Routes.GENRES) }) }
        composable(Routes.GENRES) { PickGenresScreen(onDone = { nav.navigate(Routes.HOME) { popUpTo(Routes.SPLASH) { inclusive = true } } }) }
        composable(Routes.HOME) { MainScaffold(nav, Routes.HOME, libVm, playerVm) { HomeScreen(libVm, playerVm, nav) } }
        composable(Routes.SEARCH) { MainScaffold(nav, Routes.SEARCH, libVm, playerVm) { SearchScreen(libVm, playerVm, nav) } }
        composable(Routes.LIBRARY) { MainScaffold(nav, Routes.LIBRARY, libVm, playerVm) { LibraryScreen(libVm, playerVm, nav) } }
        composable(Routes.PROFILE) { MainScaffold(nav, Routes.PROFILE, libVm, playerVm) { ProfileScreen(libVm, nav) } }
        composable(Routes.NOW_PLAYING) { NowPlayingScreen(playerVm, nav) }
        composable(Routes.LYRICS) { LyricsScreen(playerVm, nav) }
        composable(Routes.EQ) { EqualizerScreen(nav) }
        composable(Routes.ALBUM) { AlbumDetailScreen(libVm, playerVm, nav) }
        composable(Routes.ARTIST) { ArtistScreen(libVm, playerVm, nav) }
    }
}
@Composable fun MainScaffold(nav: NavHostController, current: String, libVm: LibraryViewModel, playerVm: PlayerViewModel, content: @Composable () -> Unit) {
    val queue by playerVm.queue.collectAsState(); val idx by playerVm.index.collectAsState(); val playing by playerVm.isPlaying.collectAsState()
    Scaffold(
        bottomBar = {
            Column {
                queue.getOrNull(idx)?.let { t -> MiniPlayer(t.title, t.artist, playing, { playerVm.toggle() }, { nav.navigate(Routes.NOW_PLAYING) }) }
                NavigationBar(containerColor = com.mio.player.ui.theme.MioSurface) {
                    listOf(Triple(Routes.HOME, "Home", Icons.Default.Home), Triple(Routes.SEARCH, "Search", Icons.Default.Search),
                        Triple(Routes.LIBRARY, "Library", Icons.Default.LibraryMusic), Triple(Routes.PROFILE, "Profile", Icons.Default.Person)).forEach { (r, label, icon) ->
                        NavigationBarItem(selected = current == r, onClick = { if (current != r) nav.navigate(r) { popUpTo(Routes.HOME) } },
                            icon = { Icon(icon, null) }, label = { Text(label) })
                    }
                }
            }
        }
    ) { pad -> Box(Modifier.padding(pad)) { content() } }
}
