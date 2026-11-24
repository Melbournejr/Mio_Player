package com.mio.player
import android.app.Application
import android.content.Context
import androidx.room.Room
import com.mio.player.data.db.AppDatabase
import com.mio.player.data.log.MioLog
import com.mio.player.data.prefs.SettingsStore
import com.mio.player.data.repo.LyricsRepository
import com.mio.player.data.repo.MetadataRepository
import com.mio.player.data.repo.MusicScanner
import com.mio.player.playback.PlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
/** Manual DI container — deliberately no Hilt: fewer annotation processors,
 *  faster CI builds, and one less failure mode on GitHub Actions. */
class AppContainer(ctx: Context) {
    val db: AppDatabase = Room.databaseBuilder(ctx, AppDatabase::class.java, "mio.db")
        .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).fallbackToDestructiveMigration().build()
    val settings = SettingsStore(ctx)
    val scanner = MusicScanner(ctx)
    val lyricsRepo = LyricsRepository(ctx, db)
    val metadataRepo = MetadataRepository(ctx, db, settings, lyricsRepo)
    val playerManager = PlayerManager(ctx)
}
class MioApp : Application() {
    lateinit var container: AppContainer
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        MioLog.installCrashHandler(this)
        MioLog.event(this, "App start v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        // 1.1.2: one-time lyrics-cache clear (engine v3) so wrong lyrics
        // cached by older lookups are re-fetched with the current logic.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { container.lyricsRepo.clearStaleCacheIfNeeded() }
        }
    }
}
