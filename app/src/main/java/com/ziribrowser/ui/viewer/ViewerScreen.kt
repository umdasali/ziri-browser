package com.ziribrowser.ui.viewer

import com.ziribrowser.R
import androidx.compose.ui.res.stringResource
import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ziribrowser.AppContainer
import com.ziribrowser.data.DownloadEntity
import com.ziribrowser.data.DownloadStatus
import com.ziribrowser.download.Category
import com.ziribrowser.download.FileUtils
import com.ziribrowser.download.LiveProgress
import com.ziribrowser.ui.GlassSurface

/** What the built-in viewer can show. */
enum class ViewerKind { VIDEO, IMAGE, PDF, OTHER }

fun viewerKindOf(d: DownloadEntity): ViewerKind = when {
    d.mimeType == "application/pdf" || FileUtils.extOf(d.filename) == "pdf" -> ViewerKind.PDF
    d.category == Category.VIDEOS.name || d.category == Category.AUDIO.name -> ViewerKind.VIDEO
    d.category == Category.IMAGES.name -> ViewerKind.IMAGE
    else -> ViewerKind.OTHER
}

/** Opens the right built-in viewer for a download, showing download progress first if it isn't finished. */
@Composable
fun ViewerScreen(container: AppContainer, id: Long, onBack: () -> Unit) {
    val d by remember(id) { container.db.downloads().observe(id) }.collectAsStateWithLifecycle(null)
    val live by container.downloads.live.collectAsStateWithLifecycle()
    val item = d
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            item == null -> CenterMessage(stringResource(R.string.this_file_is_no_longer_available), onBack)
            else -> when (viewerKindOf(item)) {
                ViewerKind.VIDEO -> VideoViewer(container, item, live[id], onBack)
                ViewerKind.IMAGE -> ImageViewer(container, item, live[id], onBack)
                ViewerKind.PDF -> PdfViewer(item, live[id], onBack)
                ViewerKind.OTHER -> OtherFile(container, item, onBack)
            }
        }
    }
}

/** Back button and file name over the content. */
@Composable
fun ViewerTopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassSurface(Modifier.size(44.dp), shape = CircleShape, strong = true, onClick = onBack) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
        }
        GlassSurface(Modifier.weight(1f).padding(horizontal = 10.dp), shape = CircleShape, strong = true) {
            Text(title, Modifier.padding(horizontal = 16.dp, vertical = 11.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
        }
        actions()
    }
}

/** "Downloading 43% · 2.4 MB/s · 14s left" with a progress bar, shown while a file is still arriving. */
@Composable
fun DownloadBanner(d: DownloadEntity, live: LiveProgress?, modifier: Modifier = Modifier) {
    if (d.status == DownloadStatus.COMPLETED) return
    val downloaded = live?.downloaded ?: d.downloadedSize
    val total = live?.total ?: d.fileSize
    val fraction = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null
    val line = when (d.status) {
        DownloadStatus.FAILED -> d.error.ifEmpty { stringResource(R.string.download_failed) }
        DownloadStatus.PAUSED -> stringResource(R.string.paused, FileUtils.formatBytes(downloaded))
        else -> listOf(
            if (fraction != null) "${(fraction * 100).toInt()}%" else FileUtils.formatBytes(downloaded),
            FileUtils.formatSpeed(live?.speed ?: 0), FileUtils.formatEta(live?.etaSeconds ?: 0),
        ).filter { it.isNotEmpty() }.joinToString(" · ").let { stringResource(R.string.downloading_2, it) }
    }
    GlassSurface(modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), strong = true) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(line, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            if (d.status == DownloadStatus.DOWNLOADING) {
                if (fraction != null) LinearProgressIndicator({ fraction }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

/** Shown for pictures and PDFs that are still downloading. */
@Composable
fun WaitingForDownload(d: DownloadEntity, live: LiveProgress?, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        ViewerTopBar(d.filename, onBack, Modifier.align(Alignment.TopStart))
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (d.status == DownloadStatus.DOWNLOADING || d.status == DownloadStatus.QUEUED) CircularProgressIndicator(color = Color.White)
            Text(stringResource(R.string.it_will_open_as_soon_as_the_download_f), color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
        }
        DownloadBanner(d, live, Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp))
    }
}

@Composable
fun CenterMessage(text: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        ViewerTopBar("", onBack, Modifier.align(Alignment.TopStart))
        Text(text, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(32.dp), textAlign = TextAlign.Center)
    }
}

/** A file type Ziri can't show itself: offer to open it in another app. */
@Composable
private fun OtherFile(container: AppContainer, d: DownloadEntity, onBack: () -> Unit) {
    val context = LocalContext.current
    val noAppCanOpenThisFileText = stringResource(R.string.no_app_can_open_this_file)
    Box(Modifier.fillMaxSize()) {
        ViewerTopBar(d.filename, onBack, Modifier.align(Alignment.TopStart))
        Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.ziri_can_t_show_this_kind_of_file_itse), color = Color.White, textAlign = TextAlign.Center)
            Button({
                try { context.startActivity(container.downloads.openIntent(d)) }
                catch (_: ActivityNotFoundException) { Toast.makeText(context, noAppCanOpenThisFileText, Toast.LENGTH_SHORT).show() }
            }) { Text(stringResource(R.string.open_with_another_app)) }
            TextButton(onBack) { Text(stringResource(R.string.back)) }
        }
    }
}
