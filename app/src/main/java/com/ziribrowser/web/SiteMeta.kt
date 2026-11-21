package com.ziribrowser.web

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** What a page says about itself in its `<head>`: Open Graph / Twitter card tags, title, description and icons. */
data class PagePreview(
    val finalUrl: String, val title: String, val description: String, val siteName: String,
    val image: String?, val icons: List<String>,
)

/** Pure HTML-head parsing, kept separate from the network code so it can be unit-tested. */
object MetaParser {
    private val tagRe = Regex("<(meta|link)\\b[^>]{0,2000}>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val attrRe = Regex("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))")
    private val titleRe = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun unescape(s: String): String = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun attrs(tag: String): Map<String, String> {
        val m = HashMap<String, String>()
        attrRe.findAll(tag).forEach { r ->
            val v = r.groupValues[2].ifEmpty { r.groupValues[3] }.ifEmpty { r.groupValues[4] }
            m.putIfAbsent(r.groupValues[1].lowercase(), v)
        }
        return m
    }

    fun resolve(base: String, ref: String): String? = runCatching {
        val r = ref.trim()
        if (r.isEmpty() || r.startsWith("data:")) return null
        URI(base).resolve(r).toString().takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }.getOrNull()

    fun parse(html: String, pageUrl: String): PagePreview {
        val head = html.substringBefore("</head>", html.take(200_000))
        val meta = HashMap<String, String>()
        val icons = ArrayList<Pair<String, Int>>()
        for (t in tagRe.findAll(head)) {
            val tag = t.value
            val a = attrs(tag)
            if (tag.startsWith("<meta", true)) {
                val key = (a["property"] ?: a["name"] ?: a["itemprop"])?.lowercase() ?: continue
                a["content"]?.let { meta.putIfAbsent(key, unescape(it)) }
            } else {
                val rel = a["rel"]?.lowercase() ?: continue
                val href = a["href"] ?: continue
                if (!rel.contains("icon") || rel.contains("mask-icon")) continue
                val size = a["sizes"]?.substringBefore('x')?.toIntOrNull()
                    ?: if (rel.contains("apple-touch")) 180 else 32
                resolve(pageUrl, unescape(href))?.let { icons += it to size }
            }
        }
        val title = meta["og:title"] ?: meta["twitter:title"]
            ?: titleRe.find(head)?.groupValues?.get(1)?.let { unescape(it) } ?: ""
        val desc = meta["og:description"] ?: meta["twitter:description"] ?: meta["description"] ?: ""
        val image = (meta["og:image"] ?: meta["og:image:url"] ?: meta["twitter:image"] ?: meta["twitter:image:src"])?.let { resolve(pageUrl, it) }
        val origin = runCatching { URI(pageUrl).let { "${it.scheme}://${it.authority}" } }.getOrDefault("")
        val ordered = icons.sortedByDescending { if (it.second in 48..512) it.second else it.second / 4 }.map { it.first }.distinct().toMutableList()
        if (origin.isNotEmpty()) { ordered += "$origin/apple-touch-icon.png"; ordered += "$origin/favicon.ico" }
        return PagePreview(pageUrl, title.take(160), desc.take(300), meta["og:site_name"].orEmpty(), image, ordered.distinct())
    }
}

/**
 * Looks up and stores a site's icon and title. Used to check a link when it is added as a quick-access tile or
 * bookmark, and to show real favicons instead of letters. Only the head of the page is read (first 300 KB),
 * icons are shrunk to 128 px and kept on this device, and nothing is sent anywhere except to the site itself.
 */
class SiteMetaRepository(context: Context, private val http: OkHttpClient) {
    private val dir = File(context.filesDir, "sitemeta").also { it.mkdirs() }
    private val gate = Semaphore(3)
    private val attempted = ConcurrentHashMap<String, Long>()
    private val memory = object : LruCache<String, Bitmap>(64) {}

    /** Off when the person doesn't want Ziri to look up site icons (letters are shown instead). */
    @Volatile var enabled = true

    private val _version = MutableStateFlow(0)
    /** Changes whenever a new icon has been saved, so icon views can refresh. */
    val version: StateFlow<Int> = _version

    fun key(url: String) = runCatching { URI(url).host.orEmpty().lowercase().removePrefix("www.") }.getOrDefault("")

    /** Reads and parses a page head. Returns null if the site can't be reached or isn't a web page. */
    suspend fun preview(url: String): PagePreview? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5").build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val ct = r.header("Content-Type").orEmpty()
                if (ct.isNotEmpty() && !ct.contains("html", true) && !ct.contains("xml", true)) return@withContext null
                val src = r.body.source()
                src.request(MAX_HTML)
                val n = minOf(src.buffer.size, MAX_HTML)
                MetaParser.parse(String(src.buffer.readByteArray(n), Charsets.UTF_8), r.request.url.toString())
            }
        } catch (_: Exception) { null }
    }

    /** Saves the site's icon and basic info if we don't have them yet. Safe to call repeatedly. */
    suspend fun ensure(url: String) {
        val host = key(url)
        // Icons are fetched from the site's front door only, and never for plain-http or LAN addresses: asking for the exact link a person bookmarked
        // could trigger a one-time or logout link, and pokes at devices on the home network.
        if (!enabled || host.isEmpty() || !url.startsWith("https://", ignoreCase = true) || com.ziribrowser.browser.UrlUtils.isLocalHost(host) || File(dir, "$host.png").exists()) return
        val last = attempted[host]
        if (last != null && System.currentTimeMillis() - last < RETRY_MS) return
        attempted[host] = System.currentTimeMillis()
        gate.withPermit {
            // Some sites refuse to serve their page to an app but still serve their icon: try the standard icon locations.
            val origin = runCatching { URI(url).let { "${it.scheme}://${it.authority}" } }.getOrDefault("")
            val p = preview(origin.ifEmpty { url }) ?: PagePreview(url, "", "", "", null, listOf("$origin/apple-touch-icon.png", "$origin/favicon.ico"))
            save(host, p)
        }
    }

    /** Stores [p] (and its best icon) under [host]; used by [ensure] and when a preview was already fetched. */
    suspend fun save(host: String, p: PagePreview) = withContext(Dispatchers.IO) {
        File(dir, "$host.json").writeText(JSONObject().put("title", p.title).put("desc", p.description).put("image", p.image ?: "").toString())
        for (icon in p.icons.take(4)) {
            val bmp = downloadIcon(icon) ?: continue
            File(dir, "$host.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            memory.put(host, bmp)
            _version.update { it + 1 }
            return@withContext
        }
    }

    /** The saved icon for [host], or null. */
    suspend fun icon(host: String): Bitmap? = withContext(Dispatchers.IO) {
        val h = host.lowercase().removePrefix("www.")
        memory.get(h) ?: File(dir, "$h.png").takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }?.also { memory.put(h, it) }
    }

    private fun downloadIcon(url: String): Bitmap? = try {
        if (url.substringBefore('?').endsWith(".svg", true)) null
        else http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val src = r.body.source()
            src.request(MAX_ICON)
            if (src.buffer.size >= MAX_ICON) return null
            val bytes = src.buffer.readByteArray()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth < 16 || bounds.outHeight < 16) return null
            val opts = BitmapFactory.Options().apply { inSampleSize = (bounds.outWidth / 256).coerceAtLeast(1) }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            if (bmp.width > 128) Bitmap.createScaledBitmap(bmp, 128, (128f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true) else bmp
        }
    } catch (_: Exception) { null }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
        const val MAX_HTML = 300_000L
        const val MAX_ICON = 400_000L
        const val RETRY_MS = 6L * 3600 * 1000
    }
}
