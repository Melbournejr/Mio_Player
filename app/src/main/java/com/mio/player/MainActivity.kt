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
import android.os.SystemClock
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.mio.player.data.prefs.SettingsStore
import com.mio.player.ui.components.GradientIcon
import com.mio.player.ui.components.isLandscape
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
        setContent {
            val themeMode by (application as MioApp).container.settings.themeMode.collectAsState(initial = "deep_wave")
            MioPlayerTheme(themeMode) { MioPlayerApp() }
        }
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
/** v1.1.9 TASK 1 — rapid-tab-tap debounce (black screen fix).
 *  MUST be a file-level var, not remember{} inside MainScaffold:
 *  every tab destination creates a NEW MainScaffold instance, so a
 *  remembered timestamp would reset on every navigation and the
 *  debounce would be useless exactly when it is needed. Written and
 *  read only on the main thread (NavigationBarItem onClick), so no
 *  synchronization is needed. */
private var lastTabNavUptimeMs = 0L
private const val TAB_NAV_DEBOUNCE_MS = 300L
/** Restore saved sound settings (EQ bands, preamp, bass, virtualizer,
 *  loudness, balance, crossfade, ReplayGain, speed) at startup.
 *  AudioEffectsManager remembers the values, so they are re-applied when
 *  the effects are attached to the player's audio session.
 *  v1.1.13 — preampDb is set BEFORE applyBands, because applyBands writes
 *  (band + preamp) per band; restoring bands first would play one moment
 *  without the preamp offset. */
suspend fun restoreSoundSettings(ctx: Context) {
    val container = (ctx.applicationContext as MioApp).container
    val settings = container.settings
    val pm = container.playerManager
    val bandsCsv = settings.flow(SettingsStore.KEY_EQ_BANDS, "").first()
    val bands = FloatArray(10) { 0f }
    bandsCsv.split(",").mapNotNull { it.toFloatOrNull() }.forEachIndexed { i, v -> if (i < 10) bands[i] = v }
    pm.fx.preampDb = settings.flow(SettingsStore.KEY_PREAMP, 0f).first()
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
    // v1.1.9 TASK 1 — black screen on rapid tab taps: navigation-compose
    // 2.7.7 defaults to a fade + scale transition (~700 ms). Rapid taps
    // queued overlapping transitions; an interrupted transition disposed
    // the outgoing destination while the incoming was still at ~0 alpha,
    // leaving only the window background (black) — the video frame g50
    // shows Library stuck at ~5% opacity with a bright status bar. This
    // app has no designed screen transition to preserve, so tab (and all)
    // switches are instant. The MioBg modifier is belt-and-braces: even
    // mid-transition the surface behind a destination is the theme
    // colour, never black.
    NavHost(nav, startDestination = if (onboarded == true) Routes.HOME else Routes.SPLASH,
        modifier = Modifier.fillMaxSize().background(com.mio.player.ui.theme.MioBg),
        enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None }) {
        composable(Routes.SPLASH) { SplashScreen { nav.navigate(Routes.ONBOARDING) { popUpTo(Routes.SPLASH) { inclusive = true } } } }
        composable(Routes.ONBOARDING) { OnboardingScreen(onDone = { scope.launch { container.settings.setOnboarded(true) }; nav.navigate(Routes.LOGIN) }, onSkip = { scope.launch { container.settings.setOnboarded(true) }; nav.navigate(Routes.HOME) }) }
        composable(Routes.LOGIN) { LoginScreen(onDone = { nav.navigate(Routes.GENRES) }) }
        composable(Routes.GENRES) { PickGenresScreen(onDone = { nav.navigate(Routes.HOME) { popUpTo(Routes.SPLASH) { inclusive = true } } }) }
        composable(Routes.HOME) { MainScaffold(nav, Routes.HOME, libVm, playerVm) { HomeScreen(libVm, playerVm, nav) } }
        composable(Routes.SEARCH) { MainScaffold(nav, Routes.SEARCH, libVm, playerVm) { SearchScreen(libVm, playerVm, nav) } }
        composable(Routes.LIBRARY) { MainScaffold(nav, Routes.LIBRARY, libVm, playerVm) { LibraryScreen(libVm, playerVm, nav) } }
        composable(Routes.PROFILE) { MainScaffold(nav, Routes.PROFILE, libVm, playerVm) { ProfileScreen(libVm, nav) } }
        composable(Routes.NOW_PLAYING) { NowPlayingRoute(playerVm, nav) }
        // Design system (p.8): bottom navigation + mini player are
        // mandatory on every screen. Album / Artist / Lyrics previously
        // had neither — no way back to Home/Search/Library/Profile
        // without the system back button, and no mini player. They now
        // use MainScaffold like the four main tabs. Lyrics is an
        // extension of Now Playing, so no tab is marked selected ("").
        composable(Routes.LYRICS) { MainScaffold(nav, "", libVm, playerVm) { LyricsScreen(playerVm, nav) } }
        composable(Routes.EQ) { EqualizerScreen(nav) }
        composable(Routes.LOG) { LogScreen(nav) }
        composable(Routes.ALBUM) { MainScaffold(nav, Routes.LIBRARY, libVm, playerVm) { AlbumDetailScreen(libVm, playerVm, nav) } }
        composable(Routes.ARTIST) { MainScaffold(nav, Routes.LIBRARY, libVm, playerVm) { ArtistScreen(libVm, playerVm, nav) } }
        composable(Routes.ARTIST_DETAIL) { backStackEntry ->
            val name = backStackEntry.arguments?.getString("name") ?: ""
            MainScaffold(nav, Routes.LIBRARY, libVm, playerVm) { ArtistDetailScreen(name, libVm, playerVm, nav) }
        }
    }
}
@Composable fun MainScaffold(nav: NavHostController, current: String, libVm: LibraryViewModel, playerVm: PlayerViewModel, content: @Composable () -> Unit) {
    val queue by playerVm.queue.collectAsState(); val idx by playerVm.index.collectAsState(); val playing by playerVm.isPlaying.collectAsState()
    val tabs = listOf(Triple(Routes.HOME, "Trang chủ", Icons.Default.Home), Triple(Routes.SEARCH, "Tìm kiếm", Icons.Default.Search),
        Triple(Routes.LIBRARY, "Thư viện", Icons.Default.LibraryMusic), Triple(Routes.PROFILE, "Hồ sơ", Icons.Default.Person))
    // v1.1.9 TASK 1 — black screen on rapid tab taps. The old goTab did
    // nav.navigate(r) { popUpTo(Routes.HOME) } with NO launchSingleTop /
    // restoreState / saveState: every tap pushed a NEW destination
    // instance on top of the stack after popping to HOME, so rapid taps
    // queued several overlapping fade transitions and recreated the
    // bottom bar (and its GradientIcon drawWithCache + SrcIn rect)
    // mid-transition — that is when the icons rendered as grey circles /
    // a red square blob. Now: standard bottom-nav block (single top
    // instance per tab, state saved/restored across tabs) + a 300 ms
    // debounce so a second NavController transaction cannot start while
    // the previous one is still being applied.
    val goTab: (String) -> Unit = { r ->
        if (current != r) {
            val now = SystemClock.uptimeMillis()
            if (now - lastTabNavUptimeMs >= TAB_NAV_DEBOUNCE_MS) {
                lastTabNavUptimeMs = now
                nav.navigate(r) {
                    launchSingleTop = true
                    restoreState = true
                    popUpTo(Routes.HOME) { saveState = true }
                }
            }
        }
    }
    // LANDSCAPE (v1.1.5): a bottom NavigationBar + MiniPlayer costs ~150 dp
    // of a ~360 dp-tall landscape screen, leaving lists and content
    // squeezed. The standard landscape pattern is a side NavigationRail
    // (same 4 tabs, same gradient selected icon) with the MiniPlayer
    // pinned at the bottom of the content column instead. Portrait keeps
    // the bottom bar + mini player exactly as before.
    if (isLandscape()) {
        Row(Modifier.fillMaxSize().background(com.mio.player.ui.theme.MioBg)) {
            NavigationRail(containerColor = com.mio.player.ui.theme.MioSurface) {
                tabs.forEach { (r, label, icon) ->
                    NavigationRailItem(selected = current == r, onClick = { goTab(r) },
                        icon = { if (current == r) GradientIcon(icon, null) else Icon(icon, null, tint = com.mio.player.ui.theme.MioTextSecondary) },
                        label = { Text(label, color = if (current == r) com.mio.player.ui.theme.MioText else com.mio.player.ui.theme.MioTextSecondary, fontSize = 11.sp) },
                        colors = NavigationRailItemDefaults.colors(selectedIconColor = androidx.compose.ui.graphics.Color.White, unselectedIconColor = com.mio.player.ui.theme.MioTextSecondary,
                            selectedTextColor = com.mio.player.ui.theme.MioText, unselectedTextColor = com.mio.player.ui.theme.MioTextSecondary, indicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                }
            }
            Column(Modifier.weight(1f)) {
                Box(Modifier.weight(1f)) { content() }
                queue.getOrNull(idx)?.let { t -> MiniPlayer(t.title, t.artist, playing, { playerVm.toggle() }, { playerVm.next() }, { nav.navigate(Routes.NOW_PLAYING) }, albumId = t.albumId, coverPath = t.coverPath) }
            }
        }
        return
    }
    // v1.1.9 TASK 1: containerColor + content background are MioBg so
    // the Scaffold surface itself is the theme colour — even if a
    // destination is ever mid-transition, the surface behind it is
    // never the black window background.
    Scaffold(
        containerColor = com.mio.player.ui.theme.MioBg,
        bottomBar = {
            Column {
                queue.getOrNull(idx)?.let { t -> MiniPlayer(t.title, t.artist, playing, { playerVm.toggle() }, { playerVm.next() }, { nav.navigate(Routes.NOW_PLAYING) }, albumId = t.albumId, coverPath = t.coverPath) }
                NavigationBar(containerColor = com.mio.player.ui.theme.MioSurface) {
                    tabs.forEach { (r, label, icon) ->
                        NavigationBarItem(selected = current == r, onClick = { goTab(r) },
                            // Design system: the selected icon uses the theme
                            // gradient; unselected icons are secondary text.
                            icon = { if (current == r) GradientIcon(icon, null) else Icon(icon, null, tint = com.mio.player.ui.theme.MioTextSecondary) },
                            label = { Text(label, color = if (current == r) com.mio.player.ui.theme.MioText else com.mio.player.ui.theme.MioTextSecondary) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = androidx.compose.ui.graphics.Color.White, unselectedIconColor = com.mio.player.ui.theme.MioTextSecondary,
                                selectedTextColor = com.mio.player.ui.theme.MioText, unselectedTextColor = com.mio.player.ui.theme.MioTextSecondary, indicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                    }
                }
            }
        }
    ) { pad -> Box(Modifier.padding(pad).fillMaxSize().background(com.mio.player.ui.theme.MioBg)) { content() } }
}
