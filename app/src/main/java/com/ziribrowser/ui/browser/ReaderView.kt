package com.ziribrowser.ui.browser

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ziribrowser.AppContainer
import com.ziribrowser.R
import com.ziribrowser.browser.PrivateProfile
import com.ziribrowser.browser.UrlUtils
import com.ziribrowser.reader.Article
import com.ziribrowser.reader.ReaderPage
import com.ziribrowser.reader.ReaderStyle
import com.ziribrowser.ui.GlassSurface
import kotlinx.coroutines.launch

/**
 * Reader view: the article alone, in a clean page with your text size, font and colours. It runs in its own WebView
 * with JavaScript off, so nothing on the original page can run here; images still pass through the ad and tracker lists.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReaderView(container: AppContainer, article: Article, isPrivate: Boolean, onClose: () -> Unit, onOpenLink: (String) -> Unit) {
    val s = container.settings
    val scope = rememberCoroutineScope()
    val size by s.readerSize.collectAsStateWithLifecycle(100)
    val theme by s.readerTheme.collectAsStateWithLifecycle(0)
    val serif by s.readerSerif.collectAsStateWithLifecycle(true)
    val style = ReaderStyle(size, theme, serif)
    val page = remember(article, size, theme, serif) { ReaderPage.html(article, style) }
    val bg = when (theme) { 1 -> Color(0xFFF4ECD8); 2 -> Color(0xFF121416); else -> Color.White }
    val shownKey = remember { mutableStateOf("") }

    // Keep the clock and battery readable: light icons on the dark theme, dark icons otherwise.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(theme) {
        val window = com.ziribrowser.passwords.Authenticator.activity(view.context)?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val before = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = theme != 2
        onDispose { if (before != null) controller.isAppearanceLightStatusBars = before }
    }

    Box(Modifier.fillMaxSize().background(bg)) {
        AndroidView(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            factory = { ctx ->
                WebView(ctx).apply {
                    if (isPrivate) PrivateProfile.apply(this)
                    settings.apply {
                        javaScriptEnabled = false
                        domStorageEnabled = false
                        allowFileAccess = false
                        allowContentAccess = false
                        setSupportMultipleWindows(false)
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        safeBrowsingEnabled = true
                        cacheMode = if (isPrivate) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
                    }
                    setBackgroundColor(AColor.TRANSPARENT)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            // A tap on a link inside the article opens it as a normal page.
                            val url = request.url.toString()
                            if (request.hasGesture() && UrlUtils.isHttp(url)) onOpenLink(url)
                            return true
                        }

                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val url = request.url.toString()
                            // Only https images, and not the ones the tracker lists would block.
                            val allowed = !request.isForMainFrame && request.url.scheme == "https" && !container.tabs.readerBlocks(url, article.baseUrl)
                            val isBase = request.isForMainFrame
                            return if (allowed || isBase || request.url.scheme == "data") null
                            else WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                        }
                    }
                }
            },
            update = { wv ->
                if (shownKey.value != page) {
                    shownKey.value = page
                    wv.loadDataWithBaseURL(article.baseUrl, page, "text/html", "utf-8", null)
                }
            },
        )

        // Close, and the reading controls, floating over the article.
        GlassSurface(
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 14.dp, vertical = 10.dp),
            shape = CircleShape, strong = true,
        ) {
            Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ scope.launch { s.setReaderSize(size - 10) } }, enabled = size > 80) { Icon(Icons.Rounded.TextDecrease, stringResource(R.string.reader_smaller)) }
                IconButton({ scope.launch { s.setReaderSize(size + 10) } }, enabled = size < 200) { Icon(Icons.Rounded.TextIncrease, stringResource(R.string.reader_larger)) }
                IconButton({ scope.launch { s.setReaderSerif(!serif) } }) { Icon(Icons.Rounded.TextFields, stringResource(R.string.reader_font)) }
                IconButton({ scope.launch { s.setReaderTheme((theme + 1) % 3) } }) {
                    Icon(when (theme) { 0 -> Icons.Rounded.LightMode; 1 -> Icons.Rounded.Contrast; else -> Icons.Rounded.DarkMode }, stringResource(R.string.reader_theme), tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.reader_close)) }
            }
        }
    }
}
