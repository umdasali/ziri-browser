package com.ziribrowser.ui.downloads

import com.ziribrowser.ziriString
import com.ziribrowser.R
import androidx.compose.ui.res.stringResource
import com.ziribrowser.ui.LocalBarClearance
import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ziribrowser.AppContainer
import com.ziribrowser.data.DownloadEntity
import com.ziribrowser.data.DownloadStatus
import com.ziribrowser.download.Category
import com.ziribrowser.download.FileUtils
import com.ziribrowser.ui.EmptyState
import com.ziribrowser.ui.GlassSurface
import com.ziribrowser.ui.viewer.ViewerKind
import com.ziribrowser.ui.viewer.viewerKindOf
import com.ziribrowser.ui.ZiriHeader
import kotlinx.coroutines.launch

private fun iconFor(category: String): ImageVector = when (runCatching { Category.valueOf(category) }.getOrDefault(Category.OTHERS)) {
    Category.DOCUMENTS -> Icons.Rounded.Description
    Category.IMAGES -> Icons.Rounded.Image
    Category.VIDEOS -> Icons.Rounded.Movie
    Category.AUDIO -> Icons.Rounded.MusicNote
    Category.ARCHIVES -> Icons.Rounded.FolderZip
    Category.APPS -> Icons.Rounded.Android
    Category.OTHERS -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

private enum class DlSort(val label: String) { NEWEST(ziriString(R.string.newest_first)), NAME(ziriString(R.string.name)), SIZE(ziriString(R.string.largest_first)) }

@Composable
fun DownloadsScreen(container: AppContainer, onOpenViewer: (Long) -> Unit = {}, onOpenFiles: () -> Unit = {}) {
    val all by container.db.downloads().observeAll().collectAsStateWithLifecycle(emptyList())
    val live by container.downloads.live.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val headerScope = rememberCoroutineScope()
    var filter by remember { mutableStateOf<Category?>(null) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(DlSort.NEWEST) }
    var sortMenu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmCleanup by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<DownloadEntity?>(null) }
    var addLink by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()

    val shown = remember(all, filter, query, sort) {
        all.filter { (filter == null || it.category == filter!!.name) && (query.isBlank() || it.filename.contains(query, ignoreCase = true)) }.let { l ->
            when (sort) {
                DlSort.NEWEST -> l.sortedByDescending { it.createdAt }
                DlSort.NAME -> l.sortedBy { it.filename.lowercase() }
                DlSort.SIZE -> l.sortedByDescending { it.fileSize }
            }
        }
    }
    androidx.activity.compose.BackHandler(selecting) { selected = emptySet() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        if (selecting) {
            ZiriHeader("${selected.size} selected") {
                val sel = all.filter { it.id in selected }
                if (sel.size == 1) IconButton({ details = sel.first() }) { Icon(Icons.Rounded.Info, stringResource(R.string.details)) }
                if (sel.all { it.status == DownloadStatus.COMPLETED }) IconButton({
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                        .putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(sel.map { android.net.Uri.parse(it.fileUri) }))
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { context.startActivity(android.content.Intent.createChooser(send, ziriString(R.string.share)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Icon(Icons.Rounded.Share, stringResource(R.string.share)) }
                IconButton({ selected = shown.map { it.id }.toSet() }) { Icon(Icons.Rounded.SelectAll, stringResource(R.string.select_all)) }
                IconButton({ confirmDelete = true }) { Icon(Icons.Rounded.Delete, stringResource(R.string.delete)) }
                IconButton({ selected = emptySet() }) { Icon(Icons.Rounded.Close, stringResource(R.string.cancel_selection)) }
            }
        } else {
            ZiriHeader(stringResource(R.string.downloads)) {
                val anyRunning = all.any { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }
                val anyStopped = all.any { it.status == DownloadStatus.PAUSED }
                if (anyRunning) IconButton({ headerScope.launch { container.downloads.pauseAll() } }) { Icon(Icons.Rounded.Pause, stringResource(R.string.pause_all)) }
                else if (anyStopped) IconButton({ headerScope.launch { container.downloads.resumeAll() } }) { Icon(Icons.Rounded.PlayArrow, stringResource(R.string.resume_all)) }
                IconButton({ addLink = true }) { Icon(Icons.Rounded.AddLink, stringResource(R.string.download_from_a_link)) }
                Box {
                    IconButton({ sortMenu = true }) { Icon(Icons.Rounded.Sort, stringResource(R.string.sort_and_clean_up)) }
                    androidx.compose.material3.DropdownMenu(sortMenu, { sortMenu = false }, shape = RoundedCornerShape(16.dp)) {
                        DlSort.entries.forEach { o ->
                            androidx.compose.material3.DropdownMenuItem(
                                { Text(o.label, fontWeight = if (o == sort) FontWeight.Bold else FontWeight.Normal) }, { sort = o; sortMenu = false },
                            )
                        }
                        androidx.compose.material3.DropdownMenuItem({ Text(stringResource(R.string.clear_failed_and_unfinished)) }, { sortMenu = false; confirmCleanup = true })
                    }
                }
                IconButton(onOpenFiles) { Icon(Icons.Rounded.FolderOpen, stringResource(R.string.file_manager)) }
            }
        }
        ActiveSummary(all, live)
        StorageSummary(all)
        com.ziribrowser.ui.ZiriSearchField(query, { query = it }, stringResource(R.string.search_downloads))
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(filter == null, { filter = null }, { Text(stringResource(R.string.all)) }) }
            items(Category.entries.toList()) { c -> FilterChip(filter == c, { filter = c }, { Text(c.label) }) }
        }
        if (shown.isEmpty()) EmptyState(if (query.isBlank()) stringResource(R.string.no_downloads_yet) else stringResource(R.string.no_matches), Icons.Rounded.Download)
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 8.dp + LocalBarClearance.current)) {
            items(shown, key = { it.id }) { d ->
                DownloadCard(
                    container, d, live[d.id], onOpenViewer, selecting = selecting, isSelected = d.id in selected,
                    onToggleSelect = { selected = if (d.id in selected) selected - d.id else selected + d.id },
                )
            }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false }, title = { Text(stringResource(R.string.delete_downloads, selected.size)) },
        text = { Text(stringResource(R.string.the_files_will_be_removed_from_your_de)) },
        confirmButton = { TextButton({ confirmDelete = false; val ids = selected; selected = emptySet(); headerScope.launch { ids.forEach { container.downloads.remove(it) } } }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (confirmCleanup) {
        val junk = all.filter { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.PAUSED }
        AlertDialog(
            onDismissRequest = { confirmCleanup = false }, title = { Text(stringResource(R.string.clear_unfinished_downloads)) },
            text = { Text(if (junk.isEmpty()) stringResource(R.string.nothing_to_clear) else "${junk.size} failed or paused downloads and their partial files will be removed.") },
            confirmButton = { TextButton({ confirmCleanup = false; headerScope.launch { junk.forEach { container.downloads.remove(it.id) } } }, enabled = junk.isNotEmpty()) { Text(stringResource(R.string.clear)) } },
            dismissButton = { TextButton({ confirmCleanup = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    details?.let { d -> DetailsDialog(d) { details = null } }
    if (addLink) AddLinkDialog(container, onDismiss = { addLink = false })
}

/** "Downloads use 1.2 GB · 18 GB free" */
@Composable
private fun StorageSummary(all: List<DownloadEntity>) {
    val used = all.filter { it.status == DownloadStatus.COMPLETED }.sumOf { it.fileSize.coerceAtLeast(0) }
    val free = remember(all.size) { runCatching { android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(-1L) }
    Text(
        stringResource(R.string.downloads_use, FileUtils.formatBytes(used)) + if (free >= 0) " · ${FileUtils.formatBytes(free)} free" else "",
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
    )
}

@Composable
private fun DetailsDialog(d: DownloadEntity, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(d.filename, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    stringResource(R.string.size) to FileUtils.formatBytes(d.fileSize),
                    stringResource(R.string.type) to d.mimeType,
                    stringResource(R.string.saved_to) to "Downloads/Ziri/${com.ziribrowser.download.Category.valueOf(d.category).folder}",
                    stringResource(R.string.from) to d.url,
                    stringResource(R.string.added) to java.text.DateFormat.getDateTimeInstance().format(java.util.Date(d.createdAt)),
                    stringResource(R.string.status) to stringResource(when (d.status) { DownloadStatus.QUEUED -> R.string.queued; DownloadStatus.DOWNLOADING -> R.string.status_downloading; DownloadStatus.PAUSED -> R.string.status_paused; DownloadStatus.COMPLETED -> R.string.status_completed; DownloadStatus.FAILED -> R.string.failed }),
                ).forEach { (k, v) ->
                    Column { Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary); Text(v, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** Start a download by pasting a link (the clipboard is only read when you tap Paste). */
@Composable
private fun AddLinkDialog(container: AppContainer, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val theClipboardIsEmptyText = stringResource(R.string.the_clipboard_is_empty)
    var url by remember { mutableStateOf("") }
    val ok = com.ziribrowser.browser.UrlUtils.isHttp(url.trim())
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(stringResource(R.string.download_from_a_link)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(url, { url = it }, singleLine = true, label = { Text(stringResource(R.string.https)) }, modifier = Modifier.fillMaxWidth())
                TextButton({
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                    if (t.isNotEmpty()) url = t else Toast.makeText(context, theClipboardIsEmptyText, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.paste_from_clipboard)) }
            }
        },
        confirmButton = {
            TextButton({
                onDismiss()
                container.requestDownload(com.ziribrowser.download.DownloadRequest(url = url.trim(), userAgent = android.webkit.WebSettings.getDefaultUserAgent(context)))
            }, enabled = ok) { Text(stringResource(R.string.continue_text)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun DownloadCard(
    container: AppContainer, d: DownloadEntity, live: com.ziribrowser.download.LiveProgress?, onOpenViewer: (Long) -> Unit,
    selecting: Boolean = false, isSelected: Boolean = false, onToggleSelect: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val noAppCanOpenThisFileText = stringResource(R.string.no_app_can_open_this_file)
    val theFileIsNoLongerAvailableText = stringResource(R.string.the_file_is_no_longer_available)
    var confirmOpen by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    val downloaded = live?.downloaded ?: d.downloadedSize
    val total = live?.total ?: d.fileSize
    val fraction = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
    val pct = (fraction * 100).toInt()
    val active = d.status == DownloadStatus.DOWNLOADING
    val done = d.status == DownloadStatus.COMPLETED

    fun open() {
        try { context.startActivity(container.downloads.openIntent(d)) }
        catch (_: ActivityNotFoundException) { Toast.makeText(context, noAppCanOpenThisFileText, Toast.LENGTH_SHORT).show() }
        catch (_: SecurityException) { Toast.makeText(context, theFileIsNoLongerAvailableText, Toast.LENGTH_SHORT).show() }
    }

    val privateTag = if (d.isPrivate) stringResource(R.string.private_text) + " " else ""
    val status = privateTag + when (d.status) {
        DownloadStatus.QUEUED -> stringResource(R.string.queued)
        DownloadStatus.DOWNLOADING -> listOf(
            if (total > 0) "$pct%" else "",
            if (total > 0) "${FileUtils.formatBytes(downloaded)} of ${FileUtils.formatBytes(total)}" else FileUtils.formatBytes(downloaded),
            FileUtils.formatSpeed(live?.speed ?: 0), FileUtils.formatEta(live?.etaSeconds ?: 0),
        ).filter { it.isNotEmpty() }.joinToString(" · ")
        DownloadStatus.PAUSED -> ziriString(R.string.paused, if (total > 0) "$pct%" else FileUtils.formatBytes(downloaded))
        DownloadStatus.COMPLETED -> "${FileUtils.formatBytes(d.fileSize)} · ${d.mimeType}"
        DownloadStatus.FAILED -> d.error.ifEmpty { stringResource(R.string.failed) }
    }

    GlassSurface(
        shape = RoundedCornerShape(20.dp), highlight = isSelected,
        tint = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Unspecified,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        onLongClick = onToggleSelect,
        onClick = if (selecting) onToggleSelect else if (done) ({
            // Videos, pictures and PDFs open in Ziri's own viewer; anything else goes to another app.
            if (viewerKindOf(d) != ViewerKind.OTHER) onOpenViewer(d.id)
            else if (FileUtils.isDangerous(d.filename, d.mimeType)) confirmOpen = true else open()
        }) else if (viewerKindOf(d) == ViewerKind.VIDEO && d.status != DownloadStatus.FAILED) ({ onOpenViewer(d.id) }) else null,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(iconFor(d.category), null, tint = MaterialTheme.colorScheme.primary) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(d.filename, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (active || d.status == DownloadStatus.PAUSED) {
                    if (total > 0) LinearProgressIndicator({ fraction }, Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    else LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
                Text(
                    status, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = if (d.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if ((active || d.status == DownloadStatus.PAUSED) && !d.canResume) {
                    Text(stringResource(R.string.this_server_doesn_t_support_pause_and), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Watch a video while it is still downloading.
            if (!done && viewerKindOf(d) == ViewerKind.VIDEO && d.status != DownloadStatus.FAILED) {
                IconButton({ onOpenViewer(d.id) }) { Icon(Icons.Rounded.PlayCircle, stringResource(R.string.watch_now), tint = MaterialTheme.colorScheme.primary) }
            }
            if (d.status == DownloadStatus.QUEUED) IconButton({ scope.launch { container.downloads.moveToTop(d.id) } }) { Icon(Icons.Rounded.VerticalAlignTop, stringResource(R.string.move_to_top)) }
            if (active && d.canResume) IconButton({ scope.launch { container.downloads.pause(d.id) } }) { Icon(Icons.Rounded.Pause, stringResource(R.string.pause)) }
            if (d.status == DownloadStatus.PAUSED) IconButton({ scope.launch { container.downloads.resume(d.id) } }) { Icon(Icons.Rounded.PlayArrow, stringResource(R.string.resume)) }
            if (d.status == DownloadStatus.FAILED) IconButton({ scope.launch { container.downloads.retry(d.id) } }) {
                Icon(Icons.Rounded.Refresh, stringResource(R.string.retry), tint = MaterialTheme.colorScheme.primary)
            }
            IconButton({ confirmRemove = true }) { Icon(if (done || d.status == DownloadStatus.FAILED) Icons.Rounded.Delete else Icons.Rounded.Close, stringResource(R.string.remove)) }
        }
    }

    if (confirmOpen) {
        AlertDialog(
            onDismissRequest = { confirmOpen = false },
            title = { Text(stringResource(R.string.open_this_file)) },
            text = { Text(stringResource(R.string.could_harm_device, d.filename)) },
            confirmButton = { TextButton({ confirmOpen = false; open() }) { Text(stringResource(R.string.open_anyway)) } },
            dismissButton = { TextButton({ confirmOpen = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(if (done) stringResource(R.string.delete_download) else stringResource(R.string.cancel_download)) },
            text = { Text(d.filename) },
            confirmButton = { TextButton({ confirmRemove = false; scope.launch { container.downloads.remove(d.id) } }) { Text(if (done) stringResource(R.string.delete) else stringResource(R.string.cancel_download_2)) } },
            dismissButton = { TextButton({ confirmRemove = false }) { Text(stringResource(R.string.keep)) } },
        )
    }
}

/** "2 downloading · 3.4 MB/s" across all running downloads, with an overall progress bar. */
@Composable
private fun ActiveSummary(all: List<DownloadEntity>, live: Map<Long, com.ziribrowser.download.LiveProgress>) {
    val active = all.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }
    if (active.isEmpty()) return
    val speed = active.sumOf { live[it.id]?.speed ?: 0L }
    val done = active.sumOf { live[it.id]?.downloaded ?: it.downloadedSize }
    val total = active.sumOf { (live[it.id]?.total ?: it.fileSize).coerceAtLeast(0L) }
    val known = active.all { (live[it.id]?.total ?: it.fileSize) > 0 }
    GlassSurface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), shape = RoundedCornerShape(20.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.n_downloading, active.size), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (speed > 0) FileUtils.formatSpeed(speed) else stringResource(R.string.starting), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            if (known && total > 0) LinearProgressIndicator({ (done.toFloat() / total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                if (known && total > 0) "${FileUtils.formatBytes(done)} of ${FileUtils.formatBytes(total)}" else FileUtils.formatBytes(done),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
