package com.mio.player
import android.app.Application
import android.content.Context
import androidx.room.Room
import com.mio.player.data.db.AppDatabase
import com.mio.player.data.log.MioLog
import com.mio.player.data.prefs.SettingsStore
import com.mio.player.data.repo.LyricsRepository
import com.mio.player.data.repo.MusicScanner
import com.mio.player.playback.PlayerManager
/** Manual DI container — deliberately no Hilt: fewer annotation processors,
 *  faster CI builds, and one less failure mode on GitHub Actions. */
class AppContainer(ctx: Context) {
    val db: AppDatabase = Room.databaseBuilder(ctx, AppDatabase::class.java, "mio.db").fallbackToDestructiveMigration().build()
    val settings = SettingsStore(ctx)
    val scanner = MusicScanner(ctx)
    val lyricsRepo = LyricsRepository(ctx, db)
    val playerManager = PlayerManager(ctx)
}
class MioApp : Application() {
    lateinit var container: AppContainer
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        MioLog.installCrashHandler(this)
        MioLog.event(this, "App start v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        // 1.0.12: one-time lyrics-cache clear (engine v2) so wrong lyrics
        // cached by older lookups are re-fetched with the current logic.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { container.lyricsRepo.clearStaleCacheIfNeeded() }
        }
    }
}
