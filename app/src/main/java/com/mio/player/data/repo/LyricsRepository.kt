package com.mio.player.data.repo
import android.content.Context
import com.mio.player.data.db.AppDatabase
import com.mio.player.data.db.LyricsEntity
import com.mio.player.data.log.MioLog
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
 *  1.0.12: two more reasons lyrics came out wrong or missing.
 *  (a) CACHE POISONING: a wrong lyric fetched once (e.g. a cover, or a
 *  same-name song by another artist) was cached forever and served on
 *  every later play, even after the lookup logic improved. The cache is
 *  now cleared once when the lyrics engine version bumps (see
 *  clearStaleCacheIfNeeded, called from MioApp.onCreate), so every song
 *  is re-fetched with the current logic.
 *  (b) SEARCH DID NOT VERIFY THE ARTIST: LRCLIB /search is fuzzy — it
 *  can return another artist's song with the same title, and the old
 *  code took the first result with synced lyrics. Results are now
 *  accepted only when artist AND title match the track. */
class LyricsRepository(private val ctx: Context, private val db: AppDatabase) {
    private val client = OkHttpClient()
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    /** One-time cache clear when the lyrics engine changes, so stale
     *  wrong lyrics fetched by an older, dumber lookup are re-fetched
     *  instead of being served forever. */
    suspend fun clearStaleCacheIfNeeded() {
        val prefs = ctx.getSharedPreferences("mio_lyrics", Context.MODE_PRIVATE)
        if (prefs.getInt("engine_version", 0) < 2) {
            withContext(Dispatchers.IO) { db.lyricsDao().clearAll() }
            prefs.edit().putInt("engine_version", 2).apply()
            MioLog.event(ctx, "Lyrics cache cleared (engine v2)")
        }
    }
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
        // 4) Netease Cloud Music — free, no key, and it HAS synced LRC
        //    for many songs LRCLIB does not (huge catalogue, incl.
        //    Western pop). Search by "artist title", then fetch the
        //    song's LRC. Only used when LRCLIB found nothing.
        if (queryArtist != null) {
            for (title in titleVariants) {
                tryNetease(queryArtist, title, durationMs, artist, title)?.let { return it }
            }
        }
        // 5) Last resort: lyrics.ovh — free, no key, plain lyrics only
        //    (no sync). Better than "No lyrics found" for songs neither
        //    LRCLIB nor Netease has.
        if (queryArtist != null) {
            for (title in titleVariants) {
                tryLyricsOvh(queryArtist, title, artist, title)?.let { return it }
            }
        }
        return null
    }
    /** Compare ignoring case, punctuation, spaces and "feat." extras:
     *  "Adele" matches "Adele", "Adele feat. X" matches "Adele". */
    private fun normKey(s: String): String =
        s.lowercase().replace(Regex("""(?i)\s+(feat\.?|ft\.?|featuring)\s+.*$"""), "")
            .replace(Regex("""[^\p{L}\p{N}]"""), "")
    private fun matchesArtist(resultArtist: String, queryArtist: String?): Boolean {
        if (queryArtist.isNullOrBlank()) return true
        val r = normKey(resultArtist); val q = normKey(queryArtist)
        return r.isNotEmpty() && q.isNotEmpty() && (r == q || r.contains(q) || q.contains(r))
    }
    private fun matchesTitle(resultTitle: String, queryTitle: String): Boolean {
        val r = normKey(normalizeTitle(resultTitle)); val q = normKey(normalizeTitle(queryTitle))
        return r.isNotEmpty() && q.isNotEmpty() && (r == q || r.contains(q) || q.contains(r))
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
                // 1.0.12: LRCLIB /search is fuzzy — verify the result is
                // really this track (same title, same artist) before
                // accepting it, or a same-name song by another artist
                // (or a cover) gets served as "the" lyrics.
                if (!matchesTitle(obj.optString("trackName"), queryTitle)) continue
                if (!matchesArtist(obj.optString("artistName"), queryArtist)) continue
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
    /** Netease Cloud Music (music.163.com) — free, no key. Search for the
     *  song, verify artist + title + duration, then fetch its synced LRC.
     *  Needs a browser User-Agent + Referer or the API returns nothing. */
    private suspend fun tryNetease(queryArtist: String, queryTitle: String, durationMs: Long, cacheArtist: String, cacheTitle: String): Lyrics? {
        return try {
            val searchUrl = "https://music.163.com/api/search/get?s=${enc("$queryArtist $queryTitle")}&type=1&offset=0&limit=5"
            val searchBody = getNetease(searchUrl) ?: return null
            val songs = JSONObject(searchBody).optJSONObject("result")?.optJSONArray("songs") ?: return null
            var songId = -1L
            for (i in 0 until songs.length()) {
                val s = songs.getJSONObject(i)
                if (!matchesTitle(s.optString("name"), queryTitle)) continue
                val artists = s.optJSONArray("artists")
                var artistOk = false
                if (artists != null) for (j in 0 until artists.length()) {
                    if (matchesArtist(artists.getJSONObject(j).optString("name"), queryArtist)) { artistOk = true; break }
                }
                if (!artistOk) continue
                val durMs = s.optLong("duration")
                if (durationMs > 0 && durMs > 0 && kotlin.math.abs(durMs - durationMs) > 8000L) continue
                songId = s.optLong("id")
                break
            }
            if (songId < 0) return null
            val lyricBody = getNetease("https://music.163.com/api/song/lyric?id=$songId&lv=1&kv=1&tv=-1") ?: return null
            val lrc = JSONObject(lyricBody).optJSONObject("lrc")?.optString("lyric")?.takeIf { it.isNotBlank() } ?: return null
            val lines = parseLrc(lrc)
            if (lines.isEmpty()) return null
            withContext(Dispatchers.IO) { db.lyricsDao().put(LyricsEntity(cacheArtist, cacheTitle, null, durationMs, lrc, null, "netease")) }
            Lyrics(0, lines, null, "netease")
        } catch (e: Exception) { null }
    }
    private suspend fun getNetease(url: String): String? = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
            .header("Referer", "https://music.163.com/")
            .build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
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
            // Netease LRC starts with credit lines ("作词 : Adele ...")
            // tagged at 00:00 — they are not lyrics, drop them.
            if (textPart.matches(Regex("""^(作词|作曲|编曲|制作人|演唱|词曲|Written by|Composed by)\s*[:：].*"""))) continue
            for (m in tags) {
                val ms = m.groupValues[1].toLong() * 60000 + (m.groupValues[2].toDouble() * 1000).toLong() + offsetMs
                out += LyricLine(ms, textPart)
            }
        }
        return out.sortedBy { it.timeMs }
    }
}
