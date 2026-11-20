package com.mio.player.ui.viewmodel
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mio.player.data.db.TrackEntity
import com.mio.player.data.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as com.mio.player.MioApp).container
    private val _tracks = MutableStateFlow<List<Track>>(emptyList()); val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()
    private val _query = MutableStateFlow(""); val query: StateFlow<String> = _query.asStateFlow()
    fun load() = viewModelScope.launch {
        _tracks.value = c.db.trackDao().all().map { it.toTrack() }
    }
    fun scan() = viewModelScope.launch {
        val scanned = c.scanner.scanMediaStore()
        c.db.trackDao().insertAll(scanned)
        // SAF folders, if the user picked any
        c.settings.flow(com.mio.player.data.prefs.SettingsStore.KEY_FOLDERS, emptySet()).first().forEach { uri ->
            runCatching { c.db.trackDao().insertAll(c.scanner.scanSafFolder(android.net.Uri.parse(uri))) }
        }
        load()
    }
    fun search(q: String) { _query.value = q; viewModelScope.launch { _tracks.value = c.db.trackDao().search(q).map { it.toTrack() } } }
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
