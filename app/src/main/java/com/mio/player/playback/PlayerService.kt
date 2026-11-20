package com.mio.player.playback
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
/** Foreground MediaSession service: notification + lockscreen + Bluetooth controls.
 *  The ExoPlayer instance lives in the AppContainer and is shared with the UI,
 *  so the service and the UI never run two players. */
class PlayerService : MediaSessionService() {
    private var session: MediaSession? = null
    override fun onCreate() {
        super.onCreate()
        val container = (application as com.mio.player.MioApp).container
        session = MediaSession.Builder(this, container.playerManager.player).build()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() { session?.release(); session = null; super.onDestroy() }
}
