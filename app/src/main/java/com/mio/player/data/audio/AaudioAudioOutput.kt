package com.mio.player.data.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Step 3 (AAudio Exclusive) — a Media3 [AudioOutput] backed by one native
 * AAudio stream (v1.1.39). Created by [AaudioOutputProvider] only when the
 * stream was actually granted the requested rate AND exclusive (MMAP)
 * sharing; otherwise the provider falls back to the standard AudioTrack
 * output, so the sink always gets a working output.
 *
 * Position model (simpler than AudioTrackPositionTracker, sufficient for a
 * music player): the playout position is the presentation timestamp of
 * the first buffer written after open/flush, plus the frames the device
 * has actually consumed ([AaudioEngine.framesRead]) converted to time.
 * Frames-read stops while paused and keeps advancing while the remaining
 * buffered frames play out after [stop], so pause / end-of-stream behave
 * correctly. Gapless works because the base is only reset on [flush].
 *
 * Honest limitations (AAudio has no AudioTrack session and no speed
 * control):
 * - There is no audio session, so the Android AudioEffect-based effects
 *   (Graphic EQ, Bass Boost, Virtualizer, Loudness) cannot attach and do
 *   not apply in this mode. Only the Parametric EQ (a Media3 processor
 *   in the sink chain) still applies. The UI says so.
 * - Playback speed != 1.0 is not supported by AAudio (it would need
 *   resampling); the value is stored and reported, but audio plays at
 *   1.0x. Turn Hi-Res off to use speed control.
 * - Volume (ReplayGain) IS honored: when volume != 1.0 the int16 samples
 *   are scaled in a scratch buffer before writing. The fast path
 *   (volume == 1.0) writes the sink's buffer directly, without copying.
 */
@UnstableApi
class AaudioAudioOutput(
    private val engine: AaudioEngine,
    /** The requested (== actual, enforced by the engine) sample rate. */
    private val sampleRate: Int
) : AudioOutput {

    private val listeners = mutableSetOf<AudioOutput.Listener>()

    /** Presentation timestamp (us) of the first frame written after open/flush. */
    private var basePresentationTimeUs: Long = C.TIME_UNSET
    /** framesRead() captured when the base was set. */
    private var framesReadAtBase: Long = 0L
    /** True once play() has been called and the stream is started. */
    private var started = false
    /** True after stop() (end of stream) until the next flush. */
    private var stopped = false
    /** Current volume (ReplayGain). 1.0 = fast path, no copy. */
    private var volume: Float = 1.0f
    /** Current playback parameters (speed is stored, not applied). */
    private var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
    /** Scratch direct buffer for the volume-scaling path. */
    private var scratch: ByteBuffer? = null

    override fun play() {
        if (!started) {
            started = true
            stopped = false
            engine.start()
            listeners.forEach { it.onPositionAdvancing(System.currentTimeMillis()) }
        }
    }

    override fun pause() {
        if (started) {
            started = false
            engine.pause()
        }
    }

    @Throws(AudioOutput.WriteException::class)
    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        if (!engine.isOpen) throw AudioOutput.WriteException(-1, false)
        // v1.1.43 — safety net: make sure the stream is started before
        // writing. play() can be missed (e.g. the sink creates the
        // output after its own play() already ran, or stop()/flush()
        // left the stream STOPPED/FLUSHED), and a write to a
        // non-started stream fails with INVALID_STATE, stalling the
        // sink at 0:00. requestStart on an already-started stream is
        // a harmless no-op, so this is safe to call unconditionally.
        if (!started) {
            started = true
            stopped = false
            engine.start()
            listeners.forEach { it.onPositionAdvancing(System.currentTimeMillis()) }
        }
        if (basePresentationTimeUs == C.TIME_UNSET) {
            basePresentationTimeUs = presentationTimeUs
            framesReadAtBase = engine.framesRead()
        }
        val remaining = buffer.remaining()
        if (remaining <= 0) return true
        val bytesWritten: Int
        if (volume == 1.0f) {
            // Fast path: write the sink's buffer directly (no copy).
            val offset = buffer.position()
            bytesWritten = engine.write(buffer, offset, remaining)
        } else {
            // Volume path: copy + scale int16 samples into a scratch
            // buffer (the sink's buffer must not be modified), then write
            // the scratch. The buffer is int16 stereo (float output OFF).
            val scratchBuf = getScratch(remaining)
            scratchBuf.clear()
            val src = buffer.duplicate().order(ByteOrder.nativeOrder())
            val dst = scratchBuf.order(ByteOrder.nativeOrder())
            val shortCount = remaining / 2
            val srcShorts = src.asShortBuffer()
            val dstShorts = dst.asShortBuffer()
            for (i in 0 until shortCount) {
                val scaled = (srcShorts.get(i) * volume).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                dstShorts.put(i, scaled.toShort())
            }
            scratchBuf.position(0)
            scratchBuf.limit(remaining)
            bytesWritten = engine.write(scratchBuf, 0, remaining)
        }
        if (bytesWritten < 0) {
            // Negative = native error. The output cannot be reused.
            throw AudioOutput.WriteException(bytesWritten, false)
        }
        if (bytesWritten > 0) {
            // Advance the sink buffer by exactly what was consumed.
            buffer.position(buffer.position() + bytesWritten)
        }
        return bytesWritten == remaining
    }

    override fun flush() {
        engine.flush()
        basePresentationTimeUs = C.TIME_UNSET
        framesReadAtBase = 0L
        stopped = false
        // v1.1.43 — requestFlush leaves the stream FLUSHED; it needs
        // requestStart again before writing. Reset `started` so the
        // next play() actually restarts it (play() is a no-op while
        // `started` is true).
        started = false
    }

    override fun stop() {
        if (stopped) return
        stopped = true
        // v1.1.43 — DO NOT call engine.stop() here (AAudioStream_
        // requestStop). The comment above already said so, but the code
        // did it anyway: requestStop puts the stream in STOPPED state,
        // and a write to a STOPPED stream returns INVALID_STATE, so
        // the sink's output buffer never drains, hasPendingData stays
        // true and handleBuffer returns false forever — every track
        // stuck at 0:00, no sound (v1.1.42 video). The remaining
        // buffered frames play out on their own and the stream is
        // closed on release(), which is what this method's contract
        // actually needs. `started` is intentionally left alone: the
        // stream is still started, so a later play()/write() must not
        // skip the start call — and the write() safety net restarts
        // it anyway if it ever finds itself not started.
    }

    override fun release() {
        engine.close()
        listeners.forEach { it.onReleased() }
        listeners.clear()
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
    }

    override fun isOffloadedPlayback(): Boolean = false

    override fun getAudioSessionId(): Int = C.AUDIO_SESSION_ID_UNSET

    override fun getSampleRate(): Int = sampleRate

    override fun getBufferSizeInFrames(): Long = engine.bufferSizeInFrames.toLong()

    override fun getPositionUs(): Long {
        if (basePresentationTimeUs == C.TIME_UNSET) return 0L
        val framesPlayed = (engine.framesRead() - framesReadAtBase).coerceAtLeast(0L)
        return basePresentationTimeUs + framesPlayed * 1_000_000L / sampleRate
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters

    override fun isStalled(): Boolean = false

    override fun addListener(listener: AudioOutput.Listener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: AudioOutput.Listener) {
        listeners.remove(listener)
    }

    override fun setPlaybackParameters(playbackParams: PlaybackParameters) {
        // AAudio has no speed control (it would need resampling); store
        // the value so getPlaybackParameters() reports it, but audio
        // plays at 1.0x. This is a documented limitation of Hi-Res mode.
        if (playbackParams.speed != 1.0f) {
            Log.w("MioAAudio", "playback speed ${playbackParams.speed} not supported in AAudio mode, playing at 1.0x")
        }
        playbackParameters = playbackParams
    }

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {
        // No offload in this output; nothing to do.
    }

    override fun setOffloadEndOfStream() {
        // No offload in this output; nothing to do.
    }

    override fun attachAuxEffect(effectId: Int) {
        // No audio session, so no aux effect can attach.
    }

    override fun setAuxEffectSendLevel(level: Float) {
        // No audio session, so no aux effect can attach.
    }

    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) {
        // Routing is decided by the exclusive stream itself.
    }

    private fun getScratch(bytes: Int): ByteBuffer {
        val current = scratch
        if (current == null || current.capacity() < bytes) {
            scratch = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        }
        return scratch!!
    }
}

/**
 * Step 3 — [AudioOutputProvider] that serves AAudio outputs when Hi-Res
 * mode is on, and delegates everything else to the standard
 * [AudioTrackAudioOutputProvider]. The AAudio decision happens in
 * [getAudioOutput]: the stream is opened with the config's sample rate,
 * and only kept when the engine accepted it (exact rate + exclusive);
 * otherwise the delegate's AudioTrack output is returned, so the sink
 * always gets a working output and playback never fails because of
 * AAudio.
 */
@UnstableApi
class AaudioOutputProvider(
    private val context: Context,
    /** True when the user enabled Hi-Res (AAudio) in Settings. */
    @Volatile var hiResEnabled: Boolean
) : AudioOutputProvider {

    private val delegate = AudioTrackAudioOutputProvider.Builder(context).build()
    private val listeners = mutableSetOf<AudioOutputProvider.Listener>()

    /** The last AAudio stream state, for the Settings status display. */
    @Volatile var lastAaudioState: AaudioState = AaudioState.Idle
        private set

    sealed interface AaudioState {
        data object Idle : AaudioState
        data class Active(val sampleRate: Int, val exclusive: Boolean, val lowLatency: Boolean) : AaudioState
        data class Fallback(val reason: String) : AaudioState
    }

    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport {
        return delegate.getFormatSupport(formatConfig)
    }

    @Throws(AudioOutputProvider.ConfigurationException::class)
    override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig {
        val config = delegate.getOutputConfig(formatConfig)
        // v1.1.42 — the sink calls this when the input format changes,
        // before getAudioOutput creates the new output. If the new
        // format's rate differs from the last AAudio output's rate, the
        // sink will recreate the output, so the old Active state is
        // stale (it would otherwise be shown for the new track until
        // getAudioOutput runs). Reset to Idle; getAudioOutput sets the
        // real state when the new output is created. Same rate means
        // the output is reused, so keep the state.
        val last = lastAaudioState
        val newRate = formatConfig.format.sampleRate
        if (last is AaudioState.Active && newRate > 0 && last.sampleRate != newRate) {
            lastAaudioState = AaudioState.Idle
        }
        if (!hiResEnabled) return config
        // Force the sink to recreate the output when Hi-Res toggles:
        // DefaultAudioSink reuses the current AudioOutput whenever the
        // new OutputConfig equals the old one, so without a differing
        // field a Hi-Res toggle would silently keep the old AudioTrack
        // output for every same-format track. The buffer size is the
        // benign field to vary — the AAudio engine ignores it (the HAL
        // sets the stream's own buffer) and the AudioTrack path never
        // sees this config. +1 byte guarantees inequality with the
        // delegate's config for the same format.
        return config.buildUpon().setBufferSize(config.bufferSize + 1).build()
    }

    @Throws(AudioOutputProvider.InitializationException::class)
    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        if (hiResEnabled && config.encoding == C.ENCODING_PCM_16BIT && config.channelMask == AudioFormat.CHANNEL_OUT_STEREO) {
            val engine = AaudioEngine.open(config.sampleRate, 2)
            if (engine != null) {
                lastAaudioState = AaudioState.Active(engine.sampleRate, engine.isExclusive, engine.isLowLatency)
                // v1.1.40 — log the real outcome to mio-log.txt so the
                // next user log proves whether exclusive engaged and at
                // what rate, instead of the UI guessing.
                com.mio.player.data.log.MioLog.event(context, "AAudio ACTIVE: ${engine.sampleRate} Hz exclusive=${engine.isExclusive} lowLatency=${engine.isLowLatency}")
                return AaudioAudioOutput(engine, config.sampleRate)
            }
            lastAaudioState = AaudioState.Fallback(
                "AAudio không mở được ${config.sampleRate} Hz exclusive trên máy này — đã về mixer"
            )
            com.mio.player.data.log.MioLog.event(context, "AAudio FALLBACK: open failed for ${config.sampleRate} Hz (exclusive refused or rate unsupported) — using mixer")
        } else if (hiResEnabled) {
            lastAaudioState = AaudioState.Fallback("Định dạng không phải PCM 16-bit stereo — đã về mixer")
            com.mio.player.data.log.MioLog.event(context, "AAudio FALLBACK: format not PCM16 stereo (encoding=${config.encoding}) — using mixer")
        }
        return delegate.getAudioOutput(config)
    }

    override fun addListener(listener: AudioOutputProvider.Listener) {
        listeners.add(listener)
        delegate.addListener(listener)
    }

    override fun removeListener(listener: AudioOutputProvider.Listener) {
        listeners.remove(listener)
        delegate.removeListener(listener)
    }

    override fun release() {
        delegate.release()
    }
}
