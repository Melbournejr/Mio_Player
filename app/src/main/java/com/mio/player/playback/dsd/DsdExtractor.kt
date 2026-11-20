package com.mio.player.playback.dsd

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.EOFException
import java.io.IOException

/**
 * Minimal DSF / DFF (DSD) extractor for native DSD playback (v1.1.0).
 *
 * Sniffs "DSD " (DSF) at offset 0, or "FRM8" at 0 + "DSD " at 12 (DFF),
 * parses the header, and emits the raw 1-bit DSD payload as samples to
 * the vendored FFmpeg DSD decoder (see PlayerManager: the decoder is
 * selected by the sample MIME type below).
 *
 * MIME types (must match FfmpegLibrary.getCodecName() in the vendored
 * decoder module — the other half of this feature):
 *  - DSF -> [MIME_TYPE_DSF] ("audio/dsd")       -> dsd_lsbf_planar
 *  - DFF -> [MIME_TYPE_DFF] ("audio/x-dsd-dff") -> dsd_msbf
 *
 * Deliberately NOT handled here — the extractor rejects them, so
 * PlayerManager's onPlayerError falls back to the FFmpegKit transcode
 * (DsdDecoder) instead of playing garbage or crashing:
 *  - DST-compressed .dff (CMPR chunk "DST ", or a "DST " data chunk).
 *  - DSF with bits/sample = 8 (MSBF planar). The decoder patch maps every
 *    "audio/dsd" stream to dsd_lsbf_planar, so an 8-bit DSF passed through
 *    would decode with the wrong bit order (wrong audio, not an error).
 *  - DSF with format version != 1 or format id != 0.
 *
 * Seeking: a byte-offset SeekMap (DSD is constant-bit-rate — 1 bit per
 * sample per channel — so time <-> byte offset is exact). Seek positions
 * are aligned down to the packet size.
 *
 * This extractor does NO decoding itself — it only frames the DSD bytes.
 * Written against the Media3 1.9.0 Extractor API.
 */
@OptIn(UnstableApi::class)
class DsdExtractor : Extractor {

    companion object {
        /** DSF sample MIME type. The vendored decoder maps it to "dsd_lsbf_planar". */
        const val MIME_TYPE_DSF = "audio/dsd"
        /** DFF sample MIME type. The vendored decoder maps it to "dsd_msbf". */
        const val MIME_TYPE_DFF = "audio/x-dsd-dff"

        private val DSF_MAGIC = byteArrayOf('D'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte(), ' '.code.toByte())
        private val DFF_MAGIC = byteArrayOf('F'.code.toByte(), 'R'.code.toByte(), 'M'.code.toByte(), '8'.code.toByte())
        private const val MAX_PROP_SIZE_BYTES = 64 * 1024
        private const val DEFAULT_BLOCK_SIZE_PER_CHANNEL = 4096
        private const val DFF_PACKET_BLOCKS = 1024
    }

    /** Factory so PlayerManager can prepend this extractor before DefaultExtractorsFactory. */
    class Factory : Extractor.Factory {
        override fun createExtractor(): Extractor = DsdExtractor()
    }

    private var output: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null
    private var info: DsdStreamInfo? = null
    private var dataBytesRead = 0L
    private var sampleTimeUs = 0L
    private var endOfInput = false

    override fun sniff(input: ExtractorInput): Boolean {
        return try {
            val scratch = ByteArray(16)
            input.peekFully(scratch, 0, scratch.size)
            input.resetPeekPosition()
            val peek = ParsableByteArray(scratch)
            when (peek.readString(4)) {
                "DSD " -> true // DSF
                "FRM8" -> { // DFF: "FRM8" + u64 size + "DSD " form type
                    peek.skipBytes(8)
                    peek.readString(4) == "DSD "
                }
                else -> false
            }
        } catch (e: IOException) {
            false
        }
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (endOfInput) return Extractor.RESULT_END_OF_INPUT
        var current = info
        if (current == null) {
            current = parseHeader(input)
            info = current
            trackOutput?.format(current.format)
            output?.seekMap(current.seekMap)
        }
        val remaining = current.dataSize - dataBytesRead
        if (remaining <= 0) {
            endOfInput = true
            return Extractor.RESULT_END_OF_INPUT
        }
        val packetBytes = minOf(current.packetSize.toLong(), remaining).toInt()
        val bytesRead = trackOutput?.sampleData(input, packetBytes, true) ?: 0
        if (bytesRead <= 0) {
            endOfInput = true
            return Extractor.RESULT_END_OF_INPUT
        }
        trackOutput?.sampleMetadata(sampleTimeUs, 0, bytesRead, 0, null)
        dataBytesRead += bytesRead
        // 1 bit per sample per channel: bytes -> samples -> time.
        sampleTimeUs += bytesRead * 8L / current.channelCount * 1_000_000L / current.sampleRateHz
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        // ExoPlayer repositions the input to `position` before the next
        // read(); we only have to re-derive our counters from it.
        val current = info ?: return
        val dataOffset = (position - current.dataStart).coerceIn(0, current.dataSize)
        dataBytesRead = dataOffset
        sampleTimeUs = dataOffset * 8L / current.channelCount * 1_000_000L / current.sampleRateHz
        endOfInput = false
    }

    override fun release() {
        output = null
        trackOutput = null
    }

    // ---- header parsing ----

    private data class DsdStreamInfo(
        val format: Format,
        val seekMap: SeekMap,
        val dataStart: Long,
        val dataSize: Long,
        val packetSize: Int,
        val sampleRateHz: Int,
        val channelCount: Int
    )

    @Throws(IOException::class)
    private fun parseHeader(input: ExtractorInput): DsdStreamInfo {
        val magic = ByteArray(4)
        input.readFully(magic, 0, magic.size)
        return when {
            magic.contentEquals(DSF_MAGIC) -> parseDsf(input)
            magic.contentEquals(DFF_MAGIC) -> parseDff(input)
            else -> throw ParserException.createForMalformedContainer("Not a DSF/DFF stream", null)
        }
    }

    @Throws(IOException::class)
    private fun parseDsf(input: ExtractorInput): DsdStreamInfo {
        // "DSD " chunk: u64 chunk size, u64 file size, u64 metadata pointer.
        input.skipFully(24)
        // "fmt " chunk header (little-endian).
        val fmtHead = ByteArray(12)
        input.readFully(fmtHead, 0, fmtHead.size)
        val fmtHeadData = ParsableByteArray(fmtHead)
        if (fmtHeadData.readString(4) != "fmt ") {
            throw ParserException.createForMalformedContainer("DSF fmt chunk not found", null)
        }
        val fmtSize = fmtHeadData.readLittleEndianLong()
        // Chunk sizes include the 12-byte header (52 = 12 + 40 payload).
        // Tolerate writers that store the payload size instead (40).
        val payloadSize = when {
            fmtSize in 52..1024 -> (fmtSize - 12).toInt()
            fmtSize in 40..51 -> fmtSize.toInt()
            else -> throw ParserException.createForMalformedContainer("DSF fmt chunk size invalid: $fmtSize", null)
        }
        if (payloadSize < 40) {
            throw ParserException.createForMalformedContainer("DSF fmt chunk too small: $payloadSize", null)
        }
        val payload = ByteArray(payloadSize)
        input.readFully(payload, 0, payload.size)
        val p = ParsableByteArray(payload)
        val version = p.readLittleEndianUnsignedIntToInt()
        val formatId = p.readLittleEndianUnsignedIntToInt()
        p.readLittleEndianUnsignedIntToInt() // channel type (unused: channel count is explicit)
        val channelCount = p.readLittleEndianUnsignedIntToInt()
        val sampleRate = p.readLittleEndianUnsignedIntToInt()
        val bitsPerSample = p.readLittleEndianUnsignedIntToInt()
        val sampleCount = p.readLittleEndianLong()
        val blockSizePerChannel = p.readLittleEndianUnsignedIntToInt()
        if (version != 1 || formatId != 0) {
            throw ParserException.createForUnsupportedContainerFeature(
                "Unsupported DSF version=$version format=$formatId")
        }
        if (channelCount !in 1..8 || sampleRate <= 0) {
            throw ParserException.createForMalformedContainer(
                "DSF channels=$channelCount sampleRate=$sampleRate", null)
        }
        if (bitsPerSample != 1) {
            // 8 = MSBF planar. The decoder patch maps "audio/dsd" to
            // dsd_lsbf_planar only, so passing an 8-bit DSF through would
            // decode with the wrong bit order. Reject -> transcode fallback.
            throw ParserException.createForUnsupportedContainerFeature(
                "DSF bits/sample=$bitsPerSample not supported natively")
        }
        // "data" chunk header.
        val dataHead = ByteArray(12)
        input.readFully(dataHead, 0, dataHead.size)
        val dataHeadData = ParsableByteArray(dataHead)
        if (dataHeadData.readString(4) != "data") {
            throw ParserException.createForMalformedContainer("DSF data chunk not found", null)
        }
        val dataChunkSize = dataHeadData.readLittleEndianLong()
        val dataStart = input.position
        var dataSize = dataChunkSize - 12 // DSF chunk sizes include the 12-byte header
        if (dataSize < 0) {
            throw ParserException.createForMalformedContainer("DSF data chunk size invalid: $dataChunkSize", null)
        }
        val inputLength = input.length
        if (inputLength != C.LENGTH_UNSET) {
            dataSize = minOf(dataSize, (inputLength - dataStart).coerceAtLeast(0))
        }
        val blockSize = if (blockSizePerChannel in 1..65536) blockSizePerChannel
            else DEFAULT_BLOCK_SIZE_PER_CHANNEL
        val durationUs = if (sampleCount > 0) {
            sampleCount * 1_000_000L / sampleRate
        } else {
            dataSize * 8L / channelCount * 1_000_000L / sampleRate
        }
        return buildInfo(
            mimeType = MIME_TYPE_DSF,
            sampleRateHz = sampleRate,
            channelCount = channelCount,
            packetSize = blockSize * channelCount,
            dataStart = dataStart,
            dataSize = dataSize,
            durationUs = durationUs
        )
    }

    @Throws(IOException::class)
    private fun parseDff(input: ExtractorInput): DsdStreamInfo {
        // "FRM8" + u64 form size + "DSD " form type.
        val formHead = ByteArray(12)
        input.readFully(formHead, 0, formHead.size)
        val form = ParsableByteArray(formHead)
        form.skipBytes(8)
        if (form.readString(4) != "DSD ") {
            throw ParserException.createForMalformedContainer("DFF form type is not DSD", null)
        }
        var sampleRate = 0
        var channelCount = 0
        var dataStart = -1L
        var dataSize = -1L
        val chunkHead = ByteArray(12)
        // Chunks are big-endian: 4-byte id + u64 size + payload.
        // Scan until the "DSD " audio data chunk (PROP normally precedes it).
        while (dataStart < 0) {
            try {
                input.readFully(chunkHead, 0, chunkHead.size)
            } catch (e: EOFException) {
                break
            }
            val chunk = ParsableByteArray(chunkHead)
            val id = chunk.readString(4)
            val size = chunk.readUnsignedLongToLong()
            if (size < 0) {
                throw ParserException.createForMalformedContainer("DFF chunk size invalid", null)
            }
            when (id) {
                "PROP" -> {
                    val prop = parseProp(input, size)
                    sampleRate = prop.sampleRate
                    channelCount = prop.channelCount
                    if (prop.dstCompressed) {
                        throw ParserException.createForUnsupportedContainerFeature(
                            "DST-compressed DFF is not supported natively; transcode fallback will handle it")
                    }
                }
                "DSD " -> {
                    dataStart = input.position
                    dataSize = size
                }
                "DST " -> {
                    throw ParserException.createForUnsupportedContainerFeature(
                        "DST-compressed DFF is not supported natively; transcode fallback will handle it")
                }
                else -> skipFully(input, size)
            }
        }
        if (dataStart < 0 || dataSize < 0) {
            throw ParserException.createForMalformedContainer("DFF audio data chunk not found", null)
        }
        if (sampleRate <= 0 || channelCount !in 1..8) {
            throw ParserException.createForMalformedContainer(
                "DFF sampleRate=$sampleRate channels=$channelCount", null)
        }
        val inputLength = input.length
        if (inputLength != C.LENGTH_UNSET) {
            // DSDIFF chunk sizes exclude the header; clamp anyway so a
            // writer that includes it cannot overrun the file.
            dataSize = minOf(dataSize, (inputLength - dataStart).coerceAtLeast(0))
        }
        val durationUs = dataSize * 8L / channelCount * 1_000_000L / sampleRate
        return buildInfo(
            mimeType = MIME_TYPE_DFF,
            sampleRateHz = sampleRate,
            channelCount = channelCount,
            packetSize = DFF_PACKET_BLOCKS * channelCount,
            dataStart = dataStart,
            dataSize = dataSize,
            durationUs = durationUs
        )
    }

    private data class PropInfo(val sampleRate: Int, val channelCount: Int, val dstCompressed: Boolean)

    /** PROP chunk payload: "SND " + sub-chunks FS (rate), CHNL (channels), CMPR (compression). */
    @Throws(IOException::class)
    private fun parseProp(input: ExtractorInput, size: Long): PropInfo {
        if (size > MAX_PROP_SIZE_BYTES) {
            // PROP is a small metadata chunk; an absurd size means a corrupt
            // file. Skip it and let the caller fail on missing properties.
            skipFully(input, size)
            return PropInfo(0, 0, false)
        }
        val payload = ByteArray(size.toInt())
        input.readFully(payload, 0, payload.size)
        val p = ParsableByteArray(payload)
        if (p.bytesLeft() < 4 || p.readString(4) != "SND ") return PropInfo(0, 0, false)
        var sampleRate = 0
        var channelCount = 0
        var dstCompressed = false
        while (p.bytesLeft() >= 12) {
            val id = p.readString(4)
            val subSize = p.readUnsignedLongToLong()
            if (subSize < 0 || subSize > p.bytesLeft()) break
            val subStart = p.position
            when (id) {
                "FS" -> if (subSize >= 4) sampleRate = p.readUnsignedIntToInt()
                "CHNL" -> if (subSize >= 2) channelCount = p.readUnsignedShort()
                "CMPR" -> if (subSize >= 4) dstCompressed = p.readString(4) == "DST "
            }
            p.position = minOf(subStart + subSize.toInt(), p.limit())
        }
        return PropInfo(sampleRate, channelCount, dstCompressed)
    }

    @Throws(IOException::class)
    private fun skipFully(input: ExtractorInput, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val step = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
            input.skipFully(step)
            remaining -= step
        }
    }

    private fun buildInfo(
        mimeType: String,
        sampleRateHz: Int,
        channelCount: Int,
        packetSize: Int,
        dataStart: Long,
        dataSize: Long,
        durationUs: Long
    ): DsdStreamInfo {
        val format = Format.Builder()
            .setSampleMimeType(mimeType)
            .setSampleRate(sampleRateHz)
            .setChannelCount(channelCount)
            .setAverageBitrate(sampleRateHz * channelCount) // 1 bit per sample per channel
            .build()
        val bytesPerSecond = sampleRateHz.toLong() * channelCount / 8
        val seekMap = DsdSeekMap(durationUs, dataStart, dataSize, bytesPerSecond, packetSize, sampleRateHz, channelCount)
        return DsdStreamInfo(format, seekMap, dataStart, dataSize, packetSize, sampleRateHz, channelCount)
    }

    /** Byte-offset SeekMap. DSD is constant-bit-rate, so time <-> offset is exact. */
    private class DsdSeekMap(
        private val durationUs: Long,
        private val dataStart: Long,
        private val dataSize: Long,
        private val bytesPerSecond: Long,
        private val blockAlign: Int,
        private val sampleRateHz: Int,
        private val channelCount: Int
    ) : SeekMap {
        override fun isSeekable(): Boolean = true
        override fun getDurationUs(): Long = durationUs
        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val clampedTimeUs = timeUs.coerceIn(0, durationUs)
            val byteOffset = (clampedTimeUs * bytesPerSecond / 1_000_000L).coerceIn(0, dataSize)
            val alignedBytes = byteOffset / blockAlign * blockAlign
            val alignedTimeUs = alignedBytes * 8L / channelCount * 1_000_000L / sampleRateHz
            return SeekMap.SeekPoints(SeekMap.SeekPoint(alignedTimeUs, dataStart + alignedBytes))
        }
    }
}
