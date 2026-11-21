package com.mio.player.data.repo

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.util.Locale

/**
 * v1.1.6 — embedded lyrics, read from the audio file's own tags.
 *
 * Android's MediaMetadataRetriever has NO public key for USLT/SYLT
 * (that is why the old comment said embedded lyrics "are NOT read"),
 * so the tags are parsed by hand from the file bytes instead:
 *  - MP3: ID3v2 USLT (unsynced text) and SYLT (timestamped lines).
 *  - FLAC: Vorbis comment LYRICS / UNSYNCEDLYRICS (often LRC text).
 *  - OGG: same Vorbis comment, found by walking Ogg pages.
 * DSD (.dsf/.dff) is deliberately NOT covered: DSF can carry an ID3v2
 * tag at the end of the file, but in practice local DSD rips almost
 * never have embedded lyrics — sidecar .lrc / LRCLIB cover those.
 * M4A (©lyr atom) is not covered either; same rarity, and the MP4 box
 * walk is a separate parser. Both are logged as a miss, never a crash.
 *
 * Everything here is best-effort and bounded (at most 2 MiB of tag
 * bytes are ever read, every failure returns null), so a corrupt tag
 * can never break playback — the caller just falls through to the
 * remote sources. A hit is cached in Room by LyricsRepository, so the
 * file is parsed at most once per track.
 */
object EmbeddedLyrics {
    data class Result(val syncedLrc: String?, val plainText: String?)

    private const val MAX_TAG_BYTES = 2 * 1024 * 1024L

    fun read(ctx: Context, audioPath: String?, audioUri: String?): Result? {
        val ext = (audioPath ?: audioUri ?: "").substringAfterLast('.', "").lowercase()
        val stream = openStream(ctx, audioPath, audioUri) ?: return null
        return runCatching {
            stream.use {
                when (ext) {
                    "mp3" -> readId3v2(it)
                    "flac" -> readFlac(it)
                    "ogg" -> readOgg(it)
                    else -> null
                }
            }
        }.getOrNull()
    }

    /** SAF tracks have only a RELATIVE path (MusicScanner keeps the
     *  folder-relative path in TrackEntity.path), so File(path) never
     *  resolves for them — open through the track's content Uri
     *  instead. MediaStore tracks have a real absolute path; the Uri
     *  works for those too, so content:// wins whenever it is present. */
    private fun openStream(ctx: Context, audioPath: String?, audioUri: String?): InputStream? =
        runCatching {
            if (!audioUri.isNullOrBlank() && audioUri.startsWith("content://")) {
                ctx.contentResolver.openInputStream(Uri.parse(audioUri))
            } else if (!audioPath.isNullOrBlank()) {
                val f = File(audioPath)
                if (f.isFile && f.canRead()) f.inputStream() else null
            } else null
        }.getOrNull()

    // --- helpers -------------------------------------------------------
    private fun readExact(input: InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun skipFully(input: InputStream, n: Long): Boolean {
        var remaining = n
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped
            else if (input.read() < 0) return false else remaining--
        }
        return true
    }

    /** 4 synchsafe bytes (7 bits each), as used by the ID3v2 header
     *  size and by v2.4 frame sizes. */
    private fun synchsafe(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0x7f) shl 21) or ((b[off + 1].toLong() and 0x7f) shl 14) or
            ((b[off + 2].toLong() and 0x7f) shl 7) or (b[off + 3].toLong() and 0x7f)

    private fun u32BE(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xff) shl 24) or ((b[off + 1].toLong() and 0xff) shl 16) or
            ((b[off + 2].toLong() and 0xff) shl 8) or (b[off + 3].toLong() and 0xff)

    private fun u32LE(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xff) or ((b[off + 1].toLong() and 0xff) shl 8) or
            ((b[off + 2].toLong() and 0xff) shl 16) or ((b[off + 3].toLong() and 0xff) shl 24)

    /** Index just past the text terminator starting at [from]:
     *  one 0x00 byte for ISO-8859-1 / UTF-8, two for UTF-16. */
    private fun textTerminator(enc: Int, b: ByteArray, from: Int): Int {
        var i = from
        if (enc == 1 || enc == 2) {
            while (i + 1 < b.size) {
                if (b[i] == 0.toByte() && b[i + 1] == 0.toByte()) return i + 2
                i += 2
            }
            return b.size
        }
        while (i < b.size) {
            if (b[i] == 0.toByte()) return i + 1
            i++
        }
        return b.size
    }

    /** ID3 text encodings: 0 = ISO-8859-1, 1 = UTF-16 (BOM-aware),
     *  2 = UTF-16BE, 3 = UTF-8. */
    private fun decodeText(enc: Int, b: ByteArray, from: Int, to: Int): String {
        if (from >= to) return ""
        val bytes = b.copyOfRange(from, minOf(to, b.size))
        return when (enc) {
            1 -> String(bytes, Charsets.UTF_16)
            2 -> String(bytes, Charsets.UTF_16BE)
            3 -> String(bytes, Charsets.UTF_8)
            else -> String(bytes, Charsets.ISO_8859_1)
        }.trim()
    }

    // --- MP3: ID3v2 USLT / SYLT ---------------------------------------
    private fun readId3v2(input: InputStream): Result? {
        val head = ByteArray(10)
        if (!readExact(input, head)) return null
        if (String(head, 0, 3, Charsets.US_ASCII) != "ID3") return null
        val version = head[3].toInt() and 0xff
        // The header size is synchsafe in every ID3v2 version; only
        // v2.4 frame sizes are synchsafe, v2.3 frame sizes are plain.
        val tagSize = synchsafe(head, 6)
        if (tagSize <= 0 || tagSize > MAX_TAG_BYTES) return null
        val tag = ByteArray(tagSize.toInt())
        if (!readExact(input, tag)) return null
        var synced: List<Pair<Long, String>>? = null
        var plain: String? = null
        var p = 0
        // v2.2 uses 3-byte frame ids and is not parsed; v2.3/v2.4 use
        // 4-byte ids. Padding / an unknown id ends the walk.
        while (p + 10 <= tag.size) {
            val id = String(tag, p, 4, Charsets.US_ASCII)
            if (id[0] == '\u0000' || !id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val size = (if (version >= 4) synchsafe(tag, p + 4) else u32BE(tag, p + 4)).toInt()
            if (size <= 0 || p + 10 + size > tag.size) break
            val payload = tag.copyOfRange(p + 10, p + 10 + size)
            when (id) {
                "USLT" -> plain = parseUslt(payload) ?: plain
                "SYLT" -> synced = parseSylt(payload) ?: synced
            }
            p += 10 + size
        }
        if (!synced.isNullOrEmpty()) {
            val lrc = synced.sortedBy { it.first }.joinToString("\n") { (ms, text) ->
                "[${ms / 60000}:${"%05.2f".format(Locale.US, (ms % 60000) / 1000.0)}]$text"
            }
            return Result(lrc, plain)
        }
        return plain?.takeIf { it.isNotBlank() }?.let { Result(null, it) }
    }

    /** USLT: encoding (1) + language (3) + content descriptor
     *  (terminated) + lyrics text. */
    private fun parseUslt(payload: ByteArray): String? {
        if (payload.size < 5) return null
        val enc = payload[0].toInt() and 0xff
        val textStart = textTerminator(enc, payload, 4)
        return decodeText(enc, payload, textStart, payload.size).takeIf { it.isNotBlank() }
    }

    /** SYLT: encoding (1) + language (3) + timestamp format (1) +
     *  content type (1) + descriptor (terminated), then entries of
     *  text (terminated) + 4-byte big-endian timestamp. Only format 1
     *  (milliseconds) is used — format 2 (MPEG frames) has no reliable
     *  ms conversion here, so those files fall back to USLT / remote. */
    private fun parseSylt(payload: ByteArray): List<Pair<Long, String>>? {
        if (payload.size < 6) return null
        val enc = payload[0].toInt() and 0xff
        val tsFormat = payload[4].toInt() and 0xff
        if (tsFormat != 1) return null
        var p = textTerminator(enc, payload, 6)
        val out = mutableListOf<Pair<Long, String>>()
        while (p < payload.size) {
            val end = textTerminator(enc, payload, p)
            val textEnd = if (enc == 1 || enc == 2) end - 2 else end - 1
            val text = decodeText(enc, payload, p, maxOf(textEnd, p))
            if (end + 4 > payload.size) break
            val ts = u32BE(payload, end)
            if (text.isNotBlank()) out += ts to text
            p = end + 4
        }
        return out.takeIf { it.isNotEmpty() }
    }

    // --- FLAC: Vorbis comment block ------------------------------------
    private fun readFlac(input: InputStream): Result? {
        val magic = ByteArray(4)
        if (!readExact(input, magic) || String(magic, Charsets.US_ASCII) != "fLaC") return null
        var scanned = 0L
        while (scanned < MAX_TAG_BYTES) {
            val head = ByteArray(4)
            if (!readExact(input, head)) return null
            val last = (head[0].toInt() and 0x80) != 0
            val type = head[0].toInt() and 0x7f
            val len = ((head[1].toLong() and 0xff) shl 16) or
                ((head[2].toLong() and 0xff) shl 8) or (head[3].toLong() and 0xff)
            scanned += 4 + len
            if (len > MAX_TAG_BYTES) return null
            if (type == 4) {
                val block = ByteArray(len.toInt())
                if (!readExact(input, block)) return null
                return parseVorbisComment(block)
            }
            if (!skipFully(input, len)) return null
            if (last) break
        }
        return null
    }

    /** Vorbis comment: u32LE vendor length + vendor, u32LE entry
     *  count, then entries "KEY=value" (UTF-8). LYRICS /
     *  UNSYNCEDLYRICS is the field taggers write; when its value
     *  already carries [mm:ss] stamps it is returned as synced LRC,
     *  otherwise as plain text. */
    private fun parseVorbisComment(b: ByteArray): Result? {
        if (b.size < 8) return null
        var p = 0
        val vendorLen = u32LE(b, p).toInt()
        if (vendorLen < 0 || 4 + vendorLen + 4 > b.size) return null
        p += 4 + vendorLen
        val count = u32LE(b, p).toInt()
        if (count < 0 || count > 10_000) return null
        p += 4
        var plain: String? = null
        var lrc: String? = null
        repeat(count) {
            if (p + 4 > b.size) return@repeat
            val len = u32LE(b, p).toInt()
            p += 4
            if (len < 0 || p + len > b.size) return@repeat
            val entry = String(b, p, len, Charsets.UTF_8)
            p += len
            val key = entry.substringBefore('=').trim().uppercase(Locale.US)
            val value = entry.substringAfter('=', "").trim()
            if (value.isBlank()) return@repeat
            if (key == "LYRICS" || key == "UNSYNCEDLYRICS") {
                if (value.contains(Regex("""\[\d+:\d"""))) lrc = value
                else if (plain == null) plain = value
            }
        }
        return if (lrc != null || plain != null) Result(lrc, plain) else null
    }

    // --- OGG: walk pages, reassemble packets, find the comment packet --
    private fun readOgg(input: InputStream): Result? {
        val packet = mutableListOf<Byte>()
        var scanned = 0L
        while (scanned < MAX_TAG_BYTES) {
            val head = ByteArray(27)
            if (!readExact(input, head)) return null
            if (String(head, 0, 4, Charsets.US_ASCII) != "OggS") return null
            scanned += 27
            val segments = head[26].toInt() and 0xff
            val segTable = ByteArray(segments)
            if (!readExact(input, segTable)) return null
            scanned += segments
            for (seg in segTable) {
                val segLen = seg.toInt() and 0xff
                val data = ByteArray(segLen)
                if (!readExact(input, data)) return null
                scanned += segLen
                packet.addAll(data.asList())
                if (segLen < 255) {
                    // End of one packet: the comment packet starts
                    // 0x03 "vorbis", comment body after its 7-byte head.
                    val bytes = packet.toByteArray()
                    packet.clear()
                    if (bytes.size > 7 && bytes[0] == 0x03.toByte() &&
                        String(bytes, 1, 6, Charsets.US_ASCII) == "vorbis"
                    ) {
                        return parseVorbisComment(bytes.copyOfRange(7, bytes.size))
                    }
                }
            }
        }
        return null
    }
}
