package com.mio.player.data.ota

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.mio.player.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * OTA update via GitHub Releases — no Play Store, no server of our own.
 *
 * Flow:
 * 1. GET https://api.github.com/repos/Melbournejr/Mio_Player/releases/latest
 *    (public repo, no auth needed)
 * 2. Compare tag_name (e.g. "v1.0.1") with BuildConfig.VERSION_NAME.
 * 3. If newer, download the .apk asset from the release and prompt install
 *    via FileProvider + ACTION_VIEW. Android 8+ needs the user to allow
 *    "Install unknown apps" for Mio Player once — we open that settings
 *    page when needed.
 *
 * A release is created by .github/workflows/release.yml when a tag v* is
 * pushed. Without a release, checkForUpdate() returns null (up to date).
 */
data class UpdateInfo(
    val tag: String,
    val versionName: String,
    val changelog: String,
    val apkUrl: String,
    val htmlUrl: String,
)

object OtaUpdateChecker {
    const val REPO = "Melbournejr/Mio_Player"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"

    fun compareVersions(a: String, b: String): Int {
        val pa = a.trimStart('v', 'V').split('.', '-').mapNotNull { it.toIntOrNull() }
        val pb = b.trimStart('v', 'V').split('.', '-').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "MioPlayer-OTA")
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            if (conn.responseCode != 200) return@withContext null
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.optString("tag_name", "")
            if (tag.isBlank()) return@withContext null
            val versionName = tag.trimStart('v', 'V')
            if (compareVersions(versionName, BuildConfig.VERSION_NAME) <= 0) return@withContext null
            val assets = json.optJSONArray("assets") ?: return@withContext null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url", "")
                    break
                }
            }
            if (apkUrl.isNullOrBlank()) return@withContext null
            UpdateInfo(
                tag = tag,
                versionName = versionName,
                changelog = json.optString("body", "").take(2000),
                apkUrl = apkUrl,
                htmlUrl = json.optString("html_url", ""),
            )
        }.getOrNull()
    }

    /** Download the APK to cache and return the file. Reports 0..100 progress. */
    suspend fun downloadApk(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val file = File(dir, "mio-player-${info.versionName}.apk")
                if (file.exists() && file.length() > 0) return@withContext file
                val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", "MioPlayer-OTA")
                    connectTimeout = 15_000
                    readTimeout = 60_000
                }
                if (conn.responseCode !in 200..299) return@withContext null
                val total = conn.contentLengthLong
                var done = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(file).use { out ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress(((done * 100 / total).toInt()).coerceIn(0, 100))
                        }
                    }
                }
                file
            }.getOrNull()
        }

    fun installApk(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pm = context.packageManager
            if (!pm.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun currentVersionLabel(): String =
        "Mio Player ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
}
