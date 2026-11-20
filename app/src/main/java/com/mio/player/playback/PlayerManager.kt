package com.mio.player.playback
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
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
    val waveform = WaveformTap()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val player: ExoPlayer = ExoPlayer.Builder(ctx)
        .setRenderersFactory(
            DefaultRenderersFactory(ctx).apply {
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
                    arrayOf<Extractor>(
                        DsdExtractor.Factory().createExtractor(),
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
}
