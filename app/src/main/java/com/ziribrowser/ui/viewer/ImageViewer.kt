package com.ziribrowser.ui.viewer

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ziribrowser.AppContainer
import com.ziribrowser.data.DownloadEntity
import com.ziribrowser.data.DownloadStatus
import com.ziribrowser.download.LiveProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Full-screen picture viewer: swipe between downloaded pictures, pinch or double-tap to zoom. */
@Composable
fun ImageViewer(container: AppContainer, d: DownloadEntity, live: LiveProgress?, onBack: () -> Unit) {
    if (d.status != DownloadStatus.COMPLETED) { WaitingForDownload(d, live, onBack); return }
    val all by remember { container.db.downloads().observeImages() }.collectAsStateWithLifecycle(emptyList())
    val images = if (all.any { it.id == d.id }) all else listOf(d) + all
    val start = images.indexOfFirst { it.id == d.id }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { images.size }
    var zoomed by remember { mutableStateOf(false) }
    // Reset the zoom flag when the user lands on another picture.
    LaunchedEffect(pager.currentPage) { zoomed = false }

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, key = { images[it].id }) { page ->
            ZoomableImage(Uri.parse(images[page].fileUri), onZoomChange = { if (page == pager.currentPage) zoomed = it })
        }
        ViewerTopBar(images.getOrNull(pager.currentPage)?.filename ?: d.filename, onBack, Modifier.align(Alignment.TopStart))
        if (images.size > 1) {
            Text(
                "${pager.currentPage + 1} / ${images.size}", color = Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            )
        }
    }
}

@Composable
private fun ZoomableImage(uri: Uri, onZoomChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, uri) { value = withContext(Dispatchers.IO) { decodeSampled(context, uri, 2600) } }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(scale) { onZoomChange(scale > 1.02f) }

    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { if (scale > 1.02f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f })
            }
            .pointerInput(Unit) {
                // Take over only when zooming (two fingers) or panning a zoomed picture, so a one-finger swipe still turns the page.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.isConsumed }) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (event.changes.size >= 2 || scale > 1.02f) {
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                offset = if (scale <= 1.02f) Offset.Zero else {
                                    val limit = size.width * (scale - 1f) / 2f
                                    val limitY = size.height * (scale - 1f) / 2f
                                    Offset((offset.x + pan.x).coerceIn(-limit, limit), (offset.y + pan.y).coerceIn(-limitY, limitY))
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp == null) CircularProgressIndicator(color = Color.White)
        else Image(
            bmp, null,
            Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            contentScale = ContentScale.Fit,
        )
    }
}

/** Decodes a picture no bigger than about [maxSide] pixels on its longest side, so large photos don't exhaust memory. */
private fun decodeSampled(context: Context, uri: Uri, maxSide: Int): ImageBitmap? = runCatching {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }?.asImageBitmap()
}.getOrNull()
