package com.ziribrowser.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ziribrowser.AppContainer
import com.ziribrowser.browser.UrlUtils
import com.ziribrowser.ui.home.LogoBadge
import com.ziribrowser.ui.home.logoForHost

/**
 * A site's real icon: the bundled logo for well-known brands, otherwise the favicon read from the page's own tags
 * (fetched once, then kept on the device), and a lettered circle until that arrives or if the site has none.
 */
@Composable
fun SiteIcon(container: AppContainer, url: String, label: String, size: Dp, modifier: Modifier = Modifier) {
    val host = UrlUtils.rawHost(url)
    val logo = logoForHost(host)
    if (logo != null) { LogoBadge(logo, label, size, modifier); return }
    val version by container.siteMeta.version.collectAsState()
    val icon by produceState<Bitmap?>(null, host, version) { value = container.siteMeta.icon(host) }
    LaunchedEffect(host) { if (UrlUtils.isHttp(url)) container.siteMeta.ensure(url) }
    val bmp = icon
    if (bmp != null) {
        Surface(
            shape = CircleShape, color = Color.White, shadowElevation = 1.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = modifier.size(size),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Image(bmp.asImageBitmap(), label, Modifier.size(size * 0.62f), contentScale = ContentScale.Fit)
            }
        }
    } else SiteAvatar(label, size, modifier)
}

/** A picture loaded from a web address (see [com.ziribrowser.web.RemoteImages]); draws nothing until it arrives. */
@Composable
fun rememberRemoteImage(container: AppContainer, url: String?): State<Bitmap?> =
    produceState<Bitmap?>(null, url) { value = url?.let { container.images.load(it) } }
