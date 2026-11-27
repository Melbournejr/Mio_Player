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
 *
 * v1.1.11 — MANUAL fetch + diacritic-insensitive matching:
 *  - fetchForManual(): an explicit user action ("Tìm metadata" on
 *    Now Playing) retries ONE track on demand. It bypasses BOTH the
 *    auto toggle and the 7-day retry block — the user asked for it —
 *    but still only fills fields the track lacks and stores the
 *    result exactly like the auto path (Room + cover file). The UI
 *    then swaps the queue item via PlayerManager.updateQueueTrack,
 *    the same live-update mechanism playQueue() uses.
 *  - normKey() now folds Vietnamese diacritics (NFD + strip marks,
 *    đ→d) before comparing. iTunes search itself is
 *    dash/diacritic-tolerant — verified live from this VM
 *    2026-11-21, the as-is dash-joined queries already return the
 *    right top hit — but the matcher rejected those hits whenever
 *    the library form lacked diacritics: "Dem-Ngay-Xa-Em-OnlyC-
 *    Lou-Hoa" vs "Đếm Ngày Xa Em" / "Only C & Lou Hoàng", "Buon
 *    Khong Em" vs "Buồn Không Em", and artist "Noo Phuoc Thin"
 *    (from the filename) vs "Noo Phước Thịnh". After folding, all
 *    three match (simulated against the real iTunes responses).
 *    "OST SuThatNghietNga" returns 0 results from iTunes even with
 *    diacritics ("Sự Thật Nghiệt Ngã") and nothing from MusicBrainz
 *    — no source has it, so the manual fetch correctly reports a
 *    miss for that track; no new source was added.
 *
 * v1.1.14 — joined-word queries ("25 Minutes" case): a file named
 * "25Minutes-MichaelLearnsTo" (artist tag "MTLR") used to derive
 * the query "25Minutes MichaelLearnsTo", for which iTunes returns
 * 0 results — although the song exists (verified live: "25 Minutes
 * + Michael Learns To Rock" hits immediately). The matcher already
 * folds spaces away (normKey strips non-alphanumerics), so the
 * failure was purely the QUERY. fetchForInner now tries query
 * variants in order (see queryVariants): as-is, then the spaced
 * form ("25Minutes" -> "25 Minutes", "MichaelLearnsTo" ->
 * "Michael Learns To"), then — for a short all-caps artist like
 * "MTLR" — title-only (duration-guarded). Applies to auto-fetch,
 * manual fetch and the dialog alike, since all three go through
 * fetchForInner. Diacritics folding and the duration check are
 * unchanged.
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
     *  applied over a real local tag.
     *
     *  v1.1.12 — EXCEPTION: a manually-saved entry (source "manual",
     *  written by saveTypedMetadata() from the metadata edit dialog)
     *  OVERRIDES the track's own artist/album when non-blank. That is
     *  the whole point of letting the user type a correction: the
     *  local tag can be WRONG without being "<unknown>", and the old
     *  only-fill-missing rule silently discarded exactly that
     *  correction. Fetched entries (sources "itunes" /
     *  "musicbrainz" / "none") keep the old rule.
     *  v1.1.35 — the same override now covers the TITLE: a manual
     *  title (stored since the 2→3 migration) overrides the track
     *  row's title, so the lyrics lookup — which queries by title —
     *  follows a corrected title instead of the old one. */
    fun applyMetadata(t: Track, m: TrackMetadataEntity?): Track {
        if (m == null) return t
        val cover = m.coverPath?.takeIf { File(it).exists() }
        val manual = m.source == "manual"
        return t.copy(
            title = if (!m.title.isNullOrBlank() && manual) m.title else t.title,
            artist = if (!m.artist.isNullOrBlank() && (manual || isUnknownArtist(t.artist))) m.artist else t.artist,
            album = if (!m.album.isNullOrBlank() && (manual || isUnknownAlbum(t.album))) m.album else t.album,
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
        runCatching { fetchForInner(track).stored }.onFailure {
            MioLog.event(ctx, "Metadata error: ${track.title}: ${it.message}")
        }.getOrDefault(false)
    }

    /**
     * v1.1.11 — MANUAL fetch for one track, from the "Tìm metadata"
     * action on Now Playing. Same storage as the auto path (Room
     * `track_metadata` + cover file, only missing fields filled),
     * but it bypasses BOTH gates the auto path honours: the Profile
     * auto toggle (an explicit tap is an explicit retry even when
     * auto-fetch is off) and the 7-day block on a failed attempt
     * (tracks the auto pass already gave up on are exactly the ones
     * the user retries by hand). Never throws.
     */
    suspend fun fetchForManual(track: Track): Boolean = withContext(Dispatchers.IO) {
        runCatching { fetchForInner(track, manual = true).stored }.onFailure {
            MioLog.event(ctx, "Metadata manual error: ${track.title}: ${it.message}")
        }.getOrDefault(false)
    }

    /**
     * v1.1.12 — MANUAL fetch with caller-supplied search terms, from
     * the metadata edit dialog on Now Playing. Same inner logic and
     * same storage as fetchForManual (bypasses the auto toggle and
     * the 7-day retry block; only missing fields are stored; cover
     * downloaded on a hit), but the iTunes / MusicBrainz query uses
     * the title/artist the user TYPED instead of the derived ones —
     * a track the sources return nothing for under its library tags
     * can still be found once the user corrects the spelling.
     *
     * Because the user is explicitly searching — including for a
     * track whose local tags are present but WRONG — the
     * needsMetadata() gate is also bypassed here: a wrong-but-known
     * artist would otherwise block the search outright. The hit is
     * returned (artist/album as the source reported them) so the
     * dialog can fill its fields with the found data even when the
     * stored row keeps the old only-fill-missing rule; the user then
     * taps "Lưu" to persist the correction as a manual override.
     * Never throws.
     */
    suspend fun fetchForManualWith(track: Track, title: String, artist: String): ManualFetchResult =
        withContext(Dispatchers.IO) {
            runCatching { fetchForInner(track, manual = true, titleOverride = title, artistOverride = artist) }.onFailure {
                MioLog.event(ctx, "Metadata manual error: ${track.title}: ${it.message}")
            }.getOrDefault(ManualFetchResult(found = false))
        }

    /**
     * v1.1.12 — persist the values the user TYPED in the metadata
     * edit dialog as this song's metadata (source "manual"), so
     * applyMetadata() overrides the track's own artist/album with
     * them even when the local tag is present but wrong. A blank
     * typed field keeps whatever was stored before instead of
     * wiping it, and an earlier downloaded cover is kept. Never
     * throws; returns false only on a storage failure.
     * v1.1.35 — the typed TITLE is now stored too (track_metadata
     * gained a title column in the 2→3 migration). Before, the
     * title only fed the "Tìm" search and the track row kept the
     * old title, so the lyrics lookup — which queries by title —
     * never followed a corrected title.
     */
    suspend fun saveTypedMetadata(track: Track, title: String, artist: String, album: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val now = System.currentTimeMillis()
                val existing = db.metadataDao().get(track.id)
                val typedTitle = title.trim().takeIf { it.isNotBlank() }
                val typedArtist = artist.trim().takeIf { it.isNotBlank() }
                val typedAlbum = album.trim().takeIf { it.isNotBlank() }
                db.metadataDao().put(
                    TrackMetadataEntity(
                        track.id,
                        typedArtist ?: existing?.artist,
                        typedAlbum ?: existing?.album,
                        existing?.coverPath,
                        "manual",
                        now,
                        title = typedTitle ?: existing?.title,
                    )
                )
                MioLog.event(ctx, "Metadata manual save: \"${track.title}\" -> title=${typedTitle ?: "-"} artist=${typedArtist ?: "-"} album=${typedAlbum ?: "-"}")
                true
            }.onFailure {
                MioLog.event(ctx, "Metadata manual save error: ${track.title}: ${it.message}")
            }.getOrDefault(false)
        }

    private suspend fun fetchForInner(
        track: Track,
        manual: Boolean = false,
        titleOverride: String? = null,
        artistOverride: String? = null,
    ): ManualFetchResult {
        if (!manual && !autoFetchEnabled()) return ManualFetchResult(found = false)
        // A typed manual search bypasses this gate (see
        // fetchForManualWith): the user may be correcting tags that
        // are present but wrong, which needsMetadata() cannot see.
        if (titleOverride == null && !needsMetadata(track)) return ManualFetchResult(found = false)
        val now = System.currentTimeMillis()
        val existing = db.metadataDao().get(track.id)
        // A recent failed attempt (nothing useful stored) is not
        // retried — a track no source knows must not be re-queried on
        // every scan / play. A MANUAL fetch skips this block: the
        // user explicitly asked to try again.
        if (!manual && existing != null && existing.artist == null && existing.album == null &&
            existing.coverPath == null && now - existing.attemptedAt < RETRY_AFTER_MS
        ) return ManualFetchResult(found = false)

        val title = (titleOverride?.trim()?.takeIf { it.isNotBlank() }
            ?: lyricsRepo.normalizeTitle(track.title).ifBlank { track.title.trim() })
        if (title.isBlank()) return ManualFetchResult(found = false)
        // A real metadata artist wins; a junk one (site name, "Unknown
        // Artist") is derived from the path instead — the same rules
        // the lyrics lookup uses, so "NhacCuaTui.Com" is never queried.
        // A TYPED artist wins over both when the user supplied one.
        val knownArtist = track.artist.takeIf { it.isNotBlank() && !isUnknownArtist(it) }
        val (pathArtist, _) = lyricsRepo.deriveArtistAlbumFromPath(track.path)
        val typedArtist = artistOverride?.trim()?.takeIf { it.isNotBlank() && !isUnknownArtist(it) }
        val queryArtist = typedArtist ?: knownArtist ?: pathArtist
        val wantArtistForFill = isUnknownArtist(track.artist)
        val wantAlbumForFill = isUnknownAlbum(track.album)
        val wantCover = !coverFile(track.id).exists()

        val hit = run {
            // v1.1.14: try the query variants in order — iTunes
            // first for every variant (fast, no rate limit), then
            // MusicBrainz for every variant. Same source priority as
            // before, just more queries: the as-is query, the spaced
            // variant, and (acronym artist only) title-only.
            val variants = queryVariants(title, queryArtist, track.durationMs)
            var found: Hit? = null
            for ((qt, qa) in variants) {
                found = tryItunes(qt, qa, track.durationMs)
                if (found != null) break
            }
            if (found == null) {
                for ((qt, qa) in variants) {
                    found = tryMusicBrainz(qt, qa, track.durationMs)
                    if (found != null) break
                }
            }
            found
        }
        if (hit == null) {
            // Record the miss like the existing path — but never wipe
            // a manual entry: the user's typed correction outlives a
            // failed search (only its timestamp is refreshed).
            if (existing?.source == "manual" && (!existing.artist.isNullOrBlank() || !existing.album.isNullOrBlank())) {
                db.metadataDao().put(existing.copy(attemptedAt = now))
            } else {
                db.metadataDao().put(
                    TrackMetadataEntity(track.id, null, null, null, "none", now)
                )
            }
            MioLog.event(ctx, "Metadata miss: \"$title\" artist=${queryArtist ?: "?"} tried=${queryVariants(title, queryArtist, track.durationMs).size} variants")
            return ManualFetchResult(found = false)
        }
        // Only the fields the track actually lacks are stored — a
        // fetched artist is never kept next to a real local artist.
        // A previous MANUAL entry wins over a new hit for the fields
        // it already sets (and keeps its source, so applyMetadata()
        // keeps overriding with it); the hit only fills the gaps,
        // and an earlier downloaded cover is never dropped by a hit
        // that brings no new cover.
        val artist = if (wantArtistForFill) hit.artist else null
        val album = if (wantAlbumForFill) hit.album else null
        var coverPath: String? = null
        if (wantCover && hit.artworkUrl != null) {
            coverPath = downloadCover(track.id, hit.artworkUrl)
        }
        val manualPrev = existing?.source == "manual" &&
            (!existing.artist.isNullOrBlank() || !existing.album.isNullOrBlank())
        val storedArtist = if (manualPrev && !existing?.artist.isNullOrBlank()) existing?.artist else artist
        val storedAlbum = if (manualPrev && !existing?.album.isNullOrBlank()) existing?.album else album
        val storedCover = coverPath ?: existing?.coverPath
        val storedSource = if (manualPrev) "manual" else hit.source
        db.metadataDao().put(
            TrackMetadataEntity(track.id, storedArtist, storedAlbum, storedCover, storedSource, now)
        )
        MioLog.event(
            ctx,
            "Metadata HIT (${hit.source}): \"$title\" -> artist=${storedArtist ?: "-"} album=${storedAlbum ?: "-"} cover=${storedCover != null}"
        )
        return ManualFetchResult(
            found = true,
            artist = hit.artist,
            album = hit.album,
            // Same "anything new stored" meaning as before: a
            // preserved earlier cover / manual value does not count.
            stored = artist != null || album != null || coverPath != null,
        )
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
        // v1.1.50 — the old pass counted EVERY needsMetadata track
        // against the per-scan cap, in list order, including tracks
        // already inside the 7-day retry block and tracks whose
        // only gap is a cover no source had. The same first 50
        // tracks ate the whole cap on every scan, so the rest of
        // the library was never attempted — auto-fetch looked dead.
        // Now: skip recently-attempted tracks without counting
        // them, and try the longest-waiting tracks first.
        val meta = allMetadata()
        val now = System.currentTimeMillis()
        val candidates = tracks
            .filter { needsMetadata(it) }
            .filter { t ->
                val m = meta[t.id]
                m == null || now - m.attemptedAt >= RETRY_AFTER_MS
            }
            .sortedBy { meta[it.id]?.attemptedAt ?: 0L }
        var attempted = 0
        for (t in candidates) {
            if (attempted >= MAX_PER_SCAN) break
            attempted++
            if (fetchFor(t)) updated += t.id
            delay(ITUNES_DELAY_MS)
        }
        if (attempted > 0) {
            MioLog.event(ctx, "Metadata auto-fetch: attempted=$attempted updated=${updated.size} candidates=${candidates.size}")
        }
        return updated
    }

    // --- matching helpers (same normalisation as LyricsRepository) ---
    // v1.1.11: fold Vietnamese diacritics before comparing, so a
    // library title/artist without accents still matches the accented
    // form the sources return ("Buon Khong Em" == "Buồn Không Em",
    // "Dem Ngay Xa Em" == "Đếm Ngày Xa Em", "Noo Phuoc Thin" is a
    // prefix of "Noo Phước Thịnh"). NFD splits base letters from
    // their combining marks (ơ→o+horn, ế→e+circumflex+acute, …);
    // the marks are stripped, and đ/Đ — which NFD does NOT decompose
    // — is mapped by hand. The contains-checks in matchesTitle /
    // matchesArtist and the duration check still guard against a
    // wrong same-name song, so this only turns misses into hits.
    private fun foldDiacritics(s: String): String {
        val decomposed = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        return decomposed.replace(Regex("""\p{M}"""), "")
            .replace('đ', 'd').replace('Đ', 'D')
    }

    private fun normKey(s: String): String =
        foldDiacritics(s.lowercase()).replace(Regex("""(?i)\s+(feat\.?|ft\.?|featuring)\s+.*$"""), "")
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

    // --- v1.1.14: joined-word queries ("25 Minutes" case) ---
    // A filename/tag like "25Minutes-MichaelLearnsTo" (artist tag
    // "MTLR") derives the query "25Minutes MichaelLearnsTo" — and
    // iTunes returns 0 results for it, although the song exists:
    // "25 Minutes + Michael Learns To Rock" returns "25 Minutes |
    // Michael Learns to Rock | Pop Rocks | 264000ms" immediately
    // (verified live from this VM 2026-11-21). The matcher already
    // folds spaces away (normKey strips non-alphanumerics), so the
    // failure is purely the QUERY — the API tokenises on the
    // space-less string. The fix is to also try a SPACED variant.
    /** "25Minutes" -> "25 Minutes", "MichaelLearnsTo" -> "Michael
     *  Learns To", "OnlyC" -> "Only C". Spaces are inserted at
     *  digit->letter and lowercase->uppercase boundaries; an
     *  all-caps acronym ("MTLR") has no boundary and is left alone.
     *  A name that already has spaces is returned unchanged. */
    private fun splitJoinedWords(s: String): String =
        s.replace(Regex("""(\d)([A-Za-z])"""), "$1 $2")
            .replace(Regex("""([a-z])([A-Z])"""), "$1 $2")
            .replace(Regex("""\s+"""), " ").trim()

    /** A short all-caps artist ("MTLR", "M4U", "U2"): an acronym or
     *  abbreviation, never a full name to match against — the
     *  matcher would reject the real artist ("Michael Learns to
     *  Rock") outright, so an acronym artist also earns a
     *  title-only retry (duration guards the match). */
    private fun isAcronymArtist(s: String?): Boolean {
        if (s.isNullOrBlank()) return false
        val t = s.trim()
        return t.length in 2..5 && t.any { it.isLetter() } &&
            t.all { it.isUpperCase() || it.isDigit() }
    }

    /** The query pairs to try, in order. (1) as derived; (2) the
     *  spaced variant when it differs ("25Minutes MichaelLearnsTo"
     *  -> "25 Minutes Michael Learns To"); (3) title-only when the
     *  artist is an acronym — only when the duration is known, so
     *  the 10 s duration check can guard the match. */
    private fun queryVariants(title: String, artist: String?, durationMs: Long): List<Pair<String, String?>> {
        val variants = mutableListOf<Pair<String, String?>>()
        variants += title to artist
        val spacedTitle = splitJoinedWords(title)
        val spacedArtist = artist?.let { splitJoinedWords(it) }
        if (spacedTitle != title || spacedArtist != artist) {
            variants += spacedTitle to spacedArtist
        }
        if ((isAcronymArtist(artist) || isAcronymArtist(spacedArtist)) && durationMs > 0) {
            val t = spacedTitle
            if (variants.none { it.first == t && it.second == null }) variants += t to null
        }
        // v1.1.50 — final title-only fallback for every track with a
        // known duration: a wrong-but-present artist tag ("MTLR",
        // a site name, a misspelling) otherwise poisons every
        // artist query and the auto pass misses a song the sources
        // have. Title match + the 10 s duration check still guard
        // against a same-name wrong song. Manual search behaviour
        // is unchanged (the typed artist still runs first).
        if (durationMs > 0 && artist != null) {
            val t = spacedTitle
            if (variants.none { it.first == t && it.second == null }) variants += t to null
        }
        return variants
    }

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

/**
 * v1.1.12 — result of a manual metadata search (fetchForManualWith).
 * [found] is true when a source returned a matching track, even when
 * nothing new was stored (e.g. the track already had a real artist,
 * so the hit's artist was not kept — the dialog still fills its
 * fields with [artist]/[album] as the source reported them, and the
 * user persists them with "Lưu"). [stored] keeps the old fetchFor()
 * meaning: true only when this call stored something new.
 */
data class ManualFetchResult(
    val found: Boolean,
    val artist: String? = null,
    val album: String? = null,
    val stored: Boolean = false,
)
