package com.mio.player.data.audio
import android.content.Context
import android.media.audiofx.Visualizer
import android.util.Log
import com.mio.player.data.log.MioLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt
/** Live audio levels from the player's own output, via Visualizer FFT.
 *  Attaching to our own audio session needs no microphone permission
 *  (RECORD_AUDIO is only needed to capture the output mix or another
 *  app's session). If the device refuses Visualizer, levels stay at zero
 *  and the UI shows low idle bars instead of a fake animated wave.
 *
 *  FIX 3 (v1.1.1) diagnostics: attach success/failure and the first FFT
 *  callback are logged, because on this build the Visualizer may be
 *  delivering zeros (suspects: the float-output native DSD renderer
 *  session bypassing the Visualizer tap, capture size, or a stale/zero
 *  session id). `context` is optional so `WaveformTap()` keeps working
 *  unchanged; when it is supplied the lines also land in mio-log.txt
 *  (Profile -> "Nhật ký lỗi"), otherwise they are in logcat under
 *  "MioWave". NOTE for the integrator: PlayerManager currently builds
 *  `WaveformTap()` with no Context, so pass it there (`WaveformTap(ctx)`)
 *  for the mio-log.txt lines to appear — logcat lines work regardless. */
class WaveformTap(private val bars: Int = 32, private val context: Context? = null) {
    private var visualizer: Visualizer? = null
    private var attachedSession: Int = 0
    private val _levels = MutableStateFlow(FloatArray(bars) { 0f })
    val levels: StateFlow<FloatArray> = _levels.asStateFlow()
    private var last = FloatArray(bars) { 0f }
    private var firstFftLogged = false

    /** One line to logcat always, and to mio-log.txt when a Context was
     *  supplied at construction. */
    private fun logEvent(msg: String) {
        Log.i("MioWave", msg)
        context?.let { MioLog.event(it, msg) }
    }
    private fun logWarn(msg: String, e: Exception? = null) {
        if (e != null) Log.w("MioWave", msg, e) else Log.w("MioWave", msg)
        context?.let { MioLog.event(it, msg) }
    }

    fun attach(audioSessionId: Int) {
        if (audioSessionId == 0) {
            // Session 0 means ExoPlayer has no audio session yet (or the
            // session was released): nothing to tap. The listener fires
            // again with a real id once playback starts, so just wait.
            logWarn("WaveformTap attach skipped: audioSessionId=0 (no session yet)")
            return
        }
        if (audioSessionId == attachedSession) return
        release()
        firstFftLogged = false
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
                        var maxRawMag = 0f
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
                                val mag = sqrt((re * re + im * im).toFloat())
                                if (mag > maxRawMag) maxRawMag = mag
                                sum += mag
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
                        if (!firstFftLogged) {
                            firstFftLogged = true
                            val maxLevel = out.maxOrNull() ?: 0f
                            val allZero = out.all { it < 0.02f }
                            logEvent("WaveformTap first FFT: session=$attachedSession fftBytes=${fft.size} rate=${rate}mHz maxRawMag=$maxRawMag maxLevel=$maxLevel allZero=$allZero")
                            if (allZero) {
                                logWarn("WaveformTap FFT is all zero: Visualizer attached but no audio reaches it — suspect float-output native renderer session bypassing the tap (PlayerManager.ENABLE_AUDIO_FLOAT_OUTPUT) or a wrong/stale session id; bars will stay idle")
                            }
                        }
                        last = out
                        _levels.value = out
                    }
                }, Visualizer.getMaxCaptureRate(), false, true)
                enabled = true
            }
            attachedSession = audioSessionId
            logEvent("WaveformTap attached: session=$audioSessionId captureSize=${visualizer?.captureSize} maxCaptureRate=${Visualizer.getMaxCaptureRate()}")
        } catch (e: Exception) {
            logWarn("WaveformTap attach FAILED: session=$audioSessionId ${e::class.java.simpleName}: ${e.message}", e)
            release()
        }
    }
    fun release() {
        try { visualizer?.release() } catch (_: Exception) {}
        visualizer = null
        attachedSession = 0
        firstFftLogged = false
        // Drop back to zeros so the UI shows idle bars instead of a
        // frozen frame of the last live levels.
        last = FloatArray(bars) { 0f }
        _levels.value = FloatArray(bars) { 0f }
    }
}
