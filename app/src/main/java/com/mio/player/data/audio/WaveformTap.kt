package com.mio.player.data.audio

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.mio.player.data.log.MioLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Live audio levels from the player's own output, computed IN-PROCESS
 * from the PCM stream — no Visualizer.
 *
 * v1.1.2 (Fix 2): the Visualizer path is retired. The user's v1.1.1 log
 * proved it can never work on this phone:
 * `WaveformTap attach FAILED: session=10057 / RuntimeException: Cannot
 * initialize Visualizer engine, error: -3` (ERROR_INVALID_OPERATION — a
 * device/framework limitation, not a bug in the attach call). The old
 * LiveWaveform then fell back to a time-based sine animation, which the
 * user already rejected.
 *
 * New source: [fftProcessor], an [AudioProcessor] installed in the audio
 * sink chain in PlayerManager AFTER the DSD decimator. It passes audio
 * through byte-identical (configure returns the input format, queueInput
 * returns the input buffer untouched) and only READS the samples: stereo
 * is downmixed to mono, a rolling window feeds a 1024-point FFT
 * throttled to ~10 Hz, and 32 log-spaced band magnitudes (20 Hz–12 kHz,
 * same band mapping the Visualizer code used) are published to [levels].
 * Because the tap sits in the sink chain it sees PCM for ALL formats —
 * native DSD (after decimation to 176.4/88.2 kHz) and normal files alike.
 *
 * The public surface is unchanged on purpose: PlayerManager still owns
 * `val waveform = WaveformTap(context = ctx)` and NowPlaying still reads
 * `vm.pm.waveform.levels`, so no call site had to move.
 */
@OptIn(UnstableApi::class)
class WaveformTap(private val bars: Int = 32, private val context: Context? = null) {
    private val _levels = MutableStateFlow(FloatArray(bars) { 0f })
    val levels: StateFlow<FloatArray> = _levels.asStateFlow()

    /** The single tap instance installed in the sink chain. */
    val fftProcessor: AudioProcessor = FftTapAudioProcessor()

    private var last = FloatArray(bars) { 0f }
    private var firstFftLogged = false
    private var firstConfigureLogged = false

    private fun logEvent(msg: String) {
        context?.let { MioLog.event(it, msg) }
    }

    /**
     * Passthrough tap: returns its input unchanged, but accumulates mono
     * samples and runs the FFT at most every [FFT_INTERVAL_MS].
     *
     * Only this processor's input/output runs on the playback thread;
     * all FFT state below is touched only from the sink callbacks
     * (queueInput / flush / reset / configure), so no extra locking.
     */
    private inner class FftTapAudioProcessor : AudioProcessor {
        private var format = AudioProcessor.AudioFormat.NOT_SET
        private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
        private var inputEnded = false

        // Rolling mono window: newest samples overwrite the oldest.
        // 4096 slots hold ~93 ms at 44.1 kHz, comfortably more than one
        // 1024-sample FFT frame at any supported rate.
        private val ring = FloatArray(RING_SIZE)
        private var ringWrite = 0
        private var ringCount = 0
        private var lastFftNs = 0L

        override fun configure(
            inputAudioFormat: AudioProcessor.AudioFormat
        ): AudioProcessor.AudioFormat {
            val bytesPerSample = bytesPerSample(inputAudioFormat.encoding)
            // The sink only ever delivers PCM; anything else is passed
            // through inactive rather than guessed at.
            if (inputAudioFormat.channelCount <= 0 || bytesPerSample == 0) {
                format = AudioProcessor.AudioFormat.NOT_SET
                return inputAudioFormat
            }
            format = inputAudioFormat
            // A new format (new track / new rate) must not FFT a window
            // mixed from two sample rates.
            ringWrite = 0
            ringCount = 0
            lastFftNs = 0L
            if (!firstConfigureLogged) {
                firstConfigureLogged = true
                logEvent(
                    "WaveformTap FFT tap ON: ${inputAudioFormat.sampleRate}Hz " +
                        "enc=${inputAudioFormat.encoding} ch=${inputAudioFormat.channelCount}"
                )
            }
            return inputAudioFormat
        }

        override fun isActive(): Boolean =
            format != AudioProcessor.AudioFormat.NOT_SET

        override fun queueInput(inputBuffer: ByteBuffer) {
            if (inputBuffer.remaining() == 0) {
                outputBuffer = inputBuffer
                return
            }
            // Passthrough FIRST: the returned buffer is the input itself,
            // so the audio path is untouched whatever happens below.
            outputBuffer = inputBuffer
            if (!isActive()) return
            val fmt = format
            val bytesPerSample = bytesPerSample(fmt.encoding)
            if (bytesPerSample == 0 || fmt.channelCount <= 0) return
            val frameBytes = fmt.channelCount * bytesPerSample
            val frames = inputBuffer.remaining() / frameBytes
            if (frames == 0) return
            // Read via a duplicate so the input buffer's position/limit
            // (which the sink relies on for the passthrough output) are
            // never disturbed.
            val src = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
            repeat(frames) {
                var mono = 0f
                repeat(fmt.channelCount) {
                    mono += readSample(src, fmt.encoding)
                }
                mono /= fmt.channelCount
                ring[ringWrite] = mono
                ringWrite = (ringWrite + 1) % RING_SIZE
                if (ringCount < RING_SIZE) ringCount++
            }
            maybeRunFft()
        }

        /** Runs the FFT only when a full frame is buffered AND at least
         *  [FFT_INTERVAL_MS] have passed since the last run (~10 Hz). */
        private fun maybeRunFft() {
            if (ringCount < FFT_SIZE) return
            val nowNs = System.nanoTime()
            if (lastFftNs != 0L && nowNs - lastFftNs < FFT_INTERVAL_MS * 1_000_000L) return
            lastFftNs = nowNs
            val sampleRate = format.sampleRate.takeIf { it > 0 } ?: 48000
            // Copy the NEWEST FFT_SIZE mono samples, in time order.
            val frame = FloatArray(FFT_SIZE)
            var readPos = (ringWrite - FFT_SIZE + RING_SIZE * 2) % RING_SIZE
            for (i in 0 until FFT_SIZE) {
                frame[i] = ring[readPos]
                readPos = (readPos + 1) % RING_SIZE
            }
            val out = computeBands(frame, sampleRate)
            if (!firstFftLogged) {
                firstFftLogged = true
                val maxLevel = out.maxOrNull() ?: 0f
                logEvent(
                    "WaveformTap FFT first: sampleRate=${sampleRate}Hz " +
                        "maxLevel=$maxLevel allZero=${out.all { it < 0.02f }} " +
                        "bands=${out.joinToString(",") { "%.2f".format(it) }}"
                )
            }
            last = out
            _levels.value = out
        }

        /**
         * Hann-windowed 1024-point FFT, then one magnitude per log-spaced
         * band. Band value = the STRONGEST bin in the band (not the mean):
         * a mean over a wide high-frequency band dilutes a single peak to
         * near zero, while the max keeps every bar dancing with the music.
         * Normalised so a full-scale sine reads ~1.0 (Hann coherent gain
         * is N/4), then sqrt for perceptual lift and the same fast-attack
         * / slow-release smoothing the Visualizer code used.
         */
        private fun computeBands(frame: FloatArray, sampleRate: Int): FloatArray {
            val re = FloatArray(FFT_SIZE)
            val im = FloatArray(FFT_SIZE)
            for (i in 0 until FFT_SIZE) {
                val w = 0.5f * (1f - cos((2.0 * PI * i / (FFT_SIZE - 1)).toFloat()))
                re[i] = frame[i] * w
            }
            fftRadix2(re, im)
            val binHz = sampleRate.toFloat() / FFT_SIZE
            val nyquist = sampleRate / 2f
            val ratio = 12000f / 20f
            val out = FloatArray(bars)
            for (i in 0 until bars) {
                val fLow = 20f * Math.pow(ratio.toDouble(), i.toDouble() / bars).toFloat()
                val fHigh = (20f * Math.pow(ratio.toDouble(), (i + 1).toDouble() / bars).toFloat())
                    .coerceAtMost(nyquist)
                // Skip the DC bin (0); clamp into the usable bin range.
                val start = (fLow / binHz).toInt().coerceIn(1, FFT_SIZE / 2 - 1)
                val end = (fHigh / binHz).toInt().coerceIn(start + 1, FFT_SIZE / 2)
                var peak = 0f
                for (j in start until end) {
                    val mag = sqrt(re[j] * re[j] + im[j] * im[j]) / (FFT_SIZE / 4f)
                    if (mag > peak) peak = mag
                }
                val norm = sqrt(peak.coerceIn(0f, 1f))
                out[i] = if (norm > last[i]) last[i] * 0.3f + norm * 0.7f
                else last[i] * 0.7f + norm * 0.3f
            }
            return out
        }

        /** Iterative radix-2 Cooley-Tukey FFT, in place, pure Kotlin. */
        private fun fftRadix2(re: FloatArray, im: FloatArray) {
            val n = re.size
            // Bit-reversal permutation.
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }
            // Butterfly stages.
            var len = 2
            while (len <= n) {
                val angle = (-2.0 * PI / len).toFloat()
                val wr = cos(angle)
                val wi = sin(angle)
                var i = 0
                while (i < n) {
                    var wrCur = 1f
                    var wiCur = 0f
                    for (k in 0 until len / 2) {
                        val uRe = re[i + k]
                        val uIm = im[i + k]
                        val vRe = re[i + k + len / 2] * wrCur - im[i + k + len / 2] * wiCur
                        val vIm = re[i + k + len / 2] * wiCur + im[i + k + len / 2] * wrCur
                        re[i + k] = uRe + vRe
                        im[i + k] = uIm + vIm
                        re[i + k + len / 2] = uRe - vRe
                        im[i + k + len / 2] = uIm - vIm
                        val nextWr = wrCur * wr - wiCur * wi
                        wiCur = wrCur * wi + wiCur * wr
                        wrCur = nextWr
                    }
                    i += len
                }
                len *= 2
            }
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

        override fun queueEndOfStream() {
            inputEnded = true
        }

        override fun getOutput(): ByteBuffer {
            val pending = outputBuffer
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            return pending
        }

        override fun isEnded(): Boolean =
            inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

        override fun flush() {
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            inputEnded = false
            ringWrite = 0
            ringCount = 0
            lastFftNs = 0L
        }

        override fun reset() {
            format = AudioProcessor.AudioFormat.NOT_SET
            flush()
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

    /** Zero the published levels so the UI shows idle bars instead of a
     *  frozen frame of live levels (called when playback pauses). */
    fun clearLevels() {
        last = FloatArray(bars) { 0f }
        _levels.value = FloatArray(bars) { 0f }
    }

    fun release() {
        // Drop back to zeros so the UI shows idle bars instead of a
        // frozen frame of the last live levels.
        clearLevels()
    }

    companion object {
        private const val FFT_SIZE = 1024
        private const val RING_SIZE = 4096
        private const val FFT_INTERVAL_MS = 100L // ~10 Hz publish rate
    }
}
