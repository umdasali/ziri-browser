package com.ziribrowser.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ziribrowser.ZiriApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and shows one progress notification while downloads run.
 * The engine does the actual work; this service only hosts the foreground notification.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = (application as ZiriApp).container.downloads
        DownloadNotifications.ensureChannels(this)
        ServiceCompat.startForeground(
            this, DownloadNotifications.SERVICE_ID,
            DownloadNotifications.progress(this, engine.running.value.coerceAtLeast(1), 0, -1),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        watcher?.cancel()
        watcher = scope.launch {
            engine.running.combine(engine.live) { running, live -> running to live }.collect { (running, live) ->
                if (running == 0) {
                    stopSelf()
                } else {
                    val downloaded = live.values.sumOf { it.downloaded }
                    val total = live.values.sumOf { it.total.coerceAtLeast(0) }
                    val allKnown = live.values.all { it.total > 0 }
                    getSystemService(android.app.NotificationManager::class.java).notify(
                        DownloadNotifications.SERVICE_ID,
                        DownloadNotifications.progress(this@DownloadService, running, downloaded, if (allKnown) total else -1),
                    )
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+ ends long "data sync" services after about 6 hours: pause cleanly instead of being killed. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val engine = (application as ZiriApp).container.downloads
        scope.launch { engine.pauseAll(); stopSelf() }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }
}
