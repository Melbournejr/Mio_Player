
package com.mio.player.ui.screens
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.mio.player.data.audio.AudioEffectsManager
import com.mio.player.ui.theme.*
import kotlinx.coroutines.launch
@Composable fun EqualizerScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val container = (ctx.applicationContext as com.mio.player.MioApp).container
    val pm = container.playerManager
    val scope = rememberCoroutineScope()
    var preset by remember { mutableStateOf("Flat") }
    var bands by remember { mutableStateOf(FloatArray(10) { 0f }) }
    var bass by remember { mutableStateOf(0f) }
    var virt by remember { mutableStateOf(0f) }
    var loud by remember { mutableStateOf(0f) }
    var balance by remember { mutableStateOf(0f) }
    var gapless by remember { mutableStateOf(true) }
    var crossfade by remember { mutableStateOf(0f) }
    var replayGain by remember { mutableStateOf("track") }
    var speed by remember { mutableStateOf(1f) }
    fun apply() { pm.fx.applyBands(bands); pm.fx.setBassBoost((bass*1000).toInt()); pm.fx.setVirtualizer((virt*1000).toInt()); pm.fx.setLoudness((loud*1000).toInt()); pm.fx.balance = balance; pm.crossfadeMs = (crossfade*1000).toLong(); pm.replayGainMode = replayGain; pm.setSpeed(speed) }
    LazyColumn(Modifier.fillMaxSize().background(MioBg).padding(20.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, null, tint = MioText) }; Text("Equalizer & Sound", color = MioText, fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) } }
        item { Text("Presets", color = MioTextSecondary); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Flat","Bass","Rock","Jazz").forEach { p -> FilterChip(selected = preset==p, onClick = { preset=p; bands = (pm.fx.presets[p] ?: bands).copyOf(); apply() }, label = { Text(p) }) } } }
        items(AudioEffectsManager.BAND_FREQS_HZ.size) { i ->
            Column { Text("${AudioEffectsManager.BAND_FREQS_HZ[i]} Hz — ${bands[i].toInt()} dB", color = MioText); Slider(value = bands[i], onValueChange = { v -> bands = bands.copyOf().also { it[i] = v }; apply() }, valueRange = -15f..15f) }
        }
        item {
            Text("Bass Boost", color = MioText); Slider(value = bass, onValueChange = { bass=it; apply() })
            Text("Virtualizer / 3D", color = MioText); Slider(value = virt, onValueChange = { virt=it; apply() })
            Text("Loudness", color = MioText); Slider(value = loud, onValueChange = { loud=it; apply() })
            Text("Balance (L — R)", color = MioText); Slider(value = balance, onValueChange = { balance=it; apply() }, valueRange = -1f..1f)
            Text("ReplayGain: $replayGain", color = MioText); Row { listOf("off","track","album").forEach { m -> FilterChip(selected = replayGain==m, onClick = { replayGain=m; apply() }, label = { Text(m) }) } }
            Text("Crossfade: ${crossfade.toInt()}s", color = MioText); Slider(value = crossfade, onValueChange = { crossfade=it; apply() }, valueRange = 0f..12f)
            Text("Playback speed: ${speed}x", color = MioText); Slider(value = speed, onValueChange = { speed=it; apply() }, valueRange = 0.5f..2f)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Gapless playback", color = MioText, modifier = Modifier.weight(1f)); Switch(checked = gapless, onCheckedChange = { gapless=it }) }
            Spacer(Modifier.height(16.dp))
            Text("Effects use Android AudioEffect (Equalizer / BassBoost / Virtualizer / LoudnessEnhancer) on ExoPlayer's audio session. If your phone does not support an effect, it is skipped silently — playback never breaks.", color = MioTextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(24.dp))
        }
    }
}
