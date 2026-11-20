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
    val presets: Map<String, FloatArray> = mapOf(
        "Flat" to floatArrayOf(0f,0f,0f,0f,0f,0f,0f,0f,0f,0f),
        "Bass" to floatArrayOf(6f,5f,4f,2f,0f,0f,0f,0f,0f,0f),
        "Treble" to floatArrayOf(-2f,-1f,0f,0f,0f,1f,2f,4f,5f,6f),
        "Vocal" to floatArrayOf(-2f,-1f,0f,1f,3f,4f,3f,1f,0f,-1f),
        "Rock" to floatArrayOf(4f,3f,2f,0f,-1f,0f,2f,3f,4f,4f),
        "Jazz" to floatArrayOf(3f,2f,1f,0f,0f,1f,2f,3f,3f,2f),
        "Classical" to floatArrayOf(0f,0f,0f,0f,0f,0f,0f,2f,3f,4f),
        "Lo-Fi" to floatArrayOf(3f,2f,1f,0f,-1f,-2f,-1f,0f,-2f,-4f)
    )
    fun attach(audioSessionId: Int) {
        release()
        if (audioSessionId == 0) return
        try { eq = Equalizer(0, audioSessionId).apply { enabled = true } } catch (e: Exception) { Log.w("MioFx", "EQ unavailable", e) }
        try { bass = BassBoost(0, audioSessionId).apply { enabled = true } } catch (e: Exception) { Log.w("MioFx", "BassBoost unavailable", e) }
        try { virt = Virtualizer(0, audioSessionId).apply { enabled = true } } catch (e: Exception) { Log.w("MioFx", "Virtualizer unavailable", e) }
        try { loud = LoudnessEnhancer(audioSessionId).apply { enabled = true } } catch (e: Exception) { Log.w("MioFx", "Loudness unavailable", e) }
    }
    fun applyBands(db: FloatArray) {
        val e = eq ?: return
        try {
            val n = minOf(db.size, e.numberOfBands.toInt())
            for (i in 0 until n) e.setBandLevel(i.toShort(), (db[i].coerceIn(-15f, 15f) * 100).toInt().toShort())
        } catch (ex: Exception) { Log.w("MioFx", "applyBands failed", ex) }
    }
    fun applyPreset(name: String) { presets[name]?.let { applyBands(it) } }
    fun setBassBoost(strength: Int) { try { bass?.setStrength(strength.coerceIn(0,1000).toShort()) } catch (e: Exception) {} }
    fun setVirtualizer(strength: Int) { try { virt?.setStrength(strength.coerceIn(0,1000).toShort()) } catch (e: Exception) {} }
    /** LoudnessEnhancer target is in milliBel, 0 = off. */
    fun setLoudness(targetMb: Int) { try { loud?.setTargetGain(targetMb.coerceIn(0, 2000)) } catch (e: Exception) {} }
    fun release() { listOf(eq, bass, virt, loud).forEach { try { it?.release() } catch (_: Exception) {} }; eq=null; bass=null; virt=null; loud=null }
}
