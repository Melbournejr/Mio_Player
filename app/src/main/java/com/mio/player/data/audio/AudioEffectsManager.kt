package com.mio.player.data.audio
import android.media.audiofx.*
import android.util.Log
/** 10-band EQ + Bass Boost + Virtualizer + Loudness Enhancer + Balance, attached to
 *  ExoPlayer's audio session. Every effect is created defensively: many phones
 *  (notably some Samsung / Pixel builds) reject Virtualizer or LoudnessEnhancer,
 *  so a failure disables that effect only and never crashes playback. */
class AudioEffectsManager {
    companion object { val BAND_FREQS_HZ = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000) }
    private var eq: Equalizer? = null; private var bass: BassBoost? = null
    private var virt: Virtualizer? = null; private var loud: LoudnessEnhancer? = null
    private var lastSessionId: Int = 0
    var preampDb: Float = 0f; var balance: Float = 0f // -1 left .. +1 right
    /** v1.1.32 — Parametric EQ mode. "graphic" (default) is the old
     *  Android Equalizer path, untouched. "parametric" flattens the
     *  Android Equalizer to 0 dB/band and runs the biquad processor in
     *  the sink chain instead. Never both at once. */
    var eqMode: String = "graphic"
    var parametricBands: List<ParametricBand> = ParametricEqDefaults.bands()
    private var parametricProcessor: ParametricEqAudioProcessor? = null
    fun bindParametricProcessor(p: ParametricEqAudioProcessor) { parametricProcessor = p; pushParametric() }
    private fun pushParametric() {
        parametricProcessor?.config = ParametricEqConfig(
            bands = parametricBands.map { it.copy() },
            preampDb = preampDb,
            enabled = eqMode == "parametric"
        )
    }
    /** Apply whichever EQ mode is active (plus nothing else — bass /
     *  virtualizer / loudness are applied by their own setters, as before). */
    fun applyCurrent() {
        val e = eq
        if (eqMode == "parametric") {
            // Flatten the Android EQ so it cannot double-apply on top
            // of the biquad processor; preamp lives in the processor.
            try {
                if (e != null) {
                    val n = e.numberOfBands.toInt()
                    for (i in 0 until n) e.setBandLevel(i.toShort(), 0)
                }
            } catch (ex: Exception) { Log.w("MioFx", "flatten graphic EQ failed", ex) }
            pushParametric()
        } else {
            pushParametric() // enabled=false -> clean passthrough
            try {
                if (e != null) {
                    val n = minOf(currentBands.size, e.numberOfBands.toInt())
                    // v1.1.48 — automatic headroom: subtract the largest
                    // positive band boost from every written level so a
                    // boosted preset keeps its shape without pushing
                    // peaks into clipping. The user's preamp still
                    // applies on top of this safety offset.
                    val headroomDb = currentBands.maxOrNull()?.coerceAtLeast(0f) ?: 0f
                    for (i in 0 until n) e.setBandLevel(i.toShort(), ((currentBands[i] + preampDb - headroomDb).coerceIn(-15f, 15f) * 100).toInt().toShort())
                }
            } catch (ex: Exception) { Log.w("MioFx", "applyBands failed", ex) }
        }
    }
    /** Startup restore: set both representations exactly as stored,
     *  then apply once. Unlike applyBands()/applyParametricBands()
     *  this does NOT sync one from the other — the stored parametric
     *  gains are authoritative in Parametric mode even if the stored
     *  graphic gains are older (the UI only rewrites graphic gains
     *  on a mode switch / preset, not on a parametric edit). */
    fun restoreState(mode: String, graphicBands: FloatArray, paramBands: List<ParametricBand>) {
        eqMode = if (mode == "parametric") "parametric" else "graphic"
        currentBands = graphicBands.copyOf()
        parametricBands = paramBands.map { it.copy() }
        applyCurrent()
    }
    /** Switch modes, preserving gains as far as possible: graphic ->
     *  parametric keeps the gains (and any customised freq/type/Q the
     *  user already set in parametric); parametric -> graphic takes
     *  the parametric gains as the graphic gains.
     *  (Named switchEqMode, not setEqMode: `var eqMode` already
     *  generates a JVM setEqMode(String) setter, and a fun with the
     *  same signature is a platform declaration clash — the v1.1.32
     *  first push failed CI on exactly that.) */
    fun switchEqMode(mode: String) {
        val m = if (mode == "parametric") "parametric" else "graphic"
        if (m == "parametric" && eqMode != "parametric") {
            parametricBands = parametricBands.mapIndexed { i, b -> b.copy(gainDb = currentBands.getOrElse(i) { 0f }) }
        } else if (m == "graphic" && eqMode == "parametric") {
            currentBands = ParametricEqDefaults.gainsFromBands(parametricBands)
        }
        eqMode = m
        applyCurrent()
    }
    /** Replace the parametric bands (from the UI / a preset / restore);
     *  graphic gains follow the parametric gains so the two views never
     *  disagree about loudness. */
    fun applyParametricBands(bands: List<ParametricBand>) {
        parametricBands = bands.map { it.copy() }
        currentBands = ParametricEqDefaults.gainsFromBands(parametricBands)
        applyCurrent()
    }
    /** Last applied values, remembered so they survive re-attach: the
     *  effects are recreated whenever ExoPlayer gets a new audio session,
     *  and a fresh effect starts flat. */
    var currentBands: FloatArray = FloatArray(10) { 0f }; private set
    var bassStrength: Int = 0; private set
    var virtStrength: Int = 0; private set
    var loudnessMb: Int = 0; private set
    /** v1.1.13 — the preset table now lives in EqPresets (single source of
     *  truth): the full 22-preset Evermusic table. The old invented 8
     *  presets (Flat / Bass / Treble / Vocal / Rock / Jazz / Classical /
     *  Lo-Fi) are gone; saved band values are unaffected (stored separately
     *  as CSV), only a saved old preset name may stop matching a chip. */
    val presets: Map<String, FloatArray> = EqPresets.BUILT_IN
    fun attach(audioSessionId: Int) {
        release()
        lastSessionId = audioSessionId
        // v1.1.39 — AAudio mode has no audio session (UNSET = -1), so
        // there is nothing to attach the Android effects to. Return
        // early instead of throwing (and logging) for every effect.
        if (audioSessionId <= 0) return
        try { eq = Equalizer(0, audioSessionId).apply { enabled = true } } catch (e: Exception) { Log.w("MioFx", "EQ unavailable", e) }
        try { bass = BassBoost(0, audioSessionId).apply { enabled = true } } catch (e: Exception) { Log.w("MioFx", "BassBoost unavailable", e) }
        try { virt = Virtualizer(0, audioSessionId).apply { enabled = true } } catch (e: Exception) { Log.w("MioFx", "Virtualizer unavailable", e) }
        try { loud = LoudnessEnhancer(audioSessionId).apply { enabled = true } } catch (e: Exception) { Log.w("MioFx", "Loudness unavailable", e) }
        // Re-apply the remembered values on the fresh effects.
        applyCurrent(); setBassBoost(bassStrength); setVirtualizer(virtStrength); setLoudness(loudnessMb)
    }
    /** v1.1.13 — preampDb is a uniform level offset: every band is written
     *  as (band + preampDb), clamped to ±15 dB. currentBands keeps the raw
     *  band values (without preamp) so a re-attach re-applies correctly. */
    fun applyBands(db: FloatArray) {
        currentBands = db.copyOf()
        // Keep the parametric gains in step (freq/type/Q untouched):
        // a preset or graphic edit must never silently lose the
        // parametric gains, and switching modes must preserve them.
        parametricBands = parametricBands.mapIndexed { i, b -> b.copy(gainDb = db.getOrElse(i) { 0f }.coerceIn(-15f, 15f)) }
        applyCurrent()
    }
    fun applyPreset(name: String) {
        presets[name]?.let { gains ->
            currentBands = gains.copyOf()
            // A graphic preset is defined at the standard frequencies,
            // so in parametric mode it resets freq to standard, type to
            // Peak and Q to 1.41 (per the v1.1.32 spec).
            parametricBands = ParametricEqDefaults.bandsFromGains(gains)
            applyCurrent()
        }
    }
    fun setBassBoost(strength: Int) { bassStrength = strength.coerceIn(0, 1000); try { bass?.setStrength(bassStrength.toShort()) } catch (e: Exception) {} }
    fun setVirtualizer(strength: Int) { virtStrength = strength.coerceIn(0, 1000); try { virt?.setStrength(virtStrength.toShort()) } catch (e: Exception) {} }
    /** LoudnessEnhancer target is in milliBel, 0 = off. */
    fun setLoudness(targetMb: Int) { loudnessMb = targetMb.coerceIn(0, 2000); try { loud?.setTargetGain(loudnessMb) } catch (e: Exception) {} }
    /** v1.1.37 — bit-perfect bypass. While a bit-perfect output is
     *  active the signal must reach the DAC untouched, so the biquad
     *  processor is forced to passthrough (enabled=false) and every
     *  Android effect is disabled. Restoring re-applies the stored
     *  values via applyCurrent() (parametric) and the remembered
     *  strengths (bass/virtualizer/loudness). The Graphic Equalizer
     *  effect itself is disabled here; applyCurrent() only writes band
     *  levels, it does not re-enable the effect, so restore must
     *  re-enable it explicitly. */
    fun setBitPerfectBypass(bypass: Boolean) {
        if (bypass) {
            pushParametricBypass()
            listOf(eq, bass, virt, loud).forEach { try { it?.enabled = false } catch (_: Exception) {} }
        } else {
            listOf(eq, bass, virt, loud).forEach { try { it?.enabled = true } catch (_: Exception) {} }
            applyCurrent(); setBassBoost(bassStrength); setVirtualizer(virtStrength); setLoudness(loudnessMb)
        }
    }
    private fun pushParametricBypass() {
        parametricProcessor?.config = ParametricEqConfig(
            bands = parametricBands.map { it.copy() },
            preampDb = preampDb,
            enabled = false
        )
    }
    /** v1.1.48 — one-line readback for the app log: proves whether
     *  the Graphic Equalizer actually attached to a live session and
     *  what band levels the effect reports back. */
    fun readbackSummary(): String {
        val e = eq ?: return "session=$lastSessionId eq=null mode=$eqMode"
        return runCatching {
            val n = e.numberOfBands.toInt()
            val levels = (0 until n).joinToString(",") { i -> (e.getBandLevel(i.toShort()) / 100).toString() }
            "session=$lastSessionId eqEnabled=${e.enabled} bands=$n levels=[$levels] mode=$eqMode preamp=$preampDb"
        }.getOrElse { "session=$lastSessionId eqReadbackFailed=${it::class.java.simpleName} mode=$eqMode" }
    }
    fun release() { listOf(eq, bass, virt, loud).forEach { try { it?.release() } catch (_: Exception) {} }; eq=null; bass=null; virt=null; loud=null }
}
