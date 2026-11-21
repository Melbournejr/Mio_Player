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
 *  - DFF -> [MIME_TYPE_DFF] ("audio/x-dsd-dff") -> dsd_lsbf_planar (v1.1.5)
 *
 * DFF transform (v1.1.5 — why DFF also uses dsd_lsbf_planar): a DFF file
 * stores its DSD bytes channel-INTERLEAVED, MSB-first (byte 0 = left
 * sample-byte 0, byte 1 = right sample-byte 0, ...; first sample in time
 * is each byte's MSB). FFmpeg's own demuxer (libavformat/dsfdec.c) pairs
 * DSDIFF with a PLANAR decoder and de-interleaves in the demuxer — the
 * old mapping (raw interleaved bytes -> dsd_msbf) was never exercised by
 * any proven reference, and through v1.1.2–v1.1.4 every real DSD packet
 * returned "no output" from ffmpegDecode. This extractor now does the
 * demuxer's job: [transformDffPacketToPlanarLsbf] de-interleaves (all
 * left bytes, then all right bytes) and bit-reverses every byte, so DFF
 * arrives as planar LSBF and both containers share the one mica-proven
 * decoder (dsd_lsbf_planar — the decoder mica-music proves in ExoPlayer;
 * dsd_msbf_planar would be FFmpeg's literal pairing, but no reference
 * proves it in ExoPlayer). The transform is exact — a permutation plus a
 * per-byte bit reversal, no bits lost — so it can only change the layout,
 * never the audio.
 *
 * Timestamps (v1.1.5 — the "594,184" question, settled): the read()
 * timestamp math is
 *   sampleTimeUs += bytes * 8 / channels * 1_000_000 / dsdRateHz
 * which for the user's DSD128 .dff (2048-byte packets, 2 ch, 5644800 Hz)
 * is exactly 1451 us per packet, so after 4096 packets (8 MB) the pts is
 * 4095 * 1451 = 5,941,845 us. The handoff's "594,184" is that number with
 * its final digit dropped in transcription — the extractor was never 10x
 * slow. read() now logs the expected pts alongside the actual pts, so the
 * next user log proves it numerically instead of by arithmetic.
 *
 * KEY-FRAME FLAG (v1.1.6 — THE native-DSD root cause, found by reading
 * Media3 1.9.0 SampleQueue.java): every sample MUST be flagged
 * BUFFER_FLAG_KEY_FRAME, or the SampleQueue silently drops it and the
 * decoder is never fed. The gate is SampleQueue.sampleMetadata()
 * (libraries/exoplayer/.../source/SampleQueue.java:1179-1189, tag 1.9.0):
 * `upstreamKeyframeRequired` starts true (constructor, line 338) and the
 * ONLY place it is ever cleared is a sample whose flags include
 * BUFFER_FLAG_KEY_FRAME (line 1187); until then sampleMetadata() returns
 * early and the sample is never committed — no log, no error, and the
 * bytes already written via sampleData() are orphaned in the data queue.
 * This extractor used to pass flags=0, so EVERY DSD packet was dropped:
 * the extractor's own counters still logged "delivered 8MB" (bytes it
 * wrote), the renderer read the format (so the decoder WAS created —
 * "DSD decoder init" in the log), but peekSampleMetadata() then returned
 * RESULT_NOTHING_READ forever (SampleQueue.java:1334-1354: empty queue,
 * format already delivered downstream), DecoderAudioRenderer.feedInputBuffer()
 * (libraries/exoplayer/.../audio/DecoderAudioRenderer.java:1076-1133)
 * returned false on every render, SimpleDecoder's decode thread never had
 * a queued input buffer, FfmpegAudioDecoder.decode() was never called —
 * zero "DSD decode:" lines — and the player sat in BUFFERING with an
 * empty queue until StuckPlayerDetector fired (ERROR_CODE_FAILED_RUNTIME_CHECK).
 * Every stock Media3 audio extractor flags its samples as key frames
 * (e.g. Mp3Extractor / FlacExtractor / WavExtractor all pass
 * C.BUFFER_FLAG_KEY_FRAME — audio frames are independently decodable),
 * and DSD packets are too, so this extractor now does the same. If native
 * DSD still stalls after this, the "DSD stall watch" lines in
 * PlayerManager (buffered duration while BUFFERING) are the proof of
 * whether the queue holds data — buffered=0us with the loader done means
 * the queue is empty again, buffered>0us means the stall is downstream
 * of the queue.
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
        /** DFF sample MIME type. The vendored decoder maps it to "dsd_lsbf_planar"
         *  (v1.1.5 — was "dsd_msbf"); the packets are transformed first, see
         *  [transformDffPacketToPlanarLsbf]. */
        const val MIME_TYPE_DFF = "audio/x-dsd-dff"

        private val DSF_MAGIC = byteArrayOf('D'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte(), ' '.code.toByte())
        private val DFF_MAGIC = byteArrayOf('F'.code.toByte(), 'R'.code.toByte(), 'M'.code.toByte(), '8'.code.toByte())
        private const val MAX_PROP_SIZE_BYTES = 64 * 1024
        private const val DEFAULT_BLOCK_SIZE_PER_CHANNEL = 4096
        private const val DFF_PACKET_BLOCKS = 1024

        /** Per-byte bit reversal (DFF is MSB-first, dsd_lsbf_planar wants LSB-first).
         *  Built once: reversing bit i of a byte is its own inverse, so the table
         *  is exact and branch-free in the packet loop. */
        private val BIT_REVERSE_TABLE: ByteArray = ByteArray(256) { i ->
            var v = i
            var r = 0
            repeat(8) { r = (r shl 1) or (v and 1); v = v shr 1 }
            r.toByte()
        }

        /**
         * v1.1.5: converts one DFF packet from the file layout (channel-interleaved,
         * MSB-first) to the decoder layout (planar, LSB-first: all channel-0 bytes,
         * then all channel-1 bytes, ..., each byte bit-reversed).
         *
         * [src] holds [length] file bytes starting at offset 0; [dst] must hold at
         * least [length] bytes. Returns the number of bytes written, which is
         * [length] rounded DOWN to a multiple of [channelCount] (a trailing partial
         * frame at end-of-file — at most channelCount-1 bytes — is dropped; it can
         * only occur on the final packet and is inaudible). The caller must use the
         * returned count, not [length], for sampleMetadata and the timestamp math.
         * Pure function, no allocation — the extractor reuses its scratch buffers.
         */
        fun transformDffPacketToPlanarLsbf(
            src: ByteArray,
            length: Int,
            channelCount: Int,
            dst: ByteArray
        ): Int {
            if (channelCount <= 0) return 0
            val frames = length / channelCount
            val outLen = frames * channelCount
            for (ch in 0 until channelCount) {
                val dstBase = ch * frames
                var srcPos = ch
                for (i in 0 until frames) {
                    dst[dstBase + i] = BIT_REVERSE_TABLE[src[srcPos].toInt() and 0xFF]
                    srcPos += channelCount
                }
            }
            return outLen
        }
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
    // v1.1.4: proof logging (see read()). Counters only — no per-packet
    // logging, the loader delivers ~689 packets/s for DSD128.
    private var packetsDelivered = 0L
    private var firstSampleLogged = false
    private var eightMbLogged = false
    // v1.1.5: reusable DFF scratch (read -> transform -> sampleData).
    // Sized to the stream's packet size on first use; reused so the
    // transform allocates nothing per packet.
    private var dffScratchIn: ByteArray? = null
    private var dffScratchOut: ByteArray? = null

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
            log("DSD end of input: packets=$packetsDelivered bytes=$dataBytesRead")
            return Extractor.RESULT_END_OF_INPUT
        }
        val packetBytes = minOf(current.packetSize.toLong(), remaining).toInt()
        // v1.1.5: DFF packets are transformed before delivery (see the class
        // KDoc): the file's interleaved-MSBF bytes go to the decoder as
        // planar-LSBF. DSF packets are already planar-LSBF and go through
        // untouched, straight from the input into the sample queue.
        // `deliveredBytes` is what sampleMetadata and the timestamp math use;
        // `fileBytesRead` is what the remaining-bytes accounting uses. They
        // are equal except for a truncated final DFF packet (<= 7 bytes).
        val deliveredBytes: Int
        val fileBytesRead: Int
        if (current.isDff) {
            val scratchIn = dffScratchIn?.takeIf { it.size >= packetBytes }
                ?: ByteArray(packetBytes).also { dffScratchIn = it }
            val scratchOut = dffScratchOut?.takeIf { it.size >= packetBytes }
                ?: ByteArray(packetBytes).also { dffScratchOut = it }
            var offset = 0
            while (offset < packetBytes) {
                val n = input.read(scratchIn, offset, packetBytes - offset)
                if (n <= 0) break // -1 = end of input; 0 = no progress, never spin
                offset += n
            }
            if (offset <= 0) {
                endOfInput = true
                log("DSD end of input (short read): packets=$packetsDelivered bytes=$dataBytesRead")
                return Extractor.RESULT_END_OF_INPUT
            }
            deliveredBytes = transformDffPacketToPlanarLsbf(
                scratchIn, offset, current.channelCount, scratchOut
            )
            if (deliveredBytes <= 0) {
                endOfInput = true
                log("DSD end of input (transform empty): packets=$packetsDelivered bytes=$dataBytesRead")
                return Extractor.RESULT_END_OF_INPUT
            }
            trackOutput?.sampleData(ParsableByteArray(scratchOut, deliveredBytes), deliveredBytes)
            fileBytesRead = offset
        } else {
            val bytesRead = trackOutput?.sampleData(input, packetBytes, true) ?: 0
            if (bytesRead <= 0) {
                endOfInput = true
                log("DSD end of input (short read): packets=$packetsDelivered bytes=$dataBytesRead")
                return Extractor.RESULT_END_OF_INPUT
            }
            deliveredBytes = bytesRead
            fileBytesRead = bytesRead
        }
        trackOutput?.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, deliveredBytes, 0, null)
        dataBytesRead += fileBytesRead
        packetsDelivered++
        // v1.1.4: prove samples actually flow extractor -> decoder.
        // First packet: delivery starts. 8 MB: delivery passed the
        // load-control target, so the loader/extractor are not the
        // bottleneck. NOTE (v1.1.6): this line proves the LOADER wrote
        // bytes, NOT that the SampleQueue committed them — before v1.1.6
        // the queue dropped every sample at its key-frame gate (see the
        // class KDoc) while this line still printed. The "DSD stall
        // watch" lines in PlayerManager (buffered duration while
        // BUFFERING) are what prove the queue holds data; the decimator's
        // "first output" line says which side of the decoder a later
        // stall is on.
        if (!firstSampleLogged) {
            firstSampleLogged = true
            log("DSD first sample: ${deliveredBytes}B pts=${sampleTimeUs}us packet=${current.packetSize} packetUs=${current.packetDurationUs} dataSize=${current.dataSize}")
        }
        if (!eightMbLogged && dataBytesRead >= 8 * 1024 * 1024L) {
            eightMbLogged = true
            // v1.1.5: the expected pts settles the "594,184" question in
            // the log itself: pts must equal (packets-1) * packetUs.
            // DSD128 .dff: 4095 * 1451 = 5,941,845 us.
            val expectedPtsUs = (packetsDelivered - 1) * current.packetDurationUs
            log("DSD extractor delivered 8MB: packets=$packetsDelivered pts=${sampleTimeUs}us expectedPts=${expectedPtsUs}us packetUs=${current.packetDurationUs}")
        }
        // 1 bit per sample per channel: bytes -> samples -> time.
        sampleTimeUs += deliveredBytes * 8L / current.channelCount * 1_000_000L / current.sampleRateHz
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
        val channelCount: Int,
        /** True for DFF: read() transforms each packet (see class KDoc). */
        val isDff: Boolean,
        /** Timestamp advance of one full packet, in us (packetSize * 8 / ch * 1e6 / rate). */
        val packetDurationUs: Long
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
            durationUs = durationUs,
            isDff = false
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
            durationUs = durationUs,
            isDff = true
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
            // DSDIFF chunk IDs are 4 bytes, space-padded: the sample-rate
            // chunk is "FS  " (F, S, space, space — FFmpeg libavformat/iff.c
            // MKTAG('F','S',' ',' ')), not "FS". Comparing the raw 4-byte
            // id never matched, so sampleRate stayed 0 and every .dff
            // failed with "DFF sampleRate=0". trim() covers any padding.
            when (id.trim()) {
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
        durationUs: Long,
        isDff: Boolean
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
            // v1.1.4: FfmpegAudioRenderer sizes the decoder's initial
            // input buffer from maxInputSize (default 5760). DSF packets
            // (blockSize * channels, typically 8192) exceed that, so
            // without this the decoder regrows its input buffer on
            // every packet. The extractor never emits a sample bigger
            // than packetSize, so this is exact.
            .setMaxInputSize(packetSize)
            .build()
        val bytesPerSecond = sampleRateHz.toLong() * channelCount / 8
        val seekMap = DsdSeekMap(durationUs, dataStart, dataSize, bytesPerSecond, packetSize, sampleRateHz, channelCount)
        val packetDurationUs = packetSize * 8L / channelCount * 1_000_000L / sampleRateHz
        return DsdStreamInfo(format, seekMap, dataStart, dataSize, packetSize, sampleRateHz, channelCount, isDff, packetDurationUs)
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
