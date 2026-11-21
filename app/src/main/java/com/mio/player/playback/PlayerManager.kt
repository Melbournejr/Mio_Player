package com.mio.player.playback
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
/** Gapless, high-res playback.
 *  - Native DSD (v1.1.0): .dsf/.dff are played directly — DsdExtractor
 *    frames the raw DSD bytes and the vendored FFmpeg DSD decoder
 *    (extension renderer, EXTENSION_RENDERER_MODE_PREFER, float output)
 *    decodes them in-process. DSD tracks go into the playlist as raw
 *    URIs, so playback starts immediately with no pre-decode wait.
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
         * DSD decoders output float PCM, so float AudioTrack output must be
         * on for the native DSD path to run at full quality.
         *
         * RISK — EQ vs float output: the app's AudioEffect-based EQ
         * (AudioEffectsManager, attached to the audio session) may NOT
         * process float output — on some devices / Android versions the
         * EQ silently does nothing while float output is on. If the EQ
         * stops having any effect, flip this to false: DSD still plays
         * (decoder output is converted to 16-bit PCM) and the EQ works
         * again. Re-test the EQ on a DSD track after any change here.
         */
        const val ENABLE_AUDIO_FLOAT_OUTPUT = true
    }
    val fx = AudioEffectsManager()
    val waveform = WaveformTap(context = ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val player: ExoPlayer = ExoPlayer.Builder(ctx)
        .setRenderersFactory(
            // v1.1.1: anonymous subclass so the audio sink gets the DSD
            // decimation processor — DefaultRenderersFactory has no
            // setAudioProcessors; the sink is built in buildAudioSink.
            // The processor passes everything below 352800 Hz through
            // untouched, so normal (non-DSD) audio is unaffected.
            object : DefaultRenderersFactory(ctx) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioOutputPlaybackParameters: Boolean
                ): AudioSink? {
                    return DefaultAudioSink.Builder(context)
                        .setAudioProcessors(arrayOf<AudioProcessor>(DsdDecimationAudioProcessor(ctx.applicationContext)))
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParameters)
                        .build()
                }
            }.apply {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                setEnableAudioFloatOutput(ENABLE_AUDIO_FLOAT_OUTPUT)
            }
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
    private val settings by lazy { SettingsStore(ctx.applicationContext) }
    private val _shuffleEnabled = MutableStateFlow(false); val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()
    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF); val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()
    init {
        // v1.1.1 native-DSD probe: proves (or kills) the wiring hypothesis
        // in the next user log. getCodecName() is package-private static so
        // it cannot be called from here; supportsFormat() covers it (it
        // calls getCodecName + ffmpegHasDecoder internally).
        logNativeDsdProbe()
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
                if (state == Player.STATE_READY) consecutiveErrors = 0
            }
            override fun onPlayerError(error: PlaybackException) {
                MioLog.event(ctx, "Player error: ${error.errorCodeName} ${error.message}")
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
                // session actually exists, and tap the same session for the
                // live waveform on the Now Playing screen.
                attachEffects()
                waveform.attach(audioSessionId)
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
        applyReplayGain()
    }
    fun play() { ensureServiceStarted(); player.play() }
    fun pause() = player.pause()
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
    fun isDsdCurrent(): Boolean = currentTrack?.let { DsdSupport.isDsd(it.path) } ?: false
    fun release() { scope.cancel(); fx.release(); waveform.release(); player.release() }

    /** v1.1.1 native-DSD wiring probe (see the init call). Logs one line
     *  at app start so the next user log proves or kills the wiring
     *  hypothesis without guessing. Expected on a working build:
     *  `ffmpegAvailable=true dsd=true dff=true rendererClass=true`.
     *  `ffmpegAvailable=false` means libffmpegJNI.so did not load at all;
     *  `dsd=false` with available=true means the .so loaded but has no
     *  DSD decoder (e.g. it was built without the DSD codec or without
     *  the JNI channel-layout patch — see DsdExtractor's KDoc). */
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
            MioLog.event(
                ctx,
                "Native DSD probe: ffmpegAvailable=$available version=$version dsd=$dsd dff=$dff rendererClass=$renderer"
            )
        }.onFailure {
            MioLog.event(ctx, "Native DSD probe FAILED: ${it::class.java.simpleName}: ${it.message}")
        }
    }
}

/**
 * v1.1.1: decimates the ultra-high-rate PCM that FFmpeg's DSD decoders emit
 * down to a rate AudioTrack can play, by boxcar-averaging [downsampleFactor]
 * consecutive samples per channel.
 *
 * Why this exists: the extractor now reports the PCM output rate
 * (sampleRate / 8 — 352800 Hz for DSD64), so the decoder emits float PCM
 * at 352800 Hz. AudioTrack cannot play 352800 Hz, so without this the
 * sink would fail at AudioTrack init even after the decoder works. This
 * is the same role as mica-music's DsdDecimationAudioProcessor
 * (branch exoplayer-only, Apache 2.0), simplified: same-encoding output
 * (float stays float, so the EQ/float path is unchanged) and a fixed
 * 176400 / 88200 Hz ladder instead of per-device capability probing —
 * both divide every DSD rate (2822400 / 5644800 / 11289600) exactly, so
 * the boxcar window is always an integer number of samples.
 *
 * Activation is strictly gated: input rate >= 352800 Hz AND float/16-bit
 * PCM AND at least 1 channel. Everything else passes through untouched
 * (configure returns the input format, isActive stays false, the sink
 * skips this processor), so normal FLAC/MP3/PCM playback is unaffected.
 * A rate >= 352800 Hz that divides by neither 176400 nor 88200 also
 * passes through (and logs a warning) rather than guessing a factor.
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
