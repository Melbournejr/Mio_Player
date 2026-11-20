package com.mio.player.data.audio
import android.media.audiofx.Visualizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt
/** Live audio levels from the player's own output, via Visualizer FFT.
 *  Attaching to our own audio session needs no microphone permission
 *  (RECORD_AUDIO is only needed to capture the output mix or another
 *  app's session). If the device refuses Visualizer, levels stay empty
 *  and the UI falls back to an animated wave instead of a dead one. */
class WaveformTap(private val bars: Int = 32) {
    private var visualizer: Visualizer? = null
    private var attachedSession: Int = 0
    private val _levels = MutableStateFlow(FloatArray(bars) { 0f })
    val levels: StateFlow<FloatArray> = _levels.asStateFlow()
    private var last = FloatArray(bars) { 0f }
    fun attach(audioSessionId: Int) {
        if (audioSessionId == 0 || audioSessionId == attachedSession) return
        release()
        try {
            visualizer = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, rate: Int) {}
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                        if (fft == null) return
                        val n = fft.size / 2
                        // FFT bins are linear in frequency, but music energy
                        // is logarithmic: map each bar to a log-spaced
                        // frequency band (20 Hz .. 12 kHz) so every bar
                        // dances with the music instead of only the first
                        // few. Bin width = sample rate / capture size.
                        val binHz = 48000f / fft.size
                        val out = FloatArray(bars)
                        for (i in 0 until bars) {
                            val ratio = 12000f / 20f
                            val fLow = 20f * Math.pow(ratio.toDouble(), i.toDouble() / bars).toFloat()
                            val fHigh = 20f * Math.pow(ratio.toDouble(), (i + 1).toDouble() / bars).toFloat()
                            val start = (fLow / binHz).toInt().coerceIn(0, n - 1)
                            val end = (fHigh / binHz).toInt().coerceIn(start + 1, n)
                            var sum = 0f
                            var count = 0
                            for (j in start until end) {
                                val re = fft[2 * j].toInt()
                                val im = fft[2 * j + 1].toInt()
                                sum += sqrt((re * re + im * im).toFloat())
                                count++
                            }
                            val mag = if (count > 0) sum / count else 0f
                            // Perceptual scaling: sqrt lifts quiet bands so
                            // they stay visible instead of flatlining.
                            val norm = sqrt((mag / 128f).coerceIn(0f, 1f))
                            // Fast attack, slower release: bars follow beats
                            // instead of lagging behind them.
                            out[i] = if (norm > last[i]) last[i] * 0.3f + norm * 0.7f else last[i] * 0.7f + norm * 0.3f
                        }
                        last = out
                        _levels.value = out
                    }
                }, Visualizer.getMaxCaptureRate(), false, true)
                enabled = true
            }
            attachedSession = audioSessionId
        } catch (e: Exception) {
            Log.w("MioWave", "Visualizer unavailable", e)
            release()
        }
    }
    fun release() {
        try { visualizer?.release() } catch (_: Exception) {}
        visualizer = null
        attachedSession = 0
    }
}
