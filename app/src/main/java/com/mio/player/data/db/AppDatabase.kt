package com.mio.player.data.db
import androidx.room.*
@Entity(tableName = "tracks")
data class TrackEntity(@PrimaryKey val id: Long, val title: String, val artist: String, val album: String,
    val uri: String, val path: String, val durationMs: Long, val format: String,
    val sampleRateHz: Int = 0, val bitDepth: Int = 0, val bitrateKbps: Int = 0, val dsdRate: String? = null,
    val isFavorite: Boolean = false, val playCount: Int = 0, val lastPlayedAt: Long = 0, val dateAdded: Long = 0)
@Entity(tableName = "lyrics_cache", primaryKeys = ["artist","title"])
data class LyricsEntity(val artist: String, val title: String, val album: String?, val durationMs: Long,
    val syncedLrc: String?, val plainText: String?, val source: String, val cachedAt: Long = System.currentTimeMillis())
@Entity(tableName = "playlists")
data class PlaylistEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val createdAt: Long = System.currentTimeMillis())
@Entity(tableName = "playlist_tracks", primaryKeys = ["playlistId","trackId"])
data class PlaylistTrack(val playlistId: Long, val trackId: Long, val position: Int)
@Dao interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE") suspend fun all(): List<TrackEntity>
    @Query("SELECT * FROM tracks WHERE title LIKE '%' || :q || '%' OR artist LIKE '%' || :q || '%' OR album LIKE '%' || :q || '%' ORDER BY title") suspend fun search(q: String): List<TrackEntity>
    @Query("SELECT * FROM tracks WHERE isFavorite=1 ORDER BY title") suspend fun favorites(): List<TrackEntity>
    @Query("SELECT * FROM tracks ORDER BY lastPlayedAt DESC LIMIT :n") suspend fun recentlyPlayed(n: Int): List<TrackEntity>
    @Query("SELECT * FROM tracks ORDER BY playCount DESC LIMIT :n") suspend fun mostPlayed(n: Int): List<TrackEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(list: List<TrackEntity>)
    @Query("UPDATE tracks SET isFavorite=:fav WHERE id=:id") suspend fun setFavorite(id: Long, fav: Boolean)
    @Query("UPDATE tracks SET playCount=playCount+1, lastPlayedAt=:at WHERE id=:id") suspend fun bumpPlay(id: Long, at: Long)
    @Query("DELETE FROM tracks") suspend fun clear()
}
@Dao interface LyricsDao {
    @Query("SELECT * FROM lyrics_cache WHERE artist=:a AND title=:t LIMIT 1") suspend fun get(a: String, t: String): LyricsEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(e: LyricsEntity)
    @Query("DELETE FROM lyrics_cache") suspend fun clearAll()
}
@Dao interface PlaylistDao {
    @Query("SELECT * FROM playlists") suspend fun all(): List<PlaylistEntity>
    @Insert suspend fun insert(p: PlaylistEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addTrack(pt: PlaylistTrack)
    @Query("SELECT trackId FROM playlist_tracks WHERE playlistId=:id ORDER BY position") suspend fun trackIds(id: Long): List<Long>
    @Query("DELETE FROM playlists WHERE id=:id") suspend fun delete(id: Long)
}
@Database(entities = [TrackEntity::class, LyricsEntity::class, PlaylistEntity::class, PlaylistTrack::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao; abstract fun lyricsDao(): LyricsDao; abstract fun playlistDao(): PlaylistDao
}
