package com.ziribrowser.browser

import com.ziribrowser.R
import com.ziribrowser.ziriString
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import com.ziribrowser.MainActivity
import com.ziribrowser.ZiriApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps a media notification (and lock-screen controls) up while a web video plays: play/pause and
 * jump back/forward 10 seconds. It only mirrors the page's video; it does not play anything itself.
 */
class MediaPlaybackService : Service() {
    private var session: MediaSession? = null
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as ZiriApp).container
        val video = container.tabs.video
        when (intent?.action) {
            ACTION_TOGGLE -> video.toggle()
            ACTION_BACK -> video.seekBy(-SKIP)
            ACTION_FORWARD -> video.seekBy(SKIP)
            ACTION_STOP -> { video.pause(); shutDown(); return START_NOT_STICKY }
        }
        if (job == null) {
            ensureChannel()
            val s = MediaSession(this, "Ziri").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() = video.play()
                    override fun onPause() = video.pause()
                    override fun onSeekTo(pos: Long) = video.seekTo(pos / 1000.0)
                    override fun onFastForward() = video.seekBy(SKIP)
                    override fun onRewind() = video.seekBy(-SKIP)
                })
                isActive = true
            }
            session = s
            startForeground(NOTIFICATION_ID, build(s, video.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            job = container.scope.launch(Dispatchers.Main) {
                video.state.collect { st ->
                    if (!video.wantsMediaControls) { shutDown(); return@collect }
                    update(s, st)
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(s, st))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun shutDown() {
        job?.cancel(); job = null
        session?.release(); session = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        session?.release()
        super.onDestroy()
    }

    private fun update(s: MediaSession, st: VideoState) {
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, if (st.isPrivate) ziriString(R.string.private_video) else st.title.ifEmpty { st.host })
                .putString(MediaMetadata.METADATA_KEY_ARTIST, if (st.isPrivate) "" else st.host)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, (st.duration * 1000).toLong())
                .build(),
        )
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND,
                )
                .setState(if (st.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, (st.position * 1000).toLong(), st.rate)
                .build(),
        )
    }

    private fun build(s: MediaSession, st: VideoState): Notification {
        fun action(res: Int, label: String, act: String) = Notification.Action.Builder(
            Icon.createWithResource(this, res), label,
            PendingIntent.getService(this, act.hashCode(), Intent(this, MediaPlaybackService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE),
        ).build()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(if (st.isPrivate) ziriString(R.string.private_video) else st.title.ifEmpty { st.host.ifEmpty { ziriString(R.string.video) } })
            .setContentText(if (st.isPrivate) "" else st.host)
            .setContentIntent(open)
            .setOngoing(st.playing)
            .setOnlyAlertOnce(true)
            .setVisibility(if (st.isPrivate) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_rew, ziriString(R.string.back_10_seconds), ACTION_BACK))
            .addAction(
                if (st.playing) action(android.R.drawable.ic_media_pause, ziriString(R.string.pause), ACTION_TOGGLE)
                else action(android.R.drawable.ic_media_play, ziriString(R.string.play), ACTION_TOGGLE),
            )
            .addAction(action(android.R.drawable.ic_media_ff, ziriString(R.string.forward_10_seconds), ACTION_FORWARD))
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, ziriString(R.string.media_playback), NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.ziribrowser.media.TOGGLE"
        const val ACTION_BACK = "com.ziribrowser.media.BACK"
        const val ACTION_FORWARD = "com.ziribrowser.media.FORWARD"
        const val ACTION_STOP = "com.ziribrowser.media.STOP"
        private const val CHANNEL = "media_playback"
        private const val NOTIFICATION_ID = 20_000
        private const val SKIP = 10.0
    }
}
