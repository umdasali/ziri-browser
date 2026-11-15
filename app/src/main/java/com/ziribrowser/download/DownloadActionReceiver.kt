package com.ziribrowser.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ziribrowser.ZiriApp
import kotlinx.coroutines.launch

/** Handles the buttons on the download notification. */
class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as ZiriApp).container
        val result = goAsync()
        container.scope.launch {
            try { if (intent.action == PAUSE_ALL) container.downloads.pauseAll() } finally { result.finish() }
        }
    }

    companion object { const val PAUSE_ALL = "com.ziribrowser.download.PAUSE_ALL" }
}
