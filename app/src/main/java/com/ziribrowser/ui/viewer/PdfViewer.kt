package com.ziribrowser.ui.viewer

import com.ziribrowser.ziriString
import com.ziribrowser.R
import androidx.compose.ui.res.stringResource
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ziribrowser.data.DownloadEntity
import com.ziribrowser.data.DownloadStatus
import com.ziribrowser.download.LiveProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Built-in PDF reader: scroll through the pages and pinch to zoom. Uses Android's own PDF renderer. */
@Composable
fun PdfViewer(d: DownloadEntity, live: LiveProgress?, onBack: () -> Unit) {
    if (d.status != DownloadStatus.COMPLETED) { WaitingForDownload(d, live, onBack); return }
    val context = LocalContext.current
    var doc by remember(d.id) { mutableStateOf<PdfDoc?>(null) }
    var failed by remember(d.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(d.id) {
        withContext(Dispatchers.IO) {
            try {
                val opened = PdfDoc.open(context, Uri.parse(d.fileUri))
                // Left the screen while it was opening: nobody will close it later, so do it here.
                if (!isActive) opened.close() else doc = opened
            }
            catch (e: SecurityException) { failed = ziriString(R.string.this_pdf_is_password_protected_which_zir) }
            catch (e: Exception) { failed = ziriString(R.string.this_pdf_couldn_t_be_opened) }
        }
    }
    DisposableEffect(d.id) { onDispose { doc?.let { kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) { it.close() } } } }

    Box(Modifier.fillMaxSize().background(Color(0xFF2B2D31))) {
        val pdf = doc
        when {
            failed != null -> CenterMessage(failed!!, onBack)
            pdf == null -> {
                ViewerTopBar(d.filename, onBack, Modifier.align(Alignment.TopStart))
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }
            else -> PdfPages(pdf, d.filename, onBack)
        }
    }
}

@Composable
private fun PdfPages(pdf: PdfDoc, title: String, onBack: () -> Unit) {
    var zoom by remember { mutableFloatStateOf(1f) }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewWidth = maxWidth
        val pageWidth = viewWidth * zoom
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                // Pinch with two fingers to zoom; one finger keeps scrolling.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size >= 2 && event.changes.none { it.isConsumed }) {
                            zoom = (zoom * event.calculateZoom()).coerceIn(1f, 3f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        ) {
            Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState(), enabled = zoom > 1.02f)) {
                LazyColumn(
                    Modifier.width(pageWidth), state = listState,
                    contentPadding = PaddingValues(top = 84.dp, bottom = 24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                ) {
                    items((0 until pdf.pageCount).toList(), key = { it }) { index ->
                        PdfPage(pdf, index, with(density) { pageWidth.roundToPx() }.let { ((it + 199) / 200) * 200 }) // steps of 200 px: a pinch re-renders a page a few times, not on every tiny change
                    }
                }
            }
        }
        ViewerTopBar(title, onBack, Modifier.align(Alignment.TopStart))
        Text(
            "${(listState.firstVisibleItemIndex + 1).coerceAtMost(maxOf(pdf.pageCount, 1))} / ${pdf.pageCount}", color = Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun PdfPage(pdf: PdfDoc, index: Int, widthPx: Int) {
    var failed by remember(index) { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(null, index, widthPx) {
        value = pdf.render(index, widthPx)?.asImageBitmap()
        failed = value == null && !pdf.closed
    }
    val bmp = bitmap
    Box(Modifier.fillMaxWidth().background(Color.White), contentAlignment = Alignment.Center) {
        if (bmp == null && failed) Box(Modifier.fillMaxWidth().heightIn(min = 420.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.can_t_open_the_file), color = Color.DarkGray) }
        else if (bmp == null) Box(Modifier.fillMaxWidth().heightIn(min = 420.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.padding(16.dp)) }
        else Image(bmp, stringResource(R.string.page, index + 1), Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
    }
}

/** A PDF opened for reading. Android's renderer handles one page at a time, so rendering is serialised. */
private class PdfDoc private constructor(private val pfd: ParcelFileDescriptor, private val renderer: PdfRenderer) {
    val pageCount: Int = renderer.pageCount
    private val lock = Mutex()
    // Keep a few recent pages (about 48 MB) so scrolling back is instant.
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$index@$widthPx"
        cache.get(key)?.let { return@withContext it }
        lock.withLock {
            cache.get(key)?.let { return@withLock it }
            runCatching {
                renderer.openPage(index).use { page ->
                    // Width capped, and the whole bitmap capped at ~8 megapixels (32 MB): a very tall page must not exhaust memory.
                    var w = widthPx.coerceIn(200, 2400)
                    val maxW = kotlin.math.sqrt(8_000_000.0 * page.width / page.height).toInt().coerceAtLeast(200)
                    w = minOf(w, maxW)
                    val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(AColor.WHITE) }
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    cache.put(key, bmp)
                    bmp
                }
            }.getOrNull()
        }
    }

    /** Waits for a page that is still being drawn (PdfRenderer refuses to close with a page open), then frees the file. */
    @Volatile var closed = false
        private set

    suspend fun close() {
        closed = true
        lock.withLock {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }

    companion object {
        fun open(context: Context, uri: Uri): PdfDoc {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error(ziriString(R.string.can_t_open_the_file))
            return try { PdfDoc(pfd, PdfRenderer(pfd)) } catch (e: Exception) { pfd.close(); throw e }
        }
    }
}
