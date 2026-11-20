package com.mio.player.data.audio

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
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

    /** Returns the URI to play: cached WAV if present, else converts then returns it, else null. */
    suspend fun ensureDecoded(context: Context, track: Track): Uri? = withContext(Dispatchers.IO) {
        cachedUriIfExists(context, track)?.let { return@withContext it }
        val input = File(track.path)
        if (!input.exists() || !input.canRead()) return@withContext null
        val output = cacheFile(context, track)
        val tmp = File(output.parentFile, output.name + ".tmp")
        runCatching {
            val session = FFmpegKit.execute(DsdSupport.ffmpegCommand(input.absolutePath, tmp.absolutePath))
            if (ReturnCode.isSuccess(session.returnCode) && tmp.exists() && tmp.length() > 1024) {
                tmp.renameTo(output)
                Uri.fromFile(output)
            } else {
                tmp.delete()
                null
            }
        }.getOrNull()
    }
}
