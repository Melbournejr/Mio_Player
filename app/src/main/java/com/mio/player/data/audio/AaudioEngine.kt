package com.mio.player.data.audio

import java.nio.ByteBuffer

/**
 * Step 3 (AAudio Exclusive) — Kotlin owner of one native AAudio output
 * stream (v1.1.39). The native side (mio_aaudio.cpp) only opens / writes /
 * controls the stream; this class owns the handle and enforces the
 * acceptance rule: a stream is usable ONLY when its ACTUAL sample rate
 * equals the requested rate AND sharing is EXCLUSIVE. Anything else
 * (shared mode, resampled rate) is closed immediately and the caller
 * falls back to the standard AudioTrack mixer path.
 *
 * Data reaching this engine is always stereo PCM 16-bit: the sink runs
 * with float output OFF, so the processor chain emits int16.
 */
class AaudioEngine private constructor(
    private var handle: Long,
    /** The stream's ACTUAL sample rate, read back after open. */
    val sampleRate: Int,
    /** The stream's ACTUAL channel count, read back after open. */
    val channelCount: Int,
    /** True only when the stream was granted EXCLUSIVE (MMAP) sharing. */
    val isExclusive: Boolean,
    /** True only when the stream was granted LOW_LATENCY performance. */
    val isLowLatency: Boolean,
    /** The stream's buffer size in frames, read back after open. */
    val bufferSizeInFrames: Int
) {
    companion object {
        init {
            // Loading is idempotent; AaudioProbe loads the same library.
            runCatching { System.loadLibrary("mio_aaudio") }
        }

        /** AAudio sharing-mode constant (AAUDIO_SHARING_MODE_EXCLUSIVE). */
        private const val SHARING_EXCLUSIVE = 1
        /** AAudio performance-mode constant (AAUDIO_PERFORMANCE_MODE_LOW_LATENCY). */
        private const val PERFORMANCE_LOW_LATENCY = 10

        /**
         * Opens a stream at [sampleRate] Hz. Returns an engine only when
         * the stream was actually granted that rate AND exclusive sharing;
         * returns null (stream closed) otherwise, so the caller falls
         * back to AudioTrack. Also returns null when the native open
         * itself fails or the library did not load.
         */
        fun open(sampleRate: Int, channelCount: Int): AaudioEngine? {
            if (!AaudioProbe.isLoaded) return null
            val handle = runCatching { nativeOpenStream(sampleRate, channelCount) }.getOrDefault(0L)
            if (handle == 0L) return null
            val engine = AaudioEngine(
                handle = handle,
                sampleRate = nativeGetSampleRate(handle),
                channelCount = nativeGetChannelCount(handle),
                isExclusive = nativeGetSharingMode(handle) == SHARING_EXCLUSIVE,
                isLowLatency = nativeGetPerformanceMode(handle) == PERFORMANCE_LOW_LATENCY,
                bufferSizeInFrames = nativeGetBufferSizeInFrames(handle)
            )
            // Acceptance rule: exact rate AND exclusive. A shared stream
            // goes through the mixer (resampled to 48 kHz anyway), and a
            // rate mismatch would play at the wrong pitch — both are a
            // silent failure, so refuse them and fall back.
            if (!engine.isExclusive || engine.sampleRate != sampleRate) {
                engine.close()
                return null
            }
            return engine
        }

        @JvmStatic private external fun nativeOpenStream(sampleRate: Int, channelCount: Int): Long
        @JvmStatic private external fun nativeCloseStream(handle: Long)
        @JvmStatic private external fun nativeStartStream(handle: Long): Boolean
        @JvmStatic private external fun nativePauseStream(handle: Long): Boolean
        @JvmStatic private external fun nativeStopStream(handle: Long): Boolean
        @JvmStatic private external fun nativeFlushStream(handle: Long): Boolean
        @JvmStatic private external fun nativeWrite(handle: Long, buffer: ByteBuffer, byteOffset: Int, byteLength: Int): Int
        @JvmStatic private external fun nativeGetFramesRead(handle: Long): Long
        @JvmStatic private external fun nativeGetFramesWritten(handle: Long): Long
        @JvmStatic private external fun nativeGetSampleRate(handle: Long): Int
        @JvmStatic private external fun nativeGetSharingMode(handle: Long): Int
        @JvmStatic private external fun nativeGetPerformanceMode(handle: Long): Int
        @JvmStatic private external fun nativeGetBufferSizeInFrames(handle: Long): Int
        @JvmStatic private external fun nativeGetChannelCount(handle: Long): Int
    }

    val isOpen: Boolean get() = handle != 0L

    fun start(): Boolean = if (isOpen) runCatching { nativeStartStream(handle) }.getOrDefault(false) else false
    fun pause(): Boolean = if (isOpen) runCatching { nativePauseStream(handle) }.getOrDefault(false) else false
    fun stop(): Boolean = if (isOpen) runCatching { nativeStopStream(handle) }.getOrDefault(false) else false
    fun flush(): Boolean = if (isOpen) runCatching { nativeFlushStream(handle) }.getOrDefault(false) else false

    fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            runCatching { nativeCloseStream(h) }
        }
    }

    /**
     * Non-blocking write of [byteLength] bytes from [buffer] starting at
     * [byteOffset]. Returns bytes written, 0 when the stream buffer is
     * full (caller retries), or a negative error code.
     */
    fun write(buffer: ByteBuffer, byteOffset: Int, byteLength: Int): Int =
        if (!isOpen) -1 else runCatching { nativeWrite(handle, buffer, byteOffset, byteLength) }.getOrDefault(-1)

    /** Frames consumed (played) by the device. Drives getPositionUs. */
    fun framesRead(): Long = if (isOpen) runCatching { nativeGetFramesRead(handle) }.getOrDefault(0L) else 0L

    /** Frames written into the stream. */
    fun framesWritten(): Long = if (isOpen) runCatching { nativeGetFramesWritten(handle) }.getOrDefault(0L) else 0L
}
