package com.mio.player.data.audio

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.mio.player.data.log.MioLog
import com.mio.player.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Decodes a DSD track (.dsf/.dff) to a cached PCM WAV once, using FFmpegKit.
 * See DsdSupport.kt for why this route was chosen.
 */
object DsdDecoder {
    private fun cacheFile(context: Context, track: Track): File {
        val dir = File(context.cacheDir, "dsd-cache").apply { mkdirs() }
        val key = MessageDigest.getInstance("MD5")
            .digest("${track.path}|${track.id}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$key.wav")
    }

    fun cachedUriIfExists(context: Context, track: Track): Uri? {
        val f = cacheFile(context, track)
        return if (f.exists() && f.length() > 1024) Uri.fromFile(f) else null
    }

    /** Resolve the DSD file to a real readable file. MediaStore tracks
     *  usually have a filesystem path, but SAF-folder tracks store only a
     *  RELATIVE path (e.g. "Artist/Album/song.dsf") — File(path) never
     *  exists for those, so every DSD decode silently failed and the
     *  player got the raw .dsf it cannot play. Fall back to copying the
     *  bytes through ContentResolver using the track's content URI. */
    private fun resolveInputFile(context: Context, track: Track): File? {
        val direct = File(track.path)
        if (direct.exists() && direct.canRead()) return direct
        return runCatching {
            val ext = track.path.substringAfterLast('.', "").takeIf { it.length in 1..4 } ?: "dsf"
            val tmp = File(context.cacheDir, "dsd-input/${track.id}.$ext").apply { parentFile?.mkdirs() }
            context.contentResolver.openInputStream(track.uri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            } ?: return null
            if (tmp.exists() && tmp.length() > 1024) tmp else null
        }.getOrNull()
    }

    private fun isValidWav(f: File): Boolean = runCatching {
        val head = ByteArray(12)
        f.inputStream().use { it.read(head) }
        head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte()
    }.getOrDefault(false)

    /** Returns the URI to play: cached WAV if present, else converts then returns it, else null. */
    suspend fun ensureDecoded(context: Context, track: Track): Uri? = withContext(Dispatchers.IO) {
        cachedUriIfExists(context, track)?.let { return@withContext it }
        val input = resolveInputFile(context, track) ?: run {
            MioLog.event(context, "DSD decode FAIL '${track.title}': cannot read input (path='${track.path}')")
            return@withContext null
        }
        val output = cacheFile(context, track)
        val tmp = File(output.parentFile, output.name + ".tmp")
        val t0 = System.currentTimeMillis()
        runCatching {
            val session = FFmpegKit.execute(DsdSupport.ffmpegCommand(input.absolutePath, tmp.absolutePath))
            if (ReturnCode.isSuccess(session.returnCode) && tmp.exists() && tmp.length() > 1024 && isValidWav(tmp)) {
                tmp.renameTo(output)
                MioLog.event(context, "DSD decoded '${track.title}' in ${System.currentTimeMillis() - t0}ms -> ${output.length() / 1024}KB WAV")
                Uri.fromFile(output)
            } else {
                MioLog.event(context, "DSD decode FAIL '${track.title}': rc=${session.returnCode} tail=${session.output?.takeLast(300)}")
                tmp.delete()
                null
            }
        }.getOrElse {
            MioLog.event(context, "DSD decode EXCEPTION '${track.title}': ${it::class.java.simpleName} ${it.message}")
            null
        }
    }
}
