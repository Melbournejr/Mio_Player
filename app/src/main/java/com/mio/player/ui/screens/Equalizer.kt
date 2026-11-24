package com.mio.player.ui.screens
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import com.mio.player.data.audio.AudioEffectsManager
import com.mio.player.data.audio.EqPresets
import com.mio.player.data.audio.ParametricBand
import com.mio.player.data.audio.ParametricBandType
import com.mio.player.data.audio.ParametricEqDefaults
import com.mio.player.data.prefs.SettingsStore
import com.mio.player.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
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
    /** v1.1.32 — Parametric EQ mode + bands. Graphic (default) is the
     *  old 10 fixed bands; Parametric keeps its own freq/type/Q per
     *  band, with gains kept in step with [bands] on every edit. */
    var eqMode by remember { mutableStateOf("graphic") }
    var paramBands by remember { mutableStateOf(ParametricEqDefaults.bands()) }
    var selectedBand by remember { mutableStateOf(0) }
    fun formatFreq(hz: Float): String = if (hz >= 1000f) String.format(Locale.US, "%.1f kHz", hz / 1000f) else "${hz.toInt()} Hz"
    /** Log-scale frequency slider: 0..1 maps to 20 Hz..20 kHz. */
    fun freqToSlider(hz: Float): Float = ((log10(hz.coerceIn(20f, 20000f).toDouble()) - log10(20.0)) / (log10(20000.0) - log10(20.0))).toFloat()
    fun sliderToFreq(v: Float): Float = 10.0.pow(log10(20.0) + v.coerceIn(0f, 1f) * (log10(20000.0) - log10(20.0))).toFloat()
    /** v1.1.13 — preampDb is set on pm.fx BEFORE applyBands, because
     *  applyBands writes (band + preamp) per band. */
    fun apply() {
        pm.fx.preampDb = preamp
        pm.fx.eqMode = eqMode
        if (eqMode == "parametric") pm.fx.applyParametricBands(paramBands) else pm.fx.applyBands(bands)
        pm.fx.setBassBoost((bass*1000).toInt()); pm.fx.setVirtualizer((virt*1000).toInt()); pm.fx.setLoudness((loud*1000).toInt()); pm.fx.balance = balance
    }
    fun save() {
        if (!loaded) return
        scope.launch {
            settings.set(SettingsStore.KEY_EQ_PRESET, preset)
            // v1.1.32 — in Parametric mode the gains live in paramBands;
            // store the effective gains so the graphic representation
            // never goes stale.
            val effectiveBands = if (eqMode == "parametric") ParametricEqDefaults.gainsFromBands(paramBands) else bands
            settings.set(SettingsStore.KEY_EQ_BANDS, effectiveBands.joinToString(","))
            settings.set(SettingsStore.KEY_EQ_MODE, eqMode)
            settings.set(SettingsStore.KEY_EQ_PARAMETRIC, ParametricEqDefaults.encode(paramBands))
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
        paramBands = ParametricEqDefaults.bandsFromGains(profile.bands)
        preamp = profile.preampDb
        headphoneModel = model
        preset = "Tai nghe: $model"
        applyAndSave()
    }
    fun clearHeadphone() {
        bands = FloatArray(10) { 0f }
        paramBands = ParametricEqDefaults.bands()
        preamp = 0f
        headphoneModel = ""
        preset = "Flat"
        applyAndSave()
    }
    /** v1.1.32 — switch EQ mode, preserving gains: parametric takes
     *  the graphic gains (keeping any custom freq/type/Q), graphic
     *  takes the parametric gains. */
    fun switchMode(mode: String) {
        if (mode == "parametric") {
            paramBands = paramBands.mapIndexed { i, b -> b.copy(gainDb = bands.getOrElse(i) { 0f }) }
        } else {
            bands = ParametricEqDefaults.gainsFromBands(paramBands)
        }
        eqMode = mode
        applyAndSave()
    }
    fun updateParamBand(newBand: ParametricBand) {
        paramBands = paramBands.mapIndexed { i, old -> if (i == selectedBand) newBand else old }
        headphoneModel = ""
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
                    // v1.1.32 — in Parametric mode the gains live in
                    // paramBands (bands is only kept in step on a mode
                    // switch), so export the effective gains.
                    val exportBands = if (eqMode == "parametric") ParametricEqDefaults.gainsFromBands(paramBands) else bands
                    File(dir, "${EqPresets.safeFileName(preset)}-eq.json").apply {
                        writeText(EqPresets.buildPresetJson(preset, exportBands, preamp))
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
            paramBands = ParametricEqDefaults.bandsFromGains(parsed.bands)
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
        eqMode = settings.flow(SettingsStore.KEY_EQ_MODE, "graphic").first().let { if (it == "parametric") "parametric" else "graphic" }
        paramBands = ParametricEqDefaults.decode(settings.flow(SettingsStore.KEY_EQ_PARAMETRIC, "").first())
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
                    // v1.1.32 — a preset in Parametric mode resets each
                    // band to its standard frequency, Peak, Q 1.41
                    // (bandsFromGains), so the preset sounds as defined.
                    FilterChip(selected = preset == p, onClick = { preset = p; bands = (pm.fx.presets[p] ?: bands).copyOf(); paramBands = ParametricEqDefaults.bandsFromGains(bands); headphoneModel = ""; applyAndSave() }, label = { Text(p) })
                }
            }
            if (customPresets.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Preset của bạn", color = MioTextSecondary, fontSize = 13.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(customPresets.keys.toList()) { name ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(selected = preset == name, onClick = { preset = name; bands = (customPresets[name] ?: bands).copyOf(); paramBands = ParametricEqDefaults.bandsFromGains(bands); headphoneModel = ""; applyAndSave() }, label = { Text(name) })
                            IconButton(onClick = { deleteCustom(name) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Xóa preset $name", tint = MioTextSecondary, modifier = Modifier.size(16.dp)) }
                        }
                    }
                }
            }
        }
        // --- Chế độ EQ (v1.1.32) ---
        item {
            SectionTitle("Chế độ EQ")
            Text("Graphic: 10 dải cố định như trước, qua Android Equalizer. Parametric: chỉnh tần số, loại dải và Q cho từng band, qua bộ lọc biquad trong ExoPlayer — chỉ 1 chế độ chạy tại 1 thời điểm.", color = MioTextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = eqMode == "graphic", onClick = { if (eqMode != "graphic") switchMode("graphic") })
                Text("Graphic", color = MioText, modifier = Modifier.padding(end = 24.dp))
                RadioButton(selected = eqMode == "parametric", onClick = { if (eqMode != "parametric") switchMode("parametric") })
                Text("Parametric", color = MioText)
            }
        }
        // --- Dải tần ---
        // v1.1.29 — the bands are ALWAYS one row of vertical faders
        // (1 hàng × 10 cột), in portrait AND landscape. The user
        // corrected v1.1.27/28: they never asked the screen to rotate
        // — they asked for the bands themselves to be a horizontal
        // row of faders, so the whole EQ is visible and adjustable
        // while the phone stays upright. (v1.1.26 only did this in
        // landscape and v1.1.27/28 forced the screen to rotate, both
        // wrong.) The old stacked horizontal sliders are gone.
        item {
            if (eqMode == "graphic") {
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
            } else {
                // v1.1.32 — Parametric editor: pick one band, then edit
                // its type / frequency / gain / Q. Standard Material 3
                // controls (FilterChip / RadioButton / Slider) throughout.
                SectionTitle("Parametric — Dải tần")
                val sel = paramBands.getOrElse(selectedBand) { ParametricBand() }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(10) { i ->
                        val b = paramBands[i]
                        FilterChip(
                            selected = selectedBand == i,
                            onClick = { selectedBand = i },
                            label = { Text("${i + 1}: ${formatFreq(b.freqHz)}") }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Band ${selectedBand + 1} — ${sel.type.name} · ${formatFreq(sel.freqHz)} · ${if (sel.gainDb > 0) "+" else ""}${String.format(Locale.US, "%.1f", sel.gainDb)} dB · Q ${String.format(Locale.US, "%.2f", sel.q)}", color = MioText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Loại dải", color = MioText)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = sel.type == ParametricBandType.PEAK, onClick = { updateParamBand(sel.copy(type = ParametricBandType.PEAK)) })
                    Text("Peak", color = MioText, modifier = Modifier.padding(end = 16.dp))
                    RadioButton(selected = sel.type == ParametricBandType.LOW_SHELF, onClick = { updateParamBand(sel.copy(type = ParametricBandType.LOW_SHELF)) })
                    Text("Low Shelf", color = MioText, modifier = Modifier.padding(end = 16.dp))
                    RadioButton(selected = sel.type == ParametricBandType.HIGH_SHELF, onClick = { updateParamBand(sel.copy(type = ParametricBandType.HIGH_SHELF)) })
                    Text("High Shelf", color = MioText)
                }
                Text("Tần số: ${formatFreq(sel.freqHz)}", color = MioText)
                Slider(value = freqToSlider(sel.freqHz), onValueChange = { v -> updateParamBand(sel.copy(freqHz = sliderToFreq(v))) })
                Text("Gain: ${String.format(Locale.US, "%.1f", sel.gainDb)} dB", color = MioText)
                Slider(value = sel.gainDb, onValueChange = { v -> updateParamBand(sel.copy(gainDb = v)) }, valueRange = -15f..15f)
                Text(if (sel.type == ParametricBandType.PEAK) "Q: ${String.format(Locale.US, "%.2f", sel.q)}" else "Q: chỉ áp dụng cho Peak — Shelf dùng độ dốc cố định", color = MioText)
                Slider(value = sel.q, onValueChange = { v -> updateParamBand(sel.copy(q = v)) }, valueRange = 0.5f..4.0f, enabled = sel.type == ParametricBandType.PEAK)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    paramBands = ParametricEqDefaults.bandsFromGains(ParametricEqDefaults.gainsFromBands(paramBands))
                    applyAndSave()
                }) { Text("Đặt lại tần số / loại / Q về chuẩn") }
                Spacer(Modifier.height(8.dp))
                Text("Gain 0 dB = band đó không lọc gì. Đổi tần số/Q xong nghe thử ngay — không cần khởi động lại app.", color = MioTextSecondary, fontSize = 12.sp)
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
            Text("Graphic mode uses Android AudioEffect (Equalizer / BassBoost / Virtualizer / LoudnessEnhancer) on ExoPlayer's audio session. Parametric mode (v1.1.32) flattens that Equalizer and runs a 10-band biquad filter (RBJ coefficients) in ExoPlayer's audio sink instead — only one EQ runs at a time. If your phone does not support an effect, it is skipped silently — playback never breaks.", color = MioTextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable private fun SectionTitle(text: String) {
    Text(text, color = MioText, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
}

/** A thin, refined vertical fader for one EQ band (v1.1.30).
 *  The Material3 Expressive Slider (v1.1.26–29) rendered a thick
 *  track + line thumb — the user called it "to quá, thô, xấu"
 *  (too big, crude, ugly). This is a custom Canvas fader: a 4dp
 *  track (dark unfilled, teal filled) and a small 16dp round thumb
 *  (white with a teal centre). Drag up to boost, drag down to cut;
 *  tap the track to jump to that value. Min (-15 dB) is at the
 *  bottom, max (+15 dB) at the top. The drag uses
 *  detectVerticalDragGestures (its onDragStart snapshots the value
 *  so incremental deltas never lose a step to a stale read), the
 *  tap uses detectTapGestures, and the drag consumes its moves so
 *  the EQ screen does not scroll while a fader is being dragged.
 *  (v1.1.30 first tried a manual awaitPointerEventScope loop, but
 *  that symbol does not resolve in this project's Compose version
 *  — CI failed on it — so this uses the standard Foundation
 *  gesture detectors instead.) */
@Composable private fun VerticalBandSlider(value: Float, onValueChange: (Float) -> Unit) {
    val currentValue by rememberUpdatedState(value)
    Box(
        Modifier
            .height(200.dp)
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val frac = 1f - (offset.y / size.height)
                    onValueChange((-15f + frac * 30f).coerceIn(-15f, 15f))
                }
            }
            .pointerInput(Unit) {
                var dragValue = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragValue = currentValue },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        dragValue = (dragValue - dragAmount / size.height * 30f).coerceIn(-15f, 15f)
                        onValueChange(dragValue)
                    }
                )
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val trackWidth = 4.dp.toPx()
            val thumbRadius = 8.dp.toPx()
            val centerX = size.width / 2f
            val top = thumbRadius
            val bottom = size.height - thumbRadius
            val frac = (value + 15f) / 30f
            val valueY = bottom - frac * (bottom - top)
            // Unfilled track (full height, dark).
            drawLine(
                color = Color(0xFF3A3A44),
                start = Offset(centerX, top),
                end = Offset(centerX, bottom),
                strokeWidth = trackWidth,
                cap = StrokeCap.Round
            )
            // Filled track (teal, from the bottom up to the thumb).
            drawLine(
                color = Color(0xFF14B8A6),
                start = Offset(centerX, bottom),
                end = Offset(centerX, valueY),
                strokeWidth = trackWidth,
                cap = StrokeCap.Round
            )
            // Thumb: white disc with a teal centre.
            drawCircle(color = Color.White, radius = thumbRadius, center = Offset(centerX, valueY))
            drawCircle(color = Color(0xFF14B8A6), radius = thumbRadius - 3.dp.toPx(), center = Offset(centerX, valueY))
        }
    }
}
