package com.mio.player.playback
import android.content.Context
import android.media.AudioManager
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import com.mio.player.data.audio.AudioEffectsManager
import com.mio.player.data.audio.DsdDecoder
import com.mio.player.data.audio.DsdSupport
import com.mio.player.data.model.Track
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
    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(v: Boolean) { _isPlaying.value = v }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                _index.value = player.currentMediaItemIndex
            }
        })
    }
    fun playQueue(tracks: List<Track>, startIndex: Int = 0) {
        _queue.value = tracks; _index.value = startIndex
        _dsdError.value = null
        scope.launch {
            val items = tracks.map { t ->
                val uri = if (DsdSupport.isDsd(t.path)) {
                    _isConvertingDsd.value = true
                    val decoded = DsdDecoder.ensureDecoded(ctx, t)
                    _isConvertingDsd.value = false
                    if (decoded == null) {
                        _dsdError.value = "Could not decode DSD file: ${t.title}"
                        t.uri
                    } else decoded
                } else t.uri
                MediaItem.Builder().setUri(uri).setMediaId(t.id.toString())
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album).build())
                    .build()
            }
            player.setMediaItems(items, startIndex, 0); player.prepare(); player.play()
            applyReplayGain()
        }
    }
    fun play() = player.play()
    fun pause() = player.pause()
    fun next() = player.seekToNextMediaItem()
    fun previous() = player.seekToPreviousMediaItem()
    fun seekTo(ms: Long) = player.seekTo(ms)
    fun setShuffle(on: Boolean) { player.shuffleModeEnabled = on }
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
    fun release() { scope.cancel(); fx.release(); player.release() }
}
