package com.mio.player.playback
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.DsdDecodeProbe
import androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import com.mio.player.playback.dsd.DsdExtractor
import com.mio.player.data.audio.AudioEffectsManager
import com.mio.player.data.audio.WaveformTap
import com.mio.player.data.audio.DsdDecoder
import com.mio.player.data.audio.DsdSupport
import com.mio.player.data.log.MioLog
import com.mio.player.data.model.Track
import com.mio.player.data.prefs.SettingsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.roundToInt
/** Gapless, high-res playback.
 *  - Native DSD (v1.1.0, reworked v1.1.5 onto mica-music's proven config):
 *    .dsf/.dff are played directly — DsdExtractor frames the raw DSD bytes
 *    (DFF packets de-interleaved + bit-reversed to planar LSBF) and the
 *    vendored FFmpeg DSD decoder (extension renderer,
 *    EXTENSION_RENDERER_MODE_PREFER, int16 output) decodes them in-process.
 *    Float output is OFF and the decimator runs INSIDE DefaultAudioSink's
 *    int16 processor chain — the opposite of the v1.1.0–v1.1.4 setup (float
 *    ON, decimator in a ForwardingAudioSink wrapper outside the sink),
 *    which never produced one output sample: the sink was downstream of
 *    a decoder that returned no output, and 705600 Hz float could never
 *    have opened an AudioTrack anyway. See the renderers-factory KDoc
 *    below. DSD tracks go into the playlist as raw URIs, so playback
 *    starts immediately with no pre-decode wait.
 *  - Fallback: if native decode fails for a track (DST-compressed .dff,
 *    an unsupported DSF variant, a malformed header), onPlayerError
 *    decodes it once to a cached PCM WAV with DsdDecoder (FFmpegKit)
 *    and swaps it in. That is the only remaining use of the transcode
 *    path. androidx.media3:media3-decoder-ffmpeg is NOT published to
 *    Maven (see DsdSupport.kt) — the decoder here is vendored, not that
 *    artifact.
 *  - Gapless: ExoPlayer handles gapless for same-format consecutive tracks.
 *  - High-res: AudioTrack is configured via ExoPlayer defaults; the device
 *    sample rate is read from AudioManager and exposed for the Now Playing
 *    "source -> device" label. Crossfade is implemented as a short
 *    volume ramp is NOT faked — see crossfadeMs: ExoPlayer has no built-in
 *    crossfade, so crossfade is applied by the service fading volume over
 *    the last crossfadeMs of a track (PlayerService, best-effort).
 *  - ReplayGain: track/album gain applied as volume = 10^(gain/20), clamped.
 */
@OptIn(UnstableApi::class)
class PlayerManager(private val ctx: Context) {
    companion object {
        /**
         * v1.1.5 — float output is OFF (mica-music's proven DSD config).
         *
         * v1.1.0–v1.1.4 ran float ON on the theory that the FFmpeg DSD
         * decoder "outputs float PCM". It does — but float output was one
         * of the two unproven elements in a path that never produced a
         * single output sample, and it forced the decimator into a
         * ForwardingAudioSink wrapper outside the sink (DefaultAudioSink
         * drops its whole processor chain in float mode) plus a
         * 705600 Hz -> 176400 Hz AudioTrack the framework was never
         * proven to open. mica-music (lecoix/mica-music, branch
         * exoplayer-only, Apache 2.0 — the only proven ExoPlayer DSD
         * reference) runs float OFF, the decimator inside
         * DefaultAudioSink's int16 processor chain, and split DSD/PCM
         * sinks. This flag is that move: the decoder now emits 16-bit PCM
         * (FfmpegAudioRenderer.shouldOutputFloat returns false once the
         * sink reports float unsupported), the decimator runs in the
         * sink chain, and the AudioTrack opens at 176400 Hz int16.
         *
         * EQ: AudioEffectsManager attaches to the AudioTrack's audio
         * session, which exists identically on the int16 path — int16 is
         * the classic AudioEffect path, and the EQ the user confirmed
         * working in v1.1.3 keeps working. Do NOT flip this back to true
         * without re-proving native DSD end to end: float ON also revives
         * the wrapper, which is deleted in v1.1.5.
         */
        const val ENABLE_AUDIO_FLOAT_OUTPUT = false

        /**
         * Expected SHA-256 of the libffmpegJNI.so the app was built
         * against (the 2026-11-21 rebuild: FFmpeg 6.0, NDK r26d, DSD
         * createContext patch, all four DSD decoders). The startup probe
         * hashes the loaded .so and logs match/mismatch, and release.yml
         * fails the release if the .so inside the APK differs — so a stale
         * or unpatched .so can never again ship silently (v1.1.2 shipped
         * the old .so once because nothing checked). If the .so is ever
         * deliberately rebuilt, update this constant AND the release.yml
         * gate to the new hash together.
         */
        const val EXPECTED_FFMPEG_SO_SHA256 =
            "59245f9ce27b78740189870fce74312a1014b4f694110daa6b83d1c77a69ffb7"

        /**
         * v1.1.3 (OOM fix) — hard cap on the loader's sample queue.
         *
         * Root cause (user log 2026-11-21, DFF DSD128 ~398 MB): 35 s after
         * playQueue the loader thread died allocating a fresh 64 KB
         * DefaultAllocator segment
         * (`Loader$UnexpectedLoaderException ... OutOfMemoryError: Failed to
         * allocate a 65552 byte allocation`, heap growth limit 256 MB).
         * DefaultAllocator.allocate() only news a byte[] when its free pool
         * is empty, so every segment ever allocated was still checked out
         * by the SampleQueue — the queue had grown to ~150-200 MB of raw
         * DSD bytes, far past DefaultLoadControl's 13,107,200-byte audio
         * target. The byte cap is only consulted by
         * shouldContinueLoading() once bufferedDurationUs >= minBufferUs,
         * and for local playback Media3 1.9 defaults to
         * minBufferForLocalPlaybackMs=1000 with
         * prioritizeTimeOverSizeThresholdsForLocalPlayback=true — while
         * bufferedDurationUs stays below that 1 s, the byte cap is ignored
         * entirely and loading runs to end-of-file. The extractor's
         * timestamps, the decimator and the FFT tap were all re-verified
         * bounded, and no other playback-path accumulator exists, so the
         * fix is to make the byte cap absolute: prioritize=false on BOTH
         * the streaming and the local path, plus an explicit 8 MiB target
         * and 5 s / 10 s time buffers. Trace through
         * shouldContinueLoading() with these values: below minBuffer,
         * isLoading = !targetReached; between min and max, isLoading
         * clears as soon as the target is reached; at maxBuffer it
         * clears regardless. So in-use sample-queue bytes can never
         * exceed 8 MiB + one 1 MiB load-check interval, no matter what
         * bufferedDurationUs reports. 8 MiB is ~5.7 s of DSD128
         * (1.41 MB/s), ~11 s of DSD64, ~2.8 s of DSD256, and 45 s+ of
         * FLAC/MP3 — far more than local disk reads (tens of MB/s) need
         * to refill, so normal playback, gapless transitions and seeks
         * are unaffected. The decimator still passes everything below
         * 352800 Hz through untouched; only the load control changed.
         */
        const val LOAD_CONTROL_MIN_BUFFER_MS = 5_000
        const val LOAD_CONTROL_MAX_BUFFER_MS = 10_000
        const val LOAD_CONTROL_TARGET_BUFFER_BYTES = 8 * 1024 * 1024
    }
    val fx = AudioEffectsManager()
    val waveform = WaveformTap(context = ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // v1.1.5: the enableAudioOutputPlaybackParameters flag buildAudioSink
    // receives (factory default — the app never sets it). buildAudioRenderers
    // has no such parameter, so the DSD sink is built from the value captured
    // here. createRenderers always builds the sink before the renderers, so
    // the capture has happened by the time it is read.
    private var dsdSinkPlaybackParams = true
    val player: ExoPlayer = ExoPlayer.Builder(ctx)
        .setRenderersFactory(
            // v1.1.5 — mica-music's proven DSD topology (replaces the
            // v1.1.4 DsdFloatAudioSink wrapper, deleted in v1.1.5).
            //
            // WHAT WAS WRONG (Worker A diagnosis, from the v1.1.2–v1.1.4
            // user logs): every DSD packet returned "no output" (0 bytes)
            // or "invalid data" (-1) from ffmpegDecode, and Media3's
            // SimpleDecoder silently swallows both — no output buffer was
            // ever dequeued, DecoderAudioRenderer never called
            // audioSink.configure(...), so the sink (and the v1.1.4 wrapper
            // and the decimator inside it) was NEVER REACHED. The v1.1.4
            // claim ("DefaultAudioSink drops the custom chain in float
            // mode") is true about the sink but wrong as the cause — the
            // sink is downstream of the decoder and never gets configured
            // without a first output buffer. The wrapper therefore fixed
            // nothing, and it added two unproven elements of its own:
            // float output at 705600 Hz, and a format-rewriting
            // ForwardingAudioSink outside the sink. Downstream landmine
            // on top: even with output, the wrapper handed 176400 Hz FLOAT
            // to DefaultAudioSink, and a 176400 Hz float AudioTrack was
            // never proven to open.
            //
            // THE PROVEN CONFIG (mica-music, branch exoplayer-only,
            // Apache 2.0 — the only reference that demonstrably plays DSD
            // in ExoPlayer; our DSD path before v1.1.5 had no proven
            // reference and was the opposite in every choice):
            //  - float output OFF (ENABLE_AUDIO_FLOAT_OUTPUT = false):
            //    the FFmpeg decoder emits 16-bit PCM.
            //  - decimator INSIDE DefaultAudioSink's processor chain via
            //    Builder.setAudioProcessors(...) — the chain Media3 1.9
            //    only includes on the non-float path, so this only works
            //    with float off.
            //  - SPLIT sinks: the FFmpeg (DSD) renderer gets its own sink
            //    with [decimator, FFT tap]; the MediaCodec (PCM) renderer
            //    gets a sink with [FFT tap] only. The decimator is a
            //    strict passthrough below 352800 Hz, so the split changes
            //    nothing for PCM — it exists so the DSD rate change
            //    (705600 -> 176400 Hz) lives only in the DSD sink, exactly
            //    as in mica, instead of in a wrapper around a shared sink.
            //  - the FFT tap stays installed in BOTH sinks (same
            //    WaveformTap.fftProcessor instance): it sees PCM for ALL
            //    formats, as the visualizer requires. Only one renderer
            //    drives the tap at a time — ExoPlayer drives all renderers
            //    sequentially on its single playback thread, and a track
            //    switch flushes the old sink before configuring the new
            //    one — so sharing the instance across the two sinks is
            //    safe. Do NOT "fix" this by giving each sink its own tap:
            //    WaveformTap owns exactly one fftProcessor and its
            //    levels/pcmFrame flows come from that one instance.
            //  - DFF no longer uses dsd_msbf: see DsdExtractor (packets
            //    are de-interleaved + bit-reversed to planar LSBF, and
            //    both containers now use mica's dsd_lsbf_planar).
            //
            // What this does NOT fix (honest scope): if the probe below
            // shows the decoder still returns 0 / -1 for a synthetic
            // packet, the failure is inside the JNI/decoder layer, not
            // the sink — and the next step is streaming decode (see the
            // v1.1.5 report), not another sink rework.
            object : DefaultRenderersFactory(ctx) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioOutputPlaybackParameters: Boolean
                ): AudioSink? {
                    dsdSinkPlaybackParams = enableAudioOutputPlaybackParameters
                    // PCM sink: FFT tap only, float off. The decimator is
                    // deliberately NOT here — it belongs to the DSD sink.
                    return DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParameters)
                        .setAudioProcessors(arrayOf(waveform.fftProcessor))
                        .build()
                }

                override fun buildAudioRenderers(
                    context: Context,
                    extensionRendererMode: Int,
                    mediaCodecSelector: MediaCodecSelector,
                    enableDecoderFallback: Boolean,
                    audioSink: AudioSink,
                    eventHandler: Handler?,
                    eventListener: AudioRendererEventListener?,
                    out: ArrayList<Renderer>
                ) {
                    // audioSink is the PCM sink from buildAudioSink above.
                    val mediaCodecRenderer = MediaCodecAudioRenderer(
                        context, mediaCodecSelector, enableDecoderFallback,
                        eventHandler, eventListener, audioSink
                    )
                    // DSD sink: decimator + FFT tap, float off. Built here
                    // (not in buildAudioSink) because the factory builds
                    // exactly ONE sink through buildAudioSink and hands it
                    // to buildAudioRenderers as the shared sink — the split
                    // needs a second one, and this override is where the
                    // renderers are assembled.
                    val dsdSink = DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(false)
                        .setEnableAudioOutputPlaybackParameters(dsdSinkPlaybackParams)
                        .setAudioProcessors(
                            arrayOf(DsdDecimationAudioProcessor(ctx.applicationContext), waveform.fftProcessor)
                        )
                        .build()
                    val ffmpegRenderer = if (extensionRendererMode ==
                        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                    ) {
                        null
                    } else {
                        runCatching {
                            FfmpegAudioRenderer(eventHandler, eventListener, dsdSink)
                        }.onFailure {
                            MioLog.event(ctx, "FFmpeg audio renderer unavailable: ${it::class.java.simpleName}: ${it.message}")
                        }.getOrNull()
                    }
                    // EXTENSION_RENDERER_MODE_PREFER (what the app sets):
                    // the FFmpeg renderer goes first so DSD (which only it
                    // handles) is tried before MediaCodec. ON: MediaCodec
                    // first. OFF: MediaCodec only (ffmpegRenderer is null).
                    if (extensionRendererMode ==
                        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER &&
                        ffmpegRenderer != null
                    ) {
                        out.add(ffmpegRenderer)
                        out.add(mediaCodecRenderer)
                    } else {
                        out.add(mediaCodecRenderer)
                        if (ffmpegRenderer != null) out.add(ffmpegRenderer)
                    }
                }
            }.apply {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                setEnableAudioFloatOutput(ENABLE_AUDIO_FLOAT_OUTPUT)
            }
        )
        // v1.1.3 (OOM fix): hard-capped LoadControl — see the companion
        // constants. The default 1.9 local-playback settings let the
        // loader run past the byte target while bufferedDurationUs stays
        // under 1 s; these values make the 8 MiB target absolute.
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    LOAD_CONTROL_MIN_BUFFER_MS, LOAD_CONTROL_MAX_BUFFER_MS,
                    /* bufferForPlaybackMs= */ 1_000,
                    /* bufferForPlaybackAfterRebufferMs= */ 2_000
                )
                .setBufferDurationsMsForLocalPlayback(
                    LOAD_CONTROL_MIN_BUFFER_MS, LOAD_CONTROL_MAX_BUFFER_MS,
                    /* bufferForPlaybackMs= */ 1_000,
                    /* bufferForPlaybackAfterRebufferMs= */ 2_000
                )
                .setBufferDurationsMsForStreaming(
                    LOAD_CONTROL_MIN_BUFFER_MS, LOAD_CONTROL_MAX_BUFFER_MS,
                    /* bufferForPlaybackMs= */ 1_000,
                    /* bufferForPlaybackAfterRebufferMs= */ 2_000
                )
                .setTargetBufferBytes(LOAD_CONTROL_TARGET_BUFFER_BYTES)
                .setPrioritizeTimeOverSizeThresholds(false)
                .setPrioritizeTimeOverSizeThresholdsForLocalPlayback(false)
                .build()
        )
        // There is no setExtractorsFactory on ExoPlayer.Builder (nor a
        // setter on DefaultMediaSourceFactory): the ExtractorsFactory
        // goes in the DefaultMediaSourceFactory constructor.
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                ctx,
                ExtractorsFactory {
                    // DsdExtractor first: it sniffs DSF/DFF and nothing else,
                    // so every other format falls through to the defaults.
                    // The context is only for MioLog diagnostics (parsed
                    // header values / exact parse throw site).
                    arrayOf<Extractor>(
                        DsdExtractor(ctx.applicationContext),
                        *DefaultExtractorsFactory().createExtractors()
                    )
                }
            )
        )
        .build().apply {
        setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
    }
    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()
    private val _index = MutableStateFlow(0); val index: StateFlow<Int> = _index.asStateFlow()
    private val _isPlaying = MutableStateFlow(false); val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _isConvertingDsd = MutableStateFlow(false); val isConvertingDsd: StateFlow<Boolean> = _isConvertingDsd.asStateFlow()
    private val _dsdError = MutableStateFlow<String?>(null); val dsdError: StateFlow<String?> = _dsdError.asStateFlow()
    var replayGainMode: String = "track" // off/track/album
    var crossfadeMs: Long = 0
    val deviceSampleRateHz: Int get() =
        (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
    val currentTrack: Track? get() = queue.value.getOrNull(index.value)
    private var consecutiveErrors = 0
    private var recoveringDsd = false
    private val failedDsd = mutableSetOf<String>()
    private val recoveredDsd = mutableSetOf<String>()
    // v1.1.2 (Fix 4): DSD tracks whose stale cached WAV has already been
    // pruned after a proven native play this session — so STATE_READY
    // firing again (seek / repeat / pause-resume) does not re-log.
    private val nativeDsdCacheCleaned = mutableSetOf<String>()
    // v1.1.3: DSD tracks whose STATE_READY heap line has already been
    // logged this session (see logDsdHeap) — one line per track.
    private val dsdHeapLogged = mutableSetOf<String>()
    private val settings by lazy { SettingsStore(ctx.applicationContext) }
    private val _shuffleEnabled = MutableStateFlow(false); val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()
    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF); val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()
    init {
        // v1.1.5 native-DSD diagnostics (see logNativeDsdProbe /
        // runDsdDecodeProbe below): the probe proves the wiring AND the
        // decoder itself, and the decode listener records what real DSD
        // packets return. Both log to mio-log.txt — the proof for the next
        // user log. The cache prune keeps the DSD WAV cache under its cap
        // even when no new transcode runs (see DsdDecoder.pruneCacheNow).
        logNativeDsdProbe()
        FfmpegAudioDecoder.setDsdDecodeListener(
            object : FfmpegAudioDecoder.DsdDecodeListener {
                override fun onDsdInit(
                    codecName: String, sampleRate: Int, channelCount: Int, outputFloat: Boolean
                ) {
                    MioLog.event(
                        ctx,
                        "DSD decoder init: codec=$codecName sr=$sampleRate ch=$channelCount float=$outputFloat"
                    )
                }

                override fun onDsdDecode(
                    codecName: String, inputSize: Int, result: Int, outputBytes: Int
                ) {
                    // result: >0 = PCM bytes written; 0 = no output for
                    // this packet; -1 = invalid data (packet skipped);
                    // -2 = hard error. Only the first 8 packets per
                    // decoder instance fire (see FfmpegAudioDecoder).
                    MioLog.event(
                        ctx,
                        "DSD decode: codec=$codecName in=${inputSize}B result=$result out=${outputBytes}B"
                    )
                }
            }
        )
        runDsdDecodeProbe()
        scope.launch(Dispatchers.IO) {
            runCatching { DsdDecoder.pruneCacheNow(ctx.applicationContext) }
        }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(v: Boolean) { _isPlaying.value = v }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                _index.value = player.currentMediaItemIndex
            }
            override fun onShuffleModeEnabledChanged(v: Boolean) {
                _shuffleEnabled.value = v
                scope.launch { runCatching { settings.set(SettingsStore.KEY_SHUFFLE, v) } }
            }
            override fun onRepeatModeChanged(mode: Int) {
                _repeatMode.value = mode
                scope.launch { runCatching { settings.set(SettingsStore.KEY_REPEAT, mode) } }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    consecutiveErrors = 0
                    maybePruneStaleDsdCache()
                    maybeLogDsdHeapOnReady()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                MioLog.event(ctx, "Player error: ${error.errorCodeName} ${error.message}")
                // v1.1.5: buffer state at the error — the numbers that say
                // whether the player was starved (loading, nothing
                // buffered) or stalled WITH data queued (decoder/sink
                // produced nothing while the loader had already delivered).
                // The v1.1.2–v1.1.4 StuckPlayerException was the second
                // case: totalBuffered stayed at the 8 MiB cap while
                // isLoading=false, i.e. the loader was done and the
                // decoder had not consumed/emitted. Do NOT "fix" a stall
                // by raising the load-control targets or disabling the
                // stuck detector — the detector is the thing reporting
                // correctly here.
                runCatching {
                    MioLog.event(
                        ctx,
                        "Player error buffer state: state=${player.playbackState} " +
                            "isLoading=${player.isLoading} " +
                            "buffered=${player.totalBufferedDuration}us " +
                            "bufferedPos=${player.bufferedPosition}ms " +
                            "pos=${player.currentPosition}ms " +
                            "duration=${player.duration}ms"
                    )
                }
                // v1.1.1: log the FULL cause chain. v1.1.0 only recorded
                // "Source error" here, which hides the ParserException
                // message (e.g. "DSF data chunk not found") that names the
                // exact failure. Cap the walk so a pathological chain
                // cannot flood the 300-line log.
                var cause: Throwable? = error.cause
                var depth = 0
                while (cause != null && depth < 8) {
                    MioLog.event(ctx, "Player error cause[$depth]: ${cause::class.java.name}: ${cause.message}")
                    cause = cause.cause
                    depth++
                }
                val t = currentTrack
                // Native DSD decode failed for this track (DST-compressed
                // .dff, an unsupported DSF variant, a malformed header, or
                // the vendored decoder missing). Fall back to the FFmpegKit
                // transcode: decode once to a cached PCM WAV and swap it in
                // instead of skipping the track. Only once per track, so a
                // broken file cannot loop forever.
                if (!recoveringDsd && t != null && DsdSupport.isDsd(t.path)
                    && !failedDsd.contains(t.path) && !recoveredDsd.contains(t.path)) {
                    recoveringDsd = true
                    scope.launch {
                        _isConvertingDsd.value = true
                        val decoded = DsdDecoder.ensureDecoded(ctx, t)
                        _isConvertingDsd.value = false
                        val idx = player.currentMediaItemIndex
                        if (decoded != null && queue.value.getOrNull(idx)?.id == t.id) {
                            withContext(Dispatchers.Main) {
                                runCatching {
                                    player.replaceMediaItem(idx, buildItem(t, decoded))
                                    player.prepare(); player.play()
                                }
                            }
                            recoveredDsd.add(t.path)
                            MioLog.event(ctx, "Recovered DSD on error: ${t.title}")
                        } else {
                            if (decoded == null) failedDsd.add(t.path)
                            skipAfterError()
                        }
                        recoveringDsd = false
                    }
                    return
                }
                skipAfterError()
            }
            private fun skipAfterError() {
                consecutiveErrors++
                // One bad file used to stop the whole queue. Skip to the
                // next track instead (up to 3 bad files in a row, then stop).
                if (consecutiveErrors <= 3 && _index.value + 1 < _queue.value.size) {
                    MioLog.event(ctx, "Skipping to next track after error")
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                // Effects were never attached before: attach here, when the
                // session actually exists. The waveform is NO LONGER tapped
                // from this session: v1.1.2 retired the Visualizer-based
                // WaveformTap (it cannot initialise on this phone,
                // error -3) — the live levels now come from the in-process
                // FFT AudioProcessor in the sink chain (waveform.fftProcessor).
                attachEffects()
            }
        })
    }
    /** Start the foreground MediaSession service so Android does not kill
     *  playback when the app goes to the background or the screen locks.
     *  The service was declared in the manifest but never started, so the
     *  music stopped by itself. */
    private fun ensureServiceStarted() {
        runCatching {
            ContextCompat.startForegroundService(ctx, Intent(ctx, PlayerService::class.java))
            MioLog.event(ctx, "Foreground service started")
        }.onFailure { MioLog.event(ctx, "Service start failed: ${it.message}") }
    }
    private fun buildItem(t: Track, uri: Uri): MediaItem = MediaItem.Builder().setUri(uri).setMediaId(t.id.toString())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album).build()).build()
    fun playQueue(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        // Clear the per-track failure sets — a transient failure (low
        // storage, interrupted decode) must not blacklist a track forever.
        ensureServiceStarted()
        failedDsd.clear()
        recoveredDsd.clear()
        nativeDsdCacheCleaned.clear()
        dsdHeapLogged.clear()
        _queue.value = tracks; _index.value = startIndex
        _dsdError.value = null
        MioLog.event(ctx, "playQueue: ${tracks.size} tracks from #$startIndex, first='${tracks[startIndex].title}' dsd=${DsdSupport.isDsd(tracks[startIndex].path)}")
        // v1.1.0 native DSD: every track — DSD included — goes into the
        // playlist as its raw URI and playback starts immediately. There
        // is NO pre-decode any more: no blocking decode of the first DSD
        // track, no fire-and-forget decode of the next track, no
        // background decode loop. DsdExtractor + the vendored FFmpeg DSD
        // decoder play .dsf/.dff directly. A DSD track is therefore
        // assumed playable until the player proves otherwise; if native
        // decode fails, onPlayerError falls back to the FFmpegKit
        // transcode for that track (once), and a track that fails even
        // that is skipped by the generic error path. Non-DSD playback is
        // unchanged: same URIs, same order, same behaviour as before.
        val items = tracks.map { t -> buildItem(t, t.uri) }
        player.setMediaItems(items, startIndex, 0); player.prepare()
        ensureServiceStarted()
        player.play()
        MioLog.event(ctx, "Playback started: '${tracks[startIndex].title}'")
        // v1.1.3: heap baseline for a native DSD start, so the next OOM
        // (if any) can be told apart from a queue that outgrew its cap.
        if (DsdSupport.isDsd(tracks[startIndex].path)) {
            logDsdHeap("start '${tracks[startIndex].title}'")
        }
        applyReplayGain()
    }
    fun play() { ensureServiceStarted(); player.play() }
    fun pause() {
        player.pause()
        // No more PCM reaches the FFT tap while paused: drop to idle
        // bars instead of freezing the last live frame on screen.
        waveform.clearLevels()
    }
    fun next() = player.seekToNextMediaItem()
    fun previous() = player.seekToPreviousMediaItem()
    fun seekTo(ms: Long) = player.seekTo(ms)
    fun setShuffle(on: Boolean) { player.shuffleModeEnabled = on }
    fun setRepeatMode(mode: Int) { player.repeatMode = mode }
    fun cycleRepeat(): Int {
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }; return player.repeatMode
    }
    fun setSpeed(speed: Float) { player.setPlaybackSpeed(speed) }
    private val sleepHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null
    /** Sleep timer: pauses playback after [minutes]. 0 cancels. */
    fun setSleepTimer(minutes: Int) {
        sleepRunnable?.let { sleepHandler.removeCallbacks(it) }; sleepRunnable = null
        if (minutes <= 0) return
        val r = Runnable { pause() }; sleepRunnable = r
        sleepHandler.postDelayed(r, minutes * 60_000L)
    }
    private fun applyReplayGain() {
        val t = currentTrack ?: return
        val gainDb = when (replayGainMode) { "album" -> t.replayGainAlbum; "track" -> t.replayGainTrack; else -> null } ?: 0f
        player.volume = Math.pow(10.0, (gainDb / 20.0).toDouble()).toFloat().coerceIn(0f, 1.5f)
    }
    fun attachEffects() { fx.attach(player.audioSessionId) }

    /**
     * v1.1.2 (Fix 4): when a DSD track reaches STATE_READY, its native
     * playback has proved itself for this session — delete its stale
     * cached transcode WAV (~200 MB each) so the cache shrinks on its
     * own. Guards, in order:
     *  - current track is DSD and not already pruned this session;
     *  - NOT in [recoveredDsd] (the fallback swapped this track to its
     *    WAV — the WAV is still needed) and NOT in [failedDsd];
     *  - no fallback decode is running right now ([recoveringDsd] /
     *    [_isConvertingDsd]) — DsdDecoder also refuses while its own
     *    decode job for the track is in flight;
     *  - the media item actually playing is still the track's RAW uri,
     *    not a swapped-in WAV (a replaceMediaItem to the fallback races
     *    STATE_READY, and this check kills that race even before
     *    recoveredDsd is updated).
     * Best-effort: DsdDecoder.deleteCachedWav swallows errors and logs
     * the result; a missing WAV is simply a no-op (nothing to prune).
     */
    private fun maybePruneStaleDsdCache() {
        val t = currentTrack ?: return
        if (!DsdSupport.isDsd(t.path)) return
        if (recoveredDsd.contains(t.path) || failedDsd.contains(t.path)) return
        if (nativeDsdCacheCleaned.contains(t.path)) return
        if (recoveringDsd || _isConvertingDsd.value) return
        val playingUri = player.currentMediaItem?.localConfiguration?.uri
        if (playingUri != null && playingUri != t.uri) return
        nativeDsdCacheCleaned.add(t.path)
        runCatching { DsdDecoder.deleteCachedWav(ctx, t) }
            .onFailure { MioLog.event(ctx, "DSD cache prune FAILED '${t.title}': ${it::class.java.simpleName} ${it.message}") }
    }
    /**
     * v1.1.3: one heap line per native DSD track when it reaches
     * STATE_READY (same guards as maybePruneStaleDsdCache: DSD, raw URI
     * still playing, not recovered to / failed to the transcode WAV).
     * Together with the "start" line in playQueue this bounds the next
     * OOM diagnosis: if usedMB stays flat while a track plays, the
     * sample queue is capped; if it climbs ~1.4 MB/s (DSD128) the loader
     * is outrunning the cap again.
     */
    private fun maybeLogDsdHeapOnReady() {
        val t = currentTrack ?: return
        if (!DsdSupport.isDsd(t.path)) return
        if (recoveredDsd.contains(t.path) || failedDsd.contains(t.path)) return
        if (dsdHeapLogged.contains(t.path)) return
        if (recoveringDsd || _isConvertingDsd.value) return
        val playingUri = player.currentMediaItem?.localConfiguration?.uri
        if (playingUri != null && playingUri != t.uri) return
        dsdHeapLogged.add(t.path)
        logDsdHeap("ready '${t.title}'")
    }

    /** v1.1.3: heap max/used/free + the effective load-control caps. */
    private fun logDsdHeap(tag: String) {
        runCatching {
            val rt = Runtime.getRuntime()
            val maxMb = rt.maxMemory() / 1_048_576L
            val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576L
            val freeMb = rt.freeMemory() / 1_048_576L
            MioLog.event(
                ctx,
                "DSD mem [$tag]: heap max=${maxMb}MB used=${usedMb}MB free=${freeMb}MB " +
                    "loadControl min=${LOAD_CONTROL_MIN_BUFFER_MS}ms " +
                    "max=${LOAD_CONTROL_MAX_BUFFER_MS}ms " +
                    "target=${LOAD_CONTROL_TARGET_BUFFER_BYTES / 1_048_576}MB"
            )
        }
    }

    fun isDsdCurrent(): Boolean = currentTrack?.let { DsdSupport.isDsd(it.path) } ?: false
    fun release() { scope.cancel(); fx.release(); waveform.release(); player.release() }

    /** v1.1.5 native-DSD wiring probe (see the init call). Logs at app start
     *  so the next user log proves or kills the wiring hypothesis without
     *  guessing. Expected on a working build:
     *  `ffmpegAvailable=true dsd=true dff=true rendererClass=true
     *  hasDsdLsbfPlanar=true ... soShaMatch=true`.
     *  `ffmpegAvailable=false` means libffmpegJNI.so did not load at all;
     *  `dsd=false` with available=true means the .so loaded but has no
     *  DSD decoder (e.g. it was built without the DSD codec or without
     *  the JNI channel-layout patch — see DsdExtractor's KDoc).
     *  `soShaMatch=false` means the loaded .so is NOT the build the app
     *  was verified against (stale/unpatched .so — the v1.1.2 failure
     *  mode); playback continues, but native DSD is suspect until the
     *  .so is rebuilt and this line reads true. */
    private fun logNativeDsdProbe() {
        runCatching {
            val available = FfmpegLibrary.isAvailable()
            val dsd = if (available) FfmpegLibrary.supportsFormat("audio/dsd") else false
            val dff = if (available) FfmpegLibrary.supportsFormat("audio/x-dsd-dff") else false
            val renderer = runCatching {
                Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer")
                true
            }.getOrDefault(false)
            val version = runCatching { FfmpegLibrary.getVersion() }.getOrNull()
            // Per-codec proof: supportsFormat only covers the two MIME
            // types; the .so must contain the planar decoder both now use.
            val hasLsbfPlanar = if (available) FfmpegLibrary.hasDecoderByName("dsd_lsbf_planar") else false
            val hasMsbfPlanar = if (available) FfmpegLibrary.hasDecoderByName("dsd_msbf_planar") else false
            val hasLsbf = if (available) FfmpegLibrary.hasDecoderByName("dsd_lsbf") else false
            val hasMsbf = if (available) FfmpegLibrary.hasDecoderByName("dsd_msbf") else false
            val soShaMatch = logLoadedFfmpegSoSha()
            MioLog.event(
                ctx,
                "Native DSD probe: ffmpegAvailable=$available version=$version dsd=$dsd dff=$dff rendererClass=$renderer " +
                    "hasDsdLsbfPlanar=$hasLsbfPlanar hasDsdMsbfPlanar=$hasMsbfPlanar " +
                    "hasDsdLsbf=$hasLsbf hasDsdMsbf=$hasMsbf float=$ENABLE_AUDIO_FLOAT_OUTPUT soShaMatch=$soShaMatch"
            )
        }.onFailure {
            MioLog.event(ctx, "Native DSD probe FAILED: ${it::class.java.simpleName}: ${it.message}")
        }
    }

    /**
     * v1.1.5: hashes the loaded libffmpegJNI.so and compares it with the
     * expected build (EXPECTED_FFMPEG_SO_SHA256). Logs the hash and the
     * match, returns the match (false when the file cannot be read — the
     * probe line then says soShaMatch=false and native DSD is suspect).
     * Log-only: a mismatch never blocks playback; the FFmpegKit transcode
     * fallback still works.
     */
    private fun logLoadedFfmpegSoSha(): Boolean {
        return runCatching {
            val dir = ctx.applicationInfo.nativeLibraryDir
            val so = File(dir, "libffmpegJNI.so")
            if (!so.exists()) {
                MioLog.event(ctx, "DSD .so check: libffmpegJNI.so NOT FOUND in $dir")
                return false
            }
            val digest = MessageDigest.getInstance("SHA-256")
            so.inputStream().use { input ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } > 0) digest.update(buf, 0, n)
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val match = sha.equals(EXPECTED_FFMPEG_SO_SHA256, ignoreCase = true)
            MioLog.event(
                ctx,
                "DSD .so check: sha256=${sha.take(16)}… expected=${EXPECTED_FFMPEG_SO_SHA256.take(16)}… match=$match size=${so.length()}B"
            )
            match
        }.onFailure {
            MioLog.event(ctx, "DSD .so check FAILED: ${it::class.java.simpleName}: ${it.message}")
            false
        }.getOrDefault(false)
    }

    /**
     * v1.1.5 — the step-1 probe (see DsdDecodeProbe): one synthetic DSD
     * packet per container through a fresh decoder, on a background
     * thread so decoder init (up to ~1 s) never blocks the main thread.
     *
     * The packets are DSD silence (0x69), the value FFmpeg itself uses
     * for DSD silence — content the decoder cannot reject as malformed
     * audio. The DFF packet is run through the exact transform the
     * extractor applies to real packets
     * (DsdExtractor.transformDffPacketToPlanarLsbf), so the probe tests
     * the shipped DFF path byte-for-byte; the DSF packet is the planar
     * layout the extractor delivers untouched (one 4096-byte block per
     * channel). Both use DSD128 rates (the user's failing files) and
     * outputFloat=false — the production int16 path.
     *
     * How to read the lines (see DsdDecodeProbe.KDoc for the full key):
     *  - decodeResult > 0 + gotOutput=true: the decoder WORKS. Any
     *    real-playback failure is then in the extractor's packets or
     *    downstream of the decoder — not the decoder.
     *  - decodeResult == 0: the decoder swallows packets without
     *    emitting (receive-side stall).
     *  - decodeResult == -1: the decoder rejects packets as invalid
     *    data (send-side: wrong codec/layout/init).
     *  - ERROR=...: decoder init failed outright.
     * If EITHER probe fails, the in-ExoPlayer FFmpeg path is not proven
     * and the next step is streaming decode (see the v1.1.5 report) —
     * do not answer it with another sink change.
     */
    private fun runDsdDecodeProbe() {
        if (!FfmpegLibrary.isAvailable()) {
            MioLog.event(ctx, "DSD probe skipped: ffmpeg library not available")
            return
        }
        scope.launch(Dispatchers.Default) {
            runCatching {
                // DSF: 4096 bytes per channel, planar LSBF, 0x69 silence.
                val dsfPacket = ByteArray(4096 * 2) { 0x69.toByte() }
                val dsf = DsdDecodeProbe.probe(
                    DsdExtractor.MIME_TYPE_DSF, dsfPacket,
                    /* sampleRate= */ 705_600, /* channelCount= */ 2,
                    /* outputFloat= */ false
                )
                MioLog.event(ctx, dsf.toLogLine())
                // DFF: 2048 interleaved MSBF bytes -> the extractor's
                // transform -> 2048 planar LSBF bytes (0x69 -> 0x96).
                val dffRaw = ByteArray(2048) { 0x69.toByte() }
                val dffTransformed = ByteArray(2048)
                val dffLen = DsdExtractor.transformDffPacketToPlanarLsbf(
                    dffRaw, dffRaw.size, 2, dffTransformed
                )
                val dff = DsdDecodeProbe.probe(
                    DsdExtractor.MIME_TYPE_DFF,
                    dffTransformed.copyOf(dffLen),
                    /* sampleRate= */ 705_600, /* channelCount= */ 2,
                    /* outputFloat= */ false
                )
                MioLog.event(ctx, dff.toLogLine())
            }.onFailure {
                MioLog.event(ctx, "DSD probe FAILED: ${it::class.java.simpleName}: ${it.message}")
            }
        }
    }
}

/**
 * v1.1.1: decimates the ultra-high-rate PCM that FFmpeg's DSD decoders emit
 * down to a rate AudioTrack can play, by boxcar-averaging [downsampleFactor]
 * consecutive samples per channel.
 *
 * Why this exists: the extractor reports the PCM output rate
 * (sampleRate / 8 — 352800 Hz for DSD64), so the decoder emits PCM at
 * 352800 Hz and up. AudioTrack cannot play 352800 Hz, so without this the
 * sink would fail at AudioTrack init even after the decoder works. This
 * is the same role as mica-music's DsdDecimationAudioProcessor
 * (branch exoplayer-only, Apache 2.0), simplified: same-encoding output
 * (int16 stays int16) and a fixed 176400 / 88200 Hz ladder instead of
 * per-device capability probing — both divide every DSD rate
 * (2822400 / 5644800 / 11289600) exactly, so the boxcar window is always
 * an integer number of samples.
 *
 * Activation is strictly gated: input rate >= 352800 Hz AND float/16-bit
 * PCM AND at least 1 channel. Everything else passes through untouched
 * (configure returns the input format, isActive stays false, the sink
 * skips this processor), so normal FLAC/MP3/PCM playback is unaffected.
 * A rate >= 352800 Hz that divides by neither 176400 nor 88200 also
 * passes through (and logs a warning) rather than guessing a factor.
 *
 * v1.1.5: this processor runs inside DefaultAudioSink's int16 processor
 * chain (setAudioProcessors, DSD sink only — see PlayerManager's
 * renderers factory). That is mica's placement. v1.1.4 ran it inside a
 * ForwardingAudioSink wrapper on the float path instead; the wrapper is
 * deleted in v1.1.5 — the decoder never emitted, so the wrapper was never
 * reached, and float output is off now anyway (DefaultAudioSink drops
 * its chain in float mode, which is why the wrapper existed at all).
 *
 * Only this processor's input/output runs on the playback thread; logging
 * is best-effort via MioLog.
 */
@OptIn(UnstableApi::class)
private class DsdDecimationAudioProcessor(
    private val logContext: Context? = null
) : AudioProcessor {

    private var inputFormat = AudioProcessor.AudioFormat.NOT_SET
    private var outputFormat = AudioProcessor.AudioFormat.NOT_SET
    private var downsampleFactor = 1
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var pendingBytes = ByteArray(0)
    private var pendingSize = 0
    private var inputEnded = false
    private var firstOutputLogged = false

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val shouldProcess = inputAudioFormat.sampleRate >= DSD_RATE_THRESHOLD &&
            (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT ||
                inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) &&
            inputAudioFormat.channelCount > 0
        if (!shouldProcess) {
            clearState()
            return inputAudioFormat
        }
        val targetRate = when {
            inputAudioFormat.sampleRate % TARGET_RATE_HZ == 0 -> TARGET_RATE_HZ
            inputAudioFormat.sampleRate % FALLBACK_RATE_HZ == 0 -> FALLBACK_RATE_HZ
            else -> null
        }
        if (targetRate == null) {
            logContext?.let {
                runCatching {
                    MioLog.event(
                        it,
                        "DSD decimation SKIP: ${inputAudioFormat.sampleRate}Hz does not divide by $TARGET_RATE_HZ/$FALLBACK_RATE_HZ"
                    )
                }
            }
            clearState()
            return inputAudioFormat
        }
        val factor = inputAudioFormat.sampleRate / targetRate
        if (factor < 2) {
            clearState()
            return inputAudioFormat
        }
        inputFormat = inputAudioFormat
        downsampleFactor = factor
        outputFormat = AudioProcessor.AudioFormat(
            targetRate,
            inputAudioFormat.channelCount,
            inputAudioFormat.encoding
        )
        pendingSize = 0
        logContext?.let {
            runCatching {
                MioLog.event(
                    it,
                    "DSD decimation ON: ${inputAudioFormat.sampleRate}Hz enc=${inputAudioFormat.encoding} x$factor -> ${targetRate}Hz"
                )
            }
        }
        return outputFormat
    }

    override fun isActive(): Boolean = outputFormat != AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!isActive()) return
        val bytesPerSample = bytesPerSample(inputFormat.encoding)
        val bytesPerFrame = inputFormat.channelCount * bytesPerSample
        val incoming = inputBuffer.remaining()
        if (incoming == 0) return
        ensurePendingCapacity(pendingSize + incoming)
        inputBuffer.get(pendingBytes, pendingSize, incoming)
        pendingSize += incoming
        val groupBytes = bytesPerFrame * downsampleFactor
        val outputFrames = pendingSize / groupBytes
        if (outputFrames == 0) {
            outputBuffer = AudioProcessor.EMPTY_BUFFER
            return
        }
        val outBytesPerFrame = inputFormat.channelCount * bytesPerSample
        val out = ByteBuffer
            .allocateDirect(outputFrames * outBytesPerFrame)
            .order(ByteOrder.nativeOrder())
        // Absolute reads against the pending bytes (native order, interleaved).
        val pending = ByteBuffer
            .wrap(pendingBytes, 0, outputFrames * groupBytes)
            .order(ByteOrder.nativeOrder())
        val isFloat = inputFormat.encoding == C.ENCODING_PCM_FLOAT
        val channels = inputFormat.channelCount
        repeat(outputFrames) { frame ->
            for (ch in 0 until channels) {
                var sum = 0f
                repeat(downsampleFactor) { k ->
                    val sampleIndex = (frame * downsampleFactor + k) * channels + ch
                    sum += if (isFloat) {
                        pending.getFloat(sampleIndex * 4)
                    } else {
                        pending.getShort(sampleIndex * 2).toFloat() / 32768f
                    }
                }
                val avg = (sum / downsampleFactor).coerceIn(-1f, 1f)
                if (isFloat) {
                    out.putFloat(avg)
                } else {
                    out.putShort(
                        (avg * 32768f).roundToInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            .toShort()
                    )
                }
            }
        }
        // Shift any leftover (partial-group) bytes to the front.
        val consumed = outputFrames * groupBytes
        val leftover = pendingSize - consumed
        if (leftover > 0) {
            pendingBytes.copyInto(pendingBytes, 0, consumed, pendingSize)
        }
        pendingSize = leftover
        out.flip()
        outputBuffer = out
        // v1.1.4: the proof line. The decimator only produces output
        // when the FFmpeg DSD decoder has actually emitted PCM, so this
        // line in the next user log proves native decode really started
        // (and WHEN, via elapsedRealtime) — its absence after a
        // "DSD sink configure" line would prove the decoder emitted
        // nothing instead.
        if (!firstOutputLogged) {
            firstOutputLogged = true
            logContext?.let {
                runCatching {
                    MioLog.event(
                        it,
                        "DSD decimation first output: ${inputFormat.sampleRate}Hz " +
                            "in=${incoming}B -> out=${out.remaining()}B " +
                            "elapsedRealtime=${SystemClock.elapsedRealtime()}ms"
                    )
                }
            }
        }
    }

    override fun queueEndOfStream() {
        // Drop a partial final group (at most factor-1 frames of near
        // silence) rather than emitting a short frame.
        pendingSize = 0
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val pendingOutput = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return pendingOutput
    }

    override fun isEnded(): Boolean =
        inputEnded && pendingSize == 0 && outputBuffer === AudioProcessor.EMPTY_BUFFER

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        pendingSize = 0
        inputEnded = false
    }

    override fun reset() {
        clearState()
    }

    private fun clearState() {
        inputFormat = AudioProcessor.AudioFormat.NOT_SET
        outputFormat = AudioProcessor.AudioFormat.NOT_SET
        downsampleFactor = 1
        firstOutputLogged = false
        flush()
    }

    private fun ensurePendingCapacity(requiredCapacity: Int) {
        if (pendingBytes.size >= requiredCapacity) return
        pendingBytes = pendingBytes.copyOf(
            requiredCapacity.coerceAtLeast(pendingBytes.size * 2).coerceAtLeast(1)
        )
    }

    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_FLOAT -> 4
        C.ENCODING_PCM_16BIT -> 2
        else -> 0
    }

    companion object {
        // FFmpeg emits one PCM sample per packed DSD byte, so DSD64 starts
        // at 352.8 kHz. Same threshold as mica-music.
        private const val DSD_RATE_THRESHOLD = 352_800
        private const val TARGET_RATE_HZ = 176_400
        private const val FALLBACK_RATE_HZ = 88_200
    }
}
