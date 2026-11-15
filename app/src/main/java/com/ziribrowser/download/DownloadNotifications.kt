package com.ziribrowser.download

import com.ziribrowser.R
import com.ziribrowser.ziriString
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ziribrowser.MainActivity
import com.ziribrowser.data.DownloadEntity

object DownloadNotifications {
    private const val CHANNEL_PROGRESS = "downloads_progress"
    private const val CHANNEL_DONE = "downloads_done"
    const val SERVICE_ID = 4001

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, ziriString(R.string.download_progress), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, ziriString(R.string.completed_downloads), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Tapping a finished video/picture/PDF notification opens it in Ziri's own viewer. */
    private fun openViewer(context: Context, id: Long): PendingIntent = PendingIntent.getActivity(
        context, 100_000 + id.toInt(),
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_VIEW_DOWNLOAD, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun progress(context: Context, running: Int, downloaded: Long, total: Long): Notification {
        val b = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (running == 1) ziriString(R.string.downloading_1_file) else ziriString(R.string.downloading_files, running))
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                0, ziriString(R.string.pause_all),
                PendingIntent.getBroadcast(context, 7, Intent(context, DownloadActionReceiver::class.java).setAction(DownloadActionReceiver.PAUSE_ALL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        if (total > 0) b.setProgress(100, ((downloaded * 100) / total).toInt().coerceIn(0, 100), false)
        else b.setProgress(0, 0, true)
        return b.build()
    }

    /**
     * POST_NOTIFICATIONS only exists on Android 13+; on 10-12 checkSelfPermission reports it as denied
     * and finished/failed downloads would never notify. areNotificationsEnabled() is right on every version.
     */
    private fun canNotify(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

    @SuppressLint("MissingPermission") // canNotify() checks that notifications are allowed
    fun completed(context: Context, d: DownloadEntity) {
        if (!canNotify(context)) return
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(d.fileUri), d.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // Risky file types open the app instead, where the user is warned first.
        val tap = if (FileUtils.isViewableInApp(d.category, d.mimeType, d.filename)) openViewer(context, d.id)
        else if (FileUtils.isDangerous(d.filename, d.mimeType)) openApp(context)
        else PendingIntent.getActivity(context, d.id.toInt(), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(ziriString(R.string.download_complete))
            .setContentText(d.filename)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(10_000 + d.id.toInt(), n)
    }

    @SuppressLint("MissingPermission") // canNotify() checks that notifications are allowed
    fun failed(context: Context, d: DownloadEntity) {
        if (!canNotify(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(ziriString(R.string.download_failed))
            .setContentText(d.filename)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(10_000 + d.id.toInt(), n)
    }
}
