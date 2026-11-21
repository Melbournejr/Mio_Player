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
    var preampDb: Float = 0f; var balance: Float = 0f // -1 left .. +1 right
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
        if (audioSessionId == 0) return
        try { eq = Equalizer(0, audioSessionId).apply { enabled = true } } catch (e: Exception) { Log.w("MioFx", "EQ unavailable", e) }
        try { bass = BassBoost(0, audioSessionId).apply { enabled = true } } catch (e: Exception) { Log.w("MioFx", "BassBoost unavailable", e) }
        try { virt = Virtualizer(0, audioSessionId).apply { enabled = true } } catch (e: Exception) { Log.w("MioFx", "Virtualizer unavailable", e) }
        try { loud = LoudnessEnhancer(audioSessionId).apply { enabled = true } } catch (e: Exception) { Log.w("MioFx", "Loudness unavailable", e) }
        // Re-apply the remembered values on the fresh effects.
        applyBands(currentBands); setBassBoost(bassStrength); setVirtualizer(virtStrength); setLoudness(loudnessMb)
    }
    /** v1.1.13 — preampDb is a uniform level offset: every band is written
     *  as (band + preampDb), clamped to ±15 dB. currentBands keeps the raw
     *  band values (without preamp) so a re-attach re-applies correctly. */
    fun applyBands(db: FloatArray) {
        currentBands = db.copyOf()
        val e = eq ?: return
        try {
            val n = minOf(db.size, e.numberOfBands.toInt())
            for (i in 0 until n) e.setBandLevel(i.toShort(), ((db[i] + preampDb).coerceIn(-15f, 15f) * 100).toInt().toShort())
        } catch (ex: Exception) { Log.w("MioFx", "applyBands failed", ex) }
    }
    fun applyPreset(name: String) { presets[name]?.let { applyBands(it) } }
    fun setBassBoost(strength: Int) { bassStrength = strength.coerceIn(0, 1000); try { bass?.setStrength(bassStrength.toShort()) } catch (e: Exception) {} }
    fun setVirtualizer(strength: Int) { virtStrength = strength.coerceIn(0, 1000); try { virt?.setStrength(virtStrength.toShort()) } catch (e: Exception) {} }
    /** LoudnessEnhancer target is in milliBel, 0 = off. */
    fun setLoudness(targetMb: Int) { loudnessMb = targetMb.coerceIn(0, 2000); try { loud?.setTargetGain(loudnessMb) } catch (e: Exception) {} }
    fun release() { listOf(eq, bass, virt, loud).forEach { try { it?.release() } catch (_: Exception) {} }; eq=null; bass=null; virt=null; loud=null }
}
