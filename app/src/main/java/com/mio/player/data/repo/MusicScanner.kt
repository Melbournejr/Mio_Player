package com.mio.player.data.repo
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.mio.player.data.audio.DsdSupport
import com.mio.player.data.db.TrackEntity
import com.mio.player.data.model.AudioFormat
import com.mio.player.data.model.DsdDisplay
import java.io.File
import java.io.InputStream
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
                val contentUri = ContentUris.withAppendedId(uri, id)
                // FIX 2 (v1.1.1): MediaStore reports duration 0 for .dsf
                // files — parse the DSF header so the library (and the
                // lyrics lookup) has a real duration.
                var durationMs = c.getLong(dI)
                var dsdRate = if (isDsd) "DSD64" else null
                if (isDsd && durationMs <= 0L) {
                    val dsf = dsfInfoForFile(path) ?: dsfInfoForUri(contentUri)
                    if (dsf != null) {
                        durationMs = dsf.first
                        dsdRate = DsdDisplay.dsdRateFor(dsf.second)
                    }
                }
                out += TrackEntity(id = id, title = c.getString(tI) ?: path.substringAfterLast('/'),
                    artist = c.getString(aI) ?: "Unknown Artist", album = c.getString(alI) ?: "Unknown Album",
                    uri = contentUri.toString(), path = path,
                    durationMs = durationMs, format = formatFor(path).name,
                    dsdRate = dsdRate, dateAdded = System.currentTimeMillis())
            }
        }
        out
    }
    /** SAF folder scan (user picks a folder in Settings; persist its Uri).
     *  The relative folder path is kept in [TrackEntity.path] so the
     *  Library can group songs by folder, like the MediaStore scan does
     *  with the full file path. */
    suspend fun scanSafFolder(treeUri: Uri): List<TrackEntity> = withContext(Dispatchers.IO) {
        val out = mutableListOf<TrackEntity>()
        fun walk(dir: DocumentFile, folder: String) {
            for (f in dir.listFiles()) {
                if (f.isDirectory) walk(f, if (folder.isEmpty()) (f.name ?: "") else "$folder/${f.name}")
                else if (f.isFile && f.name?.substringAfterLast('.', "")?.lowercase() in supported) {
                    val name = f.name ?: continue
                    val relPath = if (folder.isEmpty()) name else "$folder/$name"
                    val isDsd = DsdSupport.isDsd(name)
                    // FIX 2 (v1.1.1): SAF tracks got durationMs = 0; for
                    // .dsf parse the header so the library has a real
                    // duration (also used by the lyrics lookup).
                    var durationMs = 0L
                    var dsdRate = if (isDsd) "DSD64" else null
                    if (isDsd) {
                        dsfInfoForUri(f.uri)?.let { durationMs = it.first; dsdRate = DsdDisplay.dsdRateFor(it.second) }
                    }
                    out += TrackEntity(id = (f.uri.toString().hashCode().toLong() and 0xffffffffL) or (1L shl 40),
                        title = name.substringBeforeLast('.'), artist = "Unknown Artist", album = "Unknown Album",
                        uri = f.uri.toString(), path = relPath, durationMs = durationMs,
                        format = formatFor(name).name, dsdRate = dsdRate)
                }
            }
        }
        DocumentFile.fromTreeUri(ctx, treeUri)?.let { walk(it, "") }
        out
    }

    // --- DSF header duration (FIX 2, v1.1.1) ---
    // DSF layout: DSD chunk ("DSD " + u64 size + u64 file size + u64
    // metadata ptr = 28 bytes), then the fmt chunk: "fmt " (4) + u64
    // chunk size (8) + payload: version u32, format ID u32, channel
    // type u32, channel count u32, sample rate u32 (payload offset 16),
    // bits per sample u32, sample count u64 (payload offset 24),
    // block size u32, reserved u32. durationMs =
    // sampleCount * 1000 / sampleRate. DFF parsing is intentionally
    // skipped (different chunk layout). Every failure returns null so
    // a bad header can never break the scan.
    private fun dsfInfo(stream: InputStream): Pair<Long, Int>? = runCatching {
        val h = ByteArray(92)
        var read = 0
        while (read < h.size) {
            val n = stream.read(h, read, h.size - read)
            if (n < 0) return@runCatching null
            read += n
        }
        if (String(h, 0, 4, Charsets.US_ASCII) != "DSD ") return@runCatching null
        if (String(h, 28, 4, Charsets.US_ASCII) != "fmt ") return@runCatching null
        fun u32LE(off: Int): Long =
            (h[off].toLong() and 0xff) or ((h[off + 1].toLong() and 0xff) shl 8) or
            ((h[off + 2].toLong() and 0xff) shl 16) or ((h[off + 3].toLong() and 0xff) shl 24)
        fun u64LE(off: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = v or ((h[off + i].toLong() and 0xff) shl (8 * i))
            return v
        }
        // fmt payload starts at absolute offset 40 (after "fmt " + u64 size)
        val sampleRate = u32LE(40 + 16).toInt()
        val sampleCount = u64LE(40 + 24)
        if (sampleRate <= 0 || sampleCount <= 0L) return@runCatching null
        Pair(sampleCount * 1000L / sampleRate, sampleRate)
    }.getOrNull()

    private fun dsfInfoForFile(path: String): Pair<Long, Int>? = runCatching {
        val f = File(path)
        if (!f.isFile || !f.canRead()) return@runCatching null
        f.inputStream().use { dsfInfo(it) }
    }.getOrNull()

    private fun dsfInfoForUri(uri: Uri): Pair<Long, Int>? = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { dsfInfo(it) }
    }.getOrNull()
}
