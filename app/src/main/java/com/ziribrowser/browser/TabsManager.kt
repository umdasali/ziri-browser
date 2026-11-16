package com.ziribrowser.browser

import com.ziribrowser.R
import com.ziribrowser.ziriString
import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Message
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebStorage
import com.ziribrowser.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.Uri
import com.ziribrowser.data.HistoryDao
import com.ziribrowser.data.HistoryEntity
import com.ziribrowser.data.TabDao
import com.ziribrowser.data.TabEntity
import com.ziribrowser.download.DownloadRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

/** What the UI needs to know about a tab. The WebView itself is held separately. */
data class TabState(
    val id: String,
    val url: String = "",
    val title: String = "",
    val isPrivate: Boolean = false,
    /** Opened as a Tor tab: private as well, and all traffic goes through Tor. */
    val isTor: Boolean = false,
    val desktop: Boolean = false,
    val loading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    /** Private tab on a WebView that supports profiles, i.e. cookies really are separate. */
    val isolated: Boolean = false,
    val error: String? = null,
    /** A certificate problem waiting for the user's decision. The page is not loaded until they choose. */
    val sslProblem: SslProblem? = null,
    /** Host the user explicitly chose to open despite a certificate error (the lock shows a warning). */
    val certOverrideHost: String? = null,
    /** One-line note shown under the address bar, e.g. when Ziri had to fall back to http. */
    val notice: String? = null,
    /** A plain-http page waiting for the person's decision (HTTPS-only mode). Nothing is loaded until they choose. */
    val insecure: InsecurePrompt? = null,
    /** The tab whose link opened this one. Back from this tab's first page closes it and returns there. */
    val openerId: String? = null,
    /** Bumped to make the screen build a fresh WebView for this tab (after its renderer crashed and the old one was dropped). */
    val webViewEpoch: Int = 0,
)

/** The site that doesn't offer HTTPS and the address that would be opened. */
data class InsecurePrompt(val host: String, val url: String)

/** Why a certificate was refused, in plain words. */
data class SslProblem(val host: String, val reason: String, val details: List<String>)

/** What the "connection details" dialog shows about the current page's certificate. */
data class ConnectionInfo(val issuedTo: String, val issuedBy: String, val validFrom: String, val validTo: String)

/** Result of the latest find-in-page search: 1-based [active] match out of [total]. */
data class FindState(val active: Int = 0, val total: Int = 0)

/** Things a page can ask permission for. */
enum class SiteKind(val label: String, val osPermission: String) {
    CAMERA("camera", android.Manifest.permission.CAMERA),
    MICROPHONE("microphone", android.Manifest.permission.RECORD_AUDIO),
    LOCATION("location", android.Manifest.permission.ACCESS_FINE_LOCATION),
}

/** A page is asking for [kinds]; the UI answers through [resolve]. */
data class PermissionPrompt(
    val origin: String,
    val kinds: List<SiteKind>,
    /** False in private tabs, which never store site decisions. */
    val canRemember: Boolean,
    val resolve: (allow: Boolean, remember: Boolean) -> Unit,
)

enum class MediaKind { VIDEO, AUDIO, STREAM }

/** A video, audio file or stream the page loaded, which Ziri can offer to download. */
data class DetectedMedia(val url: String, val kind: MediaKind, val ext: String)

/** A long-pressed link or image. */
data class HitTarget(val linkUrl: String?, val imageUrl: String?)

private const val THUMB_WIDTH = 360

private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

@OptIn(FlowPreview::class)
class TabsManager(
    private val appContext: Context,
    private val tabDao: TabDao,
    private val historyDao: HistoryDao,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val blocker: com.ziribrowser.adblock.BlockingManager,
    private val tor: com.ziribrowser.tor.TorManager,
    private val onDownload: (DownloadRequest) -> Unit,
    private val passwords: com.ziribrowser.passwords.PasswordBridge,
) {
    private val _tabs = MutableStateFlow<List<TabState>>(emptyList())
    val tabs: StateFlow<List<TabState>> = _tabs

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId

    private val _customView = MutableStateFlow<View?>(null)
    /** Fullscreen video (or other custom view) supplied by the page. */
    val customView: StateFlow<View?> = _customView
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val _hitTarget = MutableStateFlow<HitTarget?>(null)
    val hitTarget: StateFlow<HitTarget?> = _hitTarget
    fun clearHitTarget() { _hitTarget.value = null }

    // ---- video & audio sniffing -----------------------------------------------------------------
    private val _media = MutableStateFlow<Map<String, List<DetectedMedia>>>(emptyMap())
    /** Media each tab's page has loaded so far, by tab id. Cleared when the tab loads a new page. */
    val detectedMedia: StateFlow<Map<String, List<DetectedMedia>>> = _media

    private val mediaTypes = mapOf(
        "mp4" to MediaKind.VIDEO, "m4v" to MediaKind.VIDEO, "webm" to MediaKind.VIDEO, "mov" to MediaKind.VIDEO,
        "mkv" to MediaKind.VIDEO, "3gp" to MediaKind.VIDEO, "m3u8" to MediaKind.STREAM,
        "mp3" to MediaKind.AUDIO, "m4a" to MediaKind.AUDIO, "aac" to MediaKind.AUDIO, "ogg" to MediaKind.AUDIO, "opus" to MediaKind.AUDIO,
    )

    /** Remembers a request if its address looks like video or audio. Safe to call from WebView's background thread. */
    /** Tor connection state, shown on Tor tabs while connecting. */
    val torState: StateFlow<com.ziribrowser.tor.TorState> get() = tor.state
    val torWanted: Boolean get() = tor.wanted
    fun retryTor() = tor.retry()

    // ---- privacy shield -----------------------------------------------------------------------------
    @Volatile private var privacy = com.ziribrowser.privacy.PrivacyConfig()
    /** A new random value each time Ziri starts, so fingerprints can't be matched from one session to the next. */
    private val sessionSeed = java.security.SecureRandom().let { r -> ByteArray(12).also(r::nextBytes).joinToString("") { "%02x".format(it) } }
    private val shieldScripts = HashMap<String, androidx.webkit.ScriptHandler>()

    /** Applies new privacy settings to every open tab. Call on the main thread. */
    fun setPrivacy(c: com.ziribrowser.privacy.PrivacyConfig) {
        privacy = c
        webViews.forEach { (id, wv) -> applyPrivacy(id, wv); applyDesktop(wv, tab(id)?.desktop == true) }
    }

    private fun applyPrivacy(id: String, wv: WebView) {
        val isTor = tab(id)?.isTor == true
        val c = if (isTor) privacy.copy(fingerprintShield = true, blockThirdPartyCookies = true, safeBrowsing = false, exemptHosts = emptySet()) else privacy
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(wv, !c.blockThirdPartyCookies)
        wv.settings.safeBrowsingEnabled = c.safeBrowsing
        shieldScripts.remove(id)?.remove()
        if (c.fingerprintShield && androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // Private tabs get their own seed, so they can't be linked to the normal session either.
            val seed = if (tab(id)?.isPrivate == true) sessionSeed + id else sessionSeed
            runCatching {
                shieldScripts[id] = androidx.webkit.WebViewCompat.addDocumentStartJavaScript(wv, com.ziribrowser.privacy.FingerprintShield.script(seed, c.exemptHosts, torMode = isTor), setOf("*"))
            }
        }
    }

    private fun privacyHeaders(): Map<String, String> =
        if (privacy.sendGpc) mapOf("Sec-GPC" to "1", "DNT" to "1") else emptyMap()

    private fun load(wv: WebView, url: String) = wv.loadUrl(url, privacyHeaders())

    // ---- immersive browsing: bars hide when scrolling down and return when scrolling up ----------------
    private val scrollTracker = ChromeScrollTracker()
    private val _chromeHidden = MutableStateFlow(false)
    val chromeHidden: StateFlow<Boolean> = _chromeHidden

    fun showChrome() { setChromeHidden(false) }

    private fun setChromeHidden(hidden: Boolean) {
        if (_chromeHidden.value == hidden) return
        _chromeHidden.value = hidden
        scrollTracker.reset(hidden)
    }

    private fun onPageScrolled(id: String, y: Int, dy: Int) {
        if (id != _activeId.value) return
        scrollTracker.onScroll(y, dy)?.let { _chromeHidden.value = it }
    }

    // ---- ad / tracker blocking bookkeeping ---------------------------------------------------------
    private val blockCounter = BlockCounter(scope)
    /** Requests blocked on each tab's current page. Published at most every 400 ms to avoid redrawing for every blocked request. */
    val blocked: StateFlow<Map<String, Int>> get() = blockCounter.counts

    private fun countBlocked(id: String) = blockCounter.count(id)
    private fun resetBlocked(id: String) = blockCounter.reset(id)

    private fun blockedResponse() = android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
        .apply { setStatusCodeAndReasonPhrase(403, "Blocked") }

    private fun guessType(r: WebResourceRequest): Int = RequestType.guess(r.url.path.orEmpty(), r.requestHeaders["Accept"].orEmpty())

    private fun sniff(id: String, url: String) {
        if (!UrlUtils.isHttp(url)) return
        val host = UrlUtils.rawHost(url).lowercase()
        // YouTube serves video in separate, protected chunks that can't be saved as one file.
        if (host.endsWith("googlevideo.com")) return
        val ext = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
        val kind = mediaTypes[ext] ?: return
        addMedia(id, DetectedMedia(url, kind, ext))
    }

    private fun addMedia(id: String, m: DetectedMedia) {
        _media.update { map ->
            val list = map[id].orEmpty()
            if (list.any { it.url == m.url } || list.size >= 25) map else map + (id to list + m)
        }
    }

    /** Also asks the page itself which media it is showing (catches files that were cached or loaded early). */
    fun scanPageMedia(id: String, done: () -> Unit) {
        val wv = webViews[id] ?: return done()
        val js = "(function(){var o=[];function a(u){if(u&&/^https?:/i.test(u))o.push(u)}" +
            "document.querySelectorAll('video,audio').forEach(function(v){a(v.currentSrc||v.src);" +
            "v.querySelectorAll('source').forEach(function(s){a(s.src)})});return JSON.stringify(o)})()"
        wv.evaluateJavascript(js) { raw ->
            runCatching {
                val text = org.json.JSONTokener(raw).nextValue() as? String ?: return@runCatching
                val arr = org.json.JSONArray(text)
                for (i in 0 until arr.length()) {
                    val u = arr.getString(i)
                    val ext = u.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
                    addMedia(id, DetectedMedia(u, mediaTypes[ext] ?: MediaKind.VIDEO, ext))
                }
            }
            done()
        }
    }

    // ---- find in page ----------------------------------------------------------------------
    private val _find = MutableStateFlow(FindState())
    val find: StateFlow<FindState> = _find

    fun findInPage(id: String, query: String) {
        val wv = webViews[id] ?: return
        if (query.isEmpty()) { wv.clearMatches(); _find.value = FindState() } else wv.findAllAsync(query)
    }
    fun findNext(id: String, forward: Boolean) { webViews[id]?.findNext(forward) }
    fun stopFind(id: String) { webViews[id]?.clearMatches(); _find.value = FindState() }

    // ---- tab thumbnails (kept in memory only, never for private tabs) -------------------------
    private val _thumbs = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val thumbnails: StateFlow<Map<String, Bitmap>> = _thumbs

    fun captureThumbnail(id: String) {
        val wv = webViews[id] ?: return
        val t = tab(id) ?: return
        if (t.isPrivate || t.url.isEmpty() || wv.width <= 0 || wv.height <= 0) return
        runCatching {
            val scale = THUMB_WIDTH.toFloat() / wv.width
            val bmp = Bitmap.createBitmap(THUMB_WIDTH, (wv.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.RGB_565)
            val canvas = android.graphics.Canvas(bmp)
            canvas.scale(scale, scale)
            wv.draw(canvas)
            _thumbs.update { it + (id to bmp) }
        }
    }

    // ---- browsing data -----------------------------------------------------------------------
    /** Deletes cookies and site storage (local storage, IndexedDB). Call on the main thread. */
    fun clearCookiesAndSiteData() {
        CookieManager.getInstance().apply { removeAllCookies(null); flush() }
        WebStorage.getInstance().deleteAllData()
    }

    /** Opens Android's print dialog for the page, which also offers "Save as PDF". [context] must be an Activity. */
    fun printPage(id: String, context: Context, name: String) {
        val wv = webViews[id] ?: return
        val pm = context.getSystemService(Context.PRINT_SERVICE) as? android.print.PrintManager ?: return
        val job = name.ifBlank { "Ziri page" }
        runCatching { pm.print(job, wv.createPrintDocumentAdapter(job), android.print.PrintAttributes.Builder().build()) }
    }

    /** Empties the WebView HTTP cache. Call on the main thread. */
    fun clearCache() {
        val existing = webViews.values.firstOrNull()
        val wv = existing ?: WebView(MutableContextWrapper(webContext(appContext)))
        wv.clearCache(true)
        if (existing == null) wv.destroy()
    }

    // ---- site permissions ----------------------------------------------------------------------
    private val _permissionPrompt = MutableStateFlow<PermissionPrompt?>(null)
    val permissionPrompt: StateFlow<PermissionPrompt?> = _permissionPrompt

    /** Set by MainActivity: asks Android for runtime permissions and reports whether all were granted. */
    var osPermissionRequester: ((Array<String>, (Boolean) -> Unit) -> Unit)? = null

    private fun ensureOsPermissions(kinds: List<SiteKind>, done: (Boolean) -> Unit) {
        val missing = kinds.map { it.osPermission }.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(appContext, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) { done(true); return }
        val requester = osPermissionRequester
        if (requester == null) done(false) else requester(missing.toTypedArray(), done)
    }

    /** Applies the saved decision for [origin], or asks the user. [grant] is always called exactly once, on the main thread. */
    private fun handleSiteRequest(tabId: String, origin: String, kinds: List<SiteKind>, grant: (Boolean) -> Unit) {
        if (tab(tabId)?.isTor == true) { grant(false); return } // Tor tabs never reveal location, camera or microphone
        scope.launch(Dispatchers.Main) {
            val isPrivate = tab(tabId)?.isPrivate == true
            val saved = if (isPrivate) kinds.map { null } else kinds.map { settings.sitePermission(origin, it.label) }
            when {
                saved.any { it == false } -> grant(false)
                saved.all { it == true } -> ensureOsPermissions(kinds, grant)
                else -> {
                    _permissionPrompt.value?.resolve?.invoke(false, false) // an unanswered older prompt is denied
                    _permissionPrompt.value = PermissionPrompt(origin, kinds, canRemember = !isPrivate) { allow, remember ->
                        _permissionPrompt.value = null
                        if (remember && !isPrivate) scope.launch { kinds.forEach { settings.setSitePermission(origin, it.label, allow) } }
                        if (allow) ensureOsPermissions(kinds, grant) else grant(false)
                    }
                }
            }
        }
    }

    /** Set by MainActivity: launches the system file picker and reports the result back. */
    var fileChooser: ((ValueCallback<Array<Uri>>, android.content.Intent) -> Boolean)? = null

    private val webViews = HashMap<String, WebView>()

    /** Play/pause, seek, speed and resume for the active tab's video. */
    val video = VideoController(scope, settings) { _activeId.value?.let { id -> webViews[id]?.let { wv -> tab(id)?.let { wv to it } } } }
    private val lastLogged = HashMap<String, String>()
    private val defaultUa: String by lazy { WebSettings.getDefaultUserAgent(appContext) }

    private var darkWeb = false
    /** Darken web pages (algorithmic darkening + the page's own dark styles). Set by the UI only while the app is in dark mode. */
    fun setDarkWeb(on: Boolean) {
        darkWeb = on
        webViews.values.forEach { applyDarkWeb(it) }
    }
    private fun applyDarkWeb(wv: WebView) {
        // The context theme is what decides whether a page is told "prefers-color-scheme: dark", so it follows the switch.
        ((wv.context as? MutableContextWrapper)?.baseContext as? android.view.ContextThemeWrapper)?.setTheme(webTheme())
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING)) {
            androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, darkWeb)
        }
    }
    // WebView reads the page colour scheme (prefers-color-scheme, algorithmic darkening) from its context theme, and the activity
    // theme follows the phone rather than the app's own choice. So each WebView gets a dark or light overlay theme to match setDarkWeb.
    private fun webTheme() = if (darkWeb) com.ziribrowser.R.style.Theme_Ziri_Web else com.ziribrowser.R.style.Theme_Ziri_WebLight
    private fun webContext(base: Context): Context = android.view.ContextThemeWrapper(base, webTheme())

    private var textZoom = 100
    /** Page text zoom (percent) from Settings; applied to open tabs now and to tabs created later. */
    fun setTextZoom(percent: Int) {
        textZoom = percent
        webViews.values.forEach { it.settings.textZoom = percent }
    }

    init {
        scope.launch { runCatching { historyDao.prune(System.currentTimeMillis() - 180L * 24 * 3600 * 1000) } } // keep six months
        // Tor runs only while at least one Tor tab is open.
        scope.launch {
            _tabs.map { list -> list.any { it.isTor } }.distinctUntilChanged().collect { want -> withContext(Dispatchers.Main) { tor.setWanted(want) } }
        }
        // A private session never survives the process: wipe leftovers if the app was killed with private tabs open.
        PrivateProfile.clear()
        // Persist open (non-private) tabs a moment after they change.
        scope.launch {
            _tabs.debounce(600).collect { _ ->
                // Until the saved tabs are loaded, an empty or partial list must not overwrite them.
                restoredGate.await()
                val list = _tabs.value
                val rows = list.filter { !it.isPrivate }.mapIndexed { i, t ->
                    TabEntity(t.id, t.url, t.title, i, t.desktop)
                }
                tabDao.clear()
                tabDao.insertAll(rows)
            }
        }
        // Remember which normal tab was in front, so a restart opens the same one.
        scope.launch {
            _activeId.debounce(600).collect { id ->
                restoredGate.await()
                val t = _tabs.value.firstOrNull { it.id == id }
                if (t != null && !t.isPrivate) runCatching { settings.setLastActiveTab(t.id) }
            }
        }
    }

    private val restoredGate = kotlinx.coroutines.CompletableDeferred<Unit>()

    suspend fun restore() {
        try {
            val saved = tabDao.all()
            if (saved.isEmpty()) return
            val old = saved.map { TabState(it.id, it.url, it.title, desktop = it.desktop) }
            // A link may have opened a tab during start-up already: keep it, in front of the saved ones, instead of dropping either.
            val hadOpened = _tabs.value.isNotEmpty()
            _tabs.update { opened -> old + opened }
            if (!hadOpened) _activeId.value = settings.lastActiveTab()?.takeIf { id -> old.any { it.id == id } } ?: old.first().id
        } finally {
            restoredGate.complete(Unit)
        }
    }

    // ---- tab list operations -------------------------------------------------------------

    fun openTab(url: String?, isPrivate: Boolean = false, activate: Boolean = true, isTor: Boolean = false, openerId: String? = null): String {
        val id = UUID.randomUUID().toString()
        _tabs.update { it + TabState(id, url.orEmpty(), isPrivate = isPrivate || isTor, isTor = isTor, openerId = openerId) }
        if (activate) _activeId.value = id
        return id
    }

    fun setActive(id: String) { if (_tabs.value.any { it.id == id }) { _activeId.value = id; setChromeHidden(false) } }

    fun closeTab(id: String) {
        val list = _tabs.value
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        val closed = list[idx]
        val rest = list.filterNot { it.id == id }
        _tabs.value = rest
        if (_activeId.value == id) {
            // A tab opened from a link returns to the tab it came from; any other tab hands over to its neighbour.
            _activeId.value = rest.firstOrNull { it.id == closed.openerId }?.id ?: rest.getOrNull(minOf(idx, rest.lastIndex))?.id
        }
        destroy(id, wipe = closed.isPrivate)
        // Last private tab gone: drop the whole private session.
        if (closed.isPrivate && rest.none { it.isPrivate }) PrivateProfile.clear()
    }

    /** Closes every normal tab, or every private tab, leaving the other kind alone. */
    fun closeAll(isPrivate: Boolean) {
        _tabs.value.filter { it.isPrivate == isPrivate }.map { it.id }.forEach { closeTab(it) }
    }

    /** Closes every tab of the same kind (normal, private or Tor) as [keepId] except that one. */
    fun closeOthers(keepId: String) {
        val keep = _tabs.value.firstOrNull { it.id == keepId } ?: return
        _tabs.value.filter { it.id != keepId && it.isPrivate == keep.isPrivate && it.isTor == keep.isTor }.map { it.id }.forEach { closeTab(it) }
        setActive(keepId)
    }

    fun closeAll() {
        _tabs.value.forEach { destroy(it.id, wipe = it.isPrivate) }
        _tabs.value = emptyList()
        _activeId.value = null
        PrivateProfile.clear()
    }

    private fun destroy(id: String, wipe: Boolean = false) {
        passwords.forget(id)
        webViews.remove(id)?.apply {
            (parent as? android.view.ViewGroup)?.removeView(this)
            stopLoading()
            if (wipe) { clearHistory(); clearCache(true); clearFormData() } // private tab: leave nothing behind
            destroy()
        }
        if (customViewOwner == id) hideCustomView() // a tab closed while its video was fullscreen must not leave a black overlay
        lastLogged.remove(id)
        blockCounter.remove(id)
        shieldScripts.remove(id)
        cancelSsl(id)
        upgrades.remove(id)
        _media.update { it - id }
        _thumbs.update { it - id }
    }

    /** The internal blank page is not an address the person visited: keep the tab "empty" instead of showing about:blank. */
    private fun shown(url: String) = if (url == "about:blank") "" else url

    private fun update(id: String, f: (TabState) -> TabState) =
        _tabs.update { list -> list.map { if (it.id == id) f(it) else it } }

    fun tab(id: String?): TabState? = _tabs.value.firstOrNull { it.id == id }

    // ---- navigation ------------------------------------------------------------------------

    // ---- HTTPS protection ----------------------------------------------------------------------
    /** Try https:// first for http:// addresses (Settings → HTTPS-first). */
    @Volatile var httpsFirst = true
    /** HTTPS-only mode: sites without HTTPS show a warning page first. */
    @Volatile var httpsOnly = true
    private val httpFallbackHosts = HashSet<String>()
    /** tab id -> (original http url, the https url we tried instead) while an upgrade is in flight. */
    private val upgrades = HashMap<String, Pair<String, String>>()
    private val sslHandlers = HashMap<String, SslErrorHandler>()

    /** Returns the address to actually load: the https version when HTTPS-first applies, else [url]. */
    private fun maybeUpgrade(id: String, url: String): String {
        if (!httpsFirst && !httpsOnly) return url
        val https = UrlUtils.upgradeToHttps(url) ?: return url
        val host = UrlUtils.rawHost(url)
        if (host in httpFallbackHosts) return url
        // The site bounced us from https back to http: stop upgrading it rather than loop forever.
        if (upgrades[id]?.second == https) { httpFallbackHosts += host; upgrades.remove(id); return url }
        upgrades[id] = url to https
        return https
    }

    /** In HTTPS-only mode, stops a plain-http page and asks first. Returns true if the page was held back. */
    private fun gateInsecure(id: String, url: String): Boolean {
        if (!HttpsPolicy.holdsBack(url, httpsOnly, httpFallbackHosts)) return false
        webViews[id]?.stopLoading()
        update(id) { it.copy(url = url, loading = false, error = null, insecure = InsecurePrompt(UrlUtils.rawHost(url), url)) } // show the http address, so the lock says "not secure"
        return true
    }

    /** "Continue anyway": opens the http version of the held-back page and remembers the choice for this site. */
    fun proceedInsecure(id: String) {
        val p = tab(id)?.insecure ?: return
        httpFallbackHosts += p.host.lowercase()
        update(id) { it.copy(insecure = null, url = p.url, notice = "${p.host} doesn't support HTTPS, so this page is not secure.") }
        webViews[id]?.let { load(it, p.url) }
    }

    /** "Back to safety": drops the held-back page. */
    fun leaveInsecure(id: String) {
        val bad = tab(id)?.url
        update(id) { it.copy(insecure = null, loading = false) }
        settleOnCurrentPage(id, bad)
    }

    /**
     * After a warning page is dismissed: the WebView never left the page it was on (the risky navigation was cancelled or hadn't
     * committed), so show that page again. Going "back" here would skip it and land one page too far.
     */
    private fun settleOnCurrentPage(id: String, badUrl: String?) {
        val wv = webViews[id]
        val cur = wv?.url
        when {
            wv != null && cur != null && UrlUtils.isHttp(cur) && cur != badUrl ->
                update(id) { it.copy(url = cur, title = wv.title.orEmpty(), canGoBack = wv.canGoBack(), canGoForward = wv.canGoForward()) }
            wv != null && wv.canGoBack() -> wv.goBack()
            else -> { update(id) { it.copy(url = "", title = "") }; wv?.loadUrl("about:blank") }
        }
    }

    private fun cancelSsl(id: String) { sslHandlers.remove(id)?.cancel() }

    /** "Back to safety": refuse the certificate and leave the page. */
    fun sslBack(id: String) {
        cancelSsl(id)
        val bad = tab(id)?.url
        update(id) { it.copy(sslProblem = null, loading = false) }
        settleOnCurrentPage(id, bad)
    }

    /** "Proceed anyway": only reachable after the user opens Advanced and confirms. */
    fun sslProceed(id: String) {
        val problem = tab(id)?.sslProblem ?: return
        sslHandlers.remove(id)?.proceed()
        update(id) { it.copy(sslProblem = null, certOverrideHost = problem.host) }
    }

    @Suppress("DEPRECATION")
    fun connectionInfo(id: String): ConnectionInfo? {
        val c = webViews[id]?.certificate ?: return null
        val fmt = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
        return ConnectionInfo(
            issuedTo = c.issuedTo?.cName.orEmpty(),
            issuedBy = c.issuedBy?.let { it.oName.orEmpty().ifEmpty { it.cName.orEmpty() } }.orEmpty(),
            validFrom = c.validNotBeforeDate?.let { fmt.format(it) }.orEmpty(),
            validTo = c.validNotAfterDate?.let { fmt.format(it) }.orEmpty(),
        )
    }

    private fun describe(error: SslError): SslProblem {
        val host = UrlUtils.rawHost(error.url)
        val reasons = ArrayList<String>()
        if (error.hasError(SslError.SSL_EXPIRED)) reasons += ziriString(R.string.the_site_s_certificate_has_expired)
        if (error.hasError(SslError.SSL_NOTYETVALID)) reasons += ziriString(R.string.the_site_s_certificate_is_not_valid_yet)
        if (error.hasError(SslError.SSL_IDMISMATCH)) reasons += ziriString(R.string.the_certificate_was_issued_for_a_differe)
        if (error.hasError(SslError.SSL_UNTRUSTED)) reasons += ziriString(R.string.the_certificate_was_not_issued_by_a_trus)
        if (error.hasError(SslError.SSL_DATE_INVALID)) reasons += ziriString(R.string.the_certificate_s_dates_are_invalid)
        if (error.hasError(SslError.SSL_INVALID)) reasons += ziriString(R.string.the_certificate_is_invalid)
        if (reasons.isEmpty()) reasons += ziriString(R.string.the_certificate_could_not_be_verified)
        val c = error.certificate
        val details = buildList {
            add(ziriString(R.string.site, host))
            c?.issuedTo?.cName?.takeIf { it.isNotEmpty() }?.let { add(ziriString(R.string.issued_to_2, it)) }
            c?.issuedBy?.cName?.takeIf { it.isNotEmpty() }?.let { add(ziriString(R.string.issued_by_2, it)) }
        }
        return SslProblem(host, reasons.first(), reasons.drop(1) + details)
    }

    fun navigate(id: String, url: String) {
        cancelSsl(id)
        val target = maybeUpgrade(id, url)
        update(id) { it.copy(url = target, error = null, sslProblem = null, notice = null, insecure = null) }
        if (gateInsecure(id, target)) return
        webViews[id]?.let { load(it, target) }
    }

    fun toggleDesktop(id: String) {
        val t = tab(id) ?: return
        update(id) { it.copy(desktop = !t.desktop) }
        webViews[id]?.let { applyDesktop(it, !t.desktop); it.reload() }
    }

    private fun applyDesktop(wv: WebView, desktop: Boolean) = wv.settings.run {
        userAgentString = if (desktop) DESKTOP_UA else if (privacy.fingerprintShield) com.ziribrowser.privacy.FingerprintShield.reduceUserAgent(defaultUa) else defaultUa
        useWideViewPort = true
        loadWithOverviewMode = desktop
    }

    fun goBack(id: String) { webViews[id]?.goBack() }
    fun goForward(id: String) { webViews[id]?.goForward() }
    /** Finds the pictures and downloadable files linked or shown on the current page (up to 200). */
    fun pageFiles(id: String, onResult: (List<String>) -> Unit) {
        val wv = webViews[id] ?: return onResult(emptyList())
        val js = "(function(){var o=[],s={};function add(u){if(!u||u.indexOf('data:')==0)return;try{u=new URL(u,location.href).href}catch(e){return}if(s[u])return;s[u]=1;o.push(u)}" +
            "var im=document.images;for(var i=0;i<im.length;i++){var m=im[i];if(m.naturalWidth>=150||m.width>=150)add(m.currentSrc||m.src)}" +
            "var a=document.querySelectorAll('a[href]');for(var j=0;j<a.length;j++){var h=a[j].href;if(/\\.(jpe?g|png|gif|webp|pdf|zip|mp4|mp3|apk)([?#]|\$)/i.test(h))add(h)}" +
            "return JSON.stringify(o.slice(0,200))})()"
        wv.evaluateJavascript(js) { raw ->
            val list = runCatching {
                val inner = org.json.JSONTokener(raw).nextValue() as String
                val arr = org.json.JSONArray(inner)
                (0 until arr.length()).map { arr.getString(it) }.filter { UrlUtils.isHttp(it) }
            }.getOrDefault(emptyList())
            onResult(list)
        }
    }

    fun reload(id: String) { if (webViews[id] == null) rebuildWebView(id) else webViews[id]?.reload() }

    /** The tab's WebView is gone (renderer crash): have the screen create a new one, which loads the tab's address again. */
    private fun rebuildWebView(id: String) = update(id) { it.copy(webViewEpoch = it.webViewEpoch + 1, error = null) }

    fun reloadOrStop(id: String) {
        val wv = webViews[id] ?: return rebuildWebView(id)
        if (tab(id)?.loading == true) wv.stopLoading() else wv.reload()
    }

    private var customViewOwner: String? = null

    fun hideCustomView() {
        customViewOwner = null
        video.fullscreen = false
        video.refresh()
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        _customView.value = null
    }

    // ---- WebView lifecycle -----------------------------------------------------------------

    /** Returns the tab's WebView, creating it on first use. [activity] becomes its context while attached. */
    fun attach(id: String, activity: Context): WebView? {
        val state = tab(id) ?: return null
        val wv = webViews[id] ?: createWebView(state, null)
        (wv.context as? MutableContextWrapper)?.baseContext = webContext(activity)
        (wv.parent as? android.view.ViewGroup)?.removeView(wv)
        // Compose's AndroidView gives a view with no LayoutParams wrap-content ones, and WebView then sizes the page to its
        // content: every `100vh` / `100dvh` computes to 0, so full-screen layouts (most modern sites) collapse and show blank.
        wv.layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        wv.onResume()
        return wv
    }

    /** Called when the WebView leaves the screen, so it never keeps the Activity alive. */
    fun detach(id: String) {
        captureThumbnail(id)
        webViews[id]?.let {
            (it.context as? MutableContextWrapper)?.baseContext = webContext(appContext)
            it.onPause()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    /** Pulls the article out of a tab's page for reader view; null when the page has no article. Main thread. */
    fun extractArticle(id: String, onResult: (com.ziribrowser.reader.Article?) -> Unit) {
        val wv = webViews[id] ?: return onResult(null)
        val base = tab(id)?.url.orEmpty()
        wv.evaluateJavascript(com.ziribrowser.reader.ReaderExtractor.script(appContext)) { raw ->
            onResult(com.ziribrowser.reader.ReaderExtractor.parse(raw, base))
        }
    }

    /** True if the ad and tracker lists would block this image on the page, so reader view skips it too. */
    fun readerBlocks(url: String, pageUrl: String): Boolean =
        UrlUtils.isHttp(url) && blocker.decide(url, pageUrl, com.ziribrowser.adblock.ResType.IMAGE) != null

    /** Fills a saved login into the tab's page. The UI asks for the person's unlock before calling this. */
    fun fillLogin(tabId: String, loginId: Long) { webViews[tabId]?.let { passwords.fill(tabId, it, loginId) } }

    private fun createWebView(state: TabState, preset: WebView?): WebView {
        val wv = preset ?: WebView(MutableContextWrapper(webContext(appContext)))
        val isolated = if (state.isPrivate) PrivateProfile.apply(wv) else false
        update(state.id) { it.copy(isolated = isolated) }

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Security: no access to local files or content providers from web pages.
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(true) // still gated by the permission prompt
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            builtInZoomControls = true
            displayZoomControls = false
            safeBrowsingEnabled = true
            textZoom = this@TabsManager.textZoom
            if (state.isPrivate) {
                // Private: nothing is cached to disk and no form data is remembered.
                cacheMode = WebSettings.LOAD_NO_CACHE
                saveFormData = false
            }
        }
        applyDarkWeb(wv)
        applyPrivacy(state.id, wv)
        applyDesktop(wv, state.desktop)
        wv.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY -> onPageScrolled(state.id, scrollY, scrollY - oldScrollY) }
        wv.setFindListener { activeOrdinal, numberOfMatches, _ ->
            _find.value = FindState(if (numberOfMatches > 0) activeOrdinal + 1 else 0, numberOfMatches)
        }
        // Saved logins: never on private or Tor tabs, which keep no trace of what you type.
        if (!state.isPrivate && !state.isTor) passwords.attach(state.id, wv) { tab(state.id)?.url.orEmpty() }
        wv.webViewClient = client(state.id)
        wv.webChromeClient = chromeClient(state.id)
        wv.setDownloadListener { url, ua, disposition, mime, length ->
            val t = tab(state.id)
            onDownload(
                DownloadRequest(
                    url = url, userAgent = ua, contentDisposition = disposition, mimeType = mime,
                    contentLength = length, cookie = PrivateProfile.cookieFor(url, t?.isPrivate == true),
                    referer = t?.url.orEmpty(), isPrivate = t?.isPrivate == true,
                ),
            )
        }
        wv.setOnLongClickListener {
            val r = wv.hitTestResult
            val extra = r.extra
            val target = when (r.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> HitTarget(extra, null)
                WebView.HitTestResult.IMAGE_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> HitTarget(null, extra)
                else -> null
            }
            if (target != null && UrlUtils.isHttp(extra)) { _hitTarget.value = target; true } else false
        }
        webViews[state.id] = wv
        if (preset == null && state.url.isNotEmpty()) {
            // A tab opened with an address (a link from another app, a restored tab) gets the same https-first / https-only treatment.
            val target = maybeUpgrade(state.id, state.url)
            if (target != state.url) update(state.id) { it.copy(url = target) }
            if (!gateInsecure(state.id, target)) load(wv, target)
        }
        return wv
    }

    private fun client(id: String) = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Only http(s) loads in the page; intent:, file:, javascript: and other schemes are refused.
            val u = request.url.toString()
            if (!UrlUtils.isHttp(u)) return true
            if (request.isForMainFrame) {
                // Rewriting re-issues the request as a GET, which would drop a form's POST body, so only plain GETs are rewritten.
                val isGet = request.method.equals("GET", ignoreCase = true)
                val upgraded = if (isGet) maybeUpgrade(id, u) else u
                if (upgraded != u) { load(view, upgraded); return true }
                if (gateInsecure(id, u)) return true
                if (isGet && privacy.stripTrackingParams) {
                    val cleaned = com.ziribrowser.privacy.TrackingParams.clean(u)
                    if (cleaned != u) { load(view, cleaned); return true }
                }
            }
            return false
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
            if (!request.isForMainFrame) return
            val tried = upgrades[id]
            val host = request.url.host
            if (tried != null && request.url.scheme == "https" && host != null && host == UrlUtils.rawHost(tried.first)) {
                // The site has no working HTTPS: open the http version, but say so.
                upgrades.remove(id)
                if (gateInsecure(id, tried.first)) return
                httpFallbackHosts += host
                update(id) { it.copy(url = tried.first, notice = "$host doesn't support HTTPS, so this page is not secure.") }
                load(view, tried.first)
            } else {
                update(id) { it.copy(loading = false, error = ziriString(R.string.this_page_couldn_t_load, error.description)) }
            }
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
            val url = request.url.toString()
            sniff(id, url)
            // Never touch the page itself, only what it pulls in.
            if (!request.isForMainFrame && UrlUtils.isHttp(url)) {
                val kind = blocker.decide(url, tab(id)?.url.orEmpty(), guessType(request))
                if (kind != null) { countBlocked(id); return blockedResponse() }
            }
            return null
        }

        private fun hideAdSlots(view: WebView, url: String) {
            if (UrlUtils.isHttp(url) && blocker.hidesAdSlots(UrlUtils.rawHost(url))) view.evaluateJavascript(com.ziribrowser.adblock.BlockingManager.adSlotScript, null)
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (_media.value.containsKey(id)) _media.update { it - id }
            passwords.onNavigation(id)
            resetBlocked(id)
            if (id == _activeId.value) setChromeHidden(false) // a new page: show the bars
            hideAdSlots(view, url)
            update(id) { it.copy(url = shown(url), loading = true, error = null) }
        }

        override fun onPageFinished(view: WebView, url: String) {
            // Landed on https: the upgrade worked, so a later visit to the http address must not look like a bounce back.
            if (url.startsWith("https://", ignoreCase = true)) upgrades.remove(id)
            update(id) {
                it.copy(url = shown(url), title = view.title.orEmpty(), loading = false,
                    canGoBack = view.canGoBack(), canGoForward = view.canGoForward())
            }
            val t = tab(id) ?: return
            video.restore(view, t)
            hideAdSlots(view, url)
            if (!t.isPrivate && UrlUtils.isHttp(url) && lastLogged[id] != url) {
                lastLogged[id] = url
                scope.launch { historyDao.insert(HistoryEntity(title = t.title.ifEmpty { url }, url = url)) }
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            update(id) { it.copy(url = shown(url), canGoBack = view.canGoBack(), canGoForward = view.canGoForward()) }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            // Never bypass silently: hold the decision until the user answers the warning screen.
            sslHandlers.remove(id)?.cancel()
            sslHandlers[id] = handler
            update(id) { it.copy(loading = false, sslProblem = describe(error)) }
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            // The page's renderer crashed or was killed for memory; drop the WebView and rebuild it on demand.
            webViews.remove(id)?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.destroy() }
            update(id) { it.copy(loading = false, error = ziriString(R.string.this_page_ran_out_of_memory_reload_to_tr)) }
            return true
        }
    }

    private fun chromeClient(id: String) = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            update(id) { it.copy(progress = newProgress, loading = newProgress < 100) }
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            update(id) { it.copy(title = title.orEmpty()) }
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) return false // no pop-ups the user did not ask for
            val parent = tab(id)
            val newId = openTab(null, isPrivate = parent?.isPrivate == true, isTor = parent?.isTor == true, openerId = id)
            val newView = WebView(MutableContextWrapper(webContext(appContext)))
            createWebView(tab(newId)!!, newView)
            (resultMsg.obj as WebView.WebViewTransport).webView = newView
            resultMsg.sendToTarget()
            return true
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            customViewOwner = id
            video.fullscreen = true
            video.refresh()
            customViewCallback?.onCustomViewHidden()
            customViewCallback = callback
            _customView.value = view
        }

        override fun onHideCustomView() { hideCustomView() }

        override fun onShowFileChooser(
            webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, params: FileChooserParams,
        ): Boolean {
            val launch = fileChooser ?: return false
            return runCatching { launch(filePathCallback, params.createIntent()) }.getOrDefault(false)
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val wanted = request.resources.mapNotNull {
                when (it) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> SiteKind.CAMERA to it
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> SiteKind.MICROPHONE to it
                    else -> null // DRM, MIDI etc. stay denied
                }
            }
            if (wanted.isEmpty()) { request.deny(); return }
            val origin = request.origin.toString().trimEnd('/')
            handleSiteRequest(id, origin, wanted.map { it.first }.distinct()) { ok ->
                if (ok) request.grant(wanted.map { it.second }.toTypedArray()) else request.deny()
            }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            handleSiteRequest(id, origin.trimEnd('/'), listOf(SiteKind.LOCATION)) { ok -> callback.invoke(origin, ok, false) }
        }

        override fun onConsoleMessage(m: ConsoleMessage): Boolean = true
    }
}
