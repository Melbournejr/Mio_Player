package com.mio.player.data.model
import android.net.Uri
enum class AudioFormat { MP3, FLAC, WAV, AAC, ALAC, APE, OGG, DSD, UNKNOWN }
data class Track(
    val id: Long, val title: String, val artist: String, val album: String,
    val albumId: Long = 0, val artistId: Long = 0,
    val uri: Uri, val path: String, val durationMs: Long,
    val format: AudioFormat, val sampleRateHz: Int = 0, val bitDepth: Int = 0,
    val bitrateKbps: Int = 0, val dsdRate: String? = null, // "DSD64" / "DSD128" / "DSD256"; null = unknown, shown as "DSD"
    val replayGainTrack: Float? = null, val replayGainAlbum: Float? = null,
    val isFavorite: Boolean = false, val playCount: Int = 0,
    val lastPlayedAt: Long = 0, val dateAdded: Long = 0,
    /** Local path of the cover art downloaded by the auto-metadata
     *  fetch (v1.1.10), or null — then AlbumArt falls back to the
     *  MediaStore album art / the MusicNote placeholder as before. */
    val coverPath: String? = null
) {
    val displayFormat: String get() = when {
        // Native DSD (v1.1.0): DSD plays directly, not via a PCM WAV.
        // The "-> PCM ..." label now lives only on the transcode fallback
        // (see DsdDisplay.pcmRateFor / DsdSupport.fallbackFormatLabel).
        format == AudioFormat.DSD -> "${dsdRate ?: "DSD"} · Native decode"
        sampleRateHz > 0 -> "$format ${sampleRateHz/1000.0}kHz" + if (bitDepth>0) " ${bitDepth}bit" else ""
        else -> format.name
    }
    /** Display label with a live-rate fallback (v1.1.4, Fix 2).
     *  Prefer the stored [dsdRate]; if it is null — the row was scanned
     *  before the v1.1.4 DFF-header fix, or the header was unreadable at
     *  scan time — use [liveDsdRate], the label the player parsed for
     *  this track while playing it (see
     *  DsdSupport.dsdRateFromPcmSampleRateHz in data.audio). A null live rate still
     *  shows plain "DSD", never a guess. Non-DSD tracks are unchanged.
     *  Hook (PlayerManager / UI, not this file): pass the live label for
     *  the CURRENT track only, derived from the Format of the track
     *  actually playing — Format.sampleRate is the PCM rate (DSD rate /
     *  8) and only when its MIME is "audio/dsd" or "audio/x-dsd-dff"
     *  and the playing URI is still the raw .dsf/.dff (not a swapped-in
     *  transcode WAV, whose 176.4 kHz must never be read as a DSD rate).
     *  Call sites today: NowPlaying.kt (t?.displayFormat) and the mini
     *  player in Common.kt ("${t.artist} • ${t.displayFormat}"). */
    fun displayFormatWithLiveRate(liveDsdRate: String?): String = when {
        format != AudioFormat.DSD -> displayFormat
        else -> "${dsdRate ?: liveDsdRate ?: "DSD"} · Native decode"
    }
}
object DsdDisplay {
    /** PCM rate of the cached WAV produced by the transcode FALLBACK only.
     *  The normal (native) path never produces PCM at this rate. */
    fun pcmRateFor(dsdRate: String?): String = when (dsdRate) {
        "DSD128" -> "352.8kHz"; "DSD256" -> "705.6kHz"; else -> "176.4kHz" // DSD64
    }
    /** DSD rate label from the true DSD sample rate read out of the file
     *  header (MusicScanner, v1.1.3): 2822400 -> DSD64, 5644800 -> DSD128,
     *  11289600 -> DSD256. A rate of 0 or below a real DSD rate means the
     *  rate is genuinely unknown -> "DSD", never a guessed "DSD64". */
    fun dsdRateFor(sampleRateHz: Int): String = when {
        sampleRateHz >= 11289600 -> "DSD256"; sampleRateHz >= 5644800 -> "DSD128"
        sampleRateHz >= 2822400 -> "DSD64"; else -> "DSD"
    }
}
data class Album(val id: Long, val title: String, val artist: String, val trackCount: Int, val year: Int = 0, val artUri: Uri? = null)
data class Artist(val id: Long, val name: String, val trackCount: Int, val albumCount: Int)
data class Playlist(val id: Long, val name: String, val trackIds: List<Long> = emptyList(), val createdAt: Long = System.currentTimeMillis())
data class LyricLine(val timeMs: Long, val text: String)
data class Lyrics(val trackId: Long, val synced: List<LyricLine>, val plain: String?, val source: String)
