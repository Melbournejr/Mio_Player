package com.mio.player.playback.dsd

import android.content.Context
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
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import com.mio.player.data.log.MioLog
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
 * Sample rate in the emitted Format (v1.1.1 — this was the native-DSD
 * failure): the Format carries the PCM output rate, sampleRateHz / 8
 * (352800 Hz for DSD64), NOT the DSD bit rate (2822400 Hz). Three sources
 * agree: FFmpeg's own DSF demuxer sets codecpar->sample_rate = rate / 8
 * (libavformat/dsfdec.c), mica-music's DsfFormat.decoderSampleRateHz is
 * rate / 8, and FFmpeg's DSD decoder (libavcodec/dsddec.c) never reads
 * avctx->sample_rate at all — it emits one float PCM sample per DSD byte,
 * so the PCM clock IS rate / 8. The vendored FfmpegAudioDecoder passes
 * Format.sampleRate straight to ffmpegInitialize, and the JNI reports it
 * back via ffmpegGetSampleRate for the AudioTrack format, so putting the
 * 2.8 MHz DSD rate here would make the sink try to open an AudioTrack at
 * 2.8 MHz. All time math in this file (sample timestamps, durations,
 * seek positions) still uses the true DSD rate — only the Format's
 * sampleRate is the divided PCM rate.
 *
 * NOTE (v1.1.1): the Format rate alone does not make native DSD play.
 * The prebuilt libffmpegJNI.so was built from STOCK Media3 1.9.0
 * ffmpeg_jni.cc, whose createContext() only sets sample_rate /
 * ch_layout for PCM mu-law / a-law. FFmpeg 6.0 avcodec_open2() rejects
 * any audio decoder without AV_CODEC_CAP_CHANNEL_CONF when the channel
 * layout is empty, and the DSD decoders have no CHANNEL_CONF — so
 * ffmpegInitialize("dsd_lsbf_planar") returns 0 and the decoder can
 * never initialise until the .so is rebuilt with mica-music's patch
 * (extend that special case to the four DSD codec IDs). Until then the
 * player falls back to the FFmpegKit transcode via onPlayerError, which
 * is why the probe in PlayerManager logs isAvailable/supportsFormat.
 *
 * This extractor does NO decoding itself — it only frames the DSD bytes.
 * Written against the Media3 1.9.0 Extractor API.
 */
@OptIn(UnstableApi::class)
class DsdExtractor(private val logContext: Context? = null) : Extractor {

    /** Best-effort MioLog line. The extractor runs on ExoPlayer's loader
     *  thread; the log is what names the exact parse throw site in the
     *  next user log (v1.1.0 only recorded "Source error"). Null context
     *  (e.g. unit tests) just skips logging. */
    private fun log(msg: String) {
        val ctx = logContext ?: return
        runCatching { MioLog.event(ctx, msg) }
    }

    /** Log the site, then throw the malformed-container ParserException
     *  (ExoPlayer maps it to ERROR_CODE_PARSING_CONTAINER_MALFORMED). */
    private fun malformed(message: String): Nothing {
        log("DSD parse FAIL (malformed): $message")
        throw ParserException.createForMalformedContainer(message, null)
    }

    /** Log the site, then throw the unsupported-feature ParserException
     *  (ExoPlayer maps it to ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
     *  which also triggers the transcode fallback, not a crash). */
    private fun unsupported(message: String): Nothing {
        log("DSD parse UNSUPPORTED: $message")
        throw ParserException.createForUnsupportedContainerFeature(message)
    }

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

    /** Factory so PlayerManager can prepend this extractor before DefaultExtractorsFactory.
     *  NOTE: Media3 has no nested Extractor.Factory (that type does not exist
     *  in 1.9.0 — the factory type is the plural ExtractorsFactory SAM);
     *  PlayerManager instantiates DsdExtractor() directly. */

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
            else -> malformed("Not a DSF/DFF stream, magic='${String(magic, Charsets.US_ASCII)}'")
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
            malformed("DSF fmt chunk not found, got='${String(fmtHead, 0, 4, Charsets.US_ASCII)}'")
        }
        val fmtSize = fmtHeadData.readLittleEndianLong()
        // Chunk sizes include the 12-byte header (52 = 12 + 40 payload).
        // Tolerate writers that store the payload size instead (40).
        val payloadSize = when {
            fmtSize in 52..1024 -> (fmtSize - 12).toInt()
            fmtSize in 40..51 -> fmtSize.toInt()
            else -> malformed("DSF fmt chunk size invalid: $fmtSize")
        }
        if (payloadSize < 40) {
            malformed("DSF fmt chunk too small: $payloadSize")
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
            unsupported("Unsupported DSF version=$version format=$formatId")
        }
        if (channelCount !in 1..8 || sampleRate <= 0) {
            malformed("DSF channels=$channelCount sampleRate=$sampleRate")
        }
        if (bitsPerSample != 1) {
            // 8 = MSBF planar. The decoder patch maps "audio/dsd" to
            // dsd_lsbf_planar only, so passing an 8-bit DSF through would
            // decode with the wrong bit order. Reject -> transcode fallback.
            unsupported("DSF bits/sample=$bitsPerSample not supported natively")
        }
        // "data" chunk header.
        val dataHead = ByteArray(12)
        input.readFully(dataHead, 0, dataHead.size)
        val dataHeadData = ParsableByteArray(dataHead)
        if (dataHeadData.readString(4) != "data") {
            malformed("DSF data chunk not found, got='${String(dataHead, 0, 4, Charsets.US_ASCII)}'")
        }
        val dataChunkSize = dataHeadData.readLittleEndianLong()
        val dataStart = input.position
        var dataSize = dataChunkSize - 12 // DSF chunk sizes include the 12-byte header
        if (dataSize < 0) {
            malformed("DSF data chunk size invalid: $dataChunkSize")
        }
        val inputLength = input.length
        // C.LENGTH_UNSET is an Int in Media3 1.9.0; input.length is Long.
        if (inputLength != C.LENGTH_UNSET.toLong()) {
            dataSize = minOf(dataSize, (inputLength - dataStart).coerceAtLeast(0))
        }
        val blockSize = if (blockSizePerChannel in 1..65536) blockSizePerChannel
            else DEFAULT_BLOCK_SIZE_PER_CHANNEL
        val durationUs = if (sampleCount > 0) {
            sampleCount * 1_000_000L / sampleRate
        } else {
            dataSize * 8L / channelCount * 1_000_000L / sampleRate
        }
        log("DSF header OK: version=$version formatId=$formatId ch=$channelCount dsdRate=$sampleRate pcmRate=${sampleRate / 8} bits=$bitsPerSample samples=$sampleCount block=$blockSizePerChannel dataSize=$dataSize packet=${blockSize * channelCount} durationUs=$durationUs")
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
            malformed("DFF form type is not DSD, got='${String(formHead, 8, 4, Charsets.US_ASCII)}'")
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
                malformed("DFF chunk size invalid, id='$id'")
            }
            when (id) {
                "PROP" -> {
                    val prop = parseProp(input, size)
                    sampleRate = prop.sampleRate
                    channelCount = prop.channelCount
                    if (prop.dstCompressed) {
                        unsupported("DST-compressed DFF is not supported natively; transcode fallback will handle it")
                    }
                }
                "DSD " -> {
                    dataStart = input.position
                    dataSize = size
                }
                "DST " -> {
                    unsupported("DST-compressed DFF is not supported natively; transcode fallback will handle it")
                }
                else -> skipFully(input, size)
            }
        }
        if (dataStart < 0 || dataSize < 0) {
            malformed("DFF audio data chunk not found")
        }
        if (sampleRate <= 0 || channelCount !in 1..8) {
            malformed("DFF sampleRate=$sampleRate channels=$channelCount")
        }
        val inputLength = input.length
        if (inputLength != C.LENGTH_UNSET.toLong()) {
            // DSDIFF chunk sizes exclude the header; clamp anyway so a
            // writer that includes it cannot overrun the file.
            dataSize = minOf(dataSize, (inputLength - dataStart).coerceAtLeast(0))
        }
        val durationUs = dataSize * 8L / channelCount * 1_000_000L / sampleRate
        log("DFF header OK: ch=$channelCount dsdRate=$sampleRate pcmRate=${sampleRate / 8} dataSize=$dataSize durationUs=$durationUs")
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
        // sampleRateHz here is the TRUE DSD bit rate (2822400 for DSD64)
        // and stays that way for all time math (duration, seek, sample
        // timestamps). The Format, however, carries the PCM output rate
        // sampleRateHz / 8 (352800 for DSD64) — see the class KDoc: the
        // FFmpeg DSD decoder emits one float PCM sample per DSD byte, so
        // the PCM clock is rate / 8, and the decoder + sink are clocked
        // from this Format value (FfmpegAudioDecoder passes it to
        // ffmpegInitialize; the renderer builds the AudioTrack format from
        // ffmpegGetSampleRate). The average bitrate is the DSD input
        // bitrate and is unchanged.
        val pcmSampleRateHz = sampleRateHz / 8
        val format = Format.Builder()
            .setSampleMimeType(mimeType)
            .setSampleRate(pcmSampleRateHz)
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
            return SeekMap.SeekPoints(SeekPoint(alignedTimeUs, dataStart + alignedBytes))
        }
    }
}
