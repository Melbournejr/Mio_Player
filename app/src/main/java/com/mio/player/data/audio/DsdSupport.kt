package com.mio.player.data.audio
/**
 * DSD — HONEST IMPLEMENTATION NOTE (read before changing this file).
 *
 * Android has NO native DSD passthrough / DoP on the vast majority of phones.
 * ExoPlayer / Android AudioTrack cannot output a raw 1-bit DSD stream to a
 * generic phone DAC, and claiming "native DSD output" would be false.
 *
 * What Mio Player DOES do (this file + PlayerManager):
 *  1. Detect .dsf / .dff by extension and by FFmpeg probe (DSD64 = 2.8224 MHz,
 *     DSD128 = 5.6448 MHz, DSD256 = 11.2896 MHz, 1-bit).
 *  2. Decode DSD -> high-res PCM with the official Media3 FFmpeg audio
 *     decoder extension (androidx.media3:media3-decoder-ffmpeg). FFmpeg's
 *     DSD decoder converts the 1-bit stream to PCM at DSD-rate / 16:
 *       DSD64  -> PCM 176.4 kHz, DSD128 -> PCM 352.8 kHz, DSD256 -> 705.6 kHz
 *     (capped in practice by AudioTrack / device max sample rate — see below).
 *  3. Play that PCM through ExoPlayer / AudioTrack at the highest sample
 *     rate the device reports (AudioManager.getProperty(PROPERTY_OUTPUT_SAMPLE_RATE)
 *     and AudioTrack.getNativeOutputSampleRate). If the device caps at 48/96 kHz,
 *     ExoPlayer resamples — the UI still shows the SOURCE format honestly
 *     (e.g. "DSD64 -> PCM 176.4 kHz (device 96 kHz)").
 *  4. If the FFmpeg renderer cannot handle a file, PlayerManager falls back
 *     to ExoPlayer's default decoders and shows an error instead of silence.
 *
 * WHY FFmpeg extension and NOT ffmpeg-kit?
 *  - media3-decoder-ffmpeg is the official Media3 extension, a prebuilt AAR
 *    with native libs, no NDK build step, and it tracks the Media3 version
 *    (1.4.1 here). ffmpeg-kit-full (arthenica) is unmaintained, pulls a
 *    very large native payload, and repeatedly breaks on AGP 8.x / SDK 34.
 *    A future optimisation is an offline DSD->WAV cache using the same
 *    FFmpeg decoder; it is NOT implemented yet because on-the-fly decode
 *    already preserves the full PCM rate and avoids multi-GB caches.
 *  - Do NOT add ffmpeg-kit alongside this extension: two FFmpeg copies
 *    conflict at link time.
 */
object DsdSupport {
    fun isDsd(path: String): Boolean { val p = path.lowercase(); return p.endsWith(".dsf") || p.endsWith(".dff") }
    fun dsdRateFromSampleRateHz(dsdSampleRateHz: Int): String = when {
        dsdSampleRateHz >= 11_289_600 -> "DSD256"
        dsdSampleRateHz >= 5_644_800 -> "DSD128"
        else -> "DSD64"
    }
    fun pcmSampleRateFor(dsdRate: String): Int = when (dsdRate) {
        "DSD128" -> 352_800; "DSD256" -> 705_600; else -> 176_400
    }
    fun formatLabel(path: String, dsdSampleRateHz: Int = 2_822_400): String {
        val rate = dsdRateFromSampleRateHz(dsdSampleRateHz)
        return "$rate -> PCM ${pcmSampleRateFor(rate)/1000.0}kHz"
    }
}
