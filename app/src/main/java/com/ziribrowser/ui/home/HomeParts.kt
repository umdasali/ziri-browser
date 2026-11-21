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
import androidx.compose.foundation.layout.fillMaxSize
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
    if (h.startsWith("gemini.google.")) return null // Gemini isn't Google Search: it must not borrow the Google logo
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
    label: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier, caption: String? = null, compact: Boolean = false,
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
        Text(label, style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        if (caption != null) Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

private fun articleAge(a: com.ziribrowser.feed.Article): String =
    if (a.publishedAt > 0) android.text.format.DateUtils.getRelativeTimeSpanString(a.publishedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString() else ""

private fun articleMeta(a: com.ziribrowser.feed.Article): String = listOfNotNull(
    a.source.ifBlank { null },
    if (a.publishedAt > 0) android.text.format.DateUtils.getRelativeTimeSpanString(
        a.publishedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS, android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString() else null,
).joinToString(" · ")

/** The top story: a wide picture with the headline over a dark fade, so the text stays readable on any photo. */
@Composable
fun HeroArticle(container: com.ziribrowser.AppContainer, a: com.ziribrowser.feed.Article, onClick: () -> Unit) {
    val image by com.ziribrowser.ui.rememberRemoteImage(container, a.image)
    val meta = articleMeta(a)
    val description = stringResource(R.string.article_description, a.title, a.source, articleAge(a))
    com.ziribrowser.ui.GlassSurface(
        Modifier.fillMaxWidth().androidx_semantics { contentDescription = description },
        shape = RoundedCornerShape(26.dp), onClick = onClick,
    ) {
        Box(Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(26.dp))) {
            val bmp = image
            if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Text(a.source.take(1).uppercase(), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), fontWeight = FontWeight.Bold, fontSize = 56.sp)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.30f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.82f))))
            Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.82f), maxLines = 1)
                Text(
                    a.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White,
                    maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** A smaller story in the list under the top one: square picture on the left, headline and source on the right. */
@Composable
fun ArticleRow(container: com.ziribrowser.AppContainer, a: com.ziribrowser.feed.Article, onClick: () -> Unit) {
    val image by com.ziribrowser.ui.rememberRemoteImage(container, a.image)
    val meta = articleMeta(a)
    val description = stringResource(R.string.article_description, a.title, a.source, articleAge(a))
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .androidx_semantics { contentDescription = description }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(78.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = image
            if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            else Text(a.source.take(1).uppercase(), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), fontWeight = FontWeight.Bold, fontSize = 28.sp)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(a.title, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Gemini's four-point sparkle, drawn here because Ziri doesn't bundle the service's logo. */
@Composable
fun GeminiMark(size: Dp, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape, color = Color.White, shadowElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = modifier.size(size),
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(size * 0.2f)) {
            val w = this.size.width; val h = this.size.height; val cx = w / 2; val cy = h / 2
            val star = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx, 0f); quadraticTo(cx, cy, w, cy)
                quadraticTo(cx, cy, cx, h); quadraticTo(cx, cy, 0f, cy)
                quadraticTo(cx, cy, cx, 0f); close()
            }
            drawPath(star, Brush.linearGradient(listOf(Color(0xFF4285F4), Color(0xFF9B72CB), Color(0xFFD96570)), androidx.compose.ui.geometry.Offset(0f, h), androidx.compose.ui.geometry.Offset(w, 0f)))
        }
    }
}

private fun Modifier.androidx_semantics(block: androidx.compose.ui.semantics.SemanticsPropertyReceiver.() -> Unit) =
    this.then(Modifier.semantics(mergeDescendants = true, properties = block))

/**
 * An AI assistant Ziri can open from Home. [ask] is the address with a `%s` where the question goes, or null when the service has no
 * link that takes a question (the assistant just opens). Colours are used for the tile because Ziri only bundles ChatGPT's logo.
 */
data class AiAssistant(val name: String, val home: String, val ask: String?, val color: Long, @DrawableRes val logo: Int? = null) {
    fun urlFor(question: String): String =
        if (question.isBlank() || ask == null) home else ask.replace("%s", java.net.URLEncoder.encode(question, "UTF-8"))
}

val AiAssistants = listOf(
    AiAssistant("ChatGPT", "https://chatgpt.com", "https://chatgpt.com/?q=%s", 0xFF10A37F, R.drawable.logo_chatgpt),
    AiAssistant("Gemini", "https://gemini.google.com/app", null, 0xFF4285F4),
    AiAssistant("Claude", "https://claude.ai/new", "https://claude.ai/new?q=%s", 0xFFD97757),
    AiAssistant("Perplexity", "https://www.perplexity.ai", "https://www.perplexity.ai/search?q=%s", 0xFF20808D),
    AiAssistant("Copilot", "https://copilot.microsoft.com", "https://copilot.microsoft.com/?q=%s", 0xFF0F6CBD),
)
