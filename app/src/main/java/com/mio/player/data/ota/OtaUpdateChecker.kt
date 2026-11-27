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
import java.util.zip.ZipException
import java.util.zip.ZipFile

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

    /** v1.1.51 — a check can end three ways. The old code folded
     *  every failure (GitHub API rate limit on a shared mobile IP,
     *  no network, non-200) into "up to date", so the app told the
     *  user "You are on the latest version" while a newer release
     *  existed. Failures are now reported as failures. */
    sealed interface CheckResult {
        data class Available(val info: UpdateInfo) : CheckResult
        data object UpToDate : CheckResult
        data class Failed(val reason: String) : CheckResult
    }

    suspend fun checkForUpdate(): UpdateInfo? =
        (checkForUpdateDetailed() as? CheckResult.Available)?.info

    suspend fun checkForUpdateDetailed(): CheckResult = withContext(Dispatchers.IO) {
        val api = runCatching { checkViaApi() }
        api.getOrNull()?.let { return@withContext it }
        // API failed (rate limit / network / non-200): fall back to
        // the github.com "latest" redirect, which is not API
        // rate-limited. The release workflow always names the
        // asset Mio_Player-<tag>.apk, so the download URL can be
        // built from the tag in the redirect Location.
        val web = runCatching { checkViaWebRedirect() }
        web.getOrNull()?.let { return@withContext it }
        val why = api.exceptionOrNull()?.message ?: web.exceptionOrNull()?.message ?: "không rõ"
        CheckResult.Failed(why)
    }

    private fun checkViaApi(): CheckResult {
        val conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "MioPlayer-OTA")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        if (conn.responseCode != 200) throw IllegalStateException("GitHub API HTTP ${conn.responseCode}")
        val json = JSONObject(conn.inputStream.bufferedReader().readText())
        val tag = json.optString("tag_name", "")
        if (tag.isBlank()) throw IllegalStateException("GitHub API: thiếu tag_name")
        val versionName = tag.trimStart('v', 'V')
        if (compareVersions(versionName, BuildConfig.VERSION_NAME) <= 0) return CheckResult.UpToDate
        val assets = json.optJSONArray("assets") ?: throw IllegalStateException("GitHub API: thiếu assets")
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name", "")
            if (name.endsWith(".apk", ignoreCase = true)) {
                apkUrl = a.optString("browser_download_url", "")
                break
            }
        }
        if (apkUrl.isNullOrBlank()) throw IllegalStateException("GitHub API: release không có APK")
        return CheckResult.Available(
            UpdateInfo(
                tag = tag,
                versionName = versionName,
                changelog = json.optString("body", "").take(2000),
                apkUrl = apkUrl,
                htmlUrl = json.optString("html_url", ""),
            )
        )
    }

    private fun checkViaWebRedirect(): CheckResult {
        val conn = (URL("https://github.com/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "MioPlayer-OTA")
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = false
        }
        val location = conn.getHeaderField("Location") ?: throw IllegalStateException("github.com không trả redirect")
        val tag = location.substringAfterLast("/tag/").substringBefore("/").trim()
        if (tag.isBlank() || !tag.startsWith("v")) throw IllegalStateException("github.com redirect lạ: $location")
        val versionName = tag.trimStart('v', 'V')
        if (compareVersions(versionName, BuildConfig.VERSION_NAME) <= 0) return CheckResult.UpToDate
        return CheckResult.Available(
            UpdateInfo(
                tag = tag,
                versionName = versionName,
                changelog = "",
                apkUrl = "https://github.com/$REPO/releases/download/$tag/Mio_Player-$tag.apk",
                htmlUrl = "https://github.com/$REPO/releases/tag/$tag",
            )
        )
    }

    /**
     * True only if [file] is a complete, structurally valid APK (ZIP).
     *
     * Opening it with [ZipFile] reads the ZIP end-of-central-directory (EOCD)
     * record at the very end of the file, so any file truncated by an
     * interrupted download fails here with [ZipException] (or returns no
     * entries) instead of being treated as a finished download. A valid APK
     * always contains entries (AndroidManifest.xml, classes.dex, ...).
     */
    fun isValidApk(file: File): Boolean {
        if (!file.isFile || file.length() <= 0) return false
        return runCatching {
            ZipFile(file).use { zip ->
                zip.entries().hasMoreElements()
            }
        }.getOrDefault(false)
    }

    /**
     * Download the APK to cache and return the file, or null on failure.
     * Reports 0..100 progress.
     *
     * Safety against interrupted downloads (user report 2026-11-21):
     * - The download goes to a ".tmp" file in the same directory. Only a
     *   download that finishes AND passes validation is renamed to the
     *   final "mio-player-<version>.apk" path, so the final path never
     *   holds a partial file written by this version.
     * - Completion is validated two ways: the bytes received must match
     *   the release asset's Content-Length, and the file must open as a
     *   valid ZIP/APK (see [isValidApk]). A failed check deletes the
     *   partial file and returns null, so the next check re-downloads
     *   from 0% instead of offering "Install" for a broken file.
     * - Legacy partials: a partial file left at the final path by the old
     *   version (which wrote there directly) is validated the same way
     *   and deleted if invalid, never returned as complete.
     * - No HTTP Range resume: a stale .tmp is discarded and the download
     *   restarts from 0%. Simpler and always correct.
     */
    suspend fun downloadApk(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val file = File(dir, "mio-player-${info.versionName}.apk")
            val tmpFile = File(dir, "mio-player-${info.versionName}.apk.tmp")

            // Legacy partial (or a good file) left at the final path by the
            // old version: only a structurally valid APK may be reused.
            // An invalid one is deleted so it can never reach installApk.
            if (file.exists()) {
                if (isValidApk(file)) return@withContext file
                file.delete()
            }
            // A stale temp file means a previous download was interrupted;
            // discard it and start again from 0% (no resume).
            tmpFile.delete()

            runCatching {
                val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", "MioPlayer-OTA")
                    connectTimeout = 15_000
                    readTimeout = 60_000
                }
                if (conn.responseCode !in 200..299) return@withContext null
                val total = conn.contentLengthLong
                var done = 0L
                try {
                    conn.inputStream.use { input ->
                        FileOutputStream(tmpFile).use { out ->
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
                } catch (e: Exception) {
                    // Interrupted mid-download (network drop, storage error,
                    // ...): never leave a partial file behind.
                    tmpFile.delete()
                    throw e
                }
                // Size check: a download that ends early will not match the
                // release asset's Content-Length.
                if (total > 0 && done != total) {
                    tmpFile.delete()
                    return@withContext null
                }
                // Structural check: the file must be a complete, valid APK.
                if (!isValidApk(tmpFile)) {
                    tmpFile.delete()
                    return@withContext null
                }
                // Only now does the file become the installable one.
                if (tmpFile.renameTo(file)) return@withContext file
                // renameTo can fail across filesystems; fall back to copy.
                runCatching {
                    tmpFile.copyTo(file, overwrite = true)
                    tmpFile.delete()
                }.onFailure {
                    // The temp file itself is a valid APK, so it is still
                    // safe to install; the next check will just re-download
                    // because the final path is still empty.
                    return@withContext tmpFile
                }
                file
            }.onFailure {
                tmpFile.delete()
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
