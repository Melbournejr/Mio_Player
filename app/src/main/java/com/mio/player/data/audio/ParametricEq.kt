package com.mio.player.data.audio

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Parametric EQ (v1.1.32, Rockbox-inspired).
 *
 * Why this exists: Android's [android.media.audiofx.Equalizer] only does
 * a fixed 10-band graphic EQ (31/62/125/250/500/1k/2k/4k/8k/16k Hz, gain
 * only). It cannot move a band's centre frequency or change its Q, so it
 * cannot reproduce a real parametric preset (e.g. oratory1990 / Rockbox).
 * This file implements the EQ as a biquad [AudioProcessor] in ExoPlayer's
 * sink chain instead, using the RBJ Audio EQ Cookbook coefficients.
 *
 * Placement (PlayerManager): [decimator, parametricEq, fftTap]. The
 * decimator runs first so DSD (705.6 kHz) is already at 176.4/88.2 kHz
 * when it reaches us; the FFT tap runs after us so the visualizer sees
 * the EQ'd signal, as in Rockbox.
 *
 * Modes (AudioEffectsManager owns the switch):
 * - Graphic mode: this processor is a clean passthrough (queueInput
 *   returns the input buffer untouched, no copy, no maths) and the
 *   Android Equalizer does the EQ exactly as before. Never both at once.
 * - Parametric mode: the Android Equalizer is written flat (0 dB/band,
 *   no preamp) and this processor is the EQ. Preamp is applied here,
 *   after the filters, then hard-clamped to [-1, 1] to avoid clipping.
 *
 * Threading: the UI thread swaps the whole config by writing one
 * @Volatile reference ([config]); the audio thread reads that reference
 * once per queueInput call and recomputes coefficients only when the
 * reference or the sample rate changed. Filter state (x1/x2/y1/y2 per
 * band per channel) lives only on the audio thread, in [state].
 */
enum class ParametricBandType { PEAK, LOW_SHELF, HIGH_SHELF }

data class ParametricBand(
    val type: ParametricBandType = ParametricBandType.PEAK,
    /** Centre / corner frequency in Hz, 20..20000. */
    val freqHz: Float = 1000f,
    /** Gain in dB, -15..+15. */
    val gainDb: Float = 0f,
    /** Q, 0.5..4.0. Only used for PEAK; shelves use a fixed slope (S=1). */
    val q: Float = 1.41f
)

/** Immutable snapshot the audio thread reads. Swapped atomically. */
data class ParametricEqConfig(
    val bands: List<ParametricBand> = ParametricEqDefaults.bands(),
    val preampDb: Float = 0f,
    /** True only in Parametric mode. False = passthrough, whatever the gains. */
    val enabled: Boolean = false
)

object ParametricEqDefaults {
    val STANDARD_FREQS_HZ = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    const val DEFAULT_Q = 1.41f

    fun bands(): List<ParametricBand> =
        STANDARD_FREQS_HZ.map { ParametricBand(ParametricBandType.PEAK, it, 0f, DEFAULT_Q) }

    /** Preset / headphone gains at the standard frequencies (Peak, Q 1.41). */
    fun bandsFromGains(gains: FloatArray): List<ParametricBand> =
        STANDARD_FREQS_HZ.mapIndexed { i, f ->
            ParametricBand(ParametricBandType.PEAK, f, gains.getOrElse(i) { 0f }.coerceIn(-15f, 15f), DEFAULT_Q)
        }

    fun gainsFromBands(bands: List<ParametricBand>): FloatArray =
        FloatArray(10) { i -> bands.getOrNull(i)?.gainDb?.coerceIn(-15f, 15f) ?: 0f }

    /** "PEAK,31,0.0,1.41;PEAK,62,0.0,1.41;..." — tolerant decode, never throws. */
    fun encode(bands: List<ParametricBand>): String =
        bands.joinToString(";") { b -> "${b.type.name},${b.freqHz},${b.gainDb},${b.q}" }

    fun decode(text: String): List<ParametricBand> {
        return try {
            if (text.isBlank()) return bands()
            val parts = text.split(";")
            if (parts.size != 10) return bands()
            val out = parts.map { part ->
                val f = part.split(",")
                if (f.size != 4) return bands()
                val type = try { ParametricBandType.valueOf(f[0].trim()) } catch (_: Exception) { ParametricBandType.PEAK }
                ParametricBand(
                    type = type,
                    freqHz = (f[1].toFloatOrNull() ?: 1000f).coerceIn(20f, 20000f),
                    gainDb = (f[2].toFloatOrNull() ?: 0f).coerceIn(-15f, 15f),
                    q = (f[3].toFloatOrNull() ?: DEFAULT_Q).coerceIn(0.5f, 4.0f)
                )
            }
            out
        } catch (_: Exception) {
            bands()
        }
    }

    /** RBJ Cookbook coefficients for one band, normalised by a0.
     *  Returns floatArrayOf(b0, b1, b2, a1, a2) or null for a flat band
     *  (gain == 0), which the caller skips entirely. */
    fun coefficients(band: ParametricBand, sampleRate: Int): FloatArray? {
        if (band.gainDb == 0f) return null
        val fs = sampleRate.toFloat().coerceAtLeast(8000f)
        val f0 = band.freqHz.coerceIn(20f, fs * 0.475f)
        val w0 = (2.0 * PI * f0 / fs).toFloat()
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val a = 10.0.pow((band.gainDb / 40.0).toDouble()).toFloat() // sqrt(10^(gain/20))
        val raw = when (band.type) {
            ParametricBandType.PEAK -> {
                val alpha = sinW0 / (2f * band.q.coerceIn(0.5f, 4.0f))
                val b0 = 1f + alpha * a
                val b1 = -2f * cosW0
                val b2 = 1f - alpha * a
                val a0 = 1f + alpha / a
                val a1 = -2f * cosW0
                val a2 = 1f - alpha / a
                floatArrayOf(b0, b1, b2, a0, a1, a2)
            }
            ParametricBandType.LOW_SHELF -> {
                // RBJ shelf, slope S = 1: alpha = sin(w0)/2 * sqrt(2).
                val alpha = sinW0 / 2f * sqrt(2f)
                val sqrtA = sqrt(a)
                val b0 = a * ((a + 1f) - (a - 1f) * cosW0 + 2f * sqrtA * alpha)
                val b1 = 2f * a * ((a - 1f) - (a + 1f) * cosW0)
                val b2 = a * ((a + 1f) - (a - 1f) * cosW0 - 2f * sqrtA * alpha)
                val a0 = (a + 1f) + (a - 1f) * cosW0 + 2f * sqrtA * alpha
                val a1 = -2f * ((a - 1f) + (a + 1f) * cosW0)
                val a2 = (a + 1f) + (a - 1f) * cosW0 - 2f * sqrtA * alpha
                floatArrayOf(b0, b1, b2, a0, a1, a2)
            }
            ParametricBandType.HIGH_SHELF -> {
                val alpha = sinW0 / 2f * sqrt(2f)
                val sqrtA = sqrt(a)
                val b0 = a * ((a + 1f) + (a - 1f) * cosW0 + 2f * sqrtA * alpha)
                val b1 = -2f * a * ((a - 1f) + (a + 1f) * cosW0)
                val b2 = a * ((a + 1f) + (a - 1f) * cosW0 - 2f * sqrtA * alpha)
                val a0 = (a + 1f) - (a - 1f) * cosW0 + 2f * sqrtA * alpha
                val a1 = 2f * ((a - 1f) - (a + 1f) * cosW0)
                val a2 = (a + 1f) - (a - 1f) * cosW0 - 2f * sqrtA * alpha
                floatArrayOf(b0, b1, b2, a0, a1, a2)
            }
        }
        val a0 = raw[3]
        if (a0 == 0f || !a0.isFinite()) return null
        return floatArrayOf(raw[0] / a0, raw[1] / a0, raw[2] / a0, raw[4] / a0, raw[5] / a0)
    }
}

@OptIn(UnstableApi::class)
class ParametricEqAudioProcessor : AudioProcessor {
    /** Swapped atomically by the UI thread; coefficients are recomputed
     *  on the audio thread when the reference changes (see
     *  ensureCoefficients), so no lock is needed. */
    @Volatile var config: ParametricEqConfig = ParametricEqConfig()

    private var format = AudioProcessor.AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputScratch: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false
    // Cached coefficients for the config + sample rate last seen on the
    // audio thread: [band][b0,b1,b2,a1,a2], null = flat band (skip).
    private var cachedConfig: ParametricEqConfig? = null
    private var cachedSampleRate: Int = 0
    private var coeffs: Array<FloatArray?> = arrayOfNulls(10)
    // Filter state, audio thread only: per channel, per band: x1, x2, y1, y2.
    private var state: FloatArray = FloatArray(0)
    private var channels: Int = 0

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val bytesPerSample = bytesPerSample(inputAudioFormat.encoding)
        if (inputAudioFormat.channelCount <= 0 || inputAudioFormat.sampleRate <= 0 || bytesPerSample == 0) {
            format = AudioProcessor.AudioFormat.NOT_SET
            return inputAudioFormat
        }
        // Format is passed through unchanged (same encoding / rate /
        // channels) — this processor only changes sample values.
        format = inputAudioFormat
        channels = inputAudioFormat.channelCount
        state = FloatArray(channels * 10 * 4)
        cachedConfig = null
        cachedSampleRate = 0
        inputEnded = false
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        Log.i("MioParametricEq", "configure: ${inputAudioFormat.sampleRate}Hz enc=${inputAudioFormat.encoding} ch=$channels enabled=${config.enabled}")
        return inputAudioFormat
    }

    override fun isActive(): Boolean = format != AudioProcessor.AudioFormat.NOT_SET

    private fun ensureCoefficients() {
        val cfg = config
        val sr = format.sampleRate
        if (cachedConfig === cfg && cachedSampleRate == sr) return
        cachedConfig = cfg
        cachedSampleRate = sr
        coeffs = Array(10) { i -> cfg.bands.getOrNull(i)?.let { ParametricEqDefaults.coefficients(it, sr) } }
        // A new coefficient set must not inherit the old filter's state:
        // stale x/y would thump / ring at the new frequencies.
        state.fill(0f)
    }

    private fun isBypass(cfg: ParametricEqConfig): Boolean {
        if (!cfg.enabled) return true
        if (cfg.preampDb != 0f) return false
        return cfg.bands.all { it.gainDb == 0f }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (inputBuffer.remaining() == 0) {
            outputBuffer = inputBuffer
            return
        }
        if (!isActive()) {
            outputBuffer = inputBuffer
            return
        }
        val cfg = config
        if (isBypass(cfg)) {
            // Clean passthrough: return the input buffer itself, exactly
            // like WaveformTap does — no copy, no maths, bit-identical.
            outputBuffer = inputBuffer
            return
        }
        ensureCoefficients()
        val fmt = format
        val bytesPerSample = bytesPerSample(fmt.encoding)
        val frameBytes = fmt.channelCount * bytesPerSample
        if (frameBytes <= 0) {
            outputBuffer = inputBuffer
            return
        }
        val wholeFrames = inputBuffer.remaining() / frameBytes
        if (wholeFrames == 0) {
            outputBuffer = inputBuffer
            return
        }
        val outBytes = wholeFrames * frameBytes
        ensureOutputCapacity(outBytes)
        val out = outputScratch
        out.clear()
        // v1.1.48 — automatic headroom: subtract the largest
        // positive band gain from the preamp so boosted bands keep
        // their shape without driving peaks into the hard clamp.
        val headroomDb = cfg.bands.maxOfOrNull { it.gainDb }?.coerceAtLeast(0f) ?: 0f
        val preampGain = 10.0.pow(((cfg.preampDb - headroomDb) / 20.0).toDouble()).toFloat()
        // Read via a duplicate so the input buffer's position (which the
        // sink relies on) is never disturbed; write to scratch.
        val src = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        val activeCoeffs = coeffs
        repeat(wholeFrames) {
            for (ch in 0 until fmt.channelCount) {
                var x = readSample(src, fmt.encoding)
                // Direct Form I, band by band. State index:
                // ((ch * 10) + band) * 4 + {x1, x2, y1, y2}.
                for (b in 0 until 10) {
                    val c = activeCoeffs[b] ?: continue
                    val base = ((ch * 10) + b) * 4
                    val x1 = state[base]
                    val x2 = state[base + 1]
                    val y1 = state[base + 2]
                    val y2 = state[base + 3]
                    val y = c[0] * x + c[1] * x1 + c[2] * x2 - c[3] * y1 - c[4] * y2
                    state[base] = x
                    state[base + 1] = x1
                    state[base + 2] = y
                    state[base + 3] = y1
                    x = y
                }
                // Preamp, then hard clamp: the biquad + preamp must never
                // emit a sample outside [-1, 1] (that is what clips in
                // the AudioTrack / DAC). Clamping here is the same guard
                // the graphic path gets from its ±15 dB band clamp.
                x = (x * preampGain).coerceIn(-1f, 1f)
                writeSample(out, fmt.encoding, x)
            }
        }
        // Any trailing partial frame (< frameBytes) is passed through
        // byte-identical after the processed frames; the sink in practice
        // only ever delivers whole frames, so this is a safety net, not
        // a path that should ever fire.
        val tail = inputBuffer.remaining() - wholeFrames * frameBytes
        if (tail > 0) {
            val tailSrc = inputBuffer.duplicate()
            tailSrc.position(tailSrc.position() + wholeFrames * frameBytes)
            val tailBytes = ByteArray(tail)
            tailSrc.get(tailBytes)
            out.put(tailBytes)
        }
        // Advance the caller's input position past what we consumed.
        inputBuffer.position(inputBuffer.position() + wholeFrames * frameBytes + tail)
        out.flip()
        outputBuffer = out
    }

    private fun readSample(src: ByteBuffer, encoding: Int): Float = when (encoding) {
        C.ENCODING_PCM_FLOAT -> src.float
        C.ENCODING_PCM_16BIT -> src.short.toFloat() / 32768f
        C.ENCODING_PCM_24BIT -> {
            val b0 = src.get().toInt() and 0xFF
            val b1 = src.get().toInt() and 0xFF
            val b2 = src.get().toInt()
            var v = b0 or (b1 shl 8) or (b2 shl 16)
            if (v >= 0x800000) v -= 0x1000000
            v.toFloat() / 8388608f
        }
        C.ENCODING_PCM_32BIT -> src.int.toFloat() / 2147483648f
        C.ENCODING_PCM_8BIT -> (src.get().toInt() and 0xFF).toFloat() / 128f - 1f
        else -> 0f
    }

    private fun writeSample(dst: ByteBuffer, encoding: Int, value: Float) {
        when (encoding) {
            C.ENCODING_PCM_FLOAT -> dst.putFloat(value)
            C.ENCODING_PCM_16BIT -> dst.putShort((value * 32767f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            C.ENCODING_PCM_24BIT -> {
                val v = (value * 8388607f).toInt().coerceIn(-8388608, 8388607)
                dst.put((v and 0xFF).toByte())
                dst.put(((v shr 8) and 0xFF).toByte())
                dst.put(((v shr 16) and 0xFF).toByte())
            }
            C.ENCODING_PCM_32BIT -> dst.putInt((value * 2147483647f).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
            C.ENCODING_PCM_8BIT -> dst.put((((value + 1f) * 127.5f).toInt().coerceIn(0, 255)).toByte())
            else -> { /* unsupported encoding: drop the sample */ }
        }
    }

    private fun ensureOutputCapacity(bytes: Int) {
        if (outputScratch.capacity() < bytes) {
            outputScratch = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        }
    }

    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_FLOAT -> 4
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT -> 4
        C.ENCODING_PCM_8BIT -> 1
        else -> 0
    }

    override fun queueEndOfStream() { inputEnded = true }
    override fun getOutput(): ByteBuffer {
        val pending = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return pending
    }
    override fun isEnded(): Boolean = inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER
    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        state.fill(0f)
    }
    override fun reset() {
        format = AudioProcessor.AudioFormat.NOT_SET
        flush()
    }
}
