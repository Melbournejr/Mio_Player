package com.mio.player.ui.screens
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import com.mio.player.data.audio.AudioEffectsManager
import com.mio.player.data.audio.EqPresets
import com.mio.player.data.prefs.SettingsStore
import com.mio.player.ui.components.isLandscape
import com.mio.player.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
/** Equalizer & Sound. Every change is saved to SettingsStore (DataStore)
 *  immediately, and MainActivity restores the saved values at startup —
 *  before this screen only kept the values in memory, so reopening it
 *  (or restarting the app) reset everything to Flat.
 *  v1.1.13 — reorganized into sections (Preset / Dải tần / Preamp /
 *  Hiệu ứng / Chỉnh âm tai nghe / Nhập-Xuất preset); 22
 *  built-in presets from EqPresets, a wired preamp slider, AutoEq
 *  headphone corrections, and JSON preset import/export. The playback
 *  controls (Gapless / Crossfade / ReplayGain / speed) moved to
 *  ProfileScreen ("Phát lại") in v1.1.13 — this file no longer
 *  loads, saves or applies them. */
@Composable fun EqualizerScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val container = (ctx.applicationContext as com.mio.player.MioApp).container
    val pm = container.playerManager
    val settings = container.settings
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var preset by remember { mutableStateOf("Flat") }
    var bands by remember { mutableStateOf(FloatArray(10) { 0f }) }
    var preamp by remember { mutableStateOf(0f) }
    var bass by remember { mutableStateOf(0f) }
    var virt by remember { mutableStateOf(0f) }
    var loud by remember { mutableStateOf(0f) }
    var balance by remember { mutableStateOf(0f) }
    var headphoneModel by remember { mutableStateOf("") }
    var customPresets by remember { mutableStateOf<Map<String, FloatArray>>(emptyMap()) }
    var statusMsg by remember { mutableStateOf("") }
    // v1.1.27 — the EQ screen is LANDSCAPE-only: the user asked for
    // the bands to be horizontal for easy viewing/adjusting, and the
    // screen kept opening in portrait (the phone is held upright when
    // they tap into it). Forcing landscape here rotates the display
    // even with system auto-rotate off; leaving the screen restores
    // the previous behaviour (sensor / system setting).
    val activity = ctx as? Activity
    DisposableEffect(Unit) {
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        onDispose { activity?.requestedOrientation = previous ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    // v1.1.26 — the bands section lays out differently in landscape
    // (vertical faders side by side); read the orientation once here.
    val landscape = isLandscape()
    /** v1.1.13 — preampDb is set on pm.fx BEFORE applyBands, because
     *  applyBands writes (band + preamp) per band. */
    fun apply() { pm.fx.preampDb = preamp; pm.fx.applyBands(bands); pm.fx.setBassBoost((bass*1000).toInt()); pm.fx.setVirtualizer((virt*1000).toInt()); pm.fx.setLoudness((loud*1000).toInt()); pm.fx.balance = balance }
    fun save() {
        if (!loaded) return
        scope.launch {
            settings.set(SettingsStore.KEY_EQ_PRESET, preset)
            settings.set(SettingsStore.KEY_EQ_BANDS, bands.joinToString(","))
            settings.set(SettingsStore.KEY_PREAMP, preamp)
            settings.set(SettingsStore.KEY_HEADPHONE_MODEL, headphoneModel)
            settings.set(SettingsStore.KEY_CUSTOM_PRESETS, EqPresets.buildCustomPresetsJson(customPresets))
            settings.set(SettingsStore.KEY_BASS, (bass*1000).toInt())
            settings.set(SettingsStore.KEY_VIRTUALIZER, (virt*1000).toInt())
            settings.set(SettingsStore.KEY_LOUDNESS, (loud*1000).toInt())
            settings.set(SettingsStore.KEY_BALANCE, balance)
        }
    }
    fun applyAndSave() { apply(); save() }
    /** v1.1.13 — applying a headphone model sets its 10 bands + its
     *  preamp, and records the model so the screen can show it as active. */
    fun applyHeadphone(model: String) {
        val profile = EqPresets.HEADPHONE_PROFILES[model] ?: return
        bands = profile.bands.copyOf()
        preamp = profile.preampDb
        headphoneModel = model
        preset = "Tai nghe: $model"
        applyAndSave()
    }
    fun clearHeadphone() {
        bands = FloatArray(10) { 0f }
        preamp = 0f
        headphoneModel = ""
        preset = "Flat"
        applyAndSave()
    }
    fun deleteCustom(name: String) {
        customPresets = customPresets - name
        if (preset == name) preset = ""
        save()
    }
    /** v1.1.13 — export: write the current preset (name + bands + preamp)
     *  as JSON under cacheDir/eq-presets/ and share it via FileProvider. */
    fun exportPreset() {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                try {
                    val dir = File(ctx.cacheDir, "eq-presets").apply { mkdirs() }
                    File(dir, "${EqPresets.safeFileName(preset)}-eq.json").apply {
                        writeText(EqPresets.buildPresetJson(preset, bands, preamp))
                    }
                } catch (_: Exception) { null }
            } ?: run { statusMsg = "Không xuất được preset"; return@launch }
            try {
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Mio Player preset: $preset")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(Intent.createChooser(intent, "Xuất preset"))
                statusMsg = "Đã xuất preset $preset"
            } catch (_: Exception) { statusMsg = "Không xuất được preset" }
        }
    }
    /** v1.1.13 — import: pick a .json file, validate it fully (see
     *  EqPresets.parsePresetJson), then save it as a custom preset and
     *  apply it. A bad file only shows an error — nothing is applied. */
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                try { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() } catch (_: Exception) { null }
            } ?: run { statusMsg = "Không đọc được file"; return@launch }
            val parsed = EqPresets.parsePresetJson(text)
            if (parsed == null) { statusMsg = "File không đúng định dạng"; return@launch }
            var name = parsed.name
            if (EqPresets.BUILT_IN.containsKey(name) || customPresets.containsKey(name)) name = "$name (nhập)"
            customPresets = customPresets + (name to parsed.bands.copyOf())
            preset = name
            bands = parsed.bands.copyOf()
            preamp = parsed.preampDb
            headphoneModel = ""
            applyAndSave()
            statusMsg = "Đã nhập preset $name"
        }
    }
    LaunchedEffect(Unit) {
        preset = settings.flow(SettingsStore.KEY_EQ_PRESET, "Flat").first()
        val csv = settings.flow(SettingsStore.KEY_EQ_BANDS, "").first()
        val parsed = FloatArray(10) { 0f }
        csv.split(",").mapNotNull { it.toFloatOrNull() }.forEachIndexed { i, v -> if (i < 10) parsed[i] = v }
        bands = parsed
        preamp = settings.flow(SettingsStore.KEY_PREAMP, 0f).first()
        headphoneModel = settings.flow(SettingsStore.KEY_HEADPHONE_MODEL, "").first()
        customPresets = EqPresets.parseCustomPresets(settings.flow(SettingsStore.KEY_CUSTOM_PRESETS, "").first())
        bass = settings.flow(SettingsStore.KEY_BASS, 0).first() / 1000f
        virt = settings.flow(SettingsStore.KEY_VIRTUALIZER, 0).first() / 1000f
        loud = settings.flow(SettingsStore.KEY_LOUDNESS, 0).first() / 1000f
        balance = settings.flow(SettingsStore.KEY_BALANCE, 0f).first()
        loaded = true
        apply()
    }
    if (!loaded) {
        Box(Modifier.fillMaxSize().background(MioBg), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MioCyan) }
        return
    }
    LazyColumn(Modifier.fillMaxSize().background(MioBg).padding(20.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, null, tint = MioText) }; Text("Equalizer & Sound", color = MioText, fontSize = 22.sp, fontWeight = FontWeight.Bold) } }
        // --- Preset ---
        item {
            SectionTitle("Preset")
            Text("Đang dùng: $preset", color = MioTextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pm.fx.presets.keys.toList()) { p ->
                    FilterChip(selected = preset == p, onClick = { preset = p; bands = (pm.fx.presets[p] ?: bands).copyOf(); headphoneModel = ""; applyAndSave() }, label = { Text(p) })
                }
            }
            if (customPresets.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Preset của bạn", color = MioTextSecondary, fontSize = 13.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(customPresets.keys.toList()) { name ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(selected = preset == name, onClick = { preset = name; bands = (customPresets[name] ?: bands).copyOf(); headphoneModel = ""; applyAndSave() }, label = { Text(name) })
                            IconButton(onClick = { deleteCustom(name) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Xóa preset $name", tint = MioTextSecondary, modifier = Modifier.size(16.dp)) }
                        }
                    }
                }
            }
        }
        // --- Dải tần ---
        // v1.1.26 — in landscape the 10 bands become vertical faders
        // side by side (the classic graphic-EQ layout: up = boost),
        // so the whole EQ fits the wide screen instead of a long
        // vertical scroll of horizontal sliders. Portrait keeps the
        // stacked horizontal sliders.
        if (landscape) {
            item {
                SectionTitle("Dải tần")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AudioEffectsManager.BAND_FREQS_HZ.forEachIndexed { i, freq ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${if (bands[i] > 0) "+" else ""}${bands[i].toInt()} dB", color = MioText, fontSize = 11.sp)
                            VerticalBandSlider(value = bands[i], onValueChange = { v -> bands = bands.copyOf().also { it[i] = v }; headphoneModel = ""; applyAndSave() })
                            Text(if (freq >= 1000) "${freq / 1000}k" else "$freq", color = MioTextSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
        } else {
            item { SectionTitle("Dải tần") }
            items(AudioEffectsManager.BAND_FREQS_HZ.size) { i ->
                Column { Text("${AudioEffectsManager.BAND_FREQS_HZ[i]} Hz — ${bands[i].toInt()} dB", color = MioText); Slider(value = bands[i], onValueChange = { v -> bands = bands.copyOf().also { it[i] = v }; headphoneModel = ""; applyAndSave() }, valueRange = -15f..15f) }
            }
        }
        // --- Preamp ---
        item {
            SectionTitle("Preamp")
            Text("Preamp: ${String.format(Locale.US, "%.1f", preamp)} dB", color = MioText)
            Text("Tăng/giảm đều toàn bộ dải tần. Giảm preamp nếu preset mạnh gây rè.", color = MioTextSecondary, fontSize = 12.sp)
            Slider(value = preamp, onValueChange = { preamp = it; headphoneModel = ""; applyAndSave() }, valueRange = -12f..6f)
        }
        // --- Hiệu ứng ---
        item {
            SectionTitle("Hiệu ứng")
            Text("Bass Boost", color = MioText); Slider(value = bass, onValueChange = { bass = it; applyAndSave() })
            Text("Virtualizer / 3D", color = MioText); Slider(value = virt, onValueChange = { virt = it; applyAndSave() })
            Text("Loudness", color = MioText); Slider(value = loud, onValueChange = { loud = it; applyAndSave() })
            Text("Balance (L — R)", color = MioText); Slider(value = balance, onValueChange = { balance = it; applyAndSave() }, valueRange = -1f..1f)
        }
        // --- Chỉnh âm tai nghe (separate from genre presets) ---
        item {
            SectionTitle("Chỉnh âm tai nghe — Harman target")
            Text("Hiệu chỉnh theo tai nghe bạn đang dùng, riêng với preset theo thể loại nhạc ở trên. Giá trị là hiệu chỉnh 10 dải tần AutoEq từ đo đạc của oratory1990, hướng tới chuẩn Harman over-ear 2018. EQ 10 dải cố định không tái tạo được đầy đủ preset parametric của oratory1990, nên đây là bản xấp xỉ tốt nhất với 10 dải tần.", color = MioTextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Text(if (headphoneModel.isBlank()) "Chưa bật chỉnh âm tai nghe" else "Đang dùng: $headphoneModel", color = MioText, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(EqPresets.HEADPHONE_PROFILES.keys.toList()) { model ->
                    FilterChip(selected = headphoneModel == model, onClick = { applyHeadphone(model) }, label = { Text(model) })
                }
            }
            if (headphoneModel.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { clearHeadphone() }) { Text("Tắt") }
            }
        }
        // --- Nhập / Xuất preset ---
        item {
            SectionTitle("Nhập / Xuất preset")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { importLauncher.launch("application/json") }) { Text("Nhập preset") }
                OutlinedButton(onClick = { exportPreset() }) { Text("Xuất preset") }
            }
            if (statusMsg.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(statusMsg, color = MioTextSecondary, fontSize = 13.sp)
            }
        }
        // v1.1.13 — the "Phát lại" controls (Gapless / Crossfade /
        // ReplayGain / speed) moved to ProfileScreen; only the general
        // effects footnote stays here.
        item {
            Spacer(Modifier.height(16.dp))
            Text("Effects use Android AudioEffect (Equalizer / BassBoost / Virtualizer / LoudnessEnhancer) on ExoPlayer's audio session. If your phone does not support an effect, it is skipped silently — playback never breaks.", color = MioTextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable private fun SectionTitle(text: String) {
    Text(text, color = MioText, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
}

/** A vertical fader for one EQ band (v1.1.26, landscape only): a
 *  Material3 Slider rotated -90°, so min (-15 dB) is at the bottom
 *  and max (+15 dB) at the top — drag up to boost, like a hardware
 *  graphic EQ. The Box is 200 dp tall; the Slider inside is laid
 *  out 200 dp wide (its rotated height) and rotated around its
 *  centre, so its drawn content fills the Box exactly. Touch is
 *  transformed by the same rotation, so dragging vertically on the
 *  fader changes the value.
 *  v1.1.27 — requiredWidth, not width: the parent Column is only
 *  ~1/10 of the screen wide (~75 dp), and plain width() is clamped
 *  by that constraint, so the fader rendered as a ~75 dp stub with
 *  the thumb bunched at one end. requiredWidth ignores the parent
 *  max constraint, giving the full 200 dp of travel. */
@Composable private fun VerticalBandSlider(value: Float, onValueChange: (Float) -> Unit) {
    Box(Modifier.height(200.dp), contentAlignment = Alignment.Center) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = -15f..15f,
            modifier = Modifier.requiredWidth(200.dp).graphicsLayer { rotationZ = -90f }
        )
    }
}
