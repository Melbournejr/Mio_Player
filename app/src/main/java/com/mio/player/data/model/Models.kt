package com.mio.player.data.model
import android.net.Uri
enum class AudioFormat { MP3, FLAC, WAV, AAC, ALAC, APE, OGG, DSD, UNKNOWN }
data class Track(
    val id: Long, val title: String, val artist: String, val album: String,
    val albumId: Long = 0, val artistId: Long = 0,
    val uri: Uri, val path: String, val durationMs: Long,
    val format: AudioFormat, val sampleRateHz: Int = 0, val bitDepth: Int = 0,
    val bitrateKbps: Int = 0, val dsdRate: String? = null, // "DSD64" / "DSD128"
    val replayGainTrack: Float? = null, val replayGainAlbum: Float? = null,
    val isFavorite: Boolean = false, val playCount: Int = 0,
    val lastPlayedAt: Long = 0, val dateAdded: Long = 0
) {
    val displayFormat: String get() = when {
        format == AudioFormat.DSD -> "${dsdRate ?: "DSD"} → PCM ${DsdDisplay.pcmRateFor(dsdRate)}"
        sampleRateHz > 0 -> "$format ${sampleRateHz/1000.0}kHz" + if (bitDepth>0) " ${bitDepth}bit" else ""
        else -> format.name
    }
}
object DsdDisplay {
    fun pcmRateFor(dsdRate: String?): String = when (dsdRate) {
        "DSD128" -> "352.8kHz"; "DSD256" -> "705.6kHz"; else -> "176.4kHz" // DSD64
    }
    fun dsdRateFor(sampleRateHz: Int): String = when {
        sampleRateHz >= 11289600 -> "DSD256"; sampleRateHz >= 5644800 -> "DSD128"; else -> "DSD64"
    }
}
data class Album(val id: Long, val title: String, val artist: String, val trackCount: Int, val year: Int = 0, val artUri: Uri? = null)
data class Artist(val id: Long, val name: String, val trackCount: Int, val albumCount: Int)
data class Playlist(val id: Long, val name: String, val trackIds: List<Long> = emptyList(), val createdAt: Long = System.currentTimeMillis())
data class LyricLine(val timeMs: Long, val text: String)
data class Lyrics(val trackId: Long, val synced: List<LyricLine>, val plain: String?, val source: String)
