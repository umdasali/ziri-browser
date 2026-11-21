package com.ziribrowser.ui.viewer

import com.ziribrowser.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.ziribrowser.AppContainer
import com.ziribrowser.data.DownloadEntity
import com.ziribrowser.data.DownloadStatus
import com.ziribrowser.download.LiveProgress

/**
 * Plays a downloaded video or audio file in the full player. If the download is still running it plays what has arrived
 * so far and keeps going as more arrives ("watch while downloading"). The other finished videos play next, in order.
 */
@Composable
fun VideoViewer(container: AppContainer, d: DownloadEntity, live: LiveProgress?, onBack: () -> Unit) {
    val loaded by remember { container.db.downloads().observeAll() }.collectAsState(null as List<DownloadEntity>?)
    // Wait for the first list: building the queue from an empty placeholder left out the "play next" items and subtitles for good.
    val all = loaded ?: return
    // Fixed once: the list shouldn't change under the player while it runs.
    val queue = remember(d.id) {
        // A normal video never queues a private one next (or the other way round).
        val others = all.filter { it.isPrivate == d.isPrivate && it.id != d.id && it.status == DownloadStatus.COMPLETED && (it.category == "VIDEOS" || it.category == "AUDIO") }
            .sortedBy { it.filename.lowercase() }
        // A subtitle download with the same name as a video plays along with it.
        val subs = all.filter { it.status == DownloadStatus.COMPLETED && it.filename.substringAfterLast('.', "").lowercase() in setOf("srt", "vtt", "ass", "ssa") }
        fun subsFor(v: DownloadEntity) = subs.filter { it.filename.substringBeforeLast('.').equals(v.filename.substringBeforeLast('.'), ignoreCase = true) }.map { android.net.Uri.parse(it.fileUri) }
        val items = (listOf(d) + others).map { PlayItem(downloadPlayerUri(it.id), it.filename, if (it.isPrivate) null else "download:${it.url}", it.id, it.mimeType, subsFor(it), isPrivate = it.isPrivate) }
        PlayQueue(items, 0)
    }
    MxPlayer(
        container = container,
        queue = queue,
        onBack = onBack,
        banner = { DownloadBanner(d, live) },
        errorExtra = { retry ->
            if (d.status != DownloadStatus.COMPLETED) {
                Column {
                    Text(stringResource(R.string.some_videos_can_only_start_once_enough))
                }
            }
        },
    )
}
