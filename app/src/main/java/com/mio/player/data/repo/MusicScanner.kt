package com.mio.player.data.repo
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.mio.player.data.audio.DsdSupport
import com.mio.player.data.db.TrackEntity
import com.mio.player.data.model.AudioFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
/** Scans MediaStore (all audio on device) + user-picked SAF folders.
 *  Supported: FLAC, WAV, MP3, AAC, ALAC (m4a), APE, OGG, DSD (.dsf/.dff).
 *  Metadata comes from MediaStore where available; DSD sample-rate is
 *  inferred from file size/duration only as a label — the real rate is
 *  reported by the FFmpeg decoder at playback time (see DsdSupport). */
class MusicScanner(private val ctx: Context) {
    private val supported = setOf("flac","wav","mp3","aac","m4a","alac","ape","ogg","dsf","dff")
    fun formatFor(path: String): AudioFormat = when (path.substringAfterLast('.', "").lowercase()) {
        "mp3" -> AudioFormat.MP3; "flac" -> AudioFormat.FLAC; "wav" -> AudioFormat.WAV
        "aac" -> AudioFormat.AAC; "m4a", "alac" -> AudioFormat.ALAC; "ape" -> AudioFormat.APE
        "ogg" -> AudioFormat.OGG; "dsf", "dff" -> AudioFormat.DSD; else -> AudioFormat.UNKNOWN
    }
    suspend fun scanMediaStore(): List<TrackEntity> = withContext(Dispatchers.IO) {
        val out = mutableListOf<TrackEntity>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.MIME_TYPE)
        ctx.contentResolver.query(uri, proj, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null)?.use { c ->
            val idI = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val tI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val aI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val alI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val dI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val pI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            while (c.moveToNext()) {
                val path = c.getString(pI) ?: continue
                val ext = path.substringAfterLast('.', "").lowercase()
                if (ext !in supported) continue
                val id = c.getLong(idI)
                val isDsd = DsdSupport.isDsd(path)
                out += TrackEntity(id = id, title = c.getString(tI) ?: path.substringAfterLast('/'),
                    artist = c.getString(aI) ?: "Unknown Artist", album = c.getString(alI) ?: "Unknown Album",
                    uri = ContentUris.withAppendedId(uri, id).toString(), path = path,
                    durationMs = c.getLong(dI), format = formatFor(path).name,
                    dsdRate = if (isDsd) "DSD64" else null, dateAdded = System.currentTimeMillis())
            }
        }
        out
    }
    /** SAF folder scan (user picks a folder in Settings; persist its Uri). */
    suspend fun scanSafFolder(treeUri: Uri): List<TrackEntity> = withContext(Dispatchers.IO) {
        val out = mutableListOf<TrackEntity>()
        fun walk(dir: DocumentFile) {
            for (f in dir.listFiles()) {
                if (f.isDirectory) walk(f)
                else if (f.isFile && f.name?.substringAfterLast('.', "")?.lowercase() in supported) {
                    val name = f.name ?: continue
                    out += TrackEntity(id = (f.uri.toString().hashCode().toLong() and 0xffffffffL) or (1L shl 40),
                        title = name.substringBeforeLast('.'), artist = "Unknown Artist", album = "Unknown Album",
                        uri = f.uri.toString(), path = name, durationMs = 0,
                        format = formatFor(name).name, dsdRate = if (DsdSupport.isDsd(name)) "DSD64" else null)
                }
            }
        }
        DocumentFile.fromTreeUri(ctx, treeUri)?.let { walk(it) }
        out
    }
}
