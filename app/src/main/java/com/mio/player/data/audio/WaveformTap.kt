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
                        val out = FloatArray(bars)
                        for (i in 0 until bars) {
                            val start = i * n / bars
                            val end = ((i + 1) * n / bars).coerceAtMost(n)
                            var sum = 0f
                            var count = 0
                            for (j in start until end) {
                                val re = fft[2 * j].toInt()
                                val im = fft[2 * j + 1].toInt()
                                sum += sqrt((re * re + im * im).toFloat())
                                count++
                            }
                            val mag = if (count > 0) sum / count else 0f
                            val norm = (mag / 90f).coerceIn(0f, 1f)
                            out[i] = last[i] * 0.55f + norm * 0.45f
                        }
                        last = out
                        _levels.value = out
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
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
