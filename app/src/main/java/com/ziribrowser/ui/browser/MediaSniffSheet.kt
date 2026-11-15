package com.ziribrowser.ui.browser

import com.ziribrowser.ziriString
import com.ziribrowser.R
import androidx.compose.ui.res.stringResource
import android.webkit.WebSettings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ziribrowser.AppContainer
import com.ziribrowser.browser.DetectedMedia
import com.ziribrowser.browser.MediaKind
import com.ziribrowser.browser.PrivateProfile
import com.ziribrowser.browser.TabState
import com.ziribrowser.browser.UrlUtils
import com.ziribrowser.download.DownloadRequest
import com.ziribrowser.download.EnqueueResult
import com.ziribrowser.download.FileUtils
import com.ziribrowser.ui.IconBadge
import kotlinx.coroutines.launch

/** What a probe learned about a detected item. [usable] is false for things that aren't real downloadable media. */
private data class MediaInfo(val label: String, val usable: Boolean, val length: Long = -1, val mime: String? = null, val problem: String? = null)

/** Lists the videos and audio found on the page so the user can download one, or watch it while it downloads. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaSniffSheet(container: AppContainer, tab: TabState, onDismiss: () -> Unit, onOpenViewer: (Long) -> Unit) {
    val detected by container.tabs.detectedMedia.collectAsStateWithLifecycle()
    var scanning by remember(tab.id) { mutableStateOf(true) }
    LaunchedEffect(tab.id) { container.tabs.scanPageMedia(tab.id) { scanning = false } }
    val items = detected[tab.id].orEmpty()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.videos_on_this_page), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            when {
                items.isEmpty() && scanning -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.looking_for_videos))
                }
                items.isEmpty() -> Text(
                    stringResource(R.string.no_downloadable_video_found_play_the_vid),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(items, key = { it.url }) { m -> MediaRow(container, tab, m, onOpenViewer) }
                }
            }
        }
    }
}

@Composable
private fun MediaRow(container: AppContainer, tab: TabState, m: DetectedMedia, onOpenViewer: (Long) -> Unit) {
    val context = LocalContext.current
    val downloadingText = stringResource(R.string.downloading)
    val alreadyInYourDownloadsText = stringResource(R.string.already_in_your_downloads)
    val scope = rememberCoroutineScope()
    val cookie = remember(m.url) { PrivateProfile.cookieFor(m.url, tab.isPrivate) }
    val ua = remember { WebSettings.getDefaultUserAgent(context) }
    val live by container.downloads.live.collectAsStateWithLifecycle()
    var startedId by remember(m.url) { mutableLongStateOf(-1L) }

    val info by produceState<MediaInfo?>(null, m.url) {
        value = if (m.kind == MediaKind.STREAM) {
            val plan = container.downloads.probeStream(m.url, cookie, ua, tab.url)
            plan.fold(
                onSuccess = { p -> MediaInfo(ziriString(R.string.stream, duration(p.totalSeconds)), usable = true) },
                onFailure = { e -> MediaInfo(ziriString(R.string.can_t_be_saved), usable = false, problem = e.message) },
            )
        } else {
            val p = container.downloads.probe(m.url, cookie, ua, tab.url)
            when {
                p == null -> MediaInfo(m.ext.uppercase(), usable = true)
                p.mime?.startsWith("text/") == true || p.mime?.startsWith("image/") == true -> MediaInfo("", usable = false)
                p.length in 1..150_000 -> MediaInfo("", usable = false) // a tiny clip: almost certainly an ad or a sound effect
                else -> MediaInfo(
                    listOfNotNull(m.ext.uppercase().ifEmpty { null }, if (p.length > 0) FileUtils.formatBytes(p.length) else null).joinToString(" · "),
                    usable = true, length = p.length, mime = p.mime,
                )
            }
        }
    }
    val i = info
    if (i != null && !i.usable && i.problem == null) return // not real media: hide the row

    fun start(watch: Boolean) {
        scope.launch {
            val req = DownloadRequest(
                url = m.url, userAgent = ua, cookie = cookie, referer = tab.url, title = tab.title.ifBlank { null },
                mimeType = i?.mime, contentLength = i?.length ?: -1, isPrivate = tab.isPrivate,
            )
            when (val r = container.downloads.enqueue(req)) {
                is EnqueueResult.Started -> { startedId = r.id; if (watch) onOpenViewer(r.id) else Toast.makeText(context, downloadingText, Toast.LENGTH_SHORT).show() }
                is EnqueueResult.Duplicate -> { startedId = r.existing.id; if (watch) onOpenViewer(r.existing.id) else Toast.makeText(context, alreadyInYourDownloadsText, Toast.LENGTH_SHORT).show() }
                is EnqueueResult.Rejected -> Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(if (m.kind == MediaKind.AUDIO) Icons.Rounded.PlayArrow else Icons.Rounded.Download, Modifier.padding(end = 12.dp))
        Column(Modifier.weight(1f)) {
            Text(tab.title.ifBlank { UrlUtils.host(m.url) }, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val progress = live[startedId]
            Text(
                when {
                    progress != null -> if (progress.total > 0) stringResource(R.string.downloading_percent_speed, (progress.downloaded * 100 / progress.total).toString(), FileUtils.formatSpeed(progress.speed)) else stringResource(R.string.downloading_speed, FileUtils.formatSpeed(progress.speed))
                    startedId >= 0 -> stringResource(R.string.in_your_downloads)
                    i == null -> stringResource(R.string.checking)
                    else -> i.problem ?: i.label
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (i?.usable == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (i?.usable != false) {
            IconButton({ if (startedId >= 0) onOpenViewer(startedId) else start(true) }) {
                Icon(Icons.Rounded.PlayArrow, stringResource(R.string.watch_while_downloading), tint = MaterialTheme.colorScheme.primary)
            }
            if (startedId < 0) FilledTonalButton({ start(false) }) { Text(stringResource(R.string.download)) }
        }
    }
}

private fun duration(seconds: Double): String {
    val s = seconds.toLong()
    return if (s >= 3600) "${s / 3600} h ${(s % 3600) / 60} min" else "${(s / 60).coerceAtLeast(1)} min"
}

/** A slim bar under the address bar while downloads run: how many, how fast. Tap to open the Downloads screen. */
@Composable
fun DownloadStrip(container: AppContainer, onClick: () -> Unit) {
    val all by remember { container.db.downloads().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val live by container.downloads.live.collectAsStateWithLifecycle()
    val active = all.filter { it.status == com.ziribrowser.data.DownloadStatus.DOWNLOADING }
    if (active.isEmpty()) return
    val speed = active.sumOf { live[it.id]?.speed ?: 0L }
    val done = active.sumOf { live[it.id]?.downloaded ?: it.downloadedSize }
    val total = active.sumOf { (live[it.id]?.total ?: it.fileSize).coerceAtLeast(0L) }
    val known = active.all { (live[it.id]?.total ?: it.fileSize) > 0 }
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
            .androidClickable(onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 6.dp))
            Text(
                "${active.size} downloading" + if (speed > 0) " · ${FileUtils.formatSpeed(speed)}" else "" + if (known && total > 0) " · ${done * 100 / total}%" else "",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
            )
        }
        if (known && total > 0) androidx.compose.material3.LinearProgressIndicator({ (done.toFloat() / total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 2.dp))
    }
}

private fun Modifier.androidClickable(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick))
