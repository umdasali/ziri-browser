package com.ziribrowser.adblock

import com.ziribrowser.R
import com.ziribrowser.ziriString
import android.content.Context
import android.net.ConnectivityManager
import com.ziribrowser.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request as HttpRequest
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Per-site override. Both false = the site is allow-listed (nothing is blocked there). */
data class SiteBlock(val ads: Boolean = true, val trackers: Boolean = true, val shield: Boolean = true) {
    /** Ads and trackers are both allowed here. (The fingerprint shield has its own switch.) */
    val allowListed get() = !ads && !trackers
}

data class BlockingConfig(val ads: Boolean = true, val trackers: Boolean = true, val sites: Map<String, SiteBlock> = emptyMap())

data class FilterStatus(
    val loaded: Boolean = false, val adRules: Int = 0, val trackerRules: Int = 0,
    val updatedAt: Long = 0, val updating: Boolean = false, val message: String? = null,
)

/**
 * Ad and tracker blocking from EasyList and EasyPrivacy (plus Ziri's own list of analytics endpoints).
 * Lists ship inside the app and can be refreshed over HTTPS. Parsing happens on a background thread a moment
 * after start-up, so launching the app is not slowed down; until then pages simply load unfiltered.
 */
class BlockingManager(
    private val context: Context, private val scope: CoroutineScope, private val settings: SettingsRepository,
    /** The app's shared HTTP client (goes through Tor when Tor is on). */
    private val client: () -> OkHttpClient,
) {
    /** Off when the person has turned off automatic list updates (manual update still works). */
    @Volatile var autoUpdate = true
    @Volatile var config = BlockingConfig()
        private set
    @Volatile private var ads: FilterSet? = null
    @Volatile private var trackers: FilterSet? = null

    @Volatile private var loadLatch = java.util.concurrent.CountDownLatch(1)
    @Volatile private var loading = false
    private val _status = MutableStateFlow(FilterStatus())
    val status: StateFlow<FilterStatus> = _status

    private val dir = File(context.filesDir, "filters")
    private val prefs = context.getSharedPreferences("filters", Context.MODE_PRIVATE)

    fun start() {
        scope.launch {
            settings.blocking.collect { c ->
                config = c
                // With both switches off nothing is checked, so the lists don't need to sit in memory (about 30 MB).
                if (!c.ads && !c.trackers) unload() else ensureLoaded()
            }
        }
        scope.launch(Dispatchers.Default) {
            delay(1000)
            _status.update { it.copy(updatedAt = prefs.getLong("updated", 0)) }
            // Refresh the lists about weekly, only on an unmetered connection.
            delay(20_000)
            if (System.currentTimeMillis() - prefs.getLong("updated", 0) > 7L * 24 * 3600 * 1000 && !isMetered() && autoUpdate && (config.ads || config.trackers)) update()
        }
    }

    /** Loads the lists in the background (once). Always releases anyone waiting, even if loading fails. */
    private fun ensureLoaded() {
        synchronized(this) {
            if (ads != null || loading) return
            loading = true
            loadLatch = java.util.concurrent.CountDownLatch(1)
        }
        val latch = loadLatch
        scope.launch(Dispatchers.Default) {
            try {
                try { load() } catch (e: Exception) {
                    // A damaged downloaded list: drop it and use the copy built into the app.
                    runCatching { dir.listFiles()?.forEach { it.delete() } }
                    runCatching { load() }
                }
            } finally {
                loading = false
                latch.countDown()
            }
        }
    }

    private fun unload() {
        synchronized(this) { ads = null; trackers = null }
        _status.update { it.copy(loaded = false) }
    }

    private fun isMetered() = (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered

    private fun open(name: String) =
        File(dir, name).takeIf { it.isFile }?.inputStream() ?: context.assets.open("filters/$name")

    private fun build(names: List<String>): FilterSet = FilterSet().also { set ->
        for (n in names) open(n).bufferedReader().use(set::addAll)
    }

    private fun load() {
        val a = build(listOf("easylist.txt"))
        val t = build(listOf("easyprivacy.txt", "analytics.txt"))
        ads = a; trackers = t
        _status.update { it.copy(loaded = true, adRules = a.blockCount, trackerRules = t.blockCount) }
    }

    /** Downloads fresh lists over HTTPS. A list is only replaced if it parses into a sensible number of rules. */
    suspend fun update(): Boolean = withContext(Dispatchers.IO) {
        _status.update { it.copy(updating = true, message = null) }
        try {
            dir.mkdirs()
            val http = client().newBuilder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
            for ((name, url) in listOf("easylist.txt" to "https://easylist.to/easylist/easylist.txt", "easyprivacy.txt" to "https://easylist.to/easylist/easyprivacy.txt")) {
                val tmp = File(dir, "$name.tmp")
                http.newCall(HttpRequest.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    r.body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } } // streamed to disk, never held in memory
                }
                val head = tmp.bufferedReader().use { it.readLine().orEmpty() }
                val probe = FilterSet().also { s -> tmp.bufferedReader().use(s::addAll) }
                if (!head.startsWith("[Adblock") || probe.blockCount < 5000) { tmp.delete(); throw IOException("Unexpected list contents") }
                if (!tmp.renameTo(File(dir, name))) throw IOException("Could not save the list")
            }
            load()
            val now = System.currentTimeMillis()
            prefs.edit().putLong("updated", now).apply()
            _status.update { it.copy(updating = false, updatedAt = now, message = ziriString(R.string.filter_lists_updated)) }
            true
        } catch (e: Exception) {
            _status.update { it.copy(updating = false, message = ziriString(R.string.couldn_t_update, e.message ?: ziriString(R.string.network_error))) }
            false
        }
    }

    fun clearMessage() { _status.update { it.copy(message = null) } }

    // ---- per-site settings ----------------------------------------------------------------------------
    private fun norm(host: String) = host.lowercase().removePrefix("www.")

    /** The override that applies to [host] (the most specific saved site), or null. */
    fun siteFor(host: String): SiteBlock? {
        val h = norm(host)
        return config.sites.entries.filter { h == it.key || h.endsWith("." + it.key) }.maxByOrNull { it.key.length }?.value
    }

    /** What is actually blocked on [host] right now, combining the global switches with the site's own. */
    fun effectiveFor(host: String): SiteBlock {
        val s = siteFor(host)
        return SiteBlock(config.ads && (s?.ads ?: true), config.trackers && (s?.trackers ?: true))
    }

    fun siteKey(host: String) = norm(host)

    /** True if ads are blocked on [host], so empty ad slots should be hidden there too. */
    fun hidesAdSlots(host: String) = ads != null && effectiveFor(host).ads

    // ---- the decision ---------------------------------------------------------------------------------
    /** Which list blocks [url] on the page [pageUrl], or null to let it load. Never called for the page itself. */
    fun decide(url: String, pageUrl: String, type: Int): ListKind? {
        val cfg = config
        if (!cfg.ads && !cfg.trackers) return null // blocking is off everywhere: nothing to check, nothing to wait for
        // Right after launch the lists may still be parsing. This runs on WebView's own network thread (not the UI),
        // so waiting briefly keeps the very first page of a session from slipping through unfiltered.
        if (ads == null) {
            ensureLoaded()
            runCatching { loadLatch.await(4, TimeUnit.SECONDS) }
        }
        val a = ads
        val t = trackers
        if (a == null || t == null) return null
        val req = Request(url, pageUrl, type)
        if (req.host.isEmpty() || isSafe(req)) return null
        val eff = effectiveFor(req.pageHost)
        if (eff.ads && a.blocks(req)) return ListKind.ADS
        if (eff.trackers && t.blocks(req)) return ListKind.TRACKERS
        return null
    }

    /** Sign-in, captcha and payment services are never blocked, so filtering can't lock someone out of a site. */
    private fun isSafe(req: Request): Boolean {
        val h = req.host
        if (Safe.safeHosts.any { h == it || h.endsWith(".$it") }) return true
        return req.lower.contains("/recaptcha/") || req.lower.contains("/turnstile/")
    }

    companion object {
        /**
         * A short, deliberately conservative list of ad containers, hidden so blocked ads don't leave blank gaps.
         * It only names things that are ad slots by their own markup (Google ad tags, ad-network iframes and
         * recommendation widgets), never generic words like "ad" or "banner" that real content might use.
         */
        val adSlotCss = listOf(
            "ins.adsbygoogle", ".adsbygoogle", "[id^='google_ads_iframe']", "[id^='google_ads_']", "[id^='div-gpt-ad']",
            "[data-ad-slot]", "[data-google-query-id]", "amp-ad", "amp-embed[type='taboola']", "[id^='taboola-']", "[id^='outbrain_widget']",
            ".OUTBRAIN", "iframe[src*='doubleclick.net']", "iframe[src*='googlesyndication.com']", "iframe[src*='adservice.google']",
            "iframe[id^='google_ads']", "[aria-label='Advertisement']", "[aria-label='advertisement']",
        ).joinToString(",") + "{display:none!important}"

        /** Script that adds the stylesheet above once per page. */
        val adSlotScript = "(function(){if(document.getElementById('ziri-ad-slots'))return;var s=document.createElement('style');s.id='ziri-ad-slots';" +
            "s.textContent=" + org.json.JSONObject.quote(adSlotCss) + ";(document.head||document.documentElement).appendChild(s)})()"
    }

    private object Safe {
        val safeHosts = setOf(
            "gstatic.com", "hcaptcha.com", "recaptcha.net", "challenges.cloudflare.com", "accounts.google.com", "appleid.apple.com",
            "paypal.com", "paypalobjects.com", "stripe.com", "stripe.network", "login.microsoftonline.com", "login.live.com",
            "okta.com", "auth0.com", "github.com", "githubassets.com",
        )
    }
}
