package com.mio.player.playback
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import com.mio.player.data.audio.AudioEffectsManager
import com.mio.player.data.audio.WaveformTap
import com.mio.player.data.audio.DsdDecoder
import com.mio.player.data.audio.DsdSupport
import com.mio.player.data.model.Track
import com.mio.player.data.prefs.SettingsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
/** Gapless, high-res playback.
 *  - No custom FFmpeg renderer: androidx.media3:media3-decoder-ffmpeg is
 *    NOT published to Maven (see DsdSupport.kt), so DSD is decoded to a
 *    cached PCM WAV by DsdDecoder (FFmpegKit) before playback instead.
 *  - Gapless: ExoPlayer handles gapless for same-format consecutive tracks.
 *  - High-res: AudioTrack is configured via ExoPlayer defaults; the device
 *    sample rate is read from AudioManager and exposed for the Now Playing
 *    "source -> device" label. Crossfade is implemented as a short
 *    volume ramp is NOT faked — see crossfadeMs: ExoPlayer has no built-in
 *    crossfade, so crossfade is applied by the service fading volume over
 *    the last crossfadeMs of a track (PlayerService, best-effort).
 *  - ReplayGain: track/album gain applied as volume = 10^(gain/20), clamped.
 */
class PlayerManager(private val ctx: Context) {
    val fx = AudioEffectsManager()
    val waveform = WaveformTap()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val player: ExoPlayer = ExoPlayer.Builder(ctx).build().apply {
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
    private var playGeneration = 0
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
                consecutiveErrors++
                // One bad file used to stop the whole queue. Skip to the
                // next track instead (up to 3 bad files in a row, then stop).
                if (consecutiveErrors <= 3 && _index.value + 1 < _queue.value.size) {
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
        }
    }
    private fun buildItem(t: Track, uri: Uri): MediaItem = MediaItem.Builder().setUri(uri).setMediaId(t.id.toString())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album).build()).build()
    private suspend fun resolveUri(t: Track): Uri {
        if (!DsdSupport.isDsd(t.path)) return t.uri
        _isConvertingDsd.value = true
        val decoded = DsdDecoder.ensureDecoded(ctx, t)
        _isConvertingDsd.value = false
        if (decoded == null) _dsdError.value = "Could not decode DSD file: ${t.title}"
        return decoded ?: t.uri
    }
    /** Swap a background-decoded DSD file into the playlist, only if the
     *  queue has not changed since and the track has not played yet. */
    private fun swapIn(generation: Int, t: Track, i: Int, decoded: Uri) {
        if (generation != playGeneration) return
        if (i <= player.currentMediaItemIndex || i >= player.mediaItemCount) return
        if (player.getMediaItemAt(i).mediaId != t.id.toString()) return
        runCatching { player.replaceMediaItem(i, buildItem(t, decoded)) }
    }
    fun playQueue(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        _queue.value = tracks; _index.value = startIndex
        _dsdError.value = null
        val generation = ++playGeneration
        scope.launch {
            // Decode ONLY the track that starts playing, then start
            // playback right away. The old code decoded the whole queue
            // first: on a DSD album that took minutes, while the
            // foreground service (started before the decode) must call
            // startForeground within ~10s — Android killed the app with
            // ForegroundServiceDidNotStartInTimeException ("tự thoát").
            // The service is therefore started only now, right before
            // play(), and the remaining DSD tracks decode in the
            // background and are swapped in as each one finishes.
            val startUri = withContext(Dispatchers.IO) { resolveUri(tracks[startIndex]) }
            if (generation != playGeneration) return@launch
            val items = tracks.mapIndexed { i, t -> buildItem(t, if (i == startIndex) startUri else t.uri) }
            player.setMediaItems(items, startIndex, 0); player.prepare()
            ensureServiceStarted()
            player.play()
            applyReplayGain()
            withContext(Dispatchers.IO) {
                for (i in tracks.indices) {
                    if (generation != playGeneration) return@withContext
                    if (i == startIndex || !DsdSupport.isDsd(tracks[i].path)) continue
                    val decoded = DsdDecoder.ensureDecoded(ctx, tracks[i])
                    if (decoded != null) withContext(Dispatchers.Main) { swapIn(generation, tracks[i], i, decoded) }
                }
            }
        }
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
