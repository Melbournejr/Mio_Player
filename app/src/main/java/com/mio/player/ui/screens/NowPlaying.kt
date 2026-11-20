
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
import com.mio.player.ui.components.LiveWaveform
import com.mio.player.ui.navigation.Routes
import com.mio.player.ui.theme.*
import com.mio.player.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
@Composable fun NowPlayingScreen(vm: PlayerViewModel, nav: NavHostController) {
    val queue by vm.queue.collectAsState(); val idx by vm.index.collectAsState(); val playing by vm.isPlaying.collectAsState()
    val levels by vm.pm.waveform.levels.collectAsState()
    val shuffleOn by vm.pm.shuffleEnabled.collectAsState()
    val repeatMode by vm.pm.repeatMode.collectAsState()
    val t = queue.getOrNull(idx)
    var pos by remember { mutableStateOf(0L) }
    LaunchedEffect(playing) { while (playing) { delay(500); pos += 500 } }
    Column(Modifier.fillMaxSize().background(MioBg).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.KeyboardArrowDown, null, tint = MioText) }
            IconButton(onClick = { nav.navigate(Routes.EQ) }) { Icon(Icons.Default.Tune, null, tint = MioText) }
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.size(280.dp).background(MioSurfaceRaised, CardLarge), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = MioCyan, modifier = Modifier.size(96.dp)) }
        Spacer(Modifier.height(24.dp))
        Text(t?.title ?: "No song", color = MioText, fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text(t?.artist ?: "", color = MioTextSecondary)
        Text(t?.displayFormat ?: "", color = MioCyan, fontSize = 12.sp)
        Text("Device output: ${vm.pm.deviceSampleRateHz/1000}kHz max • DSD is decoded to PCM (see DSD notes)", color = MioTextSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(16.dp))
        LiveWaveform(levels, playing, if ((t?.durationMs ?: 1L) > 0) pos.toFloat()/(t?.durationMs ?: 1L) else 0f, Modifier.fillMaxWidth())
        Slider(value = pos.toFloat(), onValueChange = { pos = it.toLong(); vm.pm.seekTo(it.toLong()) }, valueRange = 0f..(t?.durationMs?.toFloat() ?: 1f), modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.pm.setShuffle(!shuffleOn) }) { Icon(Icons.Default.Shuffle, null, tint = if (shuffleOn) MioCyan else MioTextSecondary) }
            IconButton(onClick = { vm.pm.previous() }) { Icon(Icons.Default.SkipPrevious, null, tint = MioText, modifier = Modifier.size(40.dp)) }
            FloatingActionButton(onClick = { vm.toggle() }, containerColor = MioTeal) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(32.dp)) }
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
