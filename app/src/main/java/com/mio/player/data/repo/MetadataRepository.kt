package com.mio.player.data.repo
import android.content.Context
import com.mio.player.data.db.AppDatabase
import com.mio.player.data.db.TrackMetadataEntity
import com.mio.player.data.log.MioLog
import com.mio.player.data.model.Track
import com.mio.player.data.prefs.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * v1.1.10 — automatic metadata download for tracks whose files carry
 * no usable tags (artist "<unknown>", no album, no cover — the library
 * is full of them, and Now Playing shows a music-note placeholder).
 *
 * SOURCES (both free, no API key — verified live from this VM
 * 2026-11-21 before wiring them in):
 *  - iTunes Search API (primary): one call returns artistName,
 *    collectionName (album), trackTimeMillis and artworkUrl100.
 *    Adele "Hello" -> artist "Adele", album "25", 295502 ms,
 *    artwork present. The 100 px art URL is rewritten to 600x600.
 *  - MusicBrainz (fallback, artist/album only — no cover): recording
 *    search returns Adele "Hello" (score 100) with length and a
 *    releases[] array for the album. Rate limit is 1 req/s, so a
 *    1.1 s delay is enforced before every MusicBrainz call.
 *
 * MATCHING: query by title + artist (and duration when known) and
 * verify the result the same way the lyrics code does — same title,
 * same artist, duration within 10 s — so a same-name song by another
 * artist never overwrites a correct local tag. NEVER overwrites what
 * the file already has: only fields that are missing (artist unknown
 * / album unknown / no cover) are filled, and only those fill-ins are
 * stored.
 *
 * PERSISTENCE: fetched fill-ins live in the Room `track_metadata`
 * table (keyed by track id) plus the cover image as a local file
 * (filesDir/covers/<trackId>.jpg, so it works offline). The scanner
 * never touches this table — a rescan (which REPLACE-inserts the
 * tracks rows) cannot wipe fetched metadata. The merge happens at
 * read time (LibraryViewModel.load / MetadataRepository.mergedTrack).
 *
 * AUTOMATIC: LibraryViewModel.scan() runs fetchMissing() in the
 * background after a scan (capped at 50 tracks per scan, sequential,
 * small delay between requests), and PlayerViewModel.playQueue()
 * fetches for the track that just started playing. Both are
 * fire-and-forget on a background coroutine — playback and the UI
 * never wait. Failures are recorded (attemptedAt) and not retried
 * for 7 days, so a track no source knows is not hammered on every
 * scan. The Profile toggle (SettingsStore.KEY_AUTO_METADATA,
 * default on) gates every fetch.
 */
class MetadataRepository(
    private val ctx: Context,
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val lyricsRepo: LyricsRepository,
) {
    private val client = OkHttpClient()

    companion object {
        /** A failed / no-match attempt is not retried for 7 days. */
        private const val RETRY_AFTER_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_PER_SCAN = 50
        private const val ITUNES_DELAY_MS = 150L
        private const val MUSICBRAINZ_DELAY_MS = 1100L
        /** iTunes duration tolerance, same as the lyrics matcher. */
        private const val DURATION_TOLERANCE_MS = 10_000L
    }

    suspend fun autoFetchEnabled(): Boolean =
        settings.flow(SettingsStore.KEY_AUTO_METADATA, true).first()

    fun isUnknownArtist(s: String): Boolean =
        s.trim().lowercase() in setOf("unknown artist", "<unknown>", "unknown", "")

    fun isUnknownAlbum(s: String): Boolean =
        s.trim().lowercase() in setOf("unknown album", "<unknown>", "unknown", "")

    fun coverFile(trackId: Long): File = File(File(ctx.filesDir, "covers"), "$trackId.jpg")

    /** True when this track still needs anything (artist, album or a
     *  cover file on disk). */
    fun needsMetadata(t: Track): Boolean =
        isUnknownArtist(t.artist) || isUnknownAlbum(t.album) || !coverFile(t.id).exists()

    suspend fun allMetadata(): Map<Long, TrackMetadataEntity> = withContext(Dispatchers.IO) {
        db.metadataDao().all().associateBy { it.trackId }
    }

    /** The track with its fetched fill-ins applied — only where the
     *  track's own fields are missing. A stored artist/album is never
     *  applied over a real local tag. */
    fun applyMetadata(t: Track, m: TrackMetadataEntity?): Track {
        if (m == null) return t
        val cover = m.coverPath?.takeIf { File(it).exists() }
        return t.copy(
            artist = if (isUnknownArtist(t.artist) && !m.artist.isNullOrBlank()) m.artist else t.artist,
            album = if (isUnknownAlbum(t.album) && !m.album.isNullOrBlank()) m.album else t.album,
            coverPath = cover ?: t.coverPath,
        )
    }

    suspend fun mergedTrack(t: Track): Track = withContext(Dispatchers.IO) {
        applyMetadata(t, db.metadataDao().get(t.id))
    }

    /**
     * Fetch metadata for ONE track. Returns true when anything new was
     * stored (artist, album or cover). Never throws; callers
     * fire-and-forget this on a background scope.
     */
    suspend fun fetchFor(track: Track): Boolean = withContext(Dispatchers.IO) {
        runCatching { fetchForInner(track) }.onFailure {
            MioLog.event(ctx, "Metadata error: ${track.title}: ${it.message}")
        }.getOrDefault(false)
    }

    private suspend fun fetchForInner(track: Track): Boolean {
        if (!autoFetchEnabled()) return false
        if (!needsMetadata(track)) return false
        val now = System.currentTimeMillis()
        val existing = db.metadataDao().get(track.id)
        // A recent failed attempt (nothing useful stored) is not
        // retried — a track no source knows must not be re-queried on
        // every scan / play.
        if (existing != null && existing.artist == null && existing.album == null &&
            existing.coverPath == null && now - existing.attemptedAt < RETRY_AFTER_MS
        ) return false

        val title = lyricsRepo.normalizeTitle(track.title).ifBlank { track.title.trim() }
        if (title.isBlank()) return false
        // A real metadata artist wins; a junk one (site name, "Unknown
        // Artist") is derived from the path instead — the same rules
        // the lyrics lookup uses, so "NhacCuaTui.Com" is never queried.
        val knownArtist = track.artist.takeIf { it.isNotBlank() && !isUnknownArtist(it) }
        val (pathArtist, _) = lyricsRepo.deriveArtistAlbumFromPath(track.path)
        val queryArtist = knownArtist ?: pathArtist
        val wantArtistForFill = isUnknownArtist(track.artist)
        val wantAlbumForFill = isUnknownAlbum(track.album)
        val wantCover = !coverFile(track.id).exists()

        val hit = tryItunes(title, queryArtist, track.durationMs)
            ?: tryMusicBrainz(title, queryArtist, track.durationMs)
        if (hit == null) {
            db.metadataDao().put(
                TrackMetadataEntity(track.id, null, null, null, "none", now)
            )
            MioLog.event(ctx, "Metadata miss: \"$title\" artist=${queryArtist ?: "?"}")
            return false
        }
        // Only the fields the track actually lacks are stored — a
        // fetched artist is never kept next to a real local artist.
        val artist = if (wantArtistForFill) hit.artist else null
        val album = if (wantAlbumForFill) hit.album else null
        var coverPath: String? = null
        if (wantCover && hit.artworkUrl != null) {
            coverPath = downloadCover(track.id, hit.artworkUrl)
        }
        db.metadataDao().put(
            TrackMetadataEntity(track.id, artist, album, coverPath, hit.source, now)
        )
        MioLog.event(
            ctx,
            "Metadata HIT (${hit.source}): \"$title\" -> artist=${artist ?: "-"} album=${album ?: "-"} cover=${coverPath != null}"
        )
        return artist != null || album != null || coverPath != null
    }

    /**
     * Fetch for every track in [tracks] that still needs metadata,
     * sequential and polite, capped at [MAX_PER_SCAN] per call so one
     * scan never turns into a long download session. Returns the ids
     * that gained metadata.
     */
    suspend fun fetchMissing(tracks: List<Track>): List<Long> {
        if (!autoFetchEnabled()) return emptyList()
        val updated = mutableListOf<Long>()
        var attempted = 0
        for (t in tracks) {
            if (!needsMetadata(t)) continue
            if (attempted >= MAX_PER_SCAN) break
            attempted++
            if (fetchFor(t)) updated += t.id
            delay(ITUNES_DELAY_MS)
        }
        if (attempted > 0) {
            MioLog.event(ctx, "Metadata auto-fetch: attempted=$attempted updated=${updated.size}")
        }
        return updated
    }

    // --- matching helpers (same normalisation as LyricsRepository) ---
    private fun normKey(s: String): String =
        s.lowercase().replace(Regex("""(?i)\s+(feat\.?|ft\.?|featuring)\s+.*$"""), "")
            .replace(Regex("""[^\p{L}\p{N}]"""), "")

    private fun matchesTitle(resultTitle: String, queryTitle: String): Boolean {
        val r = normKey(lyricsRepo.normalizeTitle(resultTitle))
        val q = normKey(lyricsRepo.normalizeTitle(queryTitle))
        return r.isNotEmpty() && q.isNotEmpty() && (r == q || r.contains(q) || q.contains(r))
    }

    private fun matchesArtist(resultArtist: String, queryArtist: String?): Boolean {
        if (queryArtist.isNullOrBlank()) return true
        val r = normKey(resultArtist)
        val q = normKey(queryArtist)
        return r.isNotEmpty() && q.isNotEmpty() && (r == q || r.contains(q) || q.contains(r))
    }

    private fun durationOk(resultMs: Long, wantMs: Long): Boolean =
        wantMs <= 0 || resultMs <= 0 || kotlin.math.abs(resultMs - wantMs) <= DURATION_TOLERANCE_MS

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private data class Hit(
        val artist: String?,
        val album: String?,
        val artworkUrl: String?,
        val source: String,
    )

    // --- iTunes Search API (primary) ---
    private suspend fun tryItunes(title: String, artist: String?, durationMs: Long): Hit? {
        return runCatching {
            val term = if (artist.isNullOrBlank()) title else "$artist $title"
            val body = get(
                "https://itunes.apple.com/search?term=${enc(term)}&media=music&entity=song&limit=5"
            ) ?: return null
            val results = JSONObject(body).optJSONArray("results") ?: return null
            for (i in 0 until results.length()) {
                val o = results.getJSONObject(i)
                if (o.optString("wrapperType") != "track") continue
                if (!matchesTitle(o.optString("trackName"), title)) continue
                if (!matchesArtist(o.optString("artistName"), artist)) continue
                if (!durationOk(o.optLong("trackTimeMillis"), durationMs)) continue
                val art = o.optString("artworkUrl100").takeIf { it.isNotBlank() }
                    // 100 px is a thumbnail — iTunes serves the same
                    // image at 600x600 under the same path.
                    ?.replace("100x100bb.jpg", "600x600bb.jpg")
                return Hit(
                    artist = o.optString("artistName").takeIf { it.isNotBlank() },
                    album = o.optString("collectionName").takeIf { it.isNotBlank() },
                    artworkUrl = art,
                    source = "itunes",
                )
            }
            null
        }.onFailure {
            MioLog.event(ctx, "Metadata iTunes error: $title: ${it.message}")
        }.getOrNull()
    }

    // --- MusicBrainz (fallback, artist/album only) ---
    private suspend fun tryMusicBrainz(title: String, artist: String?, durationMs: Long): Hit? {
        return runCatching {
            delay(MUSICBRAINZ_DELAY_MS) // MusicBrainz rate limit: 1 req/s
            val q = StringBuilder("recording:${lucene(title)}")
            if (!artist.isNullOrBlank()) q.append(" AND artist:${lucene(artist)}")
            val body = get(
                "https://musicbrainz.org/ws/2/recording/?query=${enc(q.toString())}&fmt=json&limit=5",
                userAgent = "MioPlayer/1.1.10 (mio-player-local)",
            ) ?: return null
            val recs = JSONObject(body).optJSONArray("recordings") ?: return null
            for (i in 0 until recs.length()) {
                val o = recs.getJSONObject(i)
                if (!matchesTitle(o.optString("title"), title)) continue
                val credit = o.optJSONArray("artist-credit")
                val resultArtist = credit?.optJSONObject(0)?.optString("name").orEmpty()
                if (!matchesArtist(resultArtist, artist)) continue
                if (!durationOk(o.optLong("length"), durationMs)) continue
                val album = o.optJSONArray("releases")?.optJSONObject(0)
                    ?.optString("title")?.takeIf { it.isNotBlank() }
                return Hit(
                    artist = resultArtist.takeIf { it.isNotBlank() },
                    album = album,
                    artworkUrl = null,
                    source = "musicbrainz",
                )
            }
            null
        }.onFailure {
            MioLog.event(ctx, "Metadata MusicBrainz error: $title: ${it.message}")
        }.getOrNull()
    }

    /** Lucene query syntax (MusicBrainz search): strip the characters
     *  that would break the query instead of escaping them. */
    private fun lucene(s: String): String =
        s.replace(Regex("""[":+\-()\[\]{}!~^\\/|&]"""), " ")
            .replace(Regex("""\s+"""), " ").trim()

    private suspend fun get(url: String, userAgent: String = "MioPlayer/1.0"): String? =
        withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder().url(url).header("User-Agent", userAgent).build()
            ).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        }

    /** Downloads the cover art to filesDir/covers/<trackId>.jpg.
     *  Returns the absolute path on success, null on any failure
     *  (the metadata is still kept — the cover just stays missing). */
    private suspend fun downloadCover(trackId: Long, url: String): String? {
        return runCatching {
            withContext(Dispatchers.IO) {
                val file = coverFile(trackId)
                file.parentFile?.mkdirs()
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@withContext null
                    val bytes = r.body?.bytes() ?: return@withContext null
                    // A real JPEG/PNG is at least a few KB — a tiny
                    // body is an error page, not art.
                    if (bytes.size < 2048) return@withContext null
                    file.outputStream().use { it.write(bytes) }
                    file.absolutePath
                }
            }
        }.onFailure {
            MioLog.event(ctx, "Metadata cover download failed: $trackId: ${it.message}")
        }.getOrNull()
    }
}
