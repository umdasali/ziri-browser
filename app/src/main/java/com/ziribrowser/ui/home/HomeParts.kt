package com.ziribrowser.ui.home

import androidx.compose.ui.res.stringResource
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.ziribrowser.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ziribrowser.data.SearchEngine

/** The official logo for each search engine, bundled with the app (no network needed to show it). */
@DrawableRes
fun SearchEngine.logo(): Int = when (this) {
    SearchEngine.GOOGLE -> R.drawable.logo_google
    SearchEngine.BING -> R.drawable.logo_bing
    SearchEngine.DUCKDUCKGO -> R.drawable.logo_duckduckgo
    SearchEngine.BRAVE -> R.drawable.logo_brave
    SearchEngine.ECOSIA -> R.drawable.logo_ecosia
    SearchEngine.YAHOO -> R.drawable.logo_yahoo
}

/** A logo on a white disc, so every brand stays readable on any theme colour. */
@Composable
fun LogoBadge(@DrawableRes logo: Int, description: String?, size: Dp, modifier: Modifier = Modifier, ring: BorderStroke? = null) {
    Surface(
        shape = CircleShape, color = Color.White, shadowElevation = 1.dp,
        border = ring ?: BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Image(painterResource(logo), description, Modifier.size(size * 0.58f))
        }
    }
}

@Composable
fun EngineIcon(engine: SearchEngine, size: Dp = 40.dp, selected: Boolean = false, modifier: Modifier = Modifier) {
    LogoBadge(
        engine.logo(), engine.label, size, modifier,
        ring = if (selected) BorderStroke(2.5.dp, MaterialTheme.colorScheme.primary) else null,
    )
}

/** The bundled logo for a well-known site, matched by its host name, or null for any other site. */
@DrawableRes
fun logoForHost(host: String): Int? {
    val h = host.lowercase()
    // Only the site's own domain counts ("google.com", "mail.google.co.uk"). Matching a name anywhere in the host let
    // "google.evil.com" borrow Google's logo.
    val tlds = listOf("com", "org", "net", "co", "co.uk", "com.au", "co.in", "in", "ru", "de", "fr", "es", "it", "jp", "be", "tv", "ai", "io", "com.br", "ca")
    fun has(vararg names: String) = names.any { n -> h == n || tlds.any { t -> h == "$n.$t" || h.endsWith(".$n.$t") } }
    return when {
        has("google") -> R.drawable.logo_google
        has("youtube", "youtu") -> R.drawable.logo_youtube
        has("facebook", "fb") -> R.drawable.logo_facebook
        has("instagram") -> R.drawable.logo_instagram
        has("chatgpt", "openai") -> R.drawable.logo_chatgpt
        has("wikipedia") -> R.drawable.logo_wikipedia
        has("whatsapp") -> R.drawable.logo_whatsapp
        has("x", "twitter") -> R.drawable.logo_x
        has("reddit") -> R.drawable.logo_reddit
        has("yahoo") -> R.drawable.logo_yahoo
        has("bing") -> R.drawable.logo_bing
        has("duckduckgo") -> R.drawable.logo_duckduckgo
        has("brave") -> R.drawable.logo_brave
        has("ecosia") -> R.drawable.logo_ecosia
        else -> null
    }
}

/** A well-known site shown in "Popular worldwide". */
data class PopularSite(val name: String, val url: String, @DrawableRes val logo: Int)

/**
 * Ten of the most visited websites in the world. This is a fixed list (public traffic rankings change
 * a little from month to month), not live data. Logos are the sites' own and belong to their owners.
 */
val PopularSites = listOf(
    PopularSite("Google", "https://www.google.com", R.drawable.logo_google),
    PopularSite("YouTube", "https://www.youtube.com", R.drawable.logo_youtube),
    PopularSite("Facebook", "https://www.facebook.com", R.drawable.logo_facebook),
    PopularSite("Instagram", "https://www.instagram.com", R.drawable.logo_instagram),
    PopularSite("ChatGPT", "https://chatgpt.com", R.drawable.logo_chatgpt),
    PopularSite("Wikipedia", "https://www.wikipedia.org", R.drawable.logo_wikipedia),
    PopularSite("WhatsApp", "https://www.whatsapp.com", R.drawable.logo_whatsapp),
    PopularSite("X", "https://x.com", R.drawable.logo_x),
    PopularSite("Reddit", "https://www.reddit.com", R.drawable.logo_reddit),
    PopularSite("Yahoo", "https://www.yahoo.com", R.drawable.logo_yahoo),
)

/** Round coloured badge with a site's first letter plus its name underneath. */
@Composable
fun SiteTile(
    label: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier, caption: String? = null,
    @DrawableRes logo: Int? = null, icon: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(horizontal = 2.dp, vertical = 6.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) icon()
        else if (logo != null) LogoBadge(logo, label, 52.dp)
        else Box(Modifier.size(52.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
            Text(
                label.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•",
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp,
            )
        }
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        if (caption != null) Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** One headline: picture, title, and where/when it was published. Tapping opens the article in the browser. */
@Composable
fun ArticleCard(container: com.ziribrowser.AppContainer, a: com.ziribrowser.feed.Article, onClick: () -> Unit) {
    val image by com.ziribrowser.ui.rememberRemoteImage(container, a.image)
    val age = if (a.publishedAt > 0) android.text.format.DateUtils.getRelativeTimeSpanString(a.publishedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString() else ""
    val articleDescription = stringResource(R.string.article_description, a.title, a.source, age)
    com.ziribrowser.ui.GlassSurface(
        Modifier.width(250.dp).androidx_semantics { contentDescription = articleDescription },
        shape = RoundedCornerShape(22.dp), onClick = onClick,
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().height(136.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = image
                if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxWidth().height(136.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                else Text(a.source.take(1).uppercase(), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), fontWeight = FontWeight.Bold, fontSize = 40.sp)
            }
            Column(Modifier.padding(14.dp)) {
                Text(a.title, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(
                    listOfNotNull(a.source, if (a.publishedAt > 0) android.text.format.DateUtils.getRelativeTimeSpanString(a.publishedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS, android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE).toString() else null).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private fun Modifier.androidx_semantics(block: androidx.compose.ui.semantics.SemanticsPropertyReceiver.() -> Unit) =
    this.then(Modifier.semantics(mergeDescendants = true, properties = block))
