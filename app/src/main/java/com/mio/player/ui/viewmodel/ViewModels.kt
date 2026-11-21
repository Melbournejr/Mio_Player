package com.mio.player.ui.viewmodel
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mio.player.data.db.TrackEntity
import com.mio.player.data.log.MioLog
import com.mio.player.data.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as com.mio.player.MioApp).container
    private val _tracks = MutableStateFlow<List<Track>>(emptyList()); val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()
    // Search results live apart from the library list (v1.1.9 TASK 3):
    // search() used to overwrite _tracks with the DAO search rows, so
    // after searching "Mua Tren Pho Bay Xa" / "Nonstop 2013" the Home
    // and Profile counts ("${tracks.size} songs in your library") read
    // 2 until something called load() again. _tracks now always holds
    // the full library (load()/scan() only); search results go here.
    private val _searchResults = MutableStateFlow<List<Track>>(emptyList()); val searchResults: StateFlow<List<Track>> = _searchResults.asStateFlow()
    private val _query = MutableStateFlow(""); val query: StateFlow<String> = _query.asStateFlow()
    fun load() = viewModelScope.launch {
        _tracks.value = c.db.trackDao().all().map { it.toTrack() }
    }
    fun scan() = viewModelScope.launch {
        val ctx = getApplication<Application>()
        val scanned = c.scanner.scanMediaStore()
        c.db.trackDao().insertAll(scanned)
        // SAF folders, if the user picked any. A failed folder still
        // adds nothing (runCatching below), exactly as before — a scan
        // failure never deletes tracks, it just inserts fewer rows.
        val folderUris = c.settings.flow(com.mio.player.data.prefs.SettingsStore.KEY_FOLDERS, emptySet()).first()
        val persisted = ctx.contentResolver.persistedUriPermissions
        val safCounts = mutableMapOf<String, Int>()
        val lostPermissions = mutableListOf<String>()
        folderUris.forEach { uri ->
            val parsed = android.net.Uri.parse(uri)
            if (persisted.none { it.uri == parsed && it.isReadPermission }) lostPermissions += uri
            safCounts[uri] = runCatching {
                c.scanner.scanSafFolder(parsed).also { c.db.trackDao().insertAll(it) }.size
            }.getOrDefault(0)
        }
        val total = scanned.size + safCounts.values.sum()
        MioLog.event(ctx, "Library scan: mediaStore=${scanned.size} saf={${safCounts.entries.joinToString { "${it.key}->${it.value}" }}} total=$total lostPermissions=$lostPermissions")
        load()
    }
    fun addFolder(uriString: String) = viewModelScope.launch {
        val cur = c.settings.flow(com.mio.player.data.prefs.SettingsStore.KEY_FOLDERS, emptySet<String>()).first()
        c.settings.set(com.mio.player.data.prefs.SettingsStore.KEY_FOLDERS, cur + uriString)
        scan()
    }
    // Writes ONLY to _searchResults — _tracks (the full library) is
    // never touched, so Home/Profile/Library counts can't show a
    // filtered list. Blank query: clear results and restore the full
    // list via load(), as before.
    fun search(q: String) {
        _query.value = q
        if (q.isBlank()) { _searchResults.value = emptyList(); load(); return }
        viewModelScope.launch { _searchResults.value = c.db.trackDao().search(q).map { it.toTrack() } }
    }
    fun toggleFavorite(t: Track) = viewModelScope.launch { c.db.trackDao().setFavorite(t.id, !t.isFavorite); load() }
    private fun TrackEntity.toTrack() = Track(id, title, artist, album, uri = android.net.Uri.parse(uri), path = path,
        durationMs = durationMs, format = runCatching { AudioFormat.valueOf(format) }.getOrDefault(AudioFormat.UNKNOWN),
        sampleRateHz = sampleRateHz, bitDepth = bitDepth, bitrateKbps = bitrateKbps, dsdRate = dsdRate,
        isFavorite = isFavorite, playCount = playCount, lastPlayedAt = lastPlayedAt, dateAdded = dateAdded)
}
class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    val pm = (app as com.mio.player.MioApp).container.playerManager
    val queue get() = pm.queue; val index get() = pm.index; val isPlaying get() = pm.isPlaying
    fun playQueue(tracks: List<Track>, i: Int = 0) = pm.playQueue(tracks, i)
    fun toggle() { if (pm.player.isPlaying) pm.pause() else pm.play() }
    fun next() = pm.next()
}
