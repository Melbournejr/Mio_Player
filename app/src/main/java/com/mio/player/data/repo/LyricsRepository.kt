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
/** Lyrics: sidecar .lrc -> embedded tags (USLT/SYLT, FLAC/OGG LYRICS)
 *  -> LRCLIB (free, no key) -> Netease -> lyrics.ovh (free, plain
 *  lyrics only) -> cache in Room.
 *  API: GET https://lrclib.net/api/get?artist_name=&track_name=&album_name=&duration=
 *       GET https://lrclib.net/api/search?track_name=&artist_name=
 *       GET https://api.lyrics.ovh/v1/{artist}/{title}
 *  Synced lyrics are parsed from LRC [mm:ss.xx] lines.
 *  1.1.6: embedded lyrics ARE now read (EmbeddedLyrics.kt — ID3v2
 *  USLT/SYLT, FLAC/OGG Vorbis comment). Android's
 *  MediaMetadataRetriever has no public USLT/SYLT key, so the old
 *  "NOT read" note meant "not readable that way", not "impossible".
 *  1.1.6: NO dedicated Vietnamese lyrics site is wired in. Verified
 *  2026-11-21 with live requests: LRCLIB already returns SYNCED
 *  lyrics for Vietnamese songs (Quân A.P "Bông Hoa Đẹp Nhất",
 *  Sơn Tùng M-TP "Chạy Ngay Đi", Đen "Đưa Nhau Đi Trốn", Mỹ Tâm
 *  "Người Hãy Quên Em Đi" — all synced=yes), and lyrics.ovh has some
 *  too. The gap for Vietnamese tracks was never the source: it was
 *  the QUERY — site-name folders ("NhacCuaTui.Com") taken for the
 *  artist, and "Title - Artist" filenames read backwards, so LRCLIB
 *  was asked for the wrong artist/title. Zing MP3's API returns
 *  403 without its signed session, NhacCuaTui redirects bot traffic
 *  (302), so neither could be verified and neither is wired in.
 *  What fixes Vietnamese coverage here is the derivation below
 *  (both filename orders tried, site folders ignored, junk metadata
 *  artist dropped) plus embedded + sidecar, not a new source.
 *  1.1.11: a plain/unsynced lyric is NEVER a final answer on its
 *  own. Embedded / sidecar / cached plain text used to return
 *  immediately, so LRCLIB was never asked and the track stayed
 *  static forever ("Ai Là Người Thương Em": "Source: embedded",
 *  no scroll, while a synced track scrolled fine). Plain text is
 *  now only a fallback: the lookup continues to the remotes and a
 *  synced hit anywhere wins. When no synced version exists
 *  anywhere, the plain text is shown STATIC — no highlight, no
 *  auto-scroll, and NO timestamps are ever estimated or
 *  synthesized for it (timing without real timestamps drifts, so
 *  the user would rather skip it than guess).
 *  1.0.12: two more reasons lyrics came out wrong or missing.
 *  (a) CACHE POISONING: a wrong lyric fetched once (e.g. a cover, or a
 *  same-name song by another artist) was cached forever and served on
 *  every later play, even after the lookup logic improved. The old fix
 *  cleared the cache once when the lyrics engine version bumped (see
 *  clearStaleCacheIfNeeded) — 1.1.6 replaces that with a schema-only
 *  clear, because the bump also wiped every GOOD lyric on upgrade /
 *  reinstall. A wrong cached lyric is now the rare exception, not the
 *  rule, and clearCache() is the explicit way to drop it.
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
    /** Cache policy, v1.1.6 — replaces the engine-version wipe.
     *  The old code cleared the WHOLE lyrics cache whenever the
     *  lookup engine version bumped, and on a fresh install (stored
     *  version 0 < 3) it logged "Lyrics cache cleared (engine v3)"
     *  even though the wipe was just the bump, not a real problem.
     *  A lyric fetched by an older lookup is still a valid lyric when
     *  only the lookup LOGIC changed — the schema did not — so now
     *  the cache is cleared ONLY when its stored SCHEMA version is
     *  older than [LYRICS_CACHE_SCHEMA] (i.e. LyricsEntity changed
     *  shape and old rows are unreadable). A first run of this code
     *  stamps the current schema WITHOUT clearing: a fresh install
     *  has an empty DB anyway, and an upgrade keeps every lyric the
     *  user already fetched. (Reinstall note: Room + SharedPrefs are
     *  both in app data, so a reinstall without an Auto Backup restore
     *  still loses the cache — nothing in this repo can preserve that;
     *  the manifest already has allowBackup=true, so a restore keeps
     *  both the DB and this stamp together and no wipe fires.)
     *  Failures are still never cached, so a later retry can succeed.
     *  Name and call site (MioApp.onCreate) are unchanged on purpose. */
    companion object { const val LYRICS_CACHE_SCHEMA = 1 }
    suspend fun clearStaleCacheIfNeeded() {
        val prefs = ctx.getSharedPreferences("mio_lyrics", Context.MODE_PRIVATE)
        val stored = prefs.getInt("lyrics_cache_schema", -1)
        if (stored == -1) {
            prefs.edit().putInt("lyrics_cache_schema", LYRICS_CACHE_SCHEMA).apply()
            MioLog.event(ctx, "Lyrics cache kept (first run, schema v$LYRICS_CACHE_SCHEMA)")
            return
        }
        if (stored < LYRICS_CACHE_SCHEMA) {
            withContext(Dispatchers.IO) { db.lyricsDao().clearAll() }
            prefs.edit().putInt("lyrics_cache_schema", LYRICS_CACHE_SCHEMA).apply()
            MioLog.event(ctx, "Lyrics cache cleared (schema v$stored -> v$LYRICS_CACHE_SCHEMA)")
        }
        // stored == current: keep everything. No log line here — the
        // old "cache cleared" line on every fresh install was noise
        // that looked like a bug in the user's log.
    }
    /** Explicit, user-triggered cache clear (e.g. a "Clear lyrics
     *  cache" button). The only way the cache is wiped now, besides
     *  a real schema change above. No UI wires this yet. */
    suspend fun clearCache() {
        withContext(Dispatchers.IO) { db.lyricsDao().clearAll() }
        MioLog.event(ctx, "Lyrics cache cleared (user)")
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
        // 1.1.6: a download-site / collection folder ("NhacCuaTui.Com",
        // "Zing MP3", "Music Viet-Au My") names the SITE, never the
        // artist — the old code took it for the artist and every
        // source missed. See isSiteName.
        if (isSiteName(name)) return true
        // A reverse-domain package segment (com.foo, com.mio.player)
        // under Android/data/... is path junk, not an artist. Each
        // dot-part must be 2+ chars so "R.E.M." is not caught.
        return n.matches(Regex("""[a-z0-9_]{2,}(\.[a-z0-9_]{2,})+"""))
    }
    /** 1.1.6: download-site / collection names. Compared on a key of
     *  lowercase letters+digits only, so "NhacCuaTui.Com", "Zing MP3"
     *  and "zingmp3.vn" all match their key. These appear as FOLDERS
     *  (".../NhacCuaTui.Com/Artist - Title.flac") and as bogus
     *  METADATA artists when a ripper wrote the site name into the
     *  artist tag — both are junk for a lyrics query. */
    private val siteNameKeys = setOf(
        "nhaccuatui", "nhaccuatuicom", "zing", "zingmp3", "zingmp3vn",
        "nhacviet", "nhactre", "nhaccuatoi", "musicvietaumy", "vietaumy"
    )
    private fun siteKey(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }
    private fun isSiteName(s: String): Boolean = siteKey(s) in siteNameKeys
    /** 1.1.6: a metadata artist that must NOT be queried with — an
     *  unknown placeholder, a site name, "Various Artists", or
     *  something with no letters at all. Such an artist is treated as
     *  unknown, so the filename split / path derivation runs instead
     *  of sending "NhacCuaTui.Com" to LRCLIB. The REAL metadata
     *  artist (Adele, Quân A.P, …) is untouched and still wins. */
    private fun isJunkArtist(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty() || isUnknownArtist(t)) return true
        if (isSiteName(t)) return true
        if (!t.any { it.isLetter() }) return true
        return t.lowercase() in setOf("various artists", "various", "unknown")
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
    /** 1.1.6: [audioUri] is the track's content Uri (Track.uri). SAF
     *  tracks have only a RELATIVE path, so File(path) never resolves
     *  for them — the Uri is what lets EmbeddedLyrics open the file.
     *  Defaults to null, so existing 6-arg callers are unaffected. */
    suspend fun getLyrics(artist: String, title: String, album: String?, durationMs: Long, audioPath: String?, playerDurationMs: Long = 0, audioUri: String? = null): Lyrics? {
        // 1.1.2: effective duration — stored duration first, player's
        // real length when the stored one is 0 (DSD files).
        val effDurationMs = if (durationMs > 0) durationMs else playerDurationMs
        // 1.1.11: a plain/unsynced lyric is a FALLBACK, never a final
        // answer. The cached row, the sidecar and the embedded tag
        // used to return immediately when they held plain text only
        // (the embedded early `return cache(...)` below), so LRCLIB
        // was never asked and the track stayed static forever.
        // A plain text is now only remembered in [plainFallback] and
        // the lookup continues to the remotes; a synced hit anywhere
        // wins and overwrites the cache. Only when NO synced version
        // exists anywhere is the plain text returned — with empty
        // synced lines, so the UI shows it STATIC (no highlight, no
        // auto-scroll). No timestamps are ever estimated or
        // synthesized for plain lyrics. Cache policy is unchanged
        // (clear only on schema change, see clearStaleCacheIfNeeded);
        // the one exception is this re-check: a plain-only cached row
        // is re-looked-up rather than trusted as final, because the
        // lookup logic changed under it — a synced version may now be
        // findable that the old early-return never looked for.
        var plainFallback: String? = null
        var plainFallbackSource: String? = null
        db.lyricsDao().get(artist, title)?.let { e ->
            val synced = parseLrc(e.syncedLrc)
            if (synced.isNotEmpty()) return Lyrics(0, synced, e.plainText, e.source)
            // Cached plain only: remember it, keep looking for synced.
            e.plainText?.takeIf { it.isNotBlank() }?.let {
                plainFallback = it; plainFallbackSource = e.source
                MioLog.event(ctx, "Lyrics cached plain only, re-checking for synced: $artist - $title")
            }
        }
        // 1) sidecar .lrc next to the audio file
        audioPath?.let { p ->
            val lrc = File(p.substringBeforeLast('.') + ".lrc")
            if (lrc.exists()) {
                val text = runCatching { lrc.readText() }.getOrNull()
                val lines = parseLrc(text)
                if (lines.isNotEmpty()) return cache(artist, title, album, effDurationMs, lines, null, "sidecar .lrc")
                // Sidecar with no timestamps: its text is a plain
                // fallback, not a synced hit — keep looking.
                text?.lines()?.map { it.trim() }
                    ?.filter { it.isNotEmpty() && !it.startsWith("[") }
                    ?.joinToString("\n")?.takeIf { it.isNotBlank() }?.let {
                        plainFallback = it; plainFallbackSource = "sidecar .lrc"
                    }
            }
        }
        // 1b) 1.1.6: embedded lyrics in the file's own tags (ID3v2
        // USLT/SYLT, FLAC/OGG Vorbis LYRICS) — before any remote
        // source, because the tag names THIS recording, no guessing.
        // Runs only when there is a file to open (absolute path or
        // content Uri); a miss just falls through to the remotes.
        // 1.1.11: a plain embedded hit no longer returns here — it
        // is only the fallback while the remotes are tried for synced.
        runCatching { EmbeddedLyrics.read(ctx, audioPath, audioUri) }.getOrNull()?.let { emb ->
            val lines = parseLrc(emb.syncedLrc)
            if (lines.isNotEmpty()) {
                MioLog.event(ctx, "Lyrics embedded HIT (synced): $artist - $title")
                return cache(artist, title, album, effDurationMs, lines, null, "embedded")
            }
            emb.plainText?.takeIf { it.isNotBlank() }?.let { plain ->
                MioLog.event(ctx, "Lyrics embedded HIT (plain, continuing to remotes for synced): $artist - $title")
                plainFallback = plain; plainFallbackSource = "embedded"
            }
        }
        // Work out what to search for, as (artist, title) candidate
        // PAIRS in priority order — 1.1.6.
        // - A real metadata artist wins outright (Adele stays Adele).
        // - A junk metadata artist (site name, "Unknown Artist",
        //   "Various Artists") counts as unknown, so the filename /
        //   path derivation below runs instead of querying LRCLIB
        //   with "NhacCuaTui.Com".
        // - With no usable artist, the filename "A - B" is tried in
        //   BOTH orders: Vietnamese download sites name files
        //   "Title - Artist" ("Anh Đã Quen Với Cô Đơn - Kaisoul")
        //   as often as "Artist - Title", and the old code only tried
        //   one order — the reversed file asked LRCLIB for artist
        //   "Anh Đã Quen Với Cô Đơn" and title "Kaisoul", which can
        //   never match anything. trySearch verifies artist AND title,
        //   so the wrong order just misses and the right one hits.
        // - The path-derived artist is the last pair, and the album
        //   is filled from the path when the metadata album is unknown.
        val metaArtist = artist.takeIf { it.isNotBlank() && !isJunkArtist(it) }
        val metaTitle = title.trim()
        val pairs = linkedSetOf<Pair<String?, String>>()
        var searchAlbum = album?.takeIf { it.isNotBlank() && !isUnknownAlbum(it) }
        var folderArtist: String? = null
        var folderAlbum: String? = null
        if (metaArtist == null || searchAlbum == null) {
            val derived = deriveArtistAlbumFromPath(audioPath)
            folderArtist = derived.first; folderAlbum = derived.second
            if (searchAlbum == null) searchAlbum = folderAlbum
        }
        if (metaArtist != null) {
            if (metaTitle.isNotEmpty()) pairs += metaArtist to metaTitle
        } else {
            val base = audioPath?.substringAfterLast('/')?.substringBeforeLast('.') ?: metaTitle
            val parts = base.split(" - ", limit = 2).map { it.trim() }
                .filter { it.isNotBlank() && it.any { c -> c.isLetter() } }
            if (parts.size == 2) {
                pairs += parts[0] to parts[1]
                pairs += parts[1] to parts[0]
            } else if (metaTitle.isNotEmpty()) {
                pairs += null to metaTitle
            }
            if (folderArtist != null && metaTitle.isNotEmpty()) pairs += folderArtist to metaTitle
        }
        if (pairs.isEmpty()) {
            MioLog.event(ctx, "Lyrics: no match for title=\"$metaTitle\" artist=${metaArtist ?: "?"} durMs=$effDurationMs (no query pair)")
            plainFallback?.let { return cache(artist, title, album, effDurationMs, emptyList(), it, plainFallbackSource ?: "embedded") }
            return null
        }
        // 1.1.2: diagnostics — the next user log shows exactly what was
        // derived from the path and what was queried with.
        MioLog.event(ctx, "Lyrics query: pairs=${pairs.joinToString { (a, t) -> "${a ?: "?"} / $t" }} (path-derived: ${folderArtist ?: "-"} / ${folderAlbum ?: "-"}) album=${searchAlbum ?: "?"} durMs=$effDurationMs path=$audioPath")
        // Query with the cleaned artist; cache under the ORIGINAL
        // artist/title (the lookup key at the top of this function),
        // so a hit found via a normalized or reversed variant is
        // actually served from cache on the next play. The old code
        // cached under the title VARIANT, so a normalized hit was
        // re-fetched on every play.
        // 1.1.6: every phase below runs per candidate pair, in pair
        // order, so "Artist - Title" is tried before "Title - Artist"
        // and the path-derived artist last. With a single pair (the
        // normal case — a real metadata artist, e.g. Adele) the
        // sequence is byte-for-byte the old one: LRCLIB /get (no
        // album, album, no duration), LRCLIB /search, Netease,
        // lyrics.ovh, artist-less search. That Adele path is proven
        // by the user's 1.1.5 log and must not change.
        fun titleVariants(qTitle: String) = listOf(qTitle, normalizeTitle(qTitle)).distinct()
        // 1.1.11: a remote hit only ends the lookup when it is SYNCED.
        // A plain-only remote hit is remembered as the fallback (only
        // if no local plain text outranks it — local text names this
        // recording) and the search goes on for a synced version in
        // the later pairs / sources. Previously the first plain hit
        // returned at once, so a later pair/source that HAD synced
        // lyrics was never tried.
        var syncedResult: Lyrics? = null
        fun consider(r: Lyrics?): Boolean {
            if (r == null) return false
            if (r.synced.isNotEmpty()) { syncedResult = r; return true }
            if (plainFallback == null) {
                r.plain?.takeIf { it.isNotBlank() }?.let { plainFallback = it; plainFallbackSource = r.source }
            }
            return false
        }
        // 2) LRCLIB exact lookup. Try each title variant (raw, then
        //    normalized), without album first (an album mismatch is the
        //    most common reason the exact lookup fails), then with it,
        //    then without duration — a duration off by a few seconds
        //    (DSD tracks scanned via SAF report duration 0 or a rounded
        //    value) also makes the exact lookup miss.
        for ((qArtist, qTitle) in pairs) {
            val queryArtist = qArtist?.let { normalizeArtist(it) } ?: continue
            for (tv in titleVariants(qTitle)) {
                if (consider(tryGet(queryArtist, tv, null, effDurationMs, artist, title))) return syncedResult!!
                if (searchAlbum != null) if (consider(tryGet(queryArtist, tv, searchAlbum, effDurationMs, artist, title))) return syncedResult!!
                if (effDurationMs > 0) if (consider(tryGet(queryArtist, tv, null, 0, artist, title))) return syncedResult!!
            }
        }
        // 3) LRCLIB search by title (+ artist when known), each title
        //    variant in turn. Prefer a result that actually has synced
        //    lyrics, and among those the one whose duration is closest
        //    to the track (covers with the same name are common).
        for ((qArtist, qTitle) in pairs) {
            val queryArtist = qArtist?.let { normalizeArtist(it) }
            // 1.1.2: with no artist to check against, duration is the
            // only thing telling same-title songs apart — refuse a
            // result whose length is >15 s off the track.
            val maxDiff = if (queryArtist == null && effDurationMs > 0) 15L else Long.MAX_VALUE
            for (tv in titleVariants(qTitle)) {
                if (consider(trySearch(queryArtist, tv, effDurationMs, artist, title, maxDiffSec = maxDiff))) return syncedResult!!
            }
        }
        // 4) Netease Cloud Music — free, no key, and it HAS synced LRC
        //    for many songs LRCLIB does not (huge catalogue, incl.
        //    Western pop). Search by "artist title", then fetch the
        //    song's LRC. Only used when LRCLIB found nothing.
        for ((qArtist, qTitle) in pairs) {
            val queryArtist = qArtist?.let { normalizeArtist(it) } ?: continue
            for (tv in titleVariants(qTitle)) {
                if (consider(tryNetease(queryArtist, tv, effDurationMs, artist, title))) return syncedResult!!
            }
        }
        // 5) Last resort: lyrics.ovh — free, no key, plain lyrics only
        //    (no sync). Better than "No lyrics found" for songs neither
        //    LRCLIB nor Netease has.
        for ((qArtist, qTitle) in pairs) {
            // lyrics.ovh is plain-only, so once any plain fallback
            // exists this phase cannot improve anything — skip it.
            if (plainFallback != null) break
            val queryArtist = qArtist?.let { normalizeArtist(it) } ?: continue
            for (tv in titleVariants(qTitle)) {
                if (consider(tryLyricsOvh(queryArtist, tv, artist, title))) return syncedResult!!
            }
        }
        // 6) 1.1.2 LAST RESORT: artist-less LRCLIB search, for every
        //    distinct title candidate. This is what saves a track
        //    whose artist is wrong in the metadata (e.g. the file says
        //    "Kaisoul" but LRCLIB has the song under SOOBIN): the
        //    title still matches, only synced lyrics are accepted, and
        //    the duration must be within 15 s of the track (Adele's
        //    Hello 296 s vs Lionel Richie's ~251 s).
        for (tv in pairs.map { it.second }.flatMap { titleVariants(it) }.distinct()) {
            if (consider(trySearch(null, tv, effDurationMs, artist, title, requireSynced = true, maxDiffSec = if (effDurationMs > 0) 15L else Long.MAX_VALUE))) return syncedResult!!
        }
        // 1.1.11: no synced version anywhere — return the plain text
        // (if any) STATIC: empty synced lines, so both the inline
        // lyrics and LyricsScreen show it as plain text with no
        // highlight and no auto-scroll. Timestamps are NEVER
        // synthesized or estimated for plain lyrics.
        plainFallback?.let {
            MioLog.event(ctx, "Lyrics plain only (no synced version found), showing static: $artist - $title")
            return cache(artist, title, album, effDurationMs, emptyList(), it, plainFallbackSource ?: "embedded")
        }
        MioLog.event(ctx, "Lyrics: no match for title=\"$metaTitle\" artist=${metaArtist ?: "?"} durMs=$effDurationMs")
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
