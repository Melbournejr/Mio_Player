package com.mio.player.playback
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
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
        startForegroundNow()
    }
    /** Start in the foreground IMMEDIATELY. MediaSessionService only
     *  foregrounds itself once its async MediaController connects and the
     *  player is playing — in practice that took longer than the ~10s the
     *  system allows after startForegroundService(), and the app was
     *  killed with ForegroundServiceDidNotStartInTimeException (proven by
     *  the 1.0.5 crash log: the last startServiceCommon call was
     *  PlayerManager.ensureServiceStarted). Use media3's own notification
     *  id (DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID = 1001)
     *  and channel ("default_channel_id") so the real media notification
     *  replaces this placeholder instead of stacking a second one. */
    private fun startForegroundNow() {
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel("default_channel_id", "Mio Player", NotificationManager.IMPORTANCE_LOW)
                )
            }
            val notification = NotificationCompat.Builder(this, "default_channel_id")
                .setSmallIcon(androidx.media3.session.R.drawable.media3_notification_small_icon)
                .setContentTitle("Mio Player")
                .setContentText("Đang phát nhạc")
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(1001, notification)
            }
        }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() { session?.release(); session = null; super.onDestroy() }
}
