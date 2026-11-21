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
 *  1.1.2: the artist itself was wrong — deriveArtistAlbumFromPath
 *  mistook Android path junk ("0" in /storage/emulated/0/...) for the
 *  artist, and "Adele 25-2015" for an artist name, so every source
 *  missed. Junk/digit folders are generic now, a derived artist is
 *  cleaned (year stripped) or dropped, the player's real duration is
 *  used when the stored one is 0, and an artist-less LRCLIB search
 *  (synced only, duration within 15 s) is the last resort. */
class LyricsRepository(private val ctx: Context, private val db: AppDatabase) {
    private val client = OkHttpClient()
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    /** One-time cache clear when the lyrics engine changes, so stale
     *  wrong lyrics fetched by an older, dumber lookup are re-fetched
     *  instead of being served forever. */
    suspend fun clearStaleCacheIfNeeded() {
        val prefs = ctx.getSharedPreferences("mio_lyrics", Context.MODE_PRIVATE)
        if (prefs.getInt("engine_version", 0) < 3) {
            withContext(Dispatchers.IO) { db.lyricsDao().clearAll() }
            prefs.edit().putInt("engine_version", 3).apply()
            MioLog.event(ctx, "Lyrics cache cleared (engine v3)")
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
    /** Folder names that carry no artist/album information. Compared
     *  case-insensitively, after trimming. */
    /** 1.1.2: Android path junk — absolute MediaStore paths look like
     *  /storage/emulated/0/Music/... and every one of those segments
     *  must be skipped, or the derivation mistakes "0" for the artist
     *  and every source fails. */
    private val genericFolderNames = setOf(
        "music", "dsd", "flac", "audio", "downloads", "download",
        "album", "albums", "songs", "mp3", "wav", "files",
        "storage", "emulated", "sdcard", "android", "data", "media",
        "primary", "document", "tree", "dcim", "obb"
    )
    private fun isGenericFolder(name: String): Boolean {
        val n = name.trim().lowercase()
        // An all-digit segment ("0" in /storage/emulated/0, a year, a
        // disc number) never names an artist or album.
        if (n in genericFolderNames || (n.isNotEmpty() && n.all { it.isDigit() })) return true
        // A reverse-domain package segment (com.foo, com.mio.player)
        // under Android/data/... is path junk, not an artist. Each
        // dot-part must be 2+ chars so "R.E.M." is not caught.
        return n.matches(Regex("""[a-z0-9_]{2,}(\.[a-z0-9_]{2,})+"""))
    }
    /** "Adele 25-2015" -> "Adele": library folders are often named
     *  "Artist Album-Year" or "Artist Year", and the year / album
     *  number belongs to the album, not the artist. The trailing
     *  number is stripped only when a year was stripped first, so a
     *  real name like "Maroon 5" survives. Returns null when nothing
     *  usable remains (all digits, or no letters at all). */
    private fun cleanDerivedArtist(raw: String): String? {
        var a = raw.trim()
        val noYear = a.replace(Regex("""\s*[-–—]\s*(19|20)\d{2}\s*$"""), "")
            .replace(Regex("""\s+(19|20)\d{2}\s*$"""), "")
        if (noYear != a) a = noYear.replace(Regex("""\s+\d{1,4}\s*$"""), "").trim()
        a = a.trim()
        if (a.isBlank() || !a.any { it.isLetter() }) return null
        return a
    }
    /** MediaStore / the UI surface an unknown artist as "Unknown Artist"
     *  or "<unknown>" (the screenshot shows "<unknown>"); an unknown
     *  album likewise. None of these is a real name to query with, so
     *  all of them count as unknown here. */
    private fun isUnknownArtist(s: String): Boolean =
        s.trim().lowercase() in setOf("unknown artist", "<unknown>", "unknown")
    private fun isUnknownAlbum(s: String): Boolean =
        s.trim().lowercase() in setOf("unknown album", "<unknown>", "unknown")
    /** 1.1.1 FIX 4: derive (artist, album) from the folders containing
     *  the audio file, for tracks whose artist is unknown.
     *  - Parent folder "Artist - Album" -> artist = before " - ",
     *    album = after " - ".
     *  - Otherwise artist = grandparent folder, album = parent folder
     *    (e.g. ".../Adele/25/A1.Hello.dsf" -> Adele, album unknown —
     *    "25" is all digits and carries no album name).
     *  1.1.2: Android junk segments ("storage", "emulated", "0", ...)
     *  and all-digit folders are generic now, so an absolute path like
     *  /storage/emulated/0/Music/Adele 25-2015/A1.Hello.dff yields
     *  artist "Adele" (year/album number stripped) instead of "0".
     *  Generic folders are skipped first, so ".../Music/Adele/..."
     *  still yields Adele and ".../Downloads/Adele/song.dsf"
     *  yields Adele (album unknown) instead of mistaking "Downloads"
     *  for the artist or "Adele" for the album. With only one
     *  meaningful folder (e.g. ".../Adele/song.dsf") it is the artist
     *  and the album stays unknown. Works on relative SAF paths too
     *  ("Adele/25/song.dsf" -> Adele / 25) because only '/' segments
     *  are used. Never throws: any odd path just yields (null, null)
     *  and the lookup proceeds as before. */
    fun deriveArtistAlbumFromPath(audioPath: String?): Pair<String?, String?> {
        return runCatching {
            if (audioPath.isNullOrBlank()) return Pair(null, null)
            val segments = audioPath.replace('\\', '/').trim().trimEnd('/')
                .split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." }
            // Last segment is the file name; the rest are folders.
            if (segments.size < 2) return Pair(null, null)
            val folders = segments.dropLast(1).filterNot { isGenericFolder(it) }
            if (folders.isEmpty()) return Pair(null, null)
            val parent = folders.last()
            if (" - " in parent) {
                val parts = parent.split(" - ", limit = 2)
                // 1.1.2: clean the artist ("Adele 25-2015" -> "Adele")
                // and drop it entirely when it has no letters ("0").
                val derivedArtist = parts[0].trim()
                    .takeIf { it.isNotBlank() && !isGenericFolder(it) }
                    ?.let { cleanDerivedArtist(it) }
                val derivedAlbum = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() && !isGenericFolder(it) }
                return Pair(derivedArtist, derivedAlbum)
            }
            // 1.1.2: same cleaning on the grandparent / single-folder
            // artist — a bare "0" or "25" is not an artist.
            if (folders.size >= 2) return Pair(cleanDerivedArtist(folders[folders.size - 2]), folders.last())
            return Pair(cleanDerivedArtist(folders[0]), null)
        }.getOrDefault(Pair(null, null))
    }
    /** 1.1.2: playerDurationMs is the ExoPlayer duration (vm.pm.player
     *  .duration in the Lyrics screen). DSD tracks report durationMs 0
     *  from MediaStore/SAF, so when the stored duration is 0 the
     *  player's real length is used instead — it is what lets the
     *  duration check tell Adele's Hello (296 s) from Lionel Richie's
     *  (251 s). Defaults to 0, so existing 5-arg callers are unaffected. */
    suspend fun getLyrics(artist: String, title: String, album: String?, durationMs: Long, audioPath: String?, playerDurationMs: Long = 0): Lyrics? {
        db.lyricsDao().get(artist, title)?.let { e ->
            return Lyrics(0, parseLrc(e.syncedLrc), e.plainText, e.source)
        }
        // 1.1.2: effective duration — stored duration first, player's
        // real length when the stored one is 0 (DSD files).
        val effDurationMs = if (durationMs > 0) durationMs else playerDurationMs
        // 1) sidecar .lrc next to the audio file
        audioPath?.let { p ->
            val lrc = File(p.substringBeforeLast('.') + ".lrc")
            if (lrc.exists()) { val lines = parseLrc(lrc.readText()); if (lines.isNotEmpty()) return cache(artist, title, album, effDurationMs, lines, null, "sidecar .lrc") }
        }
        // Work out what to search for. MediaStore often has no real artist
        // ("Unknown Artist") and no real album ("Unknown Album") — sending
        // those to LRCLIB makes the exact lookup fail, so they are dropped.
        // Many local files are named "Artist - Title.ext": use that.
        var searchArtist = artist.takeIf { it.isNotBlank() && !isUnknownArtist(it) }
        var searchTitle = title
        if (searchArtist == null) {
            val base = audioPath?.substringAfterLast('/')?.substringBeforeLast('.') ?: title
            val parts = base.split(" - ", limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                // 1.1.2: a filename artist with no letters ("0 - Hello")
                // is junk, not an artist — leave it unknown so the path
                // derivation / artist-less search can run instead.
                searchArtist = parts[0].trim().takeIf { it.any { c -> c.isLetter() } }
                searchTitle = parts[1].trim()
            }
        }
        var searchAlbum = album?.takeIf { it.isNotBlank() && !isUnknownAlbum(it) }
        // 1.1.1 FIX 4: artist still unknown — derive it from the file
        // path. The screenshot proved the track artist is "<unknown>":
        // with a null query artist, matchesArtist() returns true for
        // ANY artist, so trySearch accepted the first same-title hit —
        // Adele's "Hello" got (very likely) Lionel Richie's "Hello".
        // Local libraries are usually laid out Artist/Album/file or
        // "Artist - Album"/file, so the folders name the artist/album
        // even when MediaStore and the filename do not. This runs only
        // when the filename "Artist - Title" split above did not yield
        // an artist (the derived album is also used to fill a missing
        // album, even when the artist came from the filename). Caching
        // still uses the ORIGINAL artist/title (cacheArtist/cacheTitle
        // below), so a later fix re-fetches under the same cache key.
        var folderArtist: String? = null
        var folderAlbum: String? = null
        if (searchArtist == null || searchAlbum == null) {
            val derived = deriveArtistAlbumFromPath(audioPath)
            folderArtist = derived.first; folderAlbum = derived.second
            if (searchArtist == null && folderArtist != null) searchArtist = folderArtist
            if (searchAlbum == null && folderAlbum != null) searchAlbum = folderAlbum
        }
        // 1.1.2: diagnostics — the next user log shows exactly what was
        // derived from the path and what was queried with.
        MioLog.event(ctx, "Lyrics query: title=\"$searchTitle\" artist=${searchArtist ?: "?"} (path-derived: ${folderArtist ?: "-"} / ${folderAlbum ?: "-"}) album=${searchAlbum ?: "?"} durMs=$effDurationMs path=$audioPath")
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
                tryGet(queryArtist, title, null, effDurationMs, artist, title)?.let { return it }
                if (searchAlbum != null) tryGet(queryArtist, title, searchAlbum, effDurationMs, artist, title)?.let { return it }
                if (effDurationMs > 0) tryGet(queryArtist, title, null, 0, artist, title)?.let { return it }
            }
        }
        // 3) LRCLIB search by title (+ artist when known), each title
        //    variant in turn. Prefer a result that actually has synced
        //    lyrics, and among those the one whose duration is closest
        //    to the track (covers with the same name are common).
        for (title in titleVariants) {
            // 1.1.2: with no artist to check against, duration is the
            // only thing telling same-title songs apart — refuse a
            // result whose length is >15 s off the track.
            val maxDiff = if (queryArtist == null && effDurationMs > 0) 15L else Long.MAX_VALUE
            trySearch(queryArtist, title, effDurationMs, artist, title, maxDiffSec = maxDiff)?.let { return it }
        }
        // 4) Netease Cloud Music — free, no key, and it HAS synced LRC
        //    for many songs LRCLIB does not (huge catalogue, incl.
        //    Western pop). Search by "artist title", then fetch the
        //    song's LRC. Only used when LRCLIB found nothing.
        if (queryArtist != null) {
            for (title in titleVariants) {
                tryNetease(queryArtist, title, effDurationMs, artist, title)?.let { return it }
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
        // 6) 1.1.2 LAST RESORT: artist-less LRCLIB search. Reached when
        //    the artist is unknown AND un-derivable, or when every
        //    artist-based attempt above failed — e.g. the folder was
        //    "Adele 25-2015" and the old code queried artist "0" or
        //    "Adele 25-2015" and got nothing. Title must still match,
        //    only synced lyrics are accepted, and the duration must be
        //    within 15 s of the track (Adele's Hello 296 s vs Lionel
        //    Richie's ~251 s). Skipped when the artist was already
        //    unknown: step 3 already searched without one.
        if (queryArtist != null) {
            for (title in titleVariants) {
                trySearch(null, title, effDurationMs, artist, title, requireSynced = true, maxDiffSec = if (effDurationMs > 0) 15L else Long.MAX_VALUE)?.let { return it }
            }
        }
        MioLog.event(ctx, "Lyrics: no match for title=\"$searchTitle\" artist=${searchArtist ?: "?"} durMs=$effDurationMs")
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
            val body = get(url.toString())
            if (body == null) { MioLog.event(ctx, "Lyrics LRCLIB /get miss (no response): $queryArtist - $queryTitle album=${queryAlbum ?: "-"}"); return null }
            val res = parseLrclib(body, cacheArtist, cacheTitle, queryAlbum, durationMs)
            MioLog.event(ctx, "Lyrics LRCLIB /get ${if (res != null) "HIT" else "miss (empty)"}: $queryArtist - $queryTitle album=${queryAlbum ?: "-"} dur=${durationMs / 1000}s")
            res
        } catch (e: Exception) { MioLog.event(ctx, "Lyrics LRCLIB /get error: $queryArtist - $queryTitle: ${e.message}"); null }
    }
    /** 1.1.2: requireSynced — accept only a synced result (no plain
     *  fallback), for the artist-less last resort. maxDiffSec — refuse
     *  results whose LRCLIB duration is further than this from the
     *  track, so a same-title song by another artist is not served as
     *  "the" lyrics. Both default to the old behaviour. */
    private suspend fun trySearch(queryArtist: String?, queryTitle: String, durationMs: Long, cacheArtist: String, cacheTitle: String, requireSynced: Boolean = false, maxDiffSec: Long = Long.MAX_VALUE): Lyrics? {
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
                // 1.1.2: when a max duration gap is set (artist-less
                // searches), a result whose length is clearly off is a
                // different song with the same title — skip it entirely.
                // Results with no duration field are kept (diff 0).
                val resultSec = if (obj.has("duration")) obj.optLong("duration") else -1L
                val diff = if (wantSec > 0 && resultSec > 0) kotlin.math.abs(resultSec - wantSec) else 0L
                if (wantSec > 0 && resultSec > 0 && diff > maxDiffSec) continue
                if (obj.optString("syncedLyrics").isNotBlank()) {
                    // Prefer the synced result whose duration is closest
                    // to the track — same-name covers/remixes are common.
                    if (bestSynced == null || diff < bestDiff) { bestSynced = json; bestDiff = diff }
                    if (diff <= 2L) break
                }
                if (!requireSynced && fallback == null && obj.optString("plainLyrics").isNotBlank()) fallback = json
            }
            val res = bestSynced?.let { parseLrclib(it, cacheArtist, cacheTitle, null, durationMs) }
                ?: fallback?.let { parseLrclib(it, cacheArtist, cacheTitle, null, durationMs) }
            MioLog.event(ctx, "Lyrics LRCLIB /search ${if (res != null) "HIT (${if (bestSynced != null) "synced" else "plain"})" else "miss"}: title=$queryTitle artist=${queryArtist ?: "- (none)"} dur=${durationMs / 1000}s results=${arr.length()}")
            res
        } catch (e: Exception) { MioLog.event(ctx, "Lyrics LRCLIB /search error: $queryTitle: ${e.message}"); null }
    }
    /** Netease Cloud Music (music.163.com) — free, no key. Search for the
     *  song, verify artist + title + duration, then fetch its synced LRC.
     *  Needs a browser User-Agent + Referer or the API returns nothing. */
    private suspend fun tryNetease(queryArtist: String, queryTitle: String, durationMs: Long, cacheArtist: String, cacheTitle: String): Lyrics? {
        return try {
            val searchUrl = "https://music.163.com/api/search/get?s=${enc("$queryArtist $queryTitle")}&type=1&offset=0&limit=5"
            val searchBody = getNetease(searchUrl)
            if (searchBody == null) { MioLog.event(ctx, "Lyrics Netease miss (no response): $queryArtist - $queryTitle"); return null }
            val songs = JSONObject(searchBody).optJSONObject("result")?.optJSONArray("songs")
            if (songs == null) { MioLog.event(ctx, "Lyrics Netease miss (no songs): $queryArtist - $queryTitle"); return null }
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
            if (songId < 0) { MioLog.event(ctx, "Lyrics Netease miss (no match): $queryArtist - $queryTitle"); return null }
            val lyricBody = getNetease("https://music.163.com/api/song/lyric?id=$songId&lv=1&kv=1&tv=-1")
            if (lyricBody == null) { MioLog.event(ctx, "Lyrics Netease miss (no response): $queryArtist - $queryTitle"); return null }
            val lrc = JSONObject(lyricBody).optJSONObject("lrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
            if (lrc == null) { MioLog.event(ctx, "Lyrics Netease miss (no lrc): $queryArtist - $queryTitle"); return null }
            val lines = parseLrc(lrc)
            if (lines.isEmpty()) { MioLog.event(ctx, "Lyrics Netease miss (empty lrc): $queryArtist - $queryTitle"); return null }
            withContext(Dispatchers.IO) { db.lyricsDao().put(LyricsEntity(cacheArtist, cacheTitle, null, durationMs, lrc, null, "netease")) }
            MioLog.event(ctx, "Lyrics Netease HIT: $queryArtist - $queryTitle")
            Lyrics(0, lines, null, "netease")
        } catch (e: Exception) { MioLog.event(ctx, "Lyrics Netease error: $queryArtist - $queryTitle: ${e.message}"); null }
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
            val body = get(url)
            if (body == null) { MioLog.event(ctx, "Lyrics lyrics.ovh miss (no response): $queryArtist - $queryTitle"); return null }
            val lyrics = JSONObject(body).optString("lyrics").takeIf { it.isNotBlank() }
            if (lyrics == null) { MioLog.event(ctx, "Lyrics lyrics.ovh miss (empty): $queryArtist - $queryTitle"); return null }
            withContext(Dispatchers.IO) { db.lyricsDao().put(LyricsEntity(cacheArtist, cacheTitle, null, 0, null, lyrics, "lyrics.ovh")) }
            MioLog.event(ctx, "Lyrics lyrics.ovh HIT: $queryArtist - $queryTitle")
            Lyrics(0, emptyList(), lyrics, "lyrics.ovh")
        } catch (e: Exception) { MioLog.event(ctx, "Lyrics lyrics.ovh error: $queryArtist - $queryTitle: ${e.message}"); null }
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
