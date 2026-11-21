package com.ziribrowser.feed

import android.content.Context
import android.util.Xml
import com.ziribrowser.data.Interest
import com.ziribrowser.web.MetaParser
import com.ziribrowser.web.SiteMetaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class Article(val title: String, val link: String, val image: String?, val source: String, val publishedAt: Long, val interest: String)

/**
 * Headlines for the interests a person picked, from each site's public RSS/Atom feed (HTTPS only). Results are cached
 * on the device for 30 minutes, so Home opens instantly and works offline with the last copy. Nothing is loaded in the
 * background: it only refreshes while Home is on screen.
 */
class FeedRepository(context: Context, private val http: OkHttpClient, private val meta: SiteMetaRepository, private val scope: CoroutineScope) {
    private val cacheFile = File(context.filesDir, "feed_cache.json")
    private val lock = Mutex()
    private val _articles = MutableStateFlow<List<Article>>(emptyList())
    val articles: StateFlow<List<Article>> = _articles
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private var cachedFor = ""
    private var fetchedAt = 0L

    init { scope.launch(Dispatchers.IO) { readCache() } }

    private fun readCache() = runCatching {
        if (!cacheFile.isFile) return@runCatching
        val o = JSONObject(cacheFile.readText())
        cachedFor = o.optString("for"); fetchedAt = o.optLong("at")
        val a = o.getJSONArray("items")
        _articles.value = (0 until a.length()).map { i ->
            a.getJSONObject(i).let { Article(it.getString("t"), it.getString("l"), it.optString("i").ifEmpty { null }, it.optString("s"), it.optLong("p"), it.optString("k")) }
        }
    }

    suspend fun refresh(chosen: Set<Interest>, force: Boolean = false) {
        val wanted = chosen.filter { it.feeds.isNotEmpty() }
        val key = wanted.map { it.name }.sorted().joinToString(",")
        if (wanted.isEmpty()) { _articles.value = emptyList(); return }
        lock.withLock2 {
            if (!force && key == cachedFor && System.currentTimeMillis() - fetchedAt < TTL_MS && _articles.value.isNotEmpty()) return
            _loading.value = true
            try {
                val perInterest = coroutineScope {
                    wanted.map { i -> async(Dispatchers.IO) { i to i.feeds.flatMap { f -> fetchFeed(f.source, f.url, i.name).take(6) }.sortedByDescending { it.publishedAt } } }.awaitAll()
                }
                var items = interleave(perInterest.map { it.second })
                // Many feeds carry no picture: read the article's own preview image for the first few.
                val gate = Semaphore(3)
                items = coroutineScope {
                    items.mapIndexed { idx, a ->
                        async(Dispatchers.IO) {
                            if (a.image != null || idx >= 14) a else gate.withPermit { a.copy(image = meta.preview(a.link)?.image) }
                        }
                    }.awaitAll()
                }
                if (items.isNotEmpty()) {
                    _articles.value = items; cachedFor = key; fetchedAt = System.currentTimeMillis()
                    writeCache(items, key)
                }
            } finally { _loading.value = false }
        }
    }

    private suspend inline fun <T> Mutex.withLock2(block: () -> T): T { lock(); try { return block() } finally { unlock() } }

    private fun interleave(lists: List<List<Article>>): List<Article> {
        val out = ArrayList<Article>()
        var i = 0
        while (out.size < MAX_ITEMS && lists.any { i < it.size }) {
            lists.forEach { l -> if (i < l.size && out.size < MAX_ITEMS && out.none { it.link == l[i].link }) out += l[i] }
            i++
        }
        return out
    }

    private fun writeCache(items: List<Article>, key: String) = runCatching {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("t", it.title).put("l", it.link).put("i", it.image ?: "").put("s", it.source).put("p", it.publishedAt).put("k", it.interest)) }
        cacheFile.writeText(JSONObject().put("for", key).put("at", System.currentTimeMillis()).put("items", arr).toString())
    }

    private fun fetchFeed(source: String, url: String, interest: String): List<Article> = try {
        http.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 14) Chrome/124.0 Mobile Safari/537.36").header("Accept", "application/rss+xml, application/atom+xml, text/xml, */*;q=0.5").build()).execute().use { r ->
            if (!r.isSuccessful || !r.request.isHttps) emptyList() else parse(Bounded(r.body.byteStream(), MAX_FEED_BYTES), source, interest)
        }
    } catch (_: Exception) { emptyList() }

    /** Reads at most [limit] bytes; a feed cut off at the limit still yields the items before the cut. */
    private class Bounded(private val s: InputStream, private var left: Int) : InputStream() {
        override fun read(): Int { if (left <= 0) return -1; val b = s.read(); if (b >= 0) left--; return b }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = s.read(b, off, minOf(len, left)); if (n > 0) left -= n; return n
        }
        override fun close() = s.close()
    }

    internal companion object {
        const val TTL_MS = 30L * 60 * 1000
        const val MAX_ITEMS = 24
        const val MAX_FEED_BYTES = 700_000
        private val imgRe = Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

        fun parseDate(s: String): Long = runCatching { ZonedDateTime.parse(s.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(s.trim()).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(s.trim()).toEpochMilli() }
            .getOrDefault(0L)

        /** Parses RSS 2.0 and Atom. Keeps what it found if the XML is cut off or malformed part-way. */
        fun parse(input: InputStream, source: String, interest: String): List<Article> {
            val out = ArrayList<Article>()
            try {
                val p = Xml.newPullParser()
                p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                p.setInput(input, null)
                var inItem = false
                var title = ""; var link = ""; var image: String? = null; var date = 0L; var html = ""
                fun finish() {
                    val l = MetaParser.resolve(link, link)
                    val t = MetaParser.unescape(title)
                    if (l != null && t.isNotEmpty()) {
                        val img = image ?: imgRe.find(html)?.groupValues?.get(1)
                        out += Article(t, l, img?.let { MetaParser.resolve(l, MetaParser.unescape(it)) }, source, date, interest)
                    }
                }
                // nextText() throws if a title or summary contains child tags (XHTML), which dropped the rest of the feed: read the text and skip the tags.
                fun text(): String {
                    val sb = StringBuilder(); var depth = 1
                    while (depth > 0) when (p.next()) {
                        XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> if (depth == 1) sb.append(p.text)
                        XmlPullParser.START_TAG -> depth++
                        XmlPullParser.END_TAG -> depth--
                        XmlPullParser.END_DOCUMENT -> return sb.toString()
                    }
                    return sb.toString()
                }
                fun skipTree() { var depth = 1; while (depth > 0) when (p.next()) { XmlPullParser.START_TAG -> depth++; XmlPullParser.END_TAG -> depth--; XmlPullParser.END_DOCUMENT -> return } }
                while (true) {
                    when (p.next()) {
                        XmlPullParser.END_DOCUMENT -> break
                        XmlPullParser.START_TAG -> {
                            val n = p.name
                            if (n == "item" || n == "entry") { inItem = true; title = ""; link = ""; image = null; date = 0L; html = "" }
                            else if (inItem) when (n) {
                                "source" -> skipTree() // an aggregated feed's own title and link, not this entry's
                                "title" -> title = text()
                                "link" -> { val href = p.getAttributeValue(null, "href"); val rel = p.getAttributeValue(null, "rel")
                                    if (href != null) { if (rel == null || rel == "alternate") link = href; p.nextTag().let { } } else link = text().trim() }
                                "pubDate", "published", "updated", "dc:date" -> { val t = text(); if (date == 0L) date = parseDate(t) }
                                "media:thumbnail" -> if (image == null) image = p.getAttributeValue(null, "url")
                                "media:content" -> {
                                    val u = p.getAttributeValue(null, "url"); val medium = p.getAttributeValue(null, "medium"); val type = p.getAttributeValue(null, "type").orEmpty()
                                    if (image == null && u != null && (medium == "image" || type.startsWith("image") || u.substringBefore('?').lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp") })) image = u
                                }
                                "enclosure" -> if (image == null && p.getAttributeValue(null, "type").orEmpty().startsWith("image")) image = p.getAttributeValue(null, "url")
                                "description", "content:encoded", "summary", "content" -> html += text()
                            }
                        }
                        XmlPullParser.END_TAG -> if (inItem && (p.name == "item" || p.name == "entry")) { inItem = false; finish() }
                    }
                }
            } catch (_: Exception) { /* truncated or malformed: return what we have */ }
            return out
        }
    }
}
