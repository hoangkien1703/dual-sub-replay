package com.kienhoang.dualsubreplay.playback

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.kienhoang.dualsubreplay.MainActivity
import com.kienhoang.dualsubreplay.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val PLAYBACK_LOG_TAG = "DualSubPlayback"
private const val CHANNEL_ID = "video_playback"
private const val NOTIFICATION_ID = 1_701
private const val ACTION_TOGGLE = "com.kienhoang.dualsubreplay.playback.TOGGLE"

/**
 * Owns the media session for the YouTube page video, so earphone and headset buttons reach the
 * app, and shows its media notification. It stays in the foreground while the video plays (and a
 * while after it pauses), which keeps Android from freezing the app when the screen turns off.
 * The video itself always plays in the one WebView; this service only sends it commands.
 */
class WebVideoPlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSessionCompat
    private var inForeground = false
    private var pausedSinceMs: Long? = null
    private var lastPlayback: WebVideoPlayback? = null
    private var sessionPlayback: WebVideoPlayback? = null
    private var sessionUpdatedAtMs = 0L
    private var shownNotificationKey: Any? = null
    private var following = false
    private var artworkVideoId: String? = null
    private var artwork: Bitmap? = null
    private var artworkJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        session =
            MediaSessionCompat(this, "DualSubReplay").apply {
                setCallback(SessionCallback())
                setSessionActivity(openAppIntent())
                isActive = true
            }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_TOGGLE) WebVideoBridge.toggle()
        // startForegroundService needs startForeground before anything stops the service.
        show(WebVideoBridge.playback.value, startedAsForeground = true)
        if (!following) {
            following = true
            scope.launch { followPageVideo() }
        }
        return START_NOT_STICKY
    }

    private suspend fun followPageVideo() {
        WebVideoBridge.playback.collectLatest { playback ->
            show(playback)
            // Re-check after the paused grace period even when nothing else changes.
            while (playback != null && playback.paused) {
                delay(PAUSED_FOREGROUND_GRACE_MS / 10)
                show(playback)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        WebVideoBridge.send(WebVideoCommand.Pause)
        stopEverything()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    private fun show(
        playback: WebVideoPlayback?,
        startedAsForeground: Boolean = false,
    ) {
        val now = SystemClock.elapsedRealtime()
        pausedSinceMs =
            when {
                playback == null || !playback.paused -> null
                lastPlayback?.paused == true && lastPlayback?.videoId == playback.videoId -> pausedSinceMs ?: now
                else -> now
            }
        lastPlayback = playback
        if (playback == null) {
            if (startedAsForeground) startInForeground(placeholderNotification())
            stopEverything()
            return
        }
        if (playback.videoId != artworkVideoId) loadArtwork(playback.videoId)
        if (sessionNeedsUpdate(sessionPlayback, sessionUpdatedAtMs, playback, now)) {
            updateSession(playback, now)
        }
        val wantForeground = startedAsForeground || shouldStayInForeground(playback, pausedSinceMs, now)
        // The position moves every poll; the notification only changes with these.
        val notificationKey = listOf(playback.videoId, playback.title, playback.channel, playback.paused, wantForeground)
        if (!startedAsForeground && notificationKey == shownNotificationKey) return
        shownNotificationKey = notificationKey
        val notification = buildNotification(playback)
        if (wantForeground) {
            startInForeground(notification)
        } else {
            if (inForeground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            inForeground = false
            postNotification(notification)
        }
    }

    /**
     * Shows the new video's picture once it downloads; until then (or when it can't load) the
     * notification has none, so the last video's picture never stays on the next one.
     */
    private fun loadArtwork(videoId: String) {
        artworkJob?.cancel()
        artworkVideoId = videoId
        artwork = null
        artworkJob =
            scope.launch {
                val picture = VideoArtwork.load(videoId) ?: return@launch
                if (artworkVideoId != videoId) return@launch
                artwork = picture
                // Force a new session state and notification for the same playback.
                sessionPlayback = null
                shownNotificationKey = null
                lastPlayback?.takeIf { it.videoId == videoId }?.let { show(it) }
            }
    }

    private fun startInForeground(notification: Notification) {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else {
                    0
                },
            )
            inForeground = true
        } catch (error: IllegalStateException) {
            // Android 12+ refuses a foreground start from the background (for example a resume
            // from earphones long after the app left the screen). Keep the controls visible anyway.
            Log.w(PLAYBACK_LOG_TAG, "Could not keep video playback in the foreground.", error)
            inForeground = false
            postNotification(notification)
        }
    }

    // Android 13+ exempts media-session notifications from the notification permission, so the
    // app never asks for it; without the exemption only this notification would be hidden.
    @SuppressLint("NotificationPermission")
    private fun postNotification(notification: Notification) {
        notificationManager().notify(NOTIFICATION_ID, notification)
    }

    private fun stopEverything() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        inForeground = false
        notificationManager().cancel(NOTIFICATION_ID)
        shownNotificationKey = null
        sessionPlayback = null
        artworkJob?.cancel()
        artworkVideoId = null
        artwork = null
        session.setPlaybackState(PlaybackStateCompat.Builder().setState(PlaybackStateCompat.STATE_NONE, 0, 1f).build())
        stopSelf()
    }

    private fun updateSession(
        playback: WebVideoPlayback,
        nowMs: Long,
    ) {
        sessionPlayback = playback
        sessionUpdatedAtMs = nowMs
        session.setPlaybackState(
            PlaybackStateCompat
                .Builder()
                .setActions(sessionActions(playback))
                .setState(
                    sessionPlaybackState(playback),
                    playback.positionMs,
                    if (playback.paused) 0f else playback.playbackRate,
                    nowMs,
                ).build(),
        )
        session.setMetadata(
            MediaMetadataCompat
                .Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, playback.videoId)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, displayTitle(playback))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, displayArtist(playback))
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, playback.durationMs ?: -1L)
                .apply { artwork?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) } }
                .build(),
        )
    }

    private fun displayTitle(playback: WebVideoPlayback): String = playback.title.ifBlank { getString(R.string.app_name) }

    private fun displayArtist(playback: WebVideoPlayback): String = playback.channel.ifBlank { getString(R.string.app_name) }

    private fun buildNotification(playback: WebVideoPlayback): Notification {
        val toggleAction =
            if (playback.paused) {
                NotificationCompat.Action(R.drawable.ic_media_play, getString(R.string.playback_play), toggleIntent())
            } else {
                NotificationCompat.Action(R.drawable.ic_media_pause, getString(R.string.playback_pause), toggleIntent())
            }
        return baseNotification()
            .setContentTitle(displayTitle(playback))
            .setContentText(displayArtist(playback))
            .setLargeIcon(artwork)
            .setOngoing(!playback.paused)
            .addAction(toggleAction)
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0))
            .build()
    }

    private fun placeholderNotification(): Notification = baseNotification().setContentTitle(getString(R.string.app_name)).build()

    private fun baseNotification(): NotificationCompat.Builder =
        NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_playback)
            .setContentIntent(openAppIntent())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun toggleIntent(): PendingIntent =
        PendingIntent.getService(
            this,
            1,
            Intent(this, WebVideoPlaybackService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.playback_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.playback_notification_channel_description)
                setShowBadge(false)
            }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager = getSystemService(NotificationManager::class.java)

    private inner class SessionCallback : MediaSessionCompat.Callback() {
        override fun onPlay() = WebVideoBridge.send(WebVideoCommand.Play)

        override fun onPause() = WebVideoBridge.send(WebVideoCommand.Pause)

        override fun onStop() = WebVideoBridge.send(WebVideoCommand.Pause)

        override fun onSeekTo(pos: Long) = WebVideoBridge.send(WebVideoCommand.SeekTo(pos))
    }

    companion object {
        @Volatile
        private var running = false

        /** Starts the service when a video starts playing; does nothing if it already runs. */
        internal fun startIfNeeded(context: Context) {
            if (running) return
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, WebVideoPlaybackService::class.java),
                )
                running = true
            } catch (error: IllegalStateException) {
                Log.w(PLAYBACK_LOG_TAG, "Could not start the video playback service.", error)
            }
        }
    }
}
