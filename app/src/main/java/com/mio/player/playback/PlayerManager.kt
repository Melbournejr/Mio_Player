package com.mio.player.playback
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.AudioManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
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
    val player: ExoPlayer = ExoPlayer.Builder(ctx)
        .setRenderersFactory(
            // v1.1.4: the DSD decimation processor MUST run on the float
            // path too — see DsdFloatAudioSink. DefaultAudioSink's
            // setAudioProcessors() only fills the DefaultAudioProcessor
            // Chain, which Media3 1.9 EXCLUDES from the pipeline
            // whenever float output is in use (DefaultAudioSink.
            // configure: the chain is only added in the non-float
            // branch; the Builder doc says "Audio processing ... will
            // not be available when float output is in use"). The
            // decoder outputs float PCM at 705600 Hz for DSD128, so
            // with the decimator dropped the sink tried to open an
            // AudioTrack at 705600 Hz — impossible (Android caps at
            // 192 kHz) — and native DSD never produced a single frame:
            // BUFFERING, loader stopped at the 8 MiB cap ("not
            // loading"), StuckPlayerException after ~4 s. The wrapper
            // runs [decimator, FFT tap] itself, ahead of the delegate
            // sink, for every PCM format — float output stays ON (EQ
            // path untouched) and the AudioTrack now opens at 176400 Hz.
            object : DefaultRenderersFactory(ctx) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioOutputPlaybackParameters: Boolean
                ): AudioSink? {
                    val delegate = DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParameters)
                        .build()
                    return DsdFloatAudioSink(
                        delegate,
                        ctx.applicationContext,
                        waveform.fftProcessor
                    )
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
                if (state == Player.STATE_READY) {
                    consecutiveErrors = 0
                    maybePruneStaleDsdCache()
                    maybeLogDsdHeapOnReady()
                }
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
 * v1.1.4 (Fix 1 — native-DSD stuck error): runs the DSD decimator (and
 * the FFT waveform tap) on the FLOAT output path, where Media3 1.9
 * silently drops DefaultAudioSink's whole custom processor chain.
 *
 * ROOT CAUSE (proven by reading Media3 1.9.0 source, not by guessing):
 *  - DefaultAudioSink.Builder.setAudioProcessors() only fills the
 *    DefaultAudioProcessorChain.
 *  - DefaultAudioSink.configure() builds the processing pipeline as
 *    [trimming, channelMapping] + (float ? [toFloat] : [toInt16, chain]).
 *    In float mode the chain — our DsdDecimationAudioProcessor and the
 *    FFT tap — is NOT in the pipeline at all. The Builder doc says it
 *    plainly: "Audio processing ... will not be available when float
 *    output is in use."
 *  - The FFmpeg DSD decoder always outputs float here
 *    (FfmpegAudioRenderer.shouldOutputFloat: the sink reports PCM 16-bit
 *    AND float at 705600 Hz as SUPPORTED_DIRECTLY — the support check
 *    never looks at the sample rate — so float wins), at the raw PCM
 *    rate: 705600 Hz for the user's DSD128 .dff.
 *  - With the decimator dropped, the sink's after-processing format
 *    stayed 705600 Hz float, so it tried to open an AudioTrack at
 *    705600 Hz. Android AudioTrack caps at 192 kHz, so no audio path
 *    can ever come up, the renderer never becomes ready, the player
 *    sits in BUFFERING, the loader stops at the 8 MiB cap ("not
 *    loading"), and ExoPlayer fires StuckPlayerException. The decoder
 *    itself was fine — FFmpeg's DSD decoder emits one PCM frame per
 *    input packet, immediately, with no pre-roll — but its output
 *    never reached an AudioTrack.
 *  - Corroboration in the v1.1.3 user log: the "DSD decimation ON"
 *    line is ABSENT (the decimator's configure() never ran), while the
 *    FFT tap did log — but only for the 16-bit MP3 / fallback-WAV
 *    tracks, where the chain IS included.
 *  - The 8 MiB load-control cap (v1.1.3 OOM fix) is NOT the cause and
 *    is left untouched: the renderer never consumed anything, so the
 *    queue never drained and the loader never resumed. With the sink
 *    fixed, 8 MiB is ~5.7 s of DSD128 against a 1 s ready threshold,
 *    and local-disk refill takes milliseconds.
 *
 * FIX: this ForwardingAudioSink owns a two-processor chain
 * [DsdDecimationAudioProcessor, fftTap] and runs it BEFORE the delegate
 * DefaultAudioSink, for every PCM format — the same workaround
 * mica-music uses for its hi-res float path (MicaFloatDspAudioSink).
 * The chain is driven directly (not via Media3's AudioProcessingPipeline:
 * that helper's constructor takes a Guava ImmutableList and :app does
 * not depend on Guava, and with two processors that each emit at most
 * one output buffer per input the direct chain is exact). Float output stays enabled (ENABLE_AUDIO_FLOAT_OUTPUT untouched, EQ
 * path untouched — the delegate still creates the AudioTrack on the
 * same audio session, so AudioEffectsManager attaches exactly as
 * before), and the delegate now sees 176400 Hz float for DSD, a rate
 * AudioTrack accepts. Non-DSD audio is unaffected: the decimator
 * passes everything below 352800 Hz through untouched, the FFT tap
 * already ran on the 16-bit path, and the processor order relative to
 * the delegate's own silence/speed processors is unchanged (wrapper
 * first, then the delegate's chain).
 *
 * The wrapper mirrors DefaultAudioSink's own retain-and-retry contract:
 * the delegate may return false from handleBuffer (AudioTrack not
 * ready / backpressure) and then keeps the exact buffer object — so
 * the wrapper retains its pending output and re-presents the SAME
 * object on retry, never re-processing input that was already
 * consumed. End-of-stream drains the chain into the delegate
 * before the delegate's playToEndOfStream runs.
 *
 * Logging (the proof for the next user log): configure() logs the
 * input -> output rate mapping whenever the decimator changes the
 * rate, and the decimator logs its first output with a timestamp.
 * If native DSD really starts, both lines MUST appear. If only the
 * configure line appears, the decoder is producing PCM and the
 * AudioTrack is the problem. If neither appears, the decoder never
 * emitted.
 */
@OptIn(UnstableApi::class)
private class DsdFloatAudioSink(
    delegate: AudioSink,
    private val logContext: Context?,
    private val fftTap: AudioProcessor
) : ForwardingAudioSink(delegate) {

    private val decimator = DsdDecimationAudioProcessor(logContext)
    private var chainNeedsFlush = false
    private var processingEnabled = false
    private var inputBuffer: ByteBuffer? = null
    private var inputAccessUnitCount = 0
    private var outputBuffer: ByteBuffer? = null

    private fun log(msg: String) {
        val ctx = logContext ?: return
        runCatching { MioLog.event(ctx, msg) }
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        // Non-PCM (offload / passthrough) has no processor chain: pass
        // straight through to the delegate.
        if (inputFormat.sampleMimeType != MimeTypes.AUDIO_RAW ||
            !Util.isEncodingLinearPcm(inputFormat.pcmEncoding)
        ) {
            processingEnabled = false
            chainNeedsFlush = false
            inputBuffer = null
            outputBuffer = null
            super.configure(inputFormat, specifiedBufferSize, outputChannels)
            return
        }
        val inFormat = AudioProcessor.AudioFormat(inputFormat)
        val midFormat = decimator.configure(inFormat)
        val outFormat = fftTap.configure(midFormat)
        chainNeedsFlush = true
        processingEnabled = true
        val delegateFormat = inputFormat.buildUpon()
            .setSampleRate(outFormat.sampleRate)
            .setChannelCount(outFormat.channelCount)
            .setPcmEncoding(outFormat.encoding)
            .build()
        super.configure(delegateFormat, specifiedBufferSize, outputChannels)
        // Log only when the decimator actually changes the rate — that
        // is exactly the DSD case, and the AudioTrack rate is what used
        // to be impossible (705600 Hz) before this fix.
        if (outFormat.sampleRate != inFormat.sampleRate) {
            log(
                "DSD sink configure: ${inFormat.sampleRate}Hz enc=${inFormat.encoding} " +
                    "ch=${inFormat.channelCount} -> ${outFormat.sampleRate}Hz " +
                    "enc=${outFormat.encoding} ch=${outFormat.channelCount}"
            )
        }
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        if (!processingEnabled) {
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        if (chainNeedsFlush) {
            // configure() stages the new format; flush both processors
            // before the first buffer of the new stream — same
            // lazy-flush rule as DefaultAudioSink (which flushes its
            // pipeline inside handleBuffer before processing).
            decimator.flush()
            fftTap.flush()
            chainNeedsFlush = false
        }
        if (inputBuffer == null) {
            // First sight of this buffer. An empty buffer is consumed.
            if (!buffer.hasRemaining()) return true
            inputBuffer = buffer
            inputAccessUnitCount = encodedAccessUnitCount
        }
        // Else: retry of the same buffer after backpressure — the
        // retained inputBuffer is the same object; do NOT re-queue it.
        processBuffers(presentationTimeUs)
        if (inputBuffer?.hasRemaining() == false) {
            inputBuffer = null
            inputAccessUnitCount = 0
            return true
        }
        return false
    }

    /**
     * Runs one input buffer through [decimator] then [fftTap] and returns
     * the chain output (possibly empty). Each processor emits at most one
     * output buffer per input, and the tap is a pure passthrough, so a
     * single pass is exact — no pull loop is needed.
     */
    private fun runChain(input: ByteBuffer): ByteBuffer {
        var buffer = input
        if (decimator.isActive()) {
            decimator.queueInput(buffer)
            buffer = decimator.output
        }
        if (fftTap.isActive()) {
            fftTap.queueInput(buffer)
            buffer = fftTap.output
        }
        return buffer
    }

    /**
     * Mirrors DefaultAudioSink.processBuffers: drain retained output
     * first, then feed more input — never feeding input while undrained
     * output exists.
     */
    private fun processBuffers(presentationTimeUs: Long) {
        drainOutputBuffer(presentationTimeUs)
        if (outputBuffer != null) return
        val input = inputBuffer ?: return
        if (!input.hasRemaining()) return
        val out = runChain(input)
        if (out.hasRemaining()) {
            outputBuffer = out
            drainOutputBuffer(presentationTimeUs)
        }
    }

    private fun drainOutputBuffer(presentationTimeUs: Long) {
        val out = outputBuffer ?: return
        if (!out.hasRemaining()) {
            outputBuffer = null
            return
        }
        val handled = try {
            super.handleBuffer(out, presentationTimeUs, inputAccessUnitCount)
        } catch (e: AudioSink.InitializationException) {
            // The delegate could not open its AudioTrack — log the
            // failure and let the error surface instead of stalling
            // silently into another StuckPlayerException.
            log("DSD sink AudioTrack init FAILED: ${e::class.java.simpleName}: ${e.message}")
            throw e
        }
        if (handled) {
            // The delegate consumed the whole buffer (it only returns
            // true once its retained input is fully written).
            outputBuffer = null
        }
        // Else: the delegate retained this exact buffer object and will
        // keep draining it on retry; keep outputBuffer so the next call
        // re-presents the same object.
    }

    override fun playToEndOfStream() {
        if (processingEnabled) {
            // End the chain first: the decimator drops its partial
            // group, so the final pull below is normally empty — run it
            // anyway, then drain anything retained to the delegate
            // before the delegate ends its own stream.
            decimator.queueEndOfStream()
            fftTap.queueEndOfStream()
            val finalOut = if (fftTap.isActive()) fftTap.output
                else if (decimator.isActive()) decimator.output
                else AudioProcessor.EMPTY_BUFFER
            if (finalOut.hasRemaining() && outputBuffer == null) {
                outputBuffer = finalOut
            }
            drainOutputBuffer(C.TIME_END_OF_SOURCE)
        }
        super.playToEndOfStream()
    }

    override fun isEnded(): Boolean {
        if (!processingEnabled) return super.isEnded()
        if (!decimator.isEnded() || !fftTap.isEnded()) return false
        if (outputBuffer?.hasRemaining() == true) return false
        if (inputBuffer?.hasRemaining() == true) return false
        return super.isEnded()
    }

    override fun flush() {
        // Also the renderer's seek path: clear both processors and drop
        // retained buffers so the next stream starts clean.
        decimator.flush()
        fftTap.flush()
        chainNeedsFlush = false
        inputBuffer = null
        inputAccessUnitCount = 0
        outputBuffer = null
        super.flush()
    }

    override fun reset() {
        decimator.reset()
        fftTap.reset()
        chainNeedsFlush = false
        processingEnabled = false
        inputBuffer = null
        outputBuffer = null
        super.reset()
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
 * v1.1.4: this processor NO LONGER runs via DefaultAudioSink's
 * setAudioProcessors — Media3 1.9 excludes that whole chain on the
 * float path, so the decimator never ran for DSD at all (the v1.1.3
 * stuck error). It now runs inside DsdFloatAudioSink, ahead of the
 * delegate sink, for every PCM format.
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
