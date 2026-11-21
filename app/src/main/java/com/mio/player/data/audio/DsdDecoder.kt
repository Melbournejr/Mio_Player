package com.mio.player.data.audio

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.mio.player.data.log.MioLog
import com.mio.player.data.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Decodes a DSD track (.dsf/.dff) to a cached PCM WAV once, using FFmpegKit.
 * See DsdSupport.kt for why this route was chosen.
 *
 * v1.1.0: this is the FALLBACK path only. Native decode (DsdExtractor +
 * vendored FFmpeg DSD decoder) is tried first; this runs only when native
 * decode fails for a track (DST-compressed .dff, an unsupported DSF
 * variant, a malformed header) — via PlayerManager.onPlayerError.
 *
 * Fixes in 1.0.9 (the "DSD still doesn't play" round):
 * - In-flight dedupe: re-tapping a DSD track used to abandon the running
 *   decode, so a track could never finish converting if the user tapped
 *   twice. One decode job per track is now shared across calls.
 * - Cache validation: a corrupt/truncated cached WAV was served forever.
 *   The RIFF check now runs on cache hits too, and a bad cache is deleted
 *   and re-decoded.
 * - Cache key includes file size + last-modified, so a replaced .dsf at
 *   the same path no longer serves a stale WAV.
 * - The copied DSD input (dsd-input/) is deleted after decode, and the
 *   WAV cache is pruned to 512 MB (v1.1.5, was ~1 GB), so storage can no
 *   longer fill up silently and make every later decode fail with ENOSPC.
 * - renameTo's result is now checked (it was ignored, and a failed
 *   rename was reported as success pointing at a stale file).
 */
object DsdDecoder {
    private val decodeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inflight = mutableMapOf<String, Deferred<Uri?>>()

    /** v1.1.5: hard cap for the whole-file transcode WAV cache (see [pruneCache]). */
    const val DSD_CACHE_CAP_BYTES: Long = 512L * 1024 * 1024

    private fun cacheKey(track: Track): String {
        val f = File(track.path)
        val extra = if (f.exists()) "|${f.length()}|${f.lastModified()}" else ""
        return MessageDigest.getInstance("MD5")
            .digest("${track.path}|${track.id}$extra".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun cacheFile(context: Context, track: Track): File {
        val dir = File(context.cacheDir, "dsd-cache").apply { mkdirs() }
        return File(dir, "${cacheKey(track)}.wav")
    }

    private fun isValidWav(f: File): Boolean = runCatching {
        val head = ByteArray(12)
        f.inputStream().use { it.read(head) }
        head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte()
    }.getOrDefault(false)

    fun cachedUriIfExists(context: Context, track: Track): Uri? {
        val f = cacheFile(context, track)
        if (!f.exists() || f.length() <= 1024) return null
        if (!isValidWav(f)) {
            MioLog.event(context, "DSD cache invalid, deleting and re-decoding: ${track.title}")
            f.delete()
            return null
        }
        return Uri.fromFile(f)
    }

    /**
     * v1.1.2 (Fix 4): best-effort delete of ONE track's stale cached WAV.
     * Called by PlayerManager when a DSD track has just proved it plays
     * natively (STATE_READY with no fallback recovery), so the ~200 MB
     * transcode WAVs left over from when native decode kept failing
     * shrink away on their own instead of piling up in the cache.
     *
     * Safety: never deletes while a decode is in flight for this track
     * (the [inflight] key is present) — the caller must additionally
     * only call this for tracks NOT in its recovered set, i.e. tracks
     * the fallback does not need. Returns true only if a cached WAV
     * file was present and deleted; false if there was nothing to delete,
     * a decode is in flight, or the delete failed. Never throws.
     * The manual "Clear DSD cache" button (Detail.kt) is untouched and
     * still wipes the whole dsd-cache directory.
     */
    fun deleteCachedWav(context: Context, track: Track): Boolean {
        val key = cacheKey(track)
        synchronized(inflight) {
            if (inflight.containsKey(key)) {
                MioLog.event(context, "DSD cache keep '${track.title}': decode in flight, not deleting")
                return false
            }
        }
        return runCatching {
            val f = cacheFile(context, track)
            if (!f.exists()) return false
            val sizeKb = f.length() / 1024
            if (f.delete()) {
                MioLog.event(context, "DSD cache pruned '${track.title}': deleted stale WAV ${sizeKb}KB (native playback OK)")
                true
            } else {
                MioLog.event(context, "DSD cache prune FAILED '${track.title}': could not delete cached WAV")
                false
            }
        }.getOrElse {
            MioLog.event(context, "DSD cache prune FAILED '${track.title}': ${it::class.java.simpleName} ${it.message}")
            false
        }
    }

    /** v1.1.5: keep the WAV cache under 512 MB — delete OLDEST files first.
     *  The old 1 GB cap let the app grow to ~500 MB of stale transcode WAVs
     *  (user log 2026-11-21: Total 509 MB, Cache 409 MB) while native decode
     *  kept failing; every failed native play added another ~200 MB WAV.
     *  512 MB still holds two full DSD128 album-tracks' WAVs, and the
     *  transcode is only a last-resort fallback now — native playback (when
     *  it works) deletes its own stale WAV via deleteCachedWav. Never
     *  deletes a WAV whose decode is in flight (the [inflight] key is the
     *  cache filename without ".wav"). Logs what it deleted so the next
     *  user log shows the cache shrinking instead of growing. */
    private fun pruneCache(context: Context) {
        runCatching {
            val dir = File(context.cacheDir, "dsd-cache")
            val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
            var total = files.sumOf { it.length() }
            val cap = DSD_CACHE_CAP_BYTES
            if (total <= cap) return
            val inflightKeys = synchronized(inflight) { inflight.keys.toSet() }
            var deletedBytes = 0L
            var deletedCount = 0
            for (f in files) {
                if (total <= cap) break
                if (inflightKeys.contains(f.nameWithoutExtension)) continue
                val size = f.length()
                if (f.delete()) {
                    total -= size
                    deletedBytes += size
                    deletedCount++
                }
            }
            if (deletedCount > 0) {
                MioLog.event(
                    context,
                    "DSD cache pruned to cap: deleted $deletedCount WAV(s) ${deletedBytes / 1024}KB, total now ${total / 1024}KB (cap ${cap / 1024 / 1024}MB)"
                )
            }
        }
    }

    /**
     * v1.1.5: public entry point for the startup prune (PlayerManager init).
     * The cap used to be enforced only inside doDecode — i.e. only when a
     * NEW transcode ran — so a 409 MB pile of stale WAVs sat there forever
     * once native decode started working. Pruning at startup (off the main
     * thread) makes the cap hold even when nothing new is decoded. Never
     * throws; never deletes in-flight decodes (see [pruneCache]).
     */
    fun pruneCacheNow(context: Context) {
        pruneCache(context)
    }

    /** Resolve the DSD file to a real readable file. MediaStore tracks
     *  usually have a filesystem path, but SAF-folder tracks store only a
     *  RELATIVE path (e.g. "Artist/Album/song.dsf") — File(path) never
     *  exists for those, so every DSD decode silently failed and the
     *  player got the raw .dsf it cannot play. Fall back to copying the
     *  bytes through ContentResolver using the track's content URI.
     *  Returns the file plus true when it is a disposable copy. */
    private fun resolveInputFile(context: Context, track: Track): Pair<File, Boolean>? {
        val direct = File(track.path)
        if (direct.exists() && direct.canRead()) return direct to false
        return runCatching {
            val ext = track.path.substringAfterLast('.', "").takeIf { it.length in 1..4 } ?: "dsf"
            val tmp = File(context.cacheDir, "dsd-input/${track.id}.$ext").apply { parentFile?.mkdirs() }
            context.contentResolver.openInputStream(track.uri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            } ?: return null
            if (tmp.exists() && tmp.length() > 1024) tmp to true else null
        }.getOrNull()
    }

    /** Returns the URI to play: cached WAV if present, else converts then returns it, else null. */
    suspend fun ensureDecoded(context: Context, track: Track): Uri? {
        cachedUriIfExists(context, track)?.let { return it }
        val key = cacheKey(track)
        val job: Deferred<Uri?> = synchronized(inflight) {
            inflight[key] ?: decodeScope.async { doDecode(context, track) }.also { inflight[key] = it }
        }
        return try {
            job.await()
        } finally {
            synchronized(inflight) { if (inflight[key] === job) inflight.remove(key) }
        }
    }

    private suspend fun doDecode(context: Context, track: Track): Uri? = withContext(Dispatchers.IO) {
        cachedUriIfExists(context, track)?.let { return@withContext it }
        pruneCache(context)
        val (input, isCopy) = resolveInputFile(context, track) ?: run {
            MioLog.event(context, "DSD decode FAIL '${track.title}': cannot read input (path='${track.path}')")
            return@withContext null
        }
        val output = cacheFile(context, track)
        // The temp file MUST end in .wav: ffmpeg guesses the output format
        // from the extension, and "<hash>.wav.tmp" made it fail every DSD
        // file with "Unable to choose an output format" (rc=1) — that was
        // the 1.0.6 failure. "<hash>.tmp.wav" still guesses WAV and is
        // never mistaken for a finished cache entry (cache is "<hash>.wav").
        val tmp = File(output.parentFile, output.nameWithoutExtension + ".tmp.wav")
        val t0 = System.currentTimeMillis()
        try {
            runCatching {
                val session = FFmpegKit.execute(DsdSupport.ffmpegCommand(input.absolutePath, tmp.absolutePath))
                if (ReturnCode.isSuccess(session.returnCode) && tmp.exists() && tmp.length() > 1024 && isValidWav(tmp)) {
                    if (!tmp.renameTo(output)) {
                        output.delete()
                        if (!tmp.renameTo(output)) {
                            tmp.copyTo(output, overwrite = true)
                            tmp.delete()
                        }
                    }
                    if (output.exists() && isValidWav(output)) {
                        MioLog.event(context, "DSD decoded '${track.title}' in ${System.currentTimeMillis() - t0}ms -> ${output.length() / 1024}KB WAV")
                        Uri.fromFile(output)
                    } else {
                        MioLog.event(context, "DSD decode FAIL '${track.title}': output WAV invalid after rename")
                        null
                    }
                } else {
                    MioLog.event(context, "DSD decode FAIL '${track.title}': rc=${session.returnCode} tail=${session.output?.takeLast(500)}")
                    tmp.delete()
                    null
                }
            }.getOrElse {
                MioLog.event(context, "DSD decode EXCEPTION '${track.title}': ${it::class.java.simpleName} ${it.message}")
                null
            }
        } finally {
            // The copied input was only needed for ffmpeg; keeping it
            // doubled the storage cost of every DSD track (and could fill
            // the disk). Delete it whether the decode worked or not.
            if (isCopy) input.delete()
            tmp.delete()
        }
    }
}
