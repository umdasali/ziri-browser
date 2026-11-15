package com.ziribrowser.ui.viewer

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ziribrowser.ZiriApp

/** Shows the playback notification (lock-screen controls) and keeps audio going while the app is in the background. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    override fun onCreate() {
        super.onCreate()
        // Register the player's session so the system notification and lock-screen controls follow it.
        (application as ZiriApp).container.players.mediaSession()?.let { addSession(it) }
    }

    // Only this app and the system (lock screen, notification, Bluetooth) may control playback; other apps get nothing.
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        (application as ZiriApp).container.players.mediaSession()?.takeIf { controllerInfo.isTrusted }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // The app was swiped away: stop unless music is playing on purpose.
        val holder = (application as ZiriApp).container.players
        if (holder.player?.isPlaying != true) holder.release()
        super.onTaskRemoved(rootIntent)
    }
}
