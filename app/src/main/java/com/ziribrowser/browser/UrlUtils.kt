package com.ziribrowser.browser

import java.net.URI
import java.net.URLEncoder
import com.ziribrowser.data.SearchEngine

object UrlUtils {
    private val domainRegex = Regex("^([a-z0-9-]+\\.)+[a-z]{2,}(:\\d+)?(/.*)?$", RegexOption.IGNORE_CASE)
    private val localhostRegex = Regex("^localhost(:\\d+)?(/.*)?$", RegexOption.IGNORE_CASE)
    private val ipRegex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$")
    private val schemeRegex = Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE)

    /** Turns address-bar text into a loadable http(s) URL, or a search URL. Other schemes are searched, never loaded. */
    fun resolve(input: String, engine: SearchEngine): String {
        val text = input.trim()
        if (text.startsWith("http://", true) || text.startsWith("https://", true)) return text
        val isHostLike = domainRegex.matches(text) || localhostRegex.matches(text) || ipRegex.matches(text)
        if (isHostLike) return "https://$text"
        return engine.queryUrl + encodeQuery(text)
    }

    private val ipv4Regex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /** Host name exactly as written in [url] (no "www." stripping), or empty when it can't be parsed. */
    fun rawHost(url: String): String =
        // java.net.URI refuses addresses with characters like | { } ^ or a space and then reports no host; a page must not
        // slip past HTTPS-only / HTTPS-first that way, so fall back to reading the host by hand.
        // Chromium reads a backslash as a slash in http(s) addresses ("http://evil.com\\@10.0.0.1" is evil.com), so do the same here.
        url.replace('\\', '/').let { u -> runCatching { URI(u).host }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: u.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').substringBefore(':') }

    /** True for addresses that never have a public HTTPS certificate: localhost, LAN names and IP addresses. */
    fun isLocalHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        return h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".internal") ||
            ipv4Regex.matches(h) || h.contains(':') // IPv6 literal
    }

    /**
     * The https:// version of an http:// address, or null when it should be left alone: it isn't http,
     * uses an explicit port (the HTTPS port is unknown), or points at a local/IP host.
     */
    fun upgradeToHttps(url: String): String? {
        if (!url.startsWith("http://", ignoreCase = true)) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (uri.port != -1 || isLocalHost(host)) return null
        return "https://" + url.substring("http://".length)
    }

    fun isHttp(url: String?): Boolean =
        url != null && (url.startsWith("http://", true) || url.startsWith("https://", true))

    /** Host of [url] without a leading "www.", or [url] itself when it can't be parsed. */
    fun host(url: String): String =
        runCatching { URI(url).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotEmpty() } ?: url

    /** Percent-encodes a search query (spaces become %20, not +). Pure JVM so it can be unit-tested. */
    fun encodeQuery(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    /** Escapes LIKE wildcards for queries that use ESCAPE '\'. */
    fun likeEscape(q: String): String = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
