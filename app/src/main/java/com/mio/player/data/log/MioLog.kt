package com.mio.player.data.log
import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
/** Crash + event log, kept in the app's private files so a sudden exit
 *  stops being a guessing game: the Java crash (if any) lands in
 *  crash.log with its full stack trace, and the last playback events
 *  (decode start/finish/fail, service start, player errors) land in
 *  mio-log.txt. Both are viewable and shareable from Profile ->
 *  "Nhật ký lỗi". A native crash (ffmpeg / audio framework) leaves no
 *  Java stack trace, but the last event line still shows what the app
 *  was doing when it died. */
object MioLog {
    private const val MAX_LINES = 300
    private fun logFile(ctx: Context) = File(ctx.filesDir, "mio-log.txt")
    private fun crashFile(ctx: Context) = File(ctx.filesDir, "crash.log")
    fun event(ctx: Context, msg: String) {
        try {
            val f = logFile(ctx)
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val lines = if (f.exists()) f.readLines() else emptyList()
            f.writeText((lines + "[$stamp] $msg").takeLast(MAX_LINES).joinToString("\n"))
        } catch (_: Exception) {}
    }
    fun readLog(ctx: Context): String =
        runCatching { logFile(ctx).takeIf { it.exists() }?.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "(chưa có nhật ký sự kiện)"
    fun readCrash(ctx: Context): String =
        runCatching { crashFile(ctx).takeIf { it.exists() }?.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "(chưa có crash log — app chưa từng crash kể từ khi cài bản này)"
    fun installCrashHandler(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                crashFile(ctx).appendText("\n===== CRASH $stamp thread=${t.name} =====\n${Log.getStackTraceString(e)}\n")
                event(ctx, "CRASH: ${e::class.java.simpleName}: ${e.message}")
            } catch (_: Exception) {}
            if (prev != null) prev.uncaughtException(t, e)
            else android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
