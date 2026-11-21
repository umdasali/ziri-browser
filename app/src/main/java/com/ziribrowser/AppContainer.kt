package com.ziribrowser

import android.app.Application
import com.ziribrowser.browser.TabsManager
import com.ziribrowser.data.AppDatabase
import com.ziribrowser.data.SettingsRepository
import com.ziribrowser.download.DownloadEngine
import com.ziribrowser.download.DownloadNotifications
import com.ziribrowser.download.DownloadRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.withContext

/** Hand-rolled dependency container: one instance for the whole process. */
class AppContainer(app: Application) {
    // A failed background job (full disk, damaged store) must not kill the app; it is dropped and the rest keeps running.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> android.util.Log.w("Ziri", "background task failed", e) })
    val db = AppDatabase.create(app)
    val settings = SettingsRepository(app)
    val downloads = DownloadEngine(app, db.downloads(), scope)

    /** Tor, declared first so the shared HTTP client below can follow it. */
    val tor = com.ziribrowser.tor.TorManager(app, scope)

    /**
     * One shared HTTP client for site previews, feeds, images and list updates: short timeouts, no downgrade from https to
     * http, and while Tor is on it goes through Tor too (or fails), so Ziri's own requests never reveal your address.
     */
    val http: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(90, java.util.concurrent.TimeUnit.SECONDS) // a server that drips bytes can't hold a request (and the feed refresh lock) forever
        .proxySelector(object : java.net.ProxySelector() {
            override fun select(uri: java.net.URI?): List<java.net.Proxy> = listOf(if (tor.wanted) tor.downloadProxy() ?: java.net.Proxy.NO_PROXY else java.net.Proxy.NO_PROXY)
            override fun connectFailed(uri: java.net.URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) {}
        })
        .addInterceptor { chain ->
            if (tor.wanted && tor.downloadProxy() == null) throw java.io.IOException("Tor isn't connected, so this request was held back")
            val response = chain.proceed(chain.request())
            if (chain.request().isHttps && !response.request.isHttps) { response.close(); throw java.io.IOException("Blocked a redirect from https to http") }
            response
        }
        .build()
    val siteMeta = com.ziribrowser.web.SiteMetaRepository(app, http)
    val images = com.ziribrowser.web.RemoteImages(app, http)
    val feed = com.ziribrowser.feed.FeedRepository(app, http, siteMeta, scope)
    val blocking = com.ziribrowser.adblock.BlockingManager(app, scope, settings) { http }
    val players = com.ziribrowser.ui.viewer.PlayerHolder(app, this)
    /** Set just before opening the player on a list of videos (the folder or download list being browsed). */
    @Volatile var pendingQueue: com.ziribrowser.ui.viewer.PlayQueue? = null

    private val _pendingDownload = MutableStateFlow<DownloadRequest?>(null)
    /** A download the page asked for, waiting for the user's confirmation. */
    val pendingDownload: StateFlow<DownloadRequest?> = _pendingDownload
    fun requestDownload(r: DownloadRequest) { _pendingDownload.value = r }
    fun clearPendingDownload() { _pendingDownload.value = null }

    // A Channel (not a SharedFlow) so a request made before the UI starts listening, e.g. a link that
    // launches the app from closed, is kept until the UI is ready instead of being dropped.
    private val _navRequests = Channel<String>(Channel.BUFFERED)
    /** Screens other parts of the app ask the UI to open (e.g. "browser", "downloads"). */
    val navRequests: Flow<String> = _navRequests.receiveAsFlow()
    fun navigate(route: String) { _navRequests.trySend(route) }

    /** True while a private tab is on screen, so the status/navigation bar icons switch to light-on-dark. */
    val forceDarkBars = MutableStateFlow(false)
    /** True while a screen showing secrets (the saved passwords) is up: no screenshots, blank recents thumbnail. */
    val secureScreen = MutableStateFlow(false)

    /** True while the activity is shown as a floating Picture-in-Picture window. */
    val pipMode = MutableStateFlow(false)
    /** Set by MainActivity: shrinks the app into the PiP window. */
    var enterPip: (() -> Unit)? = null

    val passwords = com.ziribrowser.passwords.PasswordStore(db.credentials(), com.ziribrowser.passwords.Vault.box)
    val passwordBridge = com.ziribrowser.passwords.PasswordBridge(passwords, settings, scope)
    val tabs = TabsManager(app, db.tabs(), db.history(), settings, scope, blocking, tor, onDownload = ::requestDownload, passwords = passwordBridge)

    init {
        tabs.video.onPlayingVisible = {
            // Shows the media notification (play/pause, ±10s). May be refused if the app isn't visible; that's fine.
            runCatching { androidx.core.content.ContextCompat.startForegroundService(app, android.content.Intent(app, com.ziribrowser.browser.MediaPlaybackService::class.java)) }
        }
        scope.launch { settings.rememberVideo.collect { tabs.video.rememberPositions = it } }
        blocking.start()
        // While Tor is on, Ziri's own downloads go through it too (and wait if it isn't connected yet).
        downloads.torGate = { tor.downloadProxy() }
        downloads.torWanted = { tor.wanted }
        DownloadNotifications.ensureChannels(app)
        scope.launch { settings.httpsFirst.collect { tabs.httpsFirst = it } }
        scope.launch { settings.httpsOnly.collect { tabs.httpsOnly = it } }
        scope.launch {
            val a = combine(settings.fingerprintShield, settings.blockThirdPartyCookies, settings.stripTrackingParams, settings.sendGpc) { f, c, s, g -> listOf(f, c, s, g) }
            combine(a, settings.safeBrowsing, settings.blocking) { x, sb, b ->
                com.ziribrowser.privacy.PrivacyConfig(x[0], x[1], x[2], x[3], sb, b.sites.filterValues { !it.shield }.keys)
            }.collect { cfg -> withContext(Dispatchers.Main) { tabs.setPrivacy(cfg) } }
        }
        scope.launch { settings.dlConcurrent.collect { downloads.setMaxConcurrent(it) } }
        scope.launch { settings.dlSpeedKb.collect { downloads.speedLimitBps = it * 1024L } }
        scope.launch { settings.dlWifiOnly.collect { downloads.wifiOnly = it } }
        scope.launch { settings.dlParts.collect { downloads.maxParts = it } }
        scope.launch { settings.autoUpdateLists.collect { blocking.autoUpdate = it } }
        scope.launch { settings.fetchIcons.collect { siteMeta.enabled = it } }
        scope.launch { settings.webTextZoom.collect { z -> withContext(Dispatchers.Main) { tabs.setTextZoom(z) } } }
        scope.launch {
            runCatching { downloads.recover() }
            // Leftovers from a session that ended without a clean exit (swiped away from recents) go before any page loads.
            runCatching { clearOnExit(cookies = false) } // cookies were already cleared above, before any page could load
            runCatching { tabs.restore() }
        }
        // Cookies go first and right here, before MainActivity opens a tab for a link that started the app: wiping them later would
        // sign the person out of the very page they were just sent to.
        runCatching { if (kotlinx.coroutines.runBlocking { settings.exitClearNow() }.cookies) tabs.clearCookiesAndSiteData() }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val delayedClear = Runnable { scope.launch { runCatching { clearOnExit() } } }

    /** Called when the app leaves the screen. Android often keeps the process alive instead of closing it, so the wipe runs after a quiet spell. */
    fun scheduleClearOnExit() {
        handler.removeCallbacks(delayedClear)
        handler.postDelayed(delayedClear, EXIT_CLEAR_DELAY_MS)
    }

    /** Called when the app comes back: the person is still here, so nothing is wiped. */
    fun cancelClearOnExit() = handler.removeCallbacks(delayedClear)

    /**
     * Whether the welcome screen is finished. Someone who already used Ziri before that screen existed is marked done *before* the first value
     * is emitted, so an update never flashes the welcome screen at them.
     */
    val onboardingDone: kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.flow {
        runCatching {
            if (!settings.onboardingSeen() && (db.history().count() > 0 || db.bookmarks().count() > 0 || db.tabs().count() > 0)) settings.finishOnboarding(emptySet())
        }
        emitAll(settings.onboardingDone)
    }

    /** Wipes whatever "Clear on exit" is set to remove. Safe to call more than once. */
    suspend fun clearOnExit(cookies: Boolean = true) {
        val c = settings.exitClearNow()
        if (!c.any) return
        if (c.history) { db.history().clearAll(); settings.clearVideoPositions() }
        withContext(Dispatchers.Main) {
            if (c.cookies && cookies) tabs.clearCookiesAndSiteData()
            if (c.cache) tabs.clearCache()
        }
    }

    private companion object { const val EXIT_CLEAR_DELAY_MS = 5 * 60 * 1000L }
}
