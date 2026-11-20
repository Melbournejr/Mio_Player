package com.mio.player.data.repo
import android.content.Context
import com.mio.player.data.db.AppDatabase
import com.mio.player.data.db.LyricsEntity
import com.mio.player.data.model.LyricLine
import com.mio.player.data.model.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
/** Lyrics: LRCLIB (free, no key) -> embedded lyrics -> sidecar .lrc -> cache in Room.
 *  API: GET https://lrclib.net/api/get?artist_name=&track_name=&album_name=&duration=
 *       GET https://lrclib.net/api/search?track_name=&artist_name=
 *  Synced lyrics are parsed from LRC [mm:ss.xx] lines. */
class LyricsRepository(private val ctx: Context, private val db: AppDatabase) {
    private val client = OkHttpClient()
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    suspend fun getLyrics(artist: String, title: String, album: String?, durationMs: Long, audioPath: String?): Lyrics? {
        db.lyricsDao().get(artist, title)?.let { e ->
            return Lyrics(0, parseLrc(e.syncedLrc), e.plainText, e.source)
        }
        // 1) sidecar .lrc next to the audio file
        audioPath?.let { p ->
            val lrc = File(p.substringBeforeLast('.') + ".lrc")
            if (lrc.exists()) { val lines = parseLrc(lrc.readText()); if (lines.isNotEmpty()) return cache(artist, title, album, durationMs, lines, null, "sidecar .lrc") }
        }
        // 2) LRCLIB
        return try {
            val url = "https://lrclib.net/api/get?artist_name=${enc(artist)}&track_name=${enc(title)}" +
                (album?.let { "&album_name=${enc(it)}" } ?: "") + "&duration=${durationMs/1000}"
            val body = get(url) ?: get("https://lrclib.net/api/search?track_name=${enc(title)}&artist_name=${enc(artist)}")?.let { searchFirst(it) }
            body?.let { parseLrclib(it, artist, title, album, durationMs) }
        } catch (e: Exception) { null }
    }
    private suspend fun cache(a: String, t: String, al: String?, d: Long, lines: List<LyricLine>, plain: String?, src: String): Lyrics {
        val lrcText = lines.joinToString("\n") { "[${it.timeMs/60000}:${(it.timeMs%60000)/1000.0}]${it.text}" }
        withContext(Dispatchers.IO) { db.lyricsDao().put(LyricsEntity(a, t, al, d, lrcText, plain, src)) }
        return Lyrics(0, lines, plain, src)
    }
    private suspend fun parseLrclib(json: String, a: String, t: String, al: String?, d: Long): Lyrics? {
        val o = JSONObject(json)
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() }
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() }
        if (synced == null && plain == null) return null
        val lines = parseLrc(synced ?: "")
        if (lines.isNotEmpty() || plain != null) {
            withContext(Dispatchers.IO) { db.lyricsDao().put(LyricsEntity(a, t, al, d, synced, plain, "LRCLIB")) }
        }
        return Lyrics(0, lines, plain, "LRCLIB")
    }
    private fun searchFirst(json: String): String? {
        val arr = JSONArray(json); return if (arr.length() > 0) arr.getJSONObject(0).toString() else null
    }
    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).header("User-Agent", "MioPlayer/1.0").build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    }
    fun parseLrc(text: String?): List<LyricLine> {
        if (text.isNullOrBlank()) return emptyList()
        val re = Regex("""\[(\d+):(\d+\.\d+)\](.*)""")
        return text.lines().mapNotNull { line ->
            re.matchEntire(line.trim())?.let {
                val ms = it.groupValues[1].toLong()*60000 + (it.groupValues[2].toDouble()*1000).toLong()
                LyricLine(ms, it.groupValues[3].trim())
            }
        }.sortedBy { it.timeMs }
    }
}
