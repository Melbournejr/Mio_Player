package com.mio.player.data.audio
/**
 * DSD — HONEST IMPLEMENTATION NOTE (read before changing this file).
 *
 * Android has NO native DSD passthrough / DoP on the vast majority of phones.
 * ExoPlayer / Android AudioTrack cannot output a raw 1-bit DSD stream to a
 * generic phone DAC, and claiming "native DSD output" would be false.
 * "Native" here means the DSD is decoded in-process by a native (FFmpeg)
 * decoder to float PCM — not bit-perfect DSD to the DAC.
 *
 * What Mio Player DOES do (v1.1.0, native-first):
 *  1. Detect .dsf / .dff by extension (DSD64 = 2.8224 MHz, DSD128 = 5.6448 MHz,
 *     DSD256 = 11.2896 MHz, 1-bit).
 *  2. NATIVE path (default): DsdExtractor frames the raw DSD bytes and the
 *     vendored FFmpeg DSD decoder (extension renderer, float output)
 *     decodes them during playback. No WAV cache, no pre-decode wait —
 *     a DSD track starts playing immediately, like any other file.
 *  3. FALLBACK path (only when native decode fails — DST-compressed .dff,
 *     an unsupported DSF variant, a malformed header): decode DSD ->
 *     high-res PCM WAV once, cached, with FFmpeg via the maintained
 *     FFmpegKit fork (dev.ffmpegkit-maintained:ffmpeg-kit-full):
 *       ffmpeg -y -i <input.dsf> -vn -ar 176400 -ac 2 -sample_fmt s16 <cache.wav>
 *     DSD64 / DSD128 / DSD256 all decode to PCM 176.4 kHz 16-bit stereo WAV.
 *     First fallback play of a DSD file takes a few seconds to convert;
 *     later plays use the cache instantly. See DsdDecoder.
 *  4. Playback goes through ExoPlayer / AudioTrack at the highest sample
 *     rate the device reports. If the device caps at 48/96 kHz, ExoPlayer
 *     resamples — the UI shows the SOURCE format honestly (e.g. "DSD64 ·
 *     Native decode (device 96 kHz)").
 *
 * WHY FFmpegKit-maintained and NOT the Media3 FFmpeg extension?
 *  - androidx.media3:media3-decoder-ffmpeg is NOT published by Google to
 *    Google Maven or Maven Central at all (404 for every version, including
 *    1.4.1). Depending on it broke the CI build in run #2 with
 *    ModuleVersionNotFoundException. The Jellyfin fork
 *    (org.jellyfin.media3:media3-ffmpeg-decoder) does publish it, but only
 *    for Media3 1.2/1.3/1.5/1.6/1.8/1.9 — not 1.4.1 — and it also needs a
 *    custom FfmpegAudioRenderer wired into PlayerManager.
 *  - com.arthenica:ffmpeg-kit-* was removed from Maven Central in April 2025
 *    and no longer resolves. The maintained fork
 *    dev.ffmpegkit-maintained:ffmpeg-kit-full:6.0.3 (verified HTTP 200 on
 *    Maven Central, 2026-11-20) is a drop-in replacement with the same
 *    com.arthenica.ffmpegkit API.
 *  - Do NOT re-add media3-decoder-ffmpeg or com.arthenica:ffmpeg-kit-*.
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
    /** Actual PCM rate of the cached WAV produced by DsdDecoder. */
    const val CACHED_PCM_SAMPLE_RATE_HZ = 176_400
    /** Native-path label: DSD is decoded in-process, not via a PCM WAV. */
    fun formatLabel(path: String, dsdSampleRateHz: Int = 2_822_400): String {
        val rate = dsdRateFromSampleRateHz(dsdSampleRateHz)
        return "$rate · Native decode"
    }
    /** Fallback-path label: the cached WAV the transcode produces. */
    fun fallbackFormatLabel(path: String, dsdSampleRateHz: Int = 2_822_400): String {
        val rate = dsdRateFromSampleRateHz(dsdSampleRateHz)
        return "$rate -> PCM 176.4kHz"
    }
    fun ffmpegCommand(inputPath: String, outputPath: String): String =
        // "-f wav" is explicit so the output format never depends on the
        // output filename's extension (a ".tmp" suffix once made ffmpeg
        // fail with "Unable to choose an output format").
        "-y -i \"$inputPath\" -vn -ar $CACHED_PCM_SAMPLE_RATE_HZ -ac 2 -sample_fmt s16 -f wav \"$outputPath\""
}
