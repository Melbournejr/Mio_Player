package com.mio.player.ui.screens
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.mio.player.data.prefs.SettingsStore
import com.mio.player.ui.theme.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
/** Equalizer & Sound. Every change is saved to SettingsStore (DataStore)
 *  immediately, and MainActivity restores the saved values at startup —
 *  before this screen only kept the values in memory, so reopening it
 *  (or restarting the app) reset everything to Flat. */
@Composable fun EqualizerScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val container = (ctx.applicationContext as com.mio.player.MioApp).container
    val pm = container.playerManager
    val settings = container.settings
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
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
    fun save() {
        if (!loaded) return
        scope.launch {
            settings.set(SettingsStore.KEY_EQ_PRESET, preset)
            settings.set(SettingsStore.KEY_EQ_BANDS, bands.joinToString(","))
            settings.set(SettingsStore.KEY_BASS, (bass*1000).toInt())
            settings.set(SettingsStore.KEY_VIRTUALIZER, (virt*1000).toInt())
            settings.set(SettingsStore.KEY_LOUDNESS, (loud*1000).toInt())
            settings.set(SettingsStore.KEY_BALANCE, balance)
            settings.set(SettingsStore.KEY_GAPLESS, gapless)
            settings.set(SettingsStore.KEY_CROSSFADE_MS, (crossfade*1000).toInt())
            settings.set(SettingsStore.KEY_REPLAYGAIN, replayGain)
            settings.set(SettingsStore.KEY_SPEED, speed)
        }
    }
    fun applyAndSave() { apply(); save() }
    LaunchedEffect(Unit) {
        preset = settings.flow(SettingsStore.KEY_EQ_PRESET, "Flat").first()
        val csv = settings.flow(SettingsStore.KEY_EQ_BANDS, "").first()
        val parsed = FloatArray(10) { 0f }
        csv.split(",").mapNotNull { it.toFloatOrNull() }.forEachIndexed { i, v -> if (i < 10) parsed[i] = v }
        bands = parsed
        bass = settings.flow(SettingsStore.KEY_BASS, 0).first() / 1000f
        virt = settings.flow(SettingsStore.KEY_VIRTUALIZER, 0).first() / 1000f
        loud = settings.flow(SettingsStore.KEY_LOUDNESS, 0).first() / 1000f
        balance = settings.flow(SettingsStore.KEY_BALANCE, 0f).first()
        gapless = settings.flow(SettingsStore.KEY_GAPLESS, true).first()
        crossfade = settings.flow(SettingsStore.KEY_CROSSFADE_MS, 0).first() / 1000f
        replayGain = settings.flow(SettingsStore.KEY_REPLAYGAIN, "track").first()
        speed = settings.flow(SettingsStore.KEY_SPEED, 1f).first()
        loaded = true
        apply()
    }
    if (!loaded) {
        Box(Modifier.fillMaxSize().background(MioBg), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MioCyan) }
        return
    }
    LazyColumn(Modifier.fillMaxSize().background(MioBg).padding(20.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, null, tint = MioText) }; Text("Equalizer & Sound", color = MioText, fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) } }
        item { Text("Presets", color = MioTextSecondary); LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(pm.fx.presets.keys.toList()) { p -> FilterChip(selected = preset==p, onClick = { preset=p; bands = (pm.fx.presets[p] ?: bands).copyOf(); applyAndSave() }, label = { Text(p) }) } } }
        items(AudioEffectsManager.BAND_FREQS_HZ.size) { i ->
            Column { Text("${AudioEffectsManager.BAND_FREQS_HZ[i]} Hz — ${bands[i].toInt()} dB", color = MioText); Slider(value = bands[i], onValueChange = { v -> bands = bands.copyOf().also { it[i] = v }; applyAndSave() }, valueRange = -15f..15f) }
        }
        item {
            Text("Bass Boost", color = MioText); Slider(value = bass, onValueChange = { bass=it; applyAndSave() })
            Text("Virtualizer / 3D", color = MioText); Slider(value = virt, onValueChange = { virt=it; applyAndSave() })
            Text("Loudness", color = MioText); Slider(value = loud, onValueChange = { loud=it; applyAndSave() })
            Text("Balance (L — R)", color = MioText); Slider(value = balance, onValueChange = { balance=it; applyAndSave() }, valueRange = -1f..1f)
            Text("ReplayGain: $replayGain", color = MioText); Row { listOf("off","track","album").forEach { m -> FilterChip(selected = replayGain==m, onClick = { replayGain=m; applyAndSave() }, label = { Text(m) }) } }
            Text("Crossfade: ${crossfade.toInt()}s", color = MioText); Slider(value = crossfade, onValueChange = { crossfade=it; applyAndSave() }, valueRange = 0f..12f)
            Text("Playback speed: ${speed}x", color = MioText); Slider(value = speed, onValueChange = { speed=it; applyAndSave() }, valueRange = 0.5f..2f)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Gapless playback", color = MioText, modifier = Modifier.weight(1f)); Switch(checked = gapless, onCheckedChange = { gapless=it; applyAndSave() }) }
            Spacer(Modifier.height(16.dp))
            Text("Effects use Android AudioEffect (Equalizer / BassBoost / Virtualizer / LoudnessEnhancer) on ExoPlayer's audio session. If your phone does not support an effect, it is skipped silently — playback never breaks.", color = MioTextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(24.dp))
        }
    }
}
