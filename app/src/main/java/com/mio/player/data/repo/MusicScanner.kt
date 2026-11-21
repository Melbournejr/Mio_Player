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
 *  Metadata comes from MediaStore where available; for DSD the sample
 *  rate (and the duration when MediaStore has none) is read from the
 *  file header itself — DSF fmt chunk, DFF PROP/FS chunk — so the label
 *  is the file's real rate (DSD64/128/256), never a hardcoded guess. */
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
                // FIX 2 (v1.1.1, extended v1.1.3): MediaStore reports
                // duration 0 for .dsf files — parse the DSD header so the
                // library (and the lyrics lookup) has a real duration.
                // v1.1.3: the header is read for EVERY DSD file, not only
                // when duration is 0, because it also supplies the true
                // sample rate. The old code hardcoded dsdRate = "DSD64"
                // and only corrected it in the duration-0 branch, so a
                // DSD128 file whose duration MediaStore already knew (and
                // every .dff, whose header was never read) was labelled
                // "DSD64 · Native decode". A header that cannot be read
                // leaves dsdRate null -> the UI shows plain "DSD"
                // (Track.displayFormat), never a guessed "DSD64".
                var durationMs = c.getLong(dI)
                var dsdRate: String? = null
                if (isDsd) {
                    val info = dsdInfoForFile(path)
                        ?: dsdInfoForUri(contentUri, path.endsWith(".dff", ignoreCase = true))
                    if (info != null) {
                        if (durationMs <= 0L) durationMs = info.first
                        dsdRate = DsdDisplay.dsdRateFor(info.second)
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
                    // FIX 2 (v1.1.1, extended v1.1.3): SAF tracks got
                    // durationMs = 0; parse the DSD header (.dsf AND .dff)
                    // so the library has a real duration (also used by the
                    // lyrics lookup) AND the real sample rate — see
                    // scanMediaStore. An unreadable header leaves dsdRate
                    // null -> UI shows "DSD", never a guessed "DSD64".
                    var durationMs = 0L
                    var dsdRate: String? = null
                    if (isDsd) {
                        dsdInfoForUri(f.uri, name.endsWith(".dff", ignoreCase = true))?.let {
                            durationMs = it.first
                            dsdRate = DsdDisplay.dsdRateFor(it.second)
                        }
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

    // --- DSD header rate + duration (FIX 2, v1.1.1 DSF; v1.1.3 DFF) ---
    // Both parsers return (durationMs, dsdSampleRateHz) or null. Every
    // failure returns null so a bad header can never break the scan.
    // DSF layout: DSD chunk ("DSD " + u64 size + u64 file size + u64
    // metadata ptr = 28 bytes), then the fmt chunk: "fmt " (4) + u64
    // chunk size (8) + payload: version u32, format ID u32, channel
    // type u32, channel count u32, sample rate u32 (payload offset 16),
    // bits per sample u32, sample count u64 (payload offset 24),
    // block size u32, reserved u32. durationMs =
    // sampleCount * 1000 / sampleRate.
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

    /** (durationMs, dsdSampleRateHz) for a DSD file, dispatching on
     *  extension: .dff -> [dffInfo], anything else DSD -> [dsfInfo]. */
    private fun dsdInfoForFile(path: String): Pair<Long, Int>? = runCatching {
        val f = File(path)
        if (!f.isFile || !f.canRead()) return@runCatching null
        f.inputStream().use { if (path.endsWith(".dff", ignoreCase = true)) dffInfo(it) else dsfInfo(it) }
    }.getOrNull()

    private fun dsdInfoForUri(uri: Uri, isDff: Boolean): Pair<Long, Int>? = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { if (isDff) dffInfo(it) else dsfInfo(it) }
    }.getOrNull()

    // --- DFF header rate + duration (v1.1.3) ---
    // DFF (DSDIFF) layout, big-endian throughout — mirrors
    // DsdExtractor.parseDff/parseProp (same chunk IDs, same widths):
    //   "FRM8" + u64 form size + "DSD " form type, then chunks, each =
    //   4-byte id + u64 BE size + payload. The PROP chunk payload is
    //   "SND " + sub-chunks, each = 4-byte id + u64 BE size + payload;
    //   FS carries the sample rate as u32 BE, CHNL the channel count as
    //   u16 BE, CMPR "DST " means DST-compressed. Note the FS id is
    //   "FS  " (space-padded) — trim it, as the 1.1.2 extractor fix does.
    //   The "DSD " chunk's size is the audio data size in bytes, so
    //   durationMs = dataSize * 8 / channels * 1000 / sampleRate.
    //   DST-compressed files have no plain DSD data chunk, so duration
    //   cannot be derived here -> null (label falls back to "DSD").
    //   The scan stops at the data chunk; at most 1 MiB of chunk headers
    //   and skipped payload is walked, so a corrupt file cannot loop
    //   the scan forever.
    private fun dffInfo(stream: InputStream): Pair<Long, Int>? = runCatching {
        fun readExact(buf: ByteArray): Boolean {
            var read = 0
            while (read < buf.size) {
                val n = stream.read(buf, read, buf.size - read)
                if (n < 0) return false
                read += n
            }
            return true
        }
        fun u32BE(b: ByteArray, off: Int): Long =
            ((b[off].toLong() and 0xff) shl 24) or ((b[off + 1].toLong() and 0xff) shl 16) or
            ((b[off + 2].toLong() and 0xff) shl 8) or (b[off + 3].toLong() and 0xff)
        fun u64BE(b: ByteArray, off: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xff)
            return v
        }
        fun skipFully(n: Long): Boolean {
            var remaining = n
            while (remaining > 0) {
                val skipped = stream.skip(remaining)
                if (skipped <= 0) {
                    // skip() may return 0; consume one byte to progress.
                    if (stream.read() < 0) return false
                    remaining--
                } else remaining -= skipped
            }
            return true
        }
        val formHead = ByteArray(12)
        if (!readExact(formHead)) return@runCatching null
        if (String(formHead, 0, 4, Charsets.US_ASCII) != "FRM8") return@runCatching null
        if (String(formHead, 8, 4, Charsets.US_ASCII) != "DSD ") return@runCatching null
        var sampleRate = 0
        var channels = 0
        var dstCompressed = false
        var scannedBytes = 0L
        val chunkHead = ByteArray(12)
        while (true) {
            if (!readExact(chunkHead)) return@runCatching null
            scannedBytes += 12
            if (scannedBytes > 1024 * 1024L) return@runCatching null
            val id = String(chunkHead, 0, 4, Charsets.US_ASCII)
            val size = u64BE(chunkHead, 4)
            if (size < 0) return@runCatching null
            when (id) {
                "PROP" -> {
                    if (size > 1024 * 1024L) return@runCatching null
                    val payload = ByteArray(size.toInt())
                    if (!readExact(payload)) return@runCatching null
                    scannedBytes += size
                    if (payload.size < 4 || String(payload, 0, 4, Charsets.US_ASCII) != "SND ") return@runCatching null
                    var p = 4
                    while (p + 12 <= payload.size) {
                        val subId = String(payload, p, 4, Charsets.US_ASCII).trim()
                        val subSize = u64BE(payload, p + 4)
                        if (subSize < 0 || subSize > (payload.size - (p + 12)).toLong()) break
                        val v = p + 12
                        when (subId) {
                            "FS" -> if (subSize >= 4) sampleRate = u32BE(payload, v).toInt()
                            "CHNL" -> if (subSize >= 2) channels = ((payload[v].toInt() and 0xff) shl 8) or (payload[v + 1].toInt() and 0xff)
                            "CMPR" -> if (subSize >= 4) dstCompressed = String(payload, v, 4, Charsets.US_ASCII) == "DST "
                        }
                        p += 12 + subSize.toInt()
                    }
                }
                "DSD " -> {
                    if (dstCompressed) return@runCatching null
                    if (sampleRate <= 0 || channels !in 1..8 || size <= 0L) return@runCatching null
                    val durationMs = size * 8L / channels * 1000L / sampleRate
                    if (durationMs <= 0L) return@runCatching null
                    return@runCatching Pair(durationMs, sampleRate)
                }
                "DST " -> return@runCatching null // DST-compressed: no plain data chunk
                else -> {
                    if (!skipFully(size)) return@runCatching null
                    scannedBytes += size
                }
            }
        }
    }.getOrNull()
}
