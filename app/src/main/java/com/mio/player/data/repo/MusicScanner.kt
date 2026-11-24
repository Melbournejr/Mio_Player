package com.mio.player.data.repo
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.mio.player.data.audio.DsdSupport
import com.mio.player.data.db.TrackEntity
import com.mio.player.data.log.MioLog
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
                    } else {
                        // v1.1.4: a DSD file whose header cannot be read
                        // shows plain "DSD" — log it so the next user log
                        // names the file instead of failing silently.
                        MioLog.event(ctx, "DSD header unreadable (label falls back to DSD): $path")
                    }
                }
                // v1.1.36 — the real file specs (sample rate / bit depth
                // / bitrate) for FLAC/WAV/MP3. The scan never read them,
                // so Track.sampleRateHz stayed 0 and the UI could only
                // show the DEVICE output rate ("48 kHz") next to the
                // format — the user read that as the file being "only
                // 48kHz". An unreadable header leaves the specs 0 and
                // the UI falls back to the plain format name, as before.
                var sampleRateHz = 0; var bitDepth = 0; var bitrateKbps = 0
                if (!isDsd) {
                    val specs = specsForFile(path, durationMs)
                        ?: specsForUri(contentUri, ext, File(path).length(), durationMs)
                    if (specs != null) {
                        sampleRateHz = specs.sampleRateHz; bitDepth = specs.bitDepth; bitrateKbps = specs.bitrateKbps
                        if (durationMs <= 0L && specs.durationMs > 0L) durationMs = specs.durationMs
                    }
                }
                out += TrackEntity(id = id, title = c.getString(tI) ?: path.substringAfterLast('/'),
                    artist = c.getString(aI) ?: "Unknown Artist", album = c.getString(alI) ?: "Unknown Album",
                    uri = contentUri.toString(), path = path,
                    durationMs = durationMs, format = formatFor(path).name,
                    sampleRateHz = sampleRateHz, bitDepth = bitDepth, bitrateKbps = bitrateKbps,
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
                        val info = dsdInfoForUri(f.uri, name.endsWith(".dff", ignoreCase = true))
                        if (info != null) {
                            durationMs = info.first
                            dsdRate = DsdDisplay.dsdRateFor(info.second)
                        } else {
                            // v1.1.4: see scanMediaStore — log the file so
                            // a still-unreadable header is diagnosable.
                            MioLog.event(ctx, "DSD header unreadable (label falls back to DSD): $relPath")
                        }
                    }
                    // v1.1.36 — same real file specs as scanMediaStore
                    // (FLAC/WAV/MP3 header). Also fills the duration for
                    // SAF FLAC/WAV, which was 0 before (the lyrics lookup
                    // uses it).
                    var sampleRateHz = 0; var bitDepth = 0; var bitrateKbps = 0
                    if (!isDsd) {
                        val specs = specsForUri(f.uri, name.substringAfterLast('.', "").lowercase(), f.length(), durationMs)
                        if (specs != null) {
                            sampleRateHz = specs.sampleRateHz; bitDepth = specs.bitDepth; bitrateKbps = specs.bitrateKbps
                            if (durationMs <= 0L && specs.durationMs > 0L) durationMs = specs.durationMs
                        }
                    }
                    out += TrackEntity(id = (f.uri.toString().hashCode().toLong() and 0xffffffffL) or (1L shl 40),
                        title = name.substringBeforeLast('.'), artist = "Unknown Artist", album = "Unknown Album",
                        uri = f.uri.toString(), path = relPath, durationMs = durationMs,
                        sampleRateHz = sampleRateHz, bitDepth = bitDepth, bitrateKbps = bitrateKbps,
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

    // --- DFF header rate + duration (v1.1.3, header fixed v1.1.4) ---
    // DFF (DSDIFF) layout, big-endian throughout — mirrors
    // DsdExtractor.parseDff/parseProp (same chunk IDs, same widths):
    //   "FRM8" + u64 form size + "DSD " form type (a 16-byte form
    //   header: 4 + 8 + 4), then chunks, each = 4-byte id + u64 BE
    //   size + payload. The PROP chunk payload is "SND " + sub-chunks,
    //   each = 4-byte id + u64 BE size + payload; FS carries the sample
    //   rate as u32 BE, CHNL the channel count as u16 BE, CMPR "DST "
    //   means DST-compressed. Note the FS id is "FS  " (space-padded) —
    //   trim it, as the 1.1.2 extractor fix does.
    //   v1.1.4 ROOT CAUSE (why every .dff showed plain "DSD" in
    //   v1.1.3): this function read only a 12-byte form header and
    //   checked for "DSD " at offset 8. But offset 8 is inside the
    //   u64 form size — the "DSD " form type sits at offset 12, after
    //   the full 8-byte size. Bytes 8..11 are the LOW 4 bytes of the
    //   form size, which for A1.Hello.dff are 0x18E97E44 (file
    //   ~398 MiB), never "DSD " — so the check failed for every .dff,
    //   dffInfo() always returned null, and dsdRate stayed null even
    //   though DsdExtractor (which consumes the 4-byte "FRM8" magic
    //   first and then reads 12 more bytes = 16 total) parsed the
    //   same file fine ("DFF header OK: ch=2 dsdRate=5644800").
    //   The DSF path was unaffected — dsfInfo() checks "fmt " at the
    //   correct offset 28 — which is why only .dff labels were wrong.
    //   The "DSD " chunk's size is the audio data size in bytes, so
    //   durationMs = dataSize * 8 / channels * 1000 / sampleRate.
    //   DST-compressed files have no plain DSD data chunk, so duration
    //   cannot be derived here -> null (label falls back to "DSD").
    //   The scan stops at the data chunk; at most 1 MiB of chunk headers
    //   and skipped payload is walked, so a corrupt file cannot loop
    //   the scan forever.
    private fun dffInfo(stream: InputStream): Pair<Long, Int>? = runCatching<Pair<Long, Int>?> {
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
        val formHead = ByteArray(16)
        if (!readExact(formHead)) return@runCatching null
        if (String(formHead, 0, 4, Charsets.US_ASCII) != "FRM8") return@runCatching null
        if (String(formHead, 12, 4, Charsets.US_ASCII) != "DSD ") return@runCatching null
        var sampleRate = 0
        var channels = 0
        var dstCompressed = false
        var scannedBytes = 0L
        val chunkHead = ByteArray(12)
        while (scannedBytes <= 1024 * 1024L) {
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
        null
    }.getOrNull()

    // --- File specs for FLAC / WAV / MP3 (v1.1.36) ---
    // The scan never read the sample rate of a non-DSD file, so
    // Track.sampleRateHz stayed 0 and the UI could only show the
    // DEVICE output rate ("48 kHz") next to the format — the user
    // read that as the file being "only 48kHz". These parsers read
    // the real rate / bit depth from the file header (STREAMINFO for
    // FLAC, fmt chunk for WAV, first frame for MP3), following the
    // same runCatching -> null pattern as the DSD parsers: an
    // unreadable header leaves the specs 0 and the scan never breaks.
    // durationMs is filled when the header can derive it (FLAC total
    // samples, WAV data size) — the SAF scan had 0 for those.
    // bitrateKbps is the true average (file size / duration) when
    // both are known, else the header bitrate (MP3 frame, WAV byteRate).
    data class AudioSpecs(val sampleRateHz: Int, val bitDepth: Int, val bitrateKbps: Int, val durationMs: Long = 0L)

    private fun specsForStream(stream: InputStream, ext: String, fileSizeBytes: Long, durationMs: Long): AudioSpecs? = when (ext) {
        "flac" -> flacSpecs(stream, fileSizeBytes)
        "wav" -> wavSpecs(stream, fileSizeBytes)
        "mp3" -> mp3Specs(stream, fileSizeBytes, durationMs)
        else -> null
    }

    private fun specsForFile(path: String, durationMs: Long): AudioSpecs? = runCatching {
        val f = File(path)
        if (!f.isFile || !f.canRead()) return@runCatching null
        f.inputStream().use { specsForStream(it, path.substringAfterLast('.', "").lowercase(), f.length(), durationMs) }
    }.getOrNull()

    private fun specsForUri(uri: Uri, ext: String, fileSizeBytes: Long, durationMs: Long): AudioSpecs? = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { specsForStream(it, ext, fileSizeBytes, durationMs) }
    }.getOrNull()

    /** FLAC: "fLaC" + block header (type 0 = STREAMINFO, length 34)
     *  + STREAMINFO. Rate / channels / bit depth / total samples are
     *  packed big-endian in STREAMINFO bytes 10..17 (20 + 3 + 5 + 36
     *  bits). */
    private fun flacSpecs(stream: InputStream, fileSizeBytes: Long): AudioSpecs? = runCatching {
        val h = ByteArray(42)
        var read = 0
        while (read < h.size) {
            val n = stream.read(h, read, h.size - read)
            if (n < 0) return@runCatching null
            read += n
        }
        if (String(h, 0, 4, Charsets.US_ASCII) != "fLaC") return@runCatching null
        if ((h[4].toInt() and 0x7f) != 0) return@runCatching null // first block must be STREAMINFO
        val blockLen = ((h[5].toInt() and 0xff) shl 16) or ((h[6].toInt() and 0xff) shl 8) or (h[7].toInt() and 0xff)
        if (blockLen != 34) return@runCatching null
        // STREAMINFO data starts at file offset 8; packed field at +10.
        val b10 = h[18].toLong() and 0xff; val b11 = h[19].toLong() and 0xff; val b12 = h[20].toLong() and 0xff
        val b13 = h[21].toLong() and 0xff; val b14 = h[22].toLong() and 0xff; val b15 = h[23].toLong() and 0xff
        val b16 = h[24].toLong() and 0xff; val b17 = h[25].toLong() and 0xff
        val sampleRate = ((b10 shl 12) or (b11 shl 4) or (b12 shr 4)).toInt()
        val bitDepth = (((b12 and 0x1) shl 4) or (b13 shr 4)).toInt() + 1
        val totalSamples = ((b13 and 0xf) shl 32) or (b14 shl 24) or (b15 shl 16) or (b16 shl 8) or b17
        if (sampleRate <= 0 || bitDepth <= 0) return@runCatching null
        val durMs = if (totalSamples > 0) totalSamples * 1000L / sampleRate else 0L
        val bitrate = if (fileSizeBytes > 0 && durMs > 0) (fileSizeBytes * 8L / durMs).toInt() else 0
        AudioSpecs(sampleRate, bitDepth, bitrate, durMs)
    }.getOrNull()

    /** WAV: "RIFF" + "WAVE", then chunks (id + u32 LE size + payload,
     *  word-aligned). The "fmt " chunk carries sampleRate / byteRate /
     *  bitsPerSample; the "data" chunk size gives the duration. */
    private fun wavSpecs(stream: InputStream, fileSizeBytes: Long): AudioSpecs? = runCatching {
        fun readExact(buf: ByteArray): Boolean {
            var read = 0
            while (read < buf.size) {
                val n = stream.read(buf, read, buf.size - read)
                if (n < 0) return false
                read += n
            }
            return true
        }
        fun u16LE(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)
        fun u32LE(b: ByteArray, off: Int): Long =
            (b[off].toLong() and 0xff) or ((b[off + 1].toLong() and 0xff) shl 8) or
            ((b[off + 2].toLong() and 0xff) shl 16) or ((b[off + 3].toLong() and 0xff) shl 24)
        fun skipFully(n: Long): Boolean {
            var remaining = n
            while (remaining > 0) {
                val skipped = stream.skip(remaining)
                if (skipped <= 0) {
                    if (stream.read() < 0) return false
                    remaining--
                } else remaining -= skipped
            }
            return true
        }
        val head = ByteArray(12)
        if (!readExact(head)) return@runCatching null
        if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF") return@runCatching null
        if (String(head, 8, 4, Charsets.US_ASCII) != "WAVE") return@runCatching null
        var sampleRate = 0; var bitDepth = 0; var byteRate = 0L; var dataSize = 0L
        var scanned = 0L
        val chunkHead = ByteArray(8)
        while (scanned <= 8192L) {
            if (!readExact(chunkHead)) return@runCatching null
            scanned += 8
            val id = String(chunkHead, 0, 4, Charsets.US_ASCII)
            val size = u32LE(chunkHead, 4)
            if (size < 0 || size > (1L shl 31)) return@runCatching null
            if (id == "fmt ") {
                if (size < 16) return@runCatching null
                val fmt = ByteArray(16)
                if (!readExact(fmt)) return@runCatching null
                sampleRate = u32LE(fmt, 4).toInt()
                byteRate = u32LE(fmt, 8)
                bitDepth = u16LE(fmt, 14)
                // Skip the rest of the fmt payload (+1 pad byte if odd).
                if (!skipFully((size - 16) + (size and 1L))) return@runCatching null
                scanned += size + (size and 1L)
            } else if (id == "data") {
                dataSize = size
                break
            } else {
                if (!skipFully(size + (size and 1L))) return@runCatching null
                scanned += size + (size and 1L)
            }
            if (sampleRate > 0 && dataSize > 0) break
        }
        if (sampleRate <= 0 || byteRate <= 0) return@runCatching null
        val durMs = if (dataSize > 0) dataSize * 1000L / byteRate else 0L
        val bitrate = (byteRate * 8L / 1000L).toInt()
        AudioSpecs(sampleRate, bitDepth, bitrate, durMs)
    }.getOrNull()

    /** MP3: skip an ID3v2 tag if present (its size is syncsafe and
     *  the tag can hold large embedded art, so it is skipped via the
     *  stream, never buffered), then scan for the first frame sync
     *  (0xFF + top 3 bits set). Layer III only — the music case.
     *  Bitrate prefers file size + duration (true average, VBR-safe);
     *  the frame header bitrate is the fallback. No bit depth for
     *  lossy MP3. */
    private fun mp3Specs(stream: InputStream, fileSizeBytes: Long, durationMs: Long): AudioSpecs? = runCatching {
        fun skipFully(n: Long): Boolean {
            var remaining = n
            while (remaining > 0) {
                val skipped = stream.skip(remaining)
                if (skipped <= 0) {
                    if (stream.read() < 0) return false
                    remaining--
                } else remaining -= skipped
            }
            return true
        }
        fun readExact(buf: ByteArray): Boolean {
            var read = 0
            while (read < buf.size) {
                val n = stream.read(buf, read, buf.size - read)
                if (n < 0) return false
                read += n
            }
            return true
        }
        val head = ByteArray(10)
        if (!readExact(head)) return@runCatching null
        var prefix = head
        if (String(head, 0, 3, Charsets.US_ASCII) == "ID3") {
            val size = ((head[6].toInt() and 0x7f) shl 21) or ((head[7].toInt() and 0x7f) shl 14) or
                ((head[8].toInt() and 0x7f) shl 7) or (head[9].toInt() and 0x7f)
            if (size < 0) return@runCatching null
            if (!skipFully(size.toLong())) return@runCatching null
            prefix = ByteArray(0)
        }
        val buf = ByteArray(4096 + prefix.size)
        System.arraycopy(prefix, 0, buf, 0, prefix.size)
        var r = prefix.size
        while (r < buf.size) {
            val n = stream.read(buf, r, buf.size - r)
            if (n < 0) break
            r += n
        }
        val mpeg1Rates = intArrayOf(44100, 48000, 32000)
        val mpeg2Rates = intArrayOf(22050, 24000, 16000)
        val mpeg25Rates = intArrayOf(11025, 12000, 8000)
        val mpeg1Br = intArrayOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        val mpeg2Br = intArrayOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
        var i = 0
        while (i + 4 <= r) {
            if ((buf[i].toInt() and 0xff) == 0xFF && (buf[i + 1].toInt() and 0xe0) == 0xe0) {
                val b1 = buf[i + 1].toInt() and 0xff
                val b2 = buf[i + 2].toInt() and 0xff
                val versionBits = (b1 shr 3) and 0x3 // 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5, 1 = reserved
                val layerBits = (b1 shr 1) and 0x3 // 1 = Layer III
                val brIdx = (b2 shr 4) and 0xf
                val srIdx = (b2 shr 2) and 0x3
                if (versionBits != 1 && layerBits == 1 && brIdx in 1..14 && srIdx != 3) {
                    val rates = when (versionBits) { 3 -> mpeg1Rates; 2 -> mpeg2Rates; 0 -> mpeg25Rates; else -> null }
                    val brTab = if (versionBits == 3) mpeg1Br else mpeg2Br
                    val sr = rates?.getOrNull(srIdx) ?: 0
                    val br = brTab.getOrNull(brIdx - 1) ?: 0
                    if (sr > 0 && br > 0) {
                        val avgBr = if (fileSizeBytes > 0 && durationMs > 0) (fileSizeBytes * 8L / durationMs).toInt() else 0
                        return@runCatching AudioSpecs(sr, 0, if (avgBr > 0) avgBr else br, 0L)
                    }
                }
            }
            i++
        }
        null
    }.getOrNull()
}
