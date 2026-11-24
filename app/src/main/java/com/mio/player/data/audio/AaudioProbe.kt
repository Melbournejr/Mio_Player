package com.mio.player.data.audio

/**
 * Step 3 (AAudio Exclusive) — scaffold probe (v1.1.38).
 *
 * Proves the NDK/CMake toolchain builds, the `mio_aaudio` .so is packaged
 * into the APK, and JNI loads. The full AAudio output engine (open a
 * stream at the track's native rate, write PCM frames, close, report the
 * stream's ACTUAL rate/sharing/performance mode) comes in the next
 * version; this object only exposes a trivial availability probe.
 *
 * Loading is guarded: if the .so is missing (e.g. an old install), every
 * call safely returns false instead of crashing.
 */
object AaudioProbe {
    /** True when the native library loaded successfully. */
    val isLoaded: Boolean = try {
        System.loadLibrary("mio_aaudio")
        true
    } catch (e: Throwable) {
        false
    }

    @JvmStatic
    private external fun isAaudioAvailable(): Boolean

    /** Safe probe: true only when the .so loaded AND the JNI call works. */
    fun probe(): Boolean =
        if (!isLoaded) false
        else runCatching { isAaudioAvailable() }.getOrDefault(false)
}
