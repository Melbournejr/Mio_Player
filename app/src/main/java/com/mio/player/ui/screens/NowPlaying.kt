
package com.mio.player.ui.screens
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.navigation.NavHostController
import com.mio.player.ui.components.AlbumArt
import com.mio.player.ui.components.LiveWaveform
import com.mio.player.ui.components.formatDuration
import com.mio.player.ui.navigation.Routes
import com.mio.player.ui.theme.*
import com.mio.player.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
@Composable fun NowPlayingScreen(vm: PlayerViewModel, nav: NavHostController) {
    val queue by vm.queue.collectAsState(); val idx by vm.index.collectAsState(); val playing by vm.isPlaying.collectAsState()
    val levels by vm.pm.waveform.levels.collectAsState()
    val shuffleOn by vm.pm.shuffleEnabled.collectAsState()
    val repeatMode by vm.pm.repeatMode.collectAsState()
    val converting by vm.pm.isConvertingDsd.collectAsState()
    val dsdError by vm.pm.dsdError.collectAsState()
    val t = queue.getOrNull(idx)
    var pos by remember { mutableStateOf(0L) }
    // Reset on track change, and sync to the player's real position instead
    // of counting up blindly: the old loop kept counting after a track
    // change, so pos could exceed a shorter track's duration and the
    // slider/progress went out of range.
    LaunchedEffect(t?.id) { pos = 0L }
    LaunchedEffect(playing, t?.id) { while (playing) { pos = vm.pm.player.currentPosition.coerceIn(0L, (t?.durationMs ?: Long.MAX_VALUE)); delay(250) } }
    Column(Modifier.fillMaxSize().background(MioBg).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.KeyboardArrowDown, null, tint = MioText) }
            IconButton(onClick = { nav.navigate(Routes.EQ) }) { Icon(Icons.Default.Tune, null, tint = MioText) }
        }
        Spacer(Modifier.height(16.dp))
        if (t != null) AlbumArt(t.albumId, Modifier.size(280.dp), CardLarge) else Box(Modifier.size(280.dp).background(MioSurfaceRaised, CardLarge), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = MioCyan, modifier = Modifier.size(96.dp)) }
        Spacer(Modifier.height(24.dp))
        Text(t?.title ?: "No song", color = MioText, fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text(t?.artist ?: "", color = MioTextSecondary)
        Text(t?.displayFormat ?: "", color = MioCyan, fontSize = 12.sp)
        Text("Device output: ${vm.pm.deviceSampleRateHz/1000}kHz max • DSD plays natively (see DSD notes)", color = MioTextSecondary, fontSize = 11.sp)
        if (converting) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = MioCyan, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("  Converting DSD — please wait, do not tap again…", color = MioCyan, fontSize = 12.sp)
            }
        }
        dsdError?.let { err ->
            Spacer(Modifier.height(8.dp))
            Text(err, color = androidx.compose.ui.graphics.Color(0xFFFF8A80), fontSize = 12.sp)
        }
        Spacer(Modifier.height(16.dp))
        LiveWaveform(levels, playing, (if ((t?.durationMs ?: 1L) > 0) pos.toFloat()/(t?.durationMs ?: 1L) else 0f).coerceIn(0f, 1f), Modifier.fillMaxWidth())
        Slider(value = pos.toFloat().coerceIn(0f, (t?.durationMs?.toFloat() ?: 1f)), onValueChange = { pos = it.toLong(); vm.pm.seekTo(it.toLong()) }, valueRange = 0f..(t?.durationMs?.toFloat() ?: 1f), modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(pos), color = MioTextSecondary, fontSize = 12.sp)
            Text(formatDuration(t?.durationMs ?: 0L), color = MioTextSecondary, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.pm.setShuffle(!shuffleOn) }) { Icon(Icons.Default.Shuffle, null, tint = if (shuffleOn) MioCyan else MioTextSecondary) }
            IconButton(onClick = { vm.pm.previous() }) { Icon(Icons.Default.SkipPrevious, null, tint = MioText, modifier = Modifier.size(40.dp)) }
            // Design system: the Play button uses the theme gradient (135°),
            // not a solid color.
            Box(Modifier.size(72.dp).mioGradientBackground(androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                IconButton(onClick = { vm.toggle() }, modifier = Modifier.size(72.dp)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(32.dp)) }
            }
            IconButton(onClick = { vm.pm.next() }) { Icon(Icons.Default.SkipNext, null, tint = MioText, modifier = Modifier.size(40.dp)) }
            IconButton(onClick = { vm.pm.cycleRepeat() }) { Icon(if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, null, tint = if (repeatMode == Player.REPEAT_MODE_OFF) MioTextSecondary else MioCyan) }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { nav.navigate(Routes.LYRICS) }) { Text("Lyrics", color = MioText) }
            OutlinedButton(onClick = { nav.navigate(Routes.EQ) }) { Text("Equalizer", color = MioText) }
        }
    }
}
