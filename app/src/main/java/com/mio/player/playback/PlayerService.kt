package com.mio.player.playback
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
/** Foreground MediaSession service: notification + lockscreen + Bluetooth controls.
 *  The ExoPlayer instance lives in the AppContainer and is shared with the UI,
 *  so the service and the UI never run two players.
 *  v1.1.20 — the action PendingIntents come from the LEGACY
 *  androidx.media.session.MediaButtonReceiver (Media3's own receiver
 *  has no buildMediaButtonPendingIntent); the manifest registers
 *  Media3's receiver for ACTION_MEDIA_BUTTON, so the KeyEvents it
 *  builds land on the Media3 session. The MediaStyle is likewise
 *  androidx.media.app.NotificationCompat.MediaStyle — core's
 *  NotificationCompat has no MediaStyle. Both need the
 *  androidx.media:media dependency (see app/build.gradle.kts).
 *  The transport action codes below are the PlaybackStateCompat
 *  values (SKIP_TO_PREVIOUS = 2, SKIP_TO_NEXT = 32, PLAY_PAUSE =
 *  512) written as literals: PlaybackStateCompat itself is not on
 *  the compile classpath even with androidx.media:media:1.7.0
 *  (two failed CI runs proved it), and neither is MediaSession's
 *  getSessionCompatToken() return type — so the MediaStyle carries
 *  no session token. That is fine on Android 13+: the lock-screen
 *  media card and the Dynamic Island are driven by the framework
 *  MediaSession itself, which 1.1.19 already proved surfaces
 *  (island showed art + waveform); the token only mattered for
 *  pre-13 SystemUI association. */
class PlayerService : MediaSessionService() {
    private var session: MediaSession? = null
    private var lastArtworkUri: Uri? = null
    private var lastArtwork: Bitmap? = null
    // v1.1.20 — the service owns the media notification now. The
    // 1.1.19 design trusted Media3's DefaultMediaNotificationProvider
    // to replace the placeholder posted here (same id 1001, same
    // channel), but the user's 1.1.19 lock-screen frame (f38, ~38 s
    // into the video, playing "...Baby One More Time") proves it
    // never did: the shade still showed only "Mio Player / Đang
    // phát nhạc", no track, no buttons, no seek bar — while the
    // ONKYO reference on the same phone (f58) shows title + artist
    // + prev/play/next + seek bar 01:45/04:00. So this service
    // builds the real MediaStyle notification itself from the
    // session metadata (title/artist/artwork are already in
    // PlayerManager.buildItem) and re-posts it on every player
    // event. If Media3's own manager also posts to id 1001, its
    // notification is a real media notification too — either way
    // the placeholder is gone. The seek bar on the lock screen /
    // Dynamic Island comes from the MediaSession itself (its
    // playback state), not from the notification — see the class
    // KDoc on why no session token is attached.
    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { updateNotification() }
        override fun onIsPlayingChanged(isPlaying: Boolean) { updateNotification() }
        override fun onPlaybackStateChanged(playbackState: Int) { updateNotification() }
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { updateNotification() }
    }
    override fun onCreate() {
        super.onCreate()
        val container = (application as com.mio.player.MioApp).container
        // v1.1.30 — the session activity: tapping the lock-screen
        // media card / Dynamic Island / expanded media controls
        // opens MainActivity. Without it (and without a content
        // intent on the notification) those taps did nothing — the
        // user proved it on video (v1.1.29): the island and the shade
        // card only expanded the controls, never opened the app.
        session = MediaSession.Builder(this, container.playerManager.player)
            .setSessionActivity(launchPendingIntent())
            .build()
        container.playerManager.player.addListener(playerListener)
        // v1.1.19: log the session creation so the next user log proves
        // the notification path ran.
        com.mio.player.data.log.MioLog.event(this, "PlayerService created: MediaSession built")
        startForegroundNow()
    }
    /** Start in the foreground IMMEDIATELY with the REAL media
     *  notification (not a placeholder). MediaSessionService only
     *  foregrounds itself once its async MediaController connects and the
     *  player is playing — in practice that took longer than the ~10s the
     *  system allows after startForegroundService(), and the app was
     *  killed with ForegroundServiceDidNotStartInTimeException (proven by
     *  the 1.0.5 crash log: the last startServiceCommon call was
     *  PlayerManager.ensureServiceStarted). The notification id stays
     *  1001 (Media3's default) and the channel stays
     *  "default_channel_id", so this replaces any earlier placeholder
     *  instead of stacking a second notification. */
    private fun startForegroundNow() {
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel("default_channel_id", "Mio Player", NotificationManager.IMPORTANCE_LOW)
                )
            }
            val notification = buildMediaNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(1001, notification)
            }
            logNotificationState("posted foreground")
        }
    }
    /** Re-post the media notification after a player event (track
     *  change, play/pause, state change). Same id 1001, so it
     *  updates in place instead of stacking. */
    private fun updateNotification() {
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1001, buildMediaNotification())
            logNotificationState("updated")
        }
    }
    private fun logNotificationState(what: String) {
        val player = session?.player ?: return
        val granted = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        val title = player.currentMediaItem?.mediaMetadata?.title?.toString() ?: "(no track)"
        com.mio.player.data.log.MioLog.event(
            this,
            "Media notification $what: title='$title' playing=${player.isPlaying} notifGranted=$granted"
        )
    }
    /** PendingIntent that brings MainActivity to the front (or
     *  starts it if the app was killed). SINGLE_TOP + CLEAR_TOP so
     *  tapping the notification never stacks a second MainActivity;
     *  NEW_TASK because a PendingIntent fires outside an Activity.
     *  Used both as the notification's content intent and as the
     *  MediaSession's session activity (v1.1.30). */
    private fun launchPendingIntent(): PendingIntent {
        val intent = Intent(this, com.mio.player.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    /** The real media notification: track title + artist, cover art
     *  as the large icon, prev / play-pause / next actions in
     *  MediaStyle form. The system's media card (lock screen +
     *  Dynamic Island) with the seek bar comes from the MediaSession
     *  itself — no token is attached (see class KDoc). Before
     *  any track is loaded this falls back to "Mio Player / Đang
     *  phát nhạc" — the same words as the old placeholder, but
     *  already in MediaStyle form, so the first track change
     *  replaces it in place. */
    private fun buildMediaNotification(): Notification {
        val player = session?.player
        val metadata = player?.currentMediaItem?.mediaMetadata
        val title = metadata?.title?.toString() ?: "Mio Player"
        val artist = metadata?.artist?.toString()
        val isPlaying = player?.isPlaying == true
        // PlaybackStateCompat action codes as literals (see class KDoc).
        val prevIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, 2L)
        val playPauseIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, 512L)
        val nextIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, 32L)
        val builder = NotificationCompat.Builder(this, "default_channel_id")
            .setSmallIcon(androidx.media3.session.R.drawable.media3_notification_small_icon)
            .setContentTitle(title)
            .setContentText(artist ?: "Đang phát nhạc")
            .setLargeIcon(loadArtwork(metadata?.artworkUri))
            // v1.1.30 — tapping the notification body opens the app.
            // This was missing entirely, so the tap did nothing.
            .setContentIntent(launchPendingIntent())
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(androidx.media3.session.R.drawable.media3_icon_previous, "Previous", prevIntent)
            .addAction(
                if (isPlaying) androidx.media3.session.R.drawable.media3_icon_pause
                else androidx.media3.session.R.drawable.media3_icon_play,
                if (isPlaying) "Pause" else "Play", playPauseIntent
            )
            .addAction(androidx.media3.session.R.drawable.media3_icon_next, "Next", nextIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )
        return builder.build()
    }
    /** Cover art for the notification's large icon, downsampled to
     *  ~256 px and cached per artwork URI so a track change costs
     *  one decode, not one per notification update. The URI is the
     *  same one PlayerManager puts in the session metadata (the
     *  downloaded cover file, else the MediaStore album art). */
    private fun loadArtwork(uri: Uri?): Bitmap? {
        if (uri == null) return null
        if (uri == lastArtworkUri) return lastArtwork
        val bitmap = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
            val sample = (maxSide / 256).coerceAtLeast(1)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
        lastArtworkUri = uri
        lastArtwork = bitmap
        return bitmap
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() {
        runCatching { session?.player?.removeListener(playerListener) }
        session?.release(); session = null; super.onDestroy()
    }
}
