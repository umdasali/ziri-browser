package com.ziribrowser.ui.browser

import androidx.compose.material.icons.rounded.MenuBook
import com.ziribrowser.ziriString
import com.ziribrowser.R
import androidx.compose.ui.res.stringResource
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.ziribrowser.browser.FindState
import com.ziribrowser.ui.SuggestionsPanel
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import com.ziribrowser.ui.GlassSurface
import androidx.compose.ui.text.font.FontWeight
import com.ziribrowser.ui.rememberSuggestions
import com.ziribrowser.AppContainer
import com.ziribrowser.browser.UrlUtils
import com.ziribrowser.data.BookmarkEntity
import com.ziribrowser.data.QuickAccessItem
import com.ziribrowser.data.SearchEngine
import com.ziribrowser.download.DownloadRequest
import com.ziribrowser.download.FileUtils
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.asImageBitmap
import com.ziribrowser.browser.PrivateProfile
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun BrowserScreen(container: AppContainer, onClose: () -> Unit, onNavigate: (String) -> Unit) {
    val tabsMgr = container.tabs
    val tabs by tabsMgr.tabs.collectAsStateWithLifecycle()
    val activeId by tabsMgr.activeId.collectAsStateWithLifecycle()
    val customView by tabsMgr.customView.collectAsStateWithLifecycle()
    val hit by tabsMgr.hitTarget.collectAsStateWithLifecycle()
    val engine by container.settings.searchEngine.collectAsStateWithLifecycle(SearchEngine.GOOGLE)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val linkCopiedText = stringResource(R.string.link_copied)
    val bookmarkAddedText = stringResource(R.string.bookmark_added)
    val tab = tabs.firstOrNull { it.id == activeId }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    if (tab == null) {
        // No tab open (e.g. the last one was closed): start a blank one so the user can type an address.
        androidx.compose.runtime.LaunchedEffect(Unit) {
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) tabsMgr.openTab(null)
        }
        return
    }

    val bookmarked by remember(tab.url) { container.db.bookmarks().isBookmarked(tab.url) }
        .collectAsStateWithLifecycle(false)

    // The video controller only polls the page while the browser is on screen.
    androidx.compose.runtime.DisposableEffect(Unit) {
        tabsMgr.video.browserVisible = true
        tabsMgr.video.refresh()
        onDispose { tabsMgr.video.browserVisible = false; tabsMgr.video.refresh() }
    }

    BackHandler(enabled = customView != null) { tabsMgr.hideCustomView() }
    BackHandler(enabled = customView == null && tab.canGoBack) { tabsMgr.goBack(tab.id) }
    // A tab a link opened has nothing to go back to: back closes it and lands on the page that link was on.
    val openerOpen = tab.openerId != null && tabs.any { it.id == tab.openerId }
    BackHandler(enabled = customView == null && !tab.canGoBack && openerOpen) { tabsMgr.closeTab(tab.id) }
    // A warning page (certificate problem / plain http) is up: Back leaves it, instead of navigating the page hidden behind it.
    BackHandler(enabled = customView == null && (tab.insecure != null || tab.sslProblem != null)) {
        if (tab.sslProblem != null) tabsMgr.sslBack(tab.id) else tabsMgr.leaveInsecure(tab.id)
    }

    // ---- fullscreen video -------------------------------------------------------------------
    val cv = customView
    if (cv != null) {
        val view = LocalView.current
        DisposableEffect(cv) {
            val window = (context as? Activity)?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
        }
        // The player is drawn on top further down. The page itself must stay on screen underneath: a WebView that is detached or
        // paused stops drawing its video, which left the fullscreen player black with only the controls.
    }

    var tfv by remember(tab.id) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(tab.url, androidx.compose.ui.text.TextRange(0, tab.url.length))) }
    val text = tfv.text
    var menuOpen by remember { mutableStateOf(false) }
    var blockDialog by remember { mutableStateOf(false) }
    var pageFiles by remember { mutableStateOf<List<String>?>(null) }
    val blockedCounts by tabsMgr.blocked.collectAsStateWithLifecycle()
    val blockCfg by container.settings.blocking.collectAsStateWithLifecycle(com.ziribrowser.adblock.BlockingConfig())
    var focused by remember { mutableStateOf(false) }
    var findOpen by remember(tab.id) { mutableStateOf(false) }
    // Reader view: the article pulled out of this tab's page, shown over the browser.
    var reader by remember(tab.id) { mutableStateOf<com.ziribrowser.reader.Article?>(null) }
    val readerUnavailableText = stringResource(R.string.reader_unavailable)
    androidx.activity.compose.BackHandler(enabled = reader != null) { reader = null }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val suggestions by rememberSuggestions(container, if (focused) text else "", engine)
    val find by tabsMgr.find.collectAsStateWithLifecycle()
    val prompt by tabsMgr.permissionPrompt.collectAsStateWithLifecycle()
    var showConnection by remember { mutableStateOf(false) }
    var showMedia by remember { mutableStateOf(false) }
    val detected by tabsMgr.detectedMedia.collectAsStateWithLifecycle()

    BackHandler(enabled = findOpen) { tabsMgr.stopFind(tab.id); findOpen = false }
    BackHandler(enabled = focused && !findOpen) { focusManager.clearFocus() }

    // A blank tab opens ready to type in.
    val addressFocus = remember { androidx.compose.ui.focus.FocusRequester() }

    fun go(url: String) {
        focusManager.clearFocus()
        tabsMgr.navigate(tab.id, url)
    }

    // ---- immersive chrome: bars hide while scrolling down, return when scrolling up ----------------
    val chromeHidden by tabsMgr.chromeHidden.collectAsStateWithLifecycle()
    val torState by tabsMgr.torState.collectAsStateWithLifecycle()
    val torReady = torState is com.ziribrowser.tor.TorState.Ready
    val torOnForAll = torState !is com.ziribrowser.tor.TorState.Off && !tab.isTor
    // A tab a link opened is about to load that link, so it must not grab the keyboard like a blank new tab does.
    var editing by remember(tab.id) { mutableStateOf(tab.url.isEmpty() && tab.openerId == null) }
    var hadFocus by remember { mutableStateOf(false) }
    val barsVisible = !chromeHidden || focused || editing || findOpen || menuOpen || tab.sslProblem != null || tab.insecure != null || tab.url.isEmpty()
    val topPad by androidx.compose.animation.core.animateDpAsState(if (barsVisible) 56.dp else 0.dp, androidx.compose.animation.core.tween(200), label = "topPad")
    val bottomPad by androidx.compose.animation.core.animateDpAsState(if (barsVisible) 60.dp else 0.dp, androidx.compose.animation.core.tween(200), label = "bottomPad")
    val pageView = LocalView.current
    DisposableEffect(barsVisible) {
        val window = (context as? Activity)?.window
        val c = window?.let { WindowCompat.getInsetsController(it, pageView) }
        c?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (barsVisible) c?.show(WindowInsetsCompat.Type.systemBars()) else c?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose {
            val window = (context as? Activity)?.window
            window?.let { WindowCompat.getInsetsController(it, pageView).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }
    androidx.compose.runtime.LaunchedEffect(editing) { if (editing) runCatching { addressFocus.requestFocus() } }
    androidx.compose.runtime.LaunchedEffect(tab.url) { if (tab.url.isNotEmpty() && !focused) editing = false }
    // While the address bar is not being edited it shows the page address; typing is never overwritten by a loading page.
    androidx.compose.runtime.LaunchedEffect(tab.url, focused) { if (!focused) tfv = androidx.compose.ui.text.input.TextFieldValue(tab.url, androidx.compose.ui.text.TextRange(0, tab.url.length)) }

    Box(Modifier.fillMaxSize().imePadding()) {
        // ---- page: the whole screen, edge to edge ------------------------------------------------
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(top = topPad, bottom = bottomPad)
                .background(MaterialTheme.colorScheme.background),
        ) {
            // While a certificate warning is up the page is not shown at all (and not drawn behind the warning).
            if (tab.isTor && !torReady) {
                TorConnecting(torState, onRetry = { tabsMgr.retryTor() })
            } else if (tab.insecure != null) {
                InsecureWarning(tab.insecure!!.host, onBack = { tabsMgr.leaveInsecure(tab.id) }, onProceed = { tabsMgr.proceedInsecure(tab.id) })
            } else if (tab.url.isNotEmpty() && tab.sslProblem == null) {
                key(tab.id, tab.webViewEpoch) {
                    val pullEnabled by container.settings.pullToRefresh.collectAsStateWithLifecycle(true)
                    // The spinner is shown only for the moment of the pull; the page's own progress bar takes over once it reloads.
                    var pulling by remember { mutableStateOf(false) }
                    val web: @Composable () -> Unit = {
                        AndroidView(
                            factory = { ctx -> tabsMgr.attach(tab.id, ctx) ?: android.view.View(ctx) },
                            modifier = Modifier.fillMaxSize(),
                            onRelease = { tabsMgr.detach(tab.id) },
                        )
                    }
                    if (pullEnabled) androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                        isRefreshing = pulling,
                        onRefresh = { pulling = true; tabsMgr.reload(tab.id); scope.launch { kotlinx.coroutines.delay(700); pulling = false } },
                        modifier = Modifier.fillMaxSize(),
                    ) { web() } else web()
                }
            } else {
                Text(stringResource(R.string.enter_a_search_or_address_above), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center))
            }
            // UC-style floating button: appears when the page has loaded a video or audio file.
            val mediaCount = detected[tab.id]?.size ?: 0
            if (mediaCount > 0 && tab.sslProblem == null) {
                GlassSurface(
                    Modifier.align(Alignment.BottomEnd).padding(16.dp), shape = androidx.compose.foundation.shape.CircleShape,
                    strong = true, highlight = true, onClick = { showMedia = true },
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Download, stringResource(R.string.download_video), tint = MaterialTheme.colorScheme.primary)
                        Text("  " + stringResource(R.string.download_video_2, mediaCount), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            tab.sslProblem?.let { problem ->
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    CertificateWarning(problem, onBack = { tabsMgr.sslBack(tab.id) }, onProceed = { tabsMgr.sslProceed(tab.id) })
                }
            }
            if (focused && !findOpen && text != tab.url) {
                SuggestionsPanel(
                    suggestions, onPick = { tfv = androidx.compose.ui.text.input.TextFieldValue(it.url); go(it.url) },
                    modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        
            if (tab.loading) {
                LinearProgressIndicator(
                    progress = { tab.progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter),
                )
            }
        }

        // ---- top bar: slim address bar (site name when not editing) -----------------------------
        AnimatedVisibility(
            barsVisible, Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(),
        ) {
            Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                if (findOpen) FindBar(
            matches = find,
            onQuery = { tabsMgr.findInPage(tab.id, it) },
            onNext = { tabsMgr.findNext(tab.id, it) },
            onClose = { tabsMgr.stopFind(tab.id); findOpen = false },
        )
                else GlassSurface(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = androidx.compose.foundation.shape.CircleShape, strong = true,
                ) {
                    Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SecurityIndicator(if (focused) SecurityLevel.NONE else tab.securityLevel()) { showConnection = true }
                        if (editing) {
                            BasicTextField(
                                value = tfv, onValueChange = { tfv = it }, singleLine = true,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).focusRequester(addressFocus).onFocusChanged { s ->
                                    if (s.isFocused) { focused = true; hadFocus = true }
                                    else { focused = false; if (hadFocus) { hadFocus = false; if (tab.url.isNotEmpty()) editing = false } }
                                },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = { if (text.isNotBlank()) go(UrlUtils.resolve(text, engine)) }),
                                decorationBox = { inner ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (text.isEmpty()) Text(stringResource(R.string.search_or_type_url), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                        inner()
                                    }
                                },
                            )
                        } else {
                            Text(
                                UrlUtils.host(tab.url).removePrefix("www.").ifEmpty { tab.url },
                                Modifier.weight(1f).clickable(role = androidx.compose.ui.semantics.Role.Button) { editing = true }.padding(horizontal = 8.dp, vertical = 10.dp),
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
                            )
                        }
                        if (torOnForAll) Icon(Icons.Rounded.Shield, stringResource(R.string.tor_is_on_every_tab_is_using_tor_while), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp).size(20.dp))
                        if (tab.isPrivate) Icon(if (tab.isTor) Icons.Rounded.Shield else Icons.Rounded.VisibilityOff, if (tab.isTor) stringResource(R.string.tor_tab) else stringResource(R.string.private_tab), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp).size(20.dp))
                    }
                }
DownloadStrip(container) { onNavigate("downloads") }
        tab.notice?.let { note ->
            Text(note, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
        }
        tab.error?.let { msg ->
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(msg, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton({ tabsMgr.reloadOrStop(tab.id) }) { Text(stringResource(R.string.reload)) }
            }
        }


            }
        }

        // ---- bottom bar --------------------------------------------------------------------------
        AnimatedVisibility(
            barsVisible, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
        ) {
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
        // ---- bottom bar ---------------------------------------------------------------------
        GlassSurface(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            shape = androidx.compose.foundation.shape.CircleShape, strong = true,
        ) {
        Row(Modifier.height(48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val weighted = Modifier.weight(1f)
            IconButton({ tabsMgr.goBack(tab.id) }, weighted, enabled = tab.canGoBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
            IconButton({ tabsMgr.goForward(tab.id) }, weighted, enabled = tab.canGoForward) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.forward)) }
            // Back to the Ziri home screen; the page stays open as a tab ("Continue browsing").
            IconButton(onClose, weighted) { Icon(Icons.Rounded.Home, stringResource(R.string.home), tint = MaterialTheme.colorScheme.primary) }
            IconButton({ tabsMgr.reloadOrStop(tab.id) }, weighted) {
                Icon(if (tab.loading) Icons.Rounded.Close else Icons.Rounded.Refresh, if (tab.loading) stringResource(R.string.stop) else stringResource(R.string.reload))
            }
            IconButton({ tabsMgr.captureThumbnail(tab.id); onNavigate("tabs") }, weighted) {
                // The classic tab-count badge: a rounded square with the number inside.
                Box(
                    Modifier.size(26.dp).border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text("${tabs.size}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
            }
            Box(weighted, contentAlignment = Alignment.Center) {
                IconButton({ menuOpen = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.menu)) }
                DropdownMenu(
                    menuOpen, { menuOpen = false },
                    shape = RoundedCornerShape(22.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    @Composable
                    fun item(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit) =
                        DropdownMenuItem({ Text(label) }, { menuOpen = false; action() }, leadingIcon = { Icon(icon, null) })
                    item(stringResource(R.string.new_tab), Icons.Rounded.Home) { tabsMgr.openTab(null) }
                    item(stringResource(R.string.new_private_tab), Icons.Rounded.VisibilityOff) { tabsMgr.openTab(null, isPrivate = true) }
                    item(if (bookmarked) stringResource(R.string.remove_bookmark) else stringResource(R.string.add_bookmark),
                        if (bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder) {
                        scope.launch {
                            if (bookmarked) container.db.bookmarks().deleteByUrl(tab.url)
                            else {
                                container.db.bookmarks().insert(BookmarkEntity(title = tab.title.ifEmpty { tab.url }, url = tab.url))
                                Toast.makeText(context, bookmarkAddedText, Toast.LENGTH_SHORT).show()
                                // Fetch the site's icon from its own tags so the bookmark shows a real favicon (skipped for private tabs).
                                if (!tab.isPrivate) container.siteMeta.ensure(tab.url)
                            }
                        }
                    }
                    item(if (tab.desktop) stringResource(R.string.request_mobile_site) else stringResource(R.string.request_desktop_site),
                        if (tab.desktop) Icons.Rounded.PhoneAndroid else Icons.Rounded.Computer) { tabsMgr.toggleDesktop(tab.id) }
                    if (UrlUtils.isHttp(tab.url)) {
                        val n = blockedCounts[tab.id] ?: 0
                        item(if (n > 0) stringResource(R.string.ad_amp_tracker_blocking_blocked, n) else stringResource(R.string.ad_amp_tracker_blocking), Icons.Rounded.Shield) { blockDialog = true }
                    }
                    item(stringResource(R.string.find_in_page), Icons.Rounded.Search) { findOpen = true }
                    if (UrlUtils.isHttp(tab.url)) item(stringResource(R.string.reader_view), Icons.Rounded.MenuBook) {
                        tabsMgr.extractArticle(tab.id) { found ->
                            if (found != null) reader = found else Toast.makeText(context, readerUnavailableText, Toast.LENGTH_SHORT).show()
                        }
                    }
                    if (UrlUtils.isHttp(tab.url)) item(stringResource(R.string.print_page), Icons.Rounded.Print) {
                        tabsMgr.printPage(tab.id, context, tab.title.ifEmpty { UrlUtils.host(tab.url) })
                    }
                    if (UrlUtils.isHttp(tab.url)) item(stringResource(R.string.share), Icons.Rounded.Share) {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, tab.title)
                            putExtra(Intent.EXTRA_TEXT, tab.url)
                        }
                        context.startActivity(Intent.createChooser(send, ziriString(R.string.share_link)))
                    }
                    if (UrlUtils.isHttp(tab.url)) item(stringResource(R.string.copy_link), Icons.Rounded.ContentCopy) {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("link", tab.url))
                        Toast.makeText(context, linkCopiedText, Toast.LENGTH_SHORT).show()
                    }
                    if (UrlUtils.isHttp(tab.url)) item(stringResource(R.string.add_to_quick_access), Icons.Rounded.Add) {
                        scope.launch { container.settings.addQuickAccess(QuickAccessItem(tab.title.ifEmpty { UrlUtils.host(tab.url) }, tab.url)) }
                    }
                    if (UrlUtils.isHttp(tab.url)) item(stringResource(R.string.save_pictures_amp_files_from_page), Icons.Rounded.Download) {
                        tabsMgr.pageFiles(tab.id) { found -> pageFiles = found }
                    }
                    item(stringResource(R.string.downloads), Icons.Rounded.Download) { onNavigate("downloads") }
                    item(stringResource(R.string.bookmarks), Icons.Rounded.Bookmark) { onNavigate("bookmarks") }
                    item(stringResource(R.string.history), Icons.Rounded.History) { onNavigate("history") }
                    item(stringResource(R.string.settings), Icons.Rounded.Settings) { onNavigate("settings") }
                    item(stringResource(R.string.close_this_tab), Icons.Rounded.Close) { tabsMgr.closeTab(tab.id) }
                }
            }
        }
        }
            }
        }

        // ---- saved logins: "Sign in as …" and "Save password?" ---------------------------------------------
        PasswordPrompts(
            container, tab,
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = if (barsVisible) 74.dp else 12.dp),
        )

        // ---- while the bars are hidden: tap the top edge or the handle at the bottom to bring them back ---
        if (!barsVisible) {
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(22.dp).clickable(role = androidx.compose.ui.semantics.Role.Button) { tabsMgr.showChrome() })
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).size(width = 56.dp, height = 18.dp).clickable(role = androidx.compose.ui.semantics.Role.Button) { tabsMgr.showChrome() },
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(width = 40.dp, height = 4.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))) }
        }

        // ---- reader view, on top of everything else in the browser -------------------------------------------
        reader?.let { art ->
            ReaderView(container, art, tab.isPrivate, onClose = { reader = null }, onOpenLink = { url -> reader = null; tabsMgr.navigate(tab.id, url) })
        }

        // ---- fullscreen video, above everything --------------------------------------------------------------
        if (cv != null) FullscreenVideo(container, cv, onExit = { tabsMgr.hideCustomView() })
    }

    // ---- long-press on a link or image ----------------------------------------------------------
    hit?.let { target ->
        val link = target.linkUrl ?: target.imageUrl.orEmpty()
        AlertDialog(
            onDismissRequest = { tabsMgr.clearHitTarget() },
            title = { Text(if (target.imageUrl != null) stringResource(R.string.image) else stringResource(R.string.link)) },
            text = { Text(link, maxLines = 3) },
            confirmButton = {
                TextButton({
                    tabsMgr.clearHitTarget()
                    container.requestDownload(
                        DownloadRequest(
                            url = link, referer = tab.url, isPrivate = tab.isPrivate,
                            cookie = PrivateProfile.cookieFor(link, tab.isPrivate),
                            userAgent = android.webkit.WebSettings.getDefaultUserAgent(context),
                        ),
                    )
                }) { Text(stringResource(R.string.download)) }
            },
            dismissButton = {
                Row {
                    if (target.linkUrl != null) {
                        TextButton({ tabsMgr.clearHitTarget(); tabsMgr.openTab(link, tab.isPrivate, isTor = tab.isTor, openerId = tab.id) }) { Text(stringResource(R.string.new_tab)) }
                    }
                    TextButton({
                        tabsMgr.clearHitTarget()
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("link", link))
                    }) { Text(stringResource(R.string.copy)) }
                }
            },
        )
    }

    if (showMedia) MediaSniffSheet(container, tab, onDismiss = { showMedia = false }, onOpenViewer = { showMedia = false; onNavigate("viewer/$it") })
    if (blockDialog) {
        val host = UrlUtils.rawHost(tab.url)
        val key = container.blocking.siteKey(host)
        val site = container.blocking.siteFor(host) ?: com.ziribrowser.adblock.SiteBlock()
        fun save(v: com.ziribrowser.adblock.SiteBlock) { scope.launch { container.settings.setBlockSite(key, v); kotlinx.coroutines.delay(250); tabsMgr.reload(tab.id) } }
        AlertDialog(
            onDismissRequest = { blockDialog = false },
            title = { Text(key) },
            text = {
                Column {
                    Text(
                        "${blockedCounts[tab.id] ?: 0} requests blocked on this page" + if (!blockCfg.ads || !blockCfg.trackers) "\nSome blocking is also off in Settings." else "",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.block_ads), Modifier.weight(1f))
                        androidx.compose.material3.Switch(site.ads, { save(site.copy(ads = it)) })
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.block_trackers), Modifier.weight(1f))
                        androidx.compose.material3.Switch(site.trackers, { save(site.copy(trackers = it)) })
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.fingerprint_protection), Modifier.weight(1f))
                        androidx.compose.material3.Switch(site.shield, { save(site.copy(shield = it)) })
                    }
                    Text(stringResource(R.string.the_page_reloads_when_you_change_a_swi), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton({ blockDialog = false }) { Text(stringResource(R.string.done)) } },
        )
    }
    pageFiles?.let { found ->
        PageFilesDialog(container, found, tab.url, tab.isPrivate, onDismiss = { pageFiles = null })
    }
    if (showConnection) ConnectionDialog(tab, tabsMgr.connectionInfo(tab.id)) { showConnection = false }

    prompt?.let { p ->
        var remember by remember(p) { mutableStateOf(false) }
        val what = p.kinds.joinToString(" and ") { it.label }
        AlertDialog(
            onDismissRequest = { p.resolve(false, false) },
            title = { Text(stringResource(R.string.allow_2, what)) },
            text = {
                Column {
                    Text("${UrlUtils.host(p.origin)} wants to use your $what.")
                    if (p.canRemember) {
                        Row(
                            Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Checkbox) { remember = !remember }.padding(top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(remember, { remember = it })
                            Text(stringResource(R.string.remember_my_choice_for_this_site))
                        }
                    }
                }
            },
            confirmButton = { TextButton({ p.resolve(true, remember) }) { Text(stringResource(R.string.allow)) } },
            dismissButton = { TextButton({ p.resolve(false, remember) }) { Text(stringResource(R.string.block)) } },
        )
    }

    DownloadPrompts(container, onOpenViewer = { onNavigate("viewer/$it") })
}

@Composable
private fun FindBar(matches: FindState, onQuery: (String) -> Unit, onNext: (Boolean) -> Unit, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value = query, onValueChange = { query = it; onQuery(it) },
            modifier = Modifier.weight(1f).focusRequester(focus),
            singleLine = true, shape = androidx.compose.foundation.shape.CircleShape,
            placeholder = { Text(stringResource(R.string.find_in_page)) },
            trailingIcon = {
                if (query.isNotEmpty()) Text(
                    if (matches.total == 0) "0" else "${matches.active}/${matches.total}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp),
                )
            },
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onNext(true) }),
        )
        IconButton({ onNext(false) }, enabled = matches.total > 0) { Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.previous_match)) }
        IconButton({ onNext(true) }, enabled = matches.total > 0) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.next_match)) }
        IconButton(onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.close_find)) }
    }
}


/** Dark purple palette used while a private (incognito) tab is on screen. */
private fun incognitoColors() = androidx.compose.material3.darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFC4B5FD), onPrimary = androidx.compose.ui.graphics.Color(0xFF2A1B5A),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF3B2D70), onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFE6DEFF),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF332A55), onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFE6DEFF),
    tertiary = androidx.compose.ui.graphics.Color(0xFFF0ABFC),
    background = androidx.compose.ui.graphics.Color(0xFF14101F), onBackground = androidx.compose.ui.graphics.Color(0xFFECE8F7),
    surface = androidx.compose.ui.graphics.Color(0xFF14101F), onSurface = androidx.compose.ui.graphics.Color(0xFFECE8F7),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF2A2340), onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFB9B0D0),
    surfaceContainerLowest = androidx.compose.ui.graphics.Color(0xFF0E0A18), surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF1A1428),
    surfaceContainer = androidx.compose.ui.graphics.Color(0xFF1F1830), surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF282040),
    surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF322950),
    outline = androidx.compose.ui.graphics.Color(0xFF7D7399), outlineVariant = androidx.compose.ui.graphics.Color(0xFF3A3256),
    error = androidx.compose.ui.graphics.Color(0xFFF87171),
)

/**
 * The browser screen. While a private (incognito) tab is showing, the whole screen switches to a dark
 * purple look so it is always obvious which mode you are in.
 */
@Composable
fun BrowserRoute(container: AppContainer, onClose: () -> Unit, onNavigate: (String) -> Unit) {
    val tabs by container.tabs.tabs.collectAsStateWithLifecycle()
    val activeId by container.tabs.activeId.collectAsStateWithLifecycle()
    val isPrivate = tabs.firstOrNull { it.id == activeId }?.isPrivate == true
    androidx.compose.runtime.DisposableEffect(isPrivate) {
        container.forceDarkBars.value = isPrivate
        onDispose { container.forceDarkBars.value = false }
    }
    if (!isPrivate) { BrowserScreen(container, onClose, onNavigate); return }
    androidx.compose.material3.MaterialTheme(colorScheme = incognitoColors(), typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        androidx.compose.material3.Surface(
            color = androidx.compose.ui.graphics.Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier.fillMaxSize().background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color(0xFF1E1638), androidx.compose.ui.graphics.Color(0xFF0F0B1C))),
                ),
            ) { BrowserScreen(container, onClose, onNavigate) }
        }
    }
}

/** Shown on a Tor tab until Tor has connected (the page isn't loaded before that, so nothing goes out over the normal connection). */
@Composable
private fun TorConnecting(state: com.ziribrowser.tor.TorState, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Shield, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        when (state) {
            is com.ziribrowser.tor.TorState.Failed -> {
                Text(stringResource(R.string.couldn_t_connect_to_tor), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                Text(state.reason, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                androidx.compose.material3.Button(onRetry, Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.try_again)) }
            }
            else -> {
                val pct = (state as? com.ziribrowser.tor.TorState.Connecting)?.percent
                Text(if (pct != null) stringResource(R.string.connecting_to_tor, pct) else stringResource(R.string.starting_tor), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                if (pct != null) LinearProgressIndicator({ pct / 100f }, Modifier.fillMaxWidth(0.6f).padding(top = 14.dp)) else LinearProgressIndicator(Modifier.fillMaxWidth(0.6f).padding(top = 14.dp))
            }
        }
        Text(
            stringResource(R.string.pages_open_over_tor_so_sites_see_a_tor_e) + " " +
                "and while a Tor tab is open every tab uses Tor. Camera, microphone and location are always blocked in Tor tabs.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 20.dp),
        )
    }
}

/** Lists the pictures and files found on the page so several can be saved at once. */
@Composable
private fun PageFilesDialog(container: AppContainer, files: List<String>, pageUrl: String, isPrivate: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var chosen by remember(files) { mutableStateOf(files.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (files.isEmpty()) stringResource(R.string.nothing_to_save) else stringResource(R.string.save_from_this_page, chosen.size, files.size)) },
        text = {
            if (files.isEmpty()) Text(stringResource(R.string.no_pictures_or_files_were_found_on_thi))
            else androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 380.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Checkbox) { chosen = if (chosen.size == files.size) emptySet() else files.toSet() }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(chosen.size == files.size, { chosen = if (it) files.toSet() else emptySet() })
                        Text(stringResource(R.string.select_all), fontWeight = FontWeight.SemiBold)
                    }
                }
                items(files.size) { i ->
                    val u = files[i]
                    val thumb by com.ziribrowser.ui.rememberRemoteImage(container, u.takeIf { Regex("\\.(jpe?g|png|gif|webp)([?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(it) })
                    Row(Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Checkbox) { chosen = if (u in chosen) chosen - u else chosen + u }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(u in chosen, { chosen = if (it) chosen + u else chosen - u })
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            thumb?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                        }
                        Text(
                            FileUtils.sanitizeFilename(u.substringBefore('?').substringAfterLast('/').ifEmpty { UrlUtils.host(u) }),
                            Modifier.padding(start = 10.dp), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                onDismiss()
                scope.launch {
                    var started = 0
                    chosen.forEach { u ->
                        val r = container.downloads.enqueue(DownloadRequest(url = u, referer = pageUrl, cookie = PrivateProfile.cookieFor(u, isPrivate), userAgent = android.webkit.WebSettings.getDefaultUserAgent(context), isPrivate = isPrivate))
                        if (r is com.ziribrowser.download.EnqueueResult.Started) started++
                    }
                    Toast.makeText(context, if (started > 0) ziriString(R.string.saving_files, started) else ziriString(R.string.nothing_new_to_save), Toast.LENGTH_SHORT).show()
                }
            }, enabled = chosen.isNotEmpty()) { Text(stringResource(R.string.save_2, chosen.size)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
