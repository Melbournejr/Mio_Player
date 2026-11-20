package com.mio.player
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.mio.player.data.prefs.SettingsStore
import com.mio.player.ui.components.MiniPlayer
import com.mio.player.ui.navigation.Routes
import com.mio.player.ui.screens.*
import com.mio.player.ui.theme.MioPlayerTheme
import com.mio.player.ui.viewmodel.LibraryViewModel
import com.mio.player.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MioPlayerTheme { MioPlayerApp() } }
    }
}
/** Runtime permission for reading music files: READ_MEDIA_AUDIO on
 *  Android 13+, READ_EXTERNAL_STORAGE on older versions. The manifest
 *  declares both, but without this runtime request MediaStore returns
 *  nothing, so the library stayed empty on first install. */
fun musicPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE
fun hasMusicPermission(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, musicPermission()) == PackageManager.PERMISSION_GRANTED
/** Restore saved sound settings (EQ bands, bass, virtualizer, loudness,
 *  balance, crossfade, ReplayGain, speed) at startup. AudioEffectsManager
 *  remembers the values, so they are re-applied when the effects are
 *  attached to the player's audio session. */
suspend fun restoreSoundSettings(ctx: Context) {
    val container = (ctx.applicationContext as MioApp).container
    val settings = container.settings
    val pm = container.playerManager
    val bandsCsv = settings.flow(SettingsStore.KEY_EQ_BANDS, "").first()
    val bands = FloatArray(10) { 0f }
    bandsCsv.split(",").mapNotNull { it.toFloatOrNull() }.forEachIndexed { i, v -> if (i < 10) bands[i] = v }
    pm.fx.applyBands(bands)
    pm.fx.setBassBoost(settings.flow(SettingsStore.KEY_BASS, 0).first())
    pm.fx.setVirtualizer(settings.flow(SettingsStore.KEY_VIRTUALIZER, 0).first())
    pm.fx.setLoudness(settings.flow(SettingsStore.KEY_LOUDNESS, 0).first())
    pm.fx.balance = settings.flow(SettingsStore.KEY_BALANCE, 0f).first()
    pm.crossfadeMs = settings.flow(SettingsStore.KEY_CROSSFADE_MS, 0).first().toLong()
    pm.replayGainMode = settings.flow(SettingsStore.KEY_REPLAYGAIN, "track").first()
    pm.setSpeed(settings.flow(SettingsStore.KEY_SPEED, 1f).first())
    pm.setShuffle(settings.flow(SettingsStore.KEY_SHUFFLE, false).first())
    pm.setRepeatMode(settings.flow(SettingsStore.KEY_REPEAT, Player.REPEAT_MODE_OFF).first())
}
@Composable fun MioPlayerApp() {
    val ctx = LocalContext.current
    val nav = rememberNavController()
    val container = (ctx.applicationContext as MioApp).container
    val scope = rememberCoroutineScope()
    // collectAsState needs a non-null initial value, but we must not guess
    // onboarded=false before DataStore loads (that would flash the intro
    // for returning users), so use produceState with a null "not loaded
    // yet" state instead.
    val onboarded by produceState<Boolean?>(initialValue = null) {
        container.settings.onboarded.collect { value = it }
    }
    val libVm: LibraryViewModel = viewModel()
    val playerVm: PlayerViewModel = viewModel()
    val update = rememberUpdateState()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) libVm.scan() else libVm.load()
    }
    LaunchedEffect(Unit) {
        restoreSoundSettings(ctx)
        if (hasMusicPermission(ctx)) libVm.scan() else { libVm.load(); permissionLauncher.launch(musicPermission()) }
        update.check(false)
    }
    update.available?.let { info -> UpdateDialog(info) { update.available = null } }
    // First launch only: splash + intro + login + genres. Afterwards the
    // app opens straight into Home — the intro used to replay on every
    // launch because nothing ever read or wrote the onboarded flag.
    if (onboarded == null) {
        Box(Modifier.fillMaxSize().background(com.mio.player.ui.theme.MioBg)) {}
        return
    }
    NavHost(nav, startDestination = if (onboarded == true) Routes.HOME else Routes.SPLASH) {
        composable(Routes.SPLASH) { SplashScreen { nav.navigate(Routes.ONBOARDING) { popUpTo(Routes.SPLASH) { inclusive = true } } } }
        composable(Routes.ONBOARDING) { OnboardingScreen(onDone = { scope.launch { container.settings.setOnboarded(true) }; nav.navigate(Routes.LOGIN) }, onSkip = { scope.launch { container.settings.setOnboarded(true) }; nav.navigate(Routes.HOME) }) }
        composable(Routes.LOGIN) { LoginScreen(onDone = { nav.navigate(Routes.GENRES) }) }
        composable(Routes.GENRES) { PickGenresScreen(onDone = { nav.navigate(Routes.HOME) { popUpTo(Routes.SPLASH) { inclusive = true } } }) }
        composable(Routes.HOME) { MainScaffold(nav, Routes.HOME, libVm, playerVm) { HomeScreen(libVm, playerVm, nav) } }
        composable(Routes.SEARCH) { MainScaffold(nav, Routes.SEARCH, libVm, playerVm) { SearchScreen(libVm, playerVm, nav) } }
        composable(Routes.LIBRARY) { MainScaffold(nav, Routes.LIBRARY, libVm, playerVm) { LibraryScreen(libVm, playerVm, nav) } }
        composable(Routes.PROFILE) { MainScaffold(nav, Routes.PROFILE, libVm, playerVm) { ProfileScreen(libVm, nav) } }
        composable(Routes.NOW_PLAYING) { NowPlayingScreen(playerVm, nav) }
        composable(Routes.LYRICS) { LyricsScreen(playerVm, nav) }
        composable(Routes.EQ) { EqualizerScreen(nav) }
        composable(Routes.LOG) { LogScreen(nav) }
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
