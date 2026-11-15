package com.ziribrowser.ui.browser

import com.ziribrowser.R
import androidx.compose.ui.res.stringResource
import android.webkit.URLUtil
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ziribrowser.AppContainer
import com.ziribrowser.browser.UrlUtils
import com.ziribrowser.data.DownloadEntity
import com.ziribrowser.download.DownloadRequest
import com.ziribrowser.download.EnqueueResult
import com.ziribrowser.download.FileUtils
import kotlinx.coroutines.launch

/** Confirmation dialogs for downloads (spec 8.6 step 5) and duplicate handling. */
@Composable
fun DownloadPrompts(container: AppContainer, onOpenViewer: (Long) -> Unit = {}) {
    val context = LocalContext.current
    val startedText = stringResource(R.string.download_started)
    val scope = rememberCoroutineScope()
    val pending by container.pendingDownload.collectAsStateWithLifecycle()
    var duplicate by remember { mutableStateOf<Pair<DownloadRequest, DownloadEntity>?>(null) }

    fun start(req: DownloadRequest, allowDuplicate: Boolean, view: Boolean = false) {
        scope.launch {
            when (val r = container.downloads.enqueue(req, allowDuplicate)) {
                is EnqueueResult.Started -> if (view) onOpenViewer(r.id) else Toast.makeText(context, startedText, Toast.LENGTH_SHORT).show()
                is EnqueueResult.Duplicate -> duplicate = req to r.existing
                is EnqueueResult.Rejected -> Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    pending?.let { req ->
        val originalName = FileUtils.sanitizeFilename(URLUtil.guessFileName(req.url, req.contentDisposition, req.mimeType))
        var edited by remember(req) { mutableStateOf(originalName) }
        val name = edited.ifBlank { originalName }
        // A changed name is passed on as the file's title; the engine keeps the right extension.
        val named = if (name != originalName) req.copy(title = name.substringBeforeLast('.', name)) else req
        val size = if (req.contentLength > 0) " (${FileUtils.formatBytes(req.contentLength)})" else ""
        AlertDialog(
            onDismissRequest = { container.clearPendingDownload() },
            title = { Text(stringResource(R.string.download_file)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.OutlinedTextField(edited, { edited = it }, singleLine = true, label = { Text(stringResource(R.string.file_name)) }, modifier = Modifier.fillMaxWidth())
                    Text("$size from ${UrlUtils.host(req.url)}".trim(), style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                androidx.compose.foundation.layout.Row {
                    // Videos, pictures and PDFs can be opened in Ziri's own viewer as soon as the download starts.
                    val mime = req.mimeType.orEmpty().substringBefore(';')
                    if (FileUtils.isViewableInApp(FileUtils.categoryOf(name, mime).name, mime, name)) {
                        TextButton({ container.clearPendingDownload(); start(named, false, view = true) }) { Text(stringResource(R.string.download_view)) }
                    }
                    TextButton({ container.clearPendingDownload(); start(named, false) }) { Text(stringResource(R.string.download)) }
                }
            },
            dismissButton = { TextButton({ container.clearPendingDownload() }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    duplicate?.let { (req, existing) ->
        AlertDialog(
            onDismissRequest = { duplicate = null },
            title = { Text(stringResource(R.string.already_downloaded)) },
            text = { Text("${existing.filename} is already in your downloads.") },
            confirmButton = { TextButton({ duplicate = null; start(req, true) }) { Text(stringResource(R.string.download_again)) } },
            dismissButton = { TextButton({ duplicate = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
