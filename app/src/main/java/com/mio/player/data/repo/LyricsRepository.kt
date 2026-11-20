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
/** Lyrics: sidecar .lrc -> LRCLIB (free, no key) -> lyrics.ovh (free,
 *  plain lyrics only) -> cache in Room.
 *  API: GET https://lrclib.net/api/get?artist_name=&track_name=&album_name=&duration=
 *       GET https://lrclib.net/api/search?track_name=&artist_name=
 *       GET https://api.lyrics.ovh/v1/{artist}/{title}
 *  Synced lyrics are parsed from LRC [mm:ss.xx] lines.
 *  NOTE: embedded lyrics are NOT read (no reliable MediaMetadataRetriever
 *  key for them); the sidecar .lrc file next to the song is the local
 *  source that always works.
 *  1.0.11: the 1.0.10 user log proved the old lookup missed many songs
 *  for a dumb reason — local file titles carry junk LRCLIB never has:
 *  "A1.Hello" (side+track prefix from the vinyl-style file name),
 *  "01 - Hello", "Hello (2015 Remaster)". The lookup now tries the
 *  raw title AND a normalized title, with and without duration, and
 *  falls back to lyrics.ovh for plain lyrics when LRCLIB has nothing. */
class LyricsRepository(private val ctx: Context, private val db: AppDatabase) {
    private val client = OkHttpClient()
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    /** "A1.Hello" -> "Hello", "B2.River Lea" -> "River Lea",
     *  "01 - Hello" -> "Hello", "Hello (2015 Remaster)" -> "Hello".
     *  Generic "(...)" is NOT stripped — "Send My Love (To Your New
     *  Lover)" is the real title on LRCLIB. Only junk that marks a
     *  reissue (remaster/deluxe/bonus/anniversary) is removed. */
    fun normalizeTitle(raw: String): String {
        var t = raw.trim()
        t = t.replace(Regex("""^[A-Da-d]\d{1,2}[.\s\-–—]+"""), "")
        t = t.replace(Regex("""^\d{1,2}[.\s\-–—]+"""), "")
        t = t.replace(Regex("""(?i)\s*\([^)]*(remaster|remastered|deluxe|bonus track|anniversary|expanded|special edition)[^)]*\)"""), "")
        t = t.replace(Regex("""(?i)\s*[-–—]\s*(remaster|remastered|deluxe|bonus track|anniversary).*$"""), "")
        return t.replace(Regex("""\s+"""), " ").trim()
    }
    /** "Adele feat. X" -> "Adele": LRCLIB stores the main artist. */
    fun normalizeArtist(raw: String): String =
        raw.replace(Regex("""(?i)\s+(feat\.?|ft\.?|featuring)\s+.*$"""), "").trim()
    suspend fun getLyrics(artist: String, title: String, album: String?, durationMs: Long, audioPath: String?): Lyrics? {
        db.lyricsDao().get(artist, title)?.let { e ->
            return Lyrics(0, parseLrc(e.syncedLrc), e.plainText, e.source)
        }
        // 1) sidecar .lrc next to the audio file
        audioPath?.let { p ->
            val lrc = File(p.substringBeforeLast('.') + ".lrc")
            if (lrc.exists()) { val lines = parseLrc(lrc.readText()); if (lines.isNotEmpty()) return cache(artist, title, album, durationMs, lines, null, "sidecar .lrc") }
        }
        // Work out what to search for. MediaStore often has no real artist
        // ("Unknown Artist") and no real album ("Unknown Album") — sending
        // those to LRCLIB makes the exact lookup fail, so they are dropped.
        // Many local files are named "Artist - Title.ext": use that.
        var searchArtist = artist.takeIf { it.isNotBlank() && it != "Unknown Artist" }
        var searchTitle = title
        if (searchArtist == null) {
            val base = audioPath?.substringAfterLast('/')?.substringBeforeLast('.') ?: title
            val parts = base.split(" - ", limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                searchArtist = parts[0].trim()
                searchTitle = parts[1].trim()
            }
        }
        val searchAlbum = album?.takeIf { it.isNotBlank() && it != "Unknown Album" }
        val cleanArtist = searchArtist?.let { normalizeArtist(it) }
        val titleVariants = listOf(searchTitle, normalizeTitle(searchTitle)).distinct()
        // Query with the cleaned artist; cache under the ORIGINAL
        // artist/title so the next play of the same track hits the cache.
        val queryArtist = cleanArtist ?: searchArtist
        // 2) LRCLIB exact lookup. Try each title variant (raw, then
        //    normalized), without album first (an album mismatch is the
        //    most common reason the exact lookup fails), then with it,
        //    then without duration — a duration off by a few seconds
        //    (DSD tracks scanned via SAF report duration 0 or a rounded
        //    value) also makes the exact lookup miss.
        if (queryArtist != null) {
            for (title in titleVariants) {
                tryGet(queryArtist, title, null, durationMs, artist, title)?.let { return it }
                if (searchAlbum != null) tryGet(queryArtist, title, searchAlbum, durationMs, artist, title)?.let { return it }
                if (durationMs > 0) tryGet(queryArtist, title, null, 0, artist, title)?.let { return it }
            }
        }
        // 3) LRCLIB search by title (+ artist when known), each title
        //    variant in turn. Prefer a result that actually has synced
        //    lyrics, and among those the one whose duration is closest
        //    to the track (covers with the same name are common).
        for (title in titleVariants) {
            trySearch(queryArtist, title, durationMs, artist, title)?.let { return it }
        }
        // 4) Last resort: lyrics.ovh — free, no key, plain lyrics only
        //    (no sync). Better than "No lyrics found" for songs LRCLIB
        //    does not have.
        if (queryArtist != null) {
            for (title in titleVariants) {
                tryLyricsOvh(queryArtist, title, artist, title)?.let { return it }
            }
        }
        return null
    }
    private suspend fun tryGet(queryArtist: String, queryTitle: String, queryAlbum: String?, durationMs: Long, cacheArtist: String, cacheTitle: String): Lyrics? {
        return try {
            val url = StringBuilder("https://lrclib.net/api/get?artist_name=${enc(queryArtist)}&track_name=${enc(queryTitle)}")
            if (queryAlbum != null) url.append("&album_name=${enc(queryAlbum)}")
            if (durationMs > 0) url.append("&duration=${durationMs / 1000}")
            val body = get(url.toString()) ?: return null
            parseLrclib(body, cacheArtist, cacheTitle, queryAlbum, durationMs)
        } catch (e: Exception) { null }
    }
    private suspend fun trySearch(queryArtist: String?, queryTitle: String, durationMs: Long, cacheArtist: String, cacheTitle: String): Lyrics? {
        return try {
            val url = StringBuilder("https://lrclib.net/api/search?track_name=${enc(queryTitle)}")
            if (queryArtist != null) url.append("&artist_name=${enc(queryArtist)}")
            val body = get(url.toString()) ?: return null
            val arr = JSONArray(body)
            var fallback: String? = null
            var bestSynced: String? = null
            var bestDiff = Long.MAX_VALUE
            val wantSec = durationMs / 1000
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val json = obj.toString()
                if (obj.optString("syncedLyrics").isNotBlank()) {
                    // Prefer the synced result whose duration is closest
                    // to the track — same-name covers/remixes are common.
                    val diff = if (wantSec > 0 && obj.has("duration")) kotlin.math.abs(obj.optLong("duration") - wantSec) else 0L
                    if (bestSynced == null || diff < bestDiff) { bestSynced = json; bestDiff = diff }
                    if (diff <= 2L) break
                }
                if (fallback == null && obj.optString("plainLyrics").isNotBlank()) fallback = json
            }
            bestSynced?.let { parseLrclib(it, cacheArtist, cacheTitle, null, durationMs) }
                ?: fallback?.let { parseLrclib(it, cacheArtist, cacheTitle, null, durationMs) }
        } catch (e: Exception) { null }
    }
    /** lyrics.ovh: free, no key. Plain lyrics only — no synced lines —
     *  so it is only the fallback when LRCLIB has nothing at all. */
    private suspend fun tryLyricsOvh(queryArtist: String, queryTitle: String, cacheArtist: String, cacheTitle: String): Lyrics? {
        return try {
            val url = "https://api.lyrics.ovh/v1/${enc(queryArtist.replace('/', ' '))}/${enc(queryTitle.replace('/', ' '))}"
            val body = get(url) ?: return null
            val lyrics = JSONObject(body).optString("lyrics").takeIf { it.isNotBlank() } ?: return null
            withContext(Dispatchers.IO) { db.lyricsDao().put(LyricsEntity(cacheArtist, cacheTitle, null, 0, null, lyrics, "lyrics.ovh")) }
            Lyrics(0, emptyList(), lyrics, "lyrics.ovh")
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
    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).header("User-Agent", "MioPlayer/1.0").build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    }
    /** Parses LRC text. Handles [mm:ss.xx] and [mm:ss] tags, several tags
     *  on one line, metadata lines ([ti:], [ar:], [offset:] …) and the
     *  [offset:+/-ms] shift. The old parser required exactly one tag with
     *  a fractional second per line, so many real .lrc files parsed to
     *  zero lines. */
    fun parseLrc(text: String?): List<LyricLine> {
        if (text.isNullOrBlank()) return emptyList()
        var offsetMs = 0L
        val out = mutableListOf<LyricLine>()
        val tagRe = Regex("""\[(\d+):(\d+(?:\.\d+)?)\]""")
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("[offset:")) {
                offsetMs = line.substringAfter("[offset:").substringBefore(']').trim().toLongOrNull() ?: 0L
                continue
            }
            if (line.startsWith("[ti:") || line.startsWith("[ar:") || line.startsWith("[al:")
                || line.startsWith("[by:") || line.startsWith("[length:")) continue
            val tags = tagRe.findAll(line).toList()
            if (tags.isEmpty()) continue
            val textPart = line.substringAfterLast(']').trim()
            if (textPart.isEmpty()) continue
            for (m in tags) {
                val ms = m.groupValues[1].toLong() * 60000 + (m.groupValues[2].toDouble() * 1000).toLong() + offsetMs
                out += LyricLine(ms, textPart)
            }
        }
        return out.sortedBy { it.timeMs }
    }
}
