package com.ziribrowser.ui.home

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.heightIn
import com.ziribrowser.R
import androidx.compose.ui.res.stringResource
import com.ziribrowser.ui.LocalBarClearance
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ziribrowser.AppContainer
import com.ziribrowser.browser.UrlUtils
import com.ziribrowser.data.QuickAccessItem
import com.ziribrowser.data.SearchEngine
import com.ziribrowser.ui.GlassIconButton
import com.ziribrowser.ui.GlassSurface
import com.ziribrowser.ui.SiteAvatar
import com.ziribrowser.ui.SuggestionsPanel
import com.ziribrowser.ui.rememberSuggestions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.layout
import kotlinx.coroutines.launch

private const val QUICK_COLUMNS = 4

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(container: AppContainer, onOpenBrowser: () -> Unit, onNavigate: (String) -> Unit) {
    val engine by container.settings.searchEngine.collectAsStateWithLifecycle(SearchEngine.GOOGLE)
    val quick by container.settings.quickAccess.collectAsStateWithLifecycle(emptyList())
    val tabs by container.tabs.tabs.collectAsStateWithLifecycle()
    val activeId by container.tabs.activeId.collectAsStateWithLifecycle()
    val bookmarks by remember { container.db.bookmarks().search("") }.collectAsStateWithLifecycle(emptyList())
    val visits by remember { container.db.history().recent(500) }.collectAsStateWithLifecycle(emptyList())
    val mostVisited = remember(visits) { mostVisited(visits) }
    val interests by container.settings.interests.collectAsStateWithLifecycle(emptySet())
    val showFeed by container.settings.showFeed.collectAsStateWithLifecycle(true)
    val articles by container.feed.articles.collectAsStateWithLifecycle()
    val feedLoading by container.feed.loading.collectAsStateWithLifecycle()
    // Headlines refresh only while Home is on screen, and at most every 30 minutes (cached on the device).
    androidx.compose.runtime.LaunchedEffect(interests, showFeed) { if (showFeed && interests.isNotEmpty()) container.feed.refresh(interests) }
    val forYou = remember(interests) { if (interests.isEmpty()) emptyList() else com.ziribrowser.data.Interest.popularFor(interests) }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<QuickAccessItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val suggestions by rememberSuggestions(container, if (searchFocused) query else "", engine)

    fun open(url: String) {
        container.tabs.openTab(url)
        onOpenBrowser()
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 24.dp + LocalBarClearance.current),
    ) {
        // ---- top row ---------------------------------------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ziri), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.browse_download_enjoy), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // A labelled entry to private (incognito) browsing: opens a blank private tab ready to type in.
            GlassSurface(
                shape = CircleShape, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                onClick = { container.tabs.openTab(null, isPrivate = true); onOpenBrowser() },
            ) {
                Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.VisibilityOff, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Text("  " + stringResource(R.string.private_text_2), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.width(8.dp))
            GlassSurface(
                shape = CircleShape, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                onClick = { container.tabs.openTab(null, isTor = true); onOpenBrowser() },
            ) {
                Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Shield, stringResource(R.string.tor), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Text(" " + stringResource(R.string.tor), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.width(8.dp))
            GlassIconButton(Icons.Rounded.History, stringResource(R.string.history), { onNavigate("history") })
        }

        Spacer(Modifier.height(28.dp))

        // ---- search bar with engine picker ----------------------------------------------------
        fun submit() {
            if (query.isNotBlank()) { focusManager.clearFocus(); open(UrlUtils.resolve(query, engine)); query = "" }
        }
        GlassSurface(shape = CircleShape, strong = true, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 6.dp, end = 6.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                EngineDropdown(engine) { scope.launch { container.settings.setSearchEngine(it) } }
                Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp).onFocusChanged { searchFocused = it.isFocused },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) Text(stringResource(R.string.search_or_type_url_2, engine.label), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            inner()
                        }
                    },
                )
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp).clip(CircleShape).clickable(role = androidx.compose.ui.semantics.Role.Button) { submit() }) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Search, stringResource(R.string.search), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }

        if (searchFocused && query.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            SuggestionsPanel(suggestions, onPick = { focusManager.clearFocus(); query = ""; open(it.url) })
        }

        // ---- search engine icons ----------------------------------------------------------------
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SearchEngine.entries.forEach { e ->
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(role = androidx.compose.ui.semantics.Role.Button) { scope.launch { container.settings.setSearchEngine(e) } }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    EngineIcon(e, 40.dp, selected = e == engine)
                    Text(
                        e.label.substringBefore(' '), fontSize = 10.sp, maxLines = 1,
                        fontWeight = if (e == engine) FontWeight.Bold else FontWeight.Normal,
                        color = if (e == engine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        // ---- continue browsing ----------------------------------------------------------------
        val current = tabs.firstOrNull { it.id == activeId } ?: tabs.firstOrNull()
        if (current != null && current.url.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            GlassSurface(
                shape = RoundedCornerShape(20.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                modifier = Modifier.fillMaxWidth(),
                onClick = { container.tabs.setActive(current.id); onOpenBrowser() },
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.continue_browsing), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
                        Text(current.title.ifEmpty { UrlUtils.host(current.url) }, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }

        // ---- quick access (user's own sites) --------------------------------------------------
        SectionHeader(stringResource(R.string.quick_access))
        val cells: List<QuickAccessItem?> = quick + null // null = the "Add" tile
        cells.chunked(QUICK_COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                row.forEach { item ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                        if (item == null) AddTile { adding = true }
                        else QuickTile(
                            container, item, onOpen = { open(item.url) },
                            onEdit = { editing = item },
                            onRemove = { scope.launch { container.settings.removeQuickAccess(item.url) } },
                        )
                    }
                }
                repeat(QUICK_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (quick.isEmpty()) {
            Text(stringResource(R.string.add_the_sites_you_visit_most_long_pres),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ---- most visited (from this device's history) ---------------------------------------------
        if (mostVisited.isNotEmpty()) {
            SectionHeader(stringResource(R.string.most_visited), action = stringResource(R.string.history)) { onNavigate("history") }
            mostVisited.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    row.forEach { v ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                            SiteTile(v.host.substringBeforeLast('.').substringAfterLast('.').ifEmpty { v.host }.replaceFirstChar { it.uppercase() },
                                tileColor(v.host), { open(v.origin) }, icon = { com.ziribrowser.ui.SiteIcon(container, v.origin, v.host, 52.dp) }, caption = androidx.compose.ui.res.pluralStringResource(R.plurals.visit_count, v.count, v.count))
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        // ---- popular worldwide -----------------------------------------------------------------------
        if (forYou.isEmpty()) {
            SectionHeader(stringResource(R.string.popular_worldwide), action = stringResource(R.string.personalise)) { onNavigate("settings/interests") }
            PopularSites.chunked(5).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    row.forEach { site ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) { SiteTile(site.name, Color.Transparent, { open(site.url) }, logo = site.logo) }
                    }
                }
            }
        } else {
            SectionHeader(stringResource(R.string.popular_for_you), action = stringResource(R.string.edit)) { onNavigate("settings/interests") }
            forYou.chunked(5).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    row.forEach { site ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                            SiteTile(site.name, tileColor(site.url), { open(site.url) }, icon = { com.ziribrowser.ui.SiteIcon(container, site.url, site.name, 52.dp) })
                        }
                    }
                    repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        // ---- latest headlines from the chosen interests ---------------------------------------------
        if (showFeed && interests.isNotEmpty()) {
            SectionHeader(stringResource(R.string.latest_for_you), action = stringResource(R.string.refresh)) { scope.launch { container.feed.refresh(interests, force = true) } }
            if (articles.isEmpty()) {
                Text(if (feedLoading) stringResource(R.string.loading_the_latest_headlines) else stringResource(R.string.couldn_t_load_headlines_check_your_con),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.layout { m, c -> // let the row run edge to edge past the screen padding
                        val extra = 20.dp.roundToPx()
                        val p = m.measure(c.copy(maxWidth = c.maxWidth + extra * 2))
                        layout(c.maxWidth, p.height) { p.place(-extra, 0) }
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                ) {
                    items(articles, key = { it.link }) { a -> ArticleCard(container, a) { open(a.link) } }
                }
            }
        }

        // ---- bookmarks --------------------------------------------------------------------------
        SectionHeader(stringResource(R.string.bookmarks), action = if (bookmarks.isNotEmpty()) stringResource(R.string.see_all) else null) { onNavigate("bookmarks") }
        if (bookmarks.isEmpty()) {
            Text(stringResource(R.string.nothing_here_yet_open_a_page_and_choos),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            GlassSurface(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column {
                    bookmarks.take(5).forEach { b ->
                        Row(
                            Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button) { open(b.url) }.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            com.ziribrowser.ui.SiteIcon(container, b.url, b.title.ifEmpty { b.url }, 36.dp)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(b.title.ifEmpty { b.url }, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(UrlUtils.host(b.url), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding) QuickAccessDialog(
        container = container, initial = null, bookmarks = bookmarks.map { QuickAccessItem(it.title.ifEmpty { UrlUtils.host(it.url) }, it.url) }
            .filter { b -> quick.none { it.url == b.url } },
        onDismiss = { adding = false },
        onSave = { item -> scope.launch { container.settings.addQuickAccess(item) }; adding = false },
    )
    editing?.let { old ->
        QuickAccessDialog(
            container = container, initial = old, bookmarks = emptyList(), onDismiss = { editing = null },
            onSave = { item -> scope.launch { container.settings.replaceQuickAccess(old, item) }; editing = null },
        )
    }
}

@Composable
private fun SectionHeader(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
        if (action != null) TextButton(onAction) { Text(action) }
    }
}

@Composable
private fun EngineDropdown(engine: SearchEngine, onSelect: (SearchEngine) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(CircleShape).clickable(role = androidx.compose.ui.semantics.Role.Button) { open = true }.padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EngineIcon(engine, 36.dp)
            Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.choose_search_engine), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(open, { open = false }) {
            SearchEngine.entries.forEach { e ->
                DropdownMenuItem(
                    text = { Text(e.label, fontWeight = if (e == engine) FontWeight.Bold else FontWeight.Normal) },
                    leadingIcon = { EngineIcon(e, 28.dp) },
                    trailingIcon = { if (e == engine) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = { onSelect(e); open = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickTile(container: AppContainer, item: QuickAccessItem, onOpen: () -> Unit, onEdit: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).combinedClickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onOpen, onLongClick = { menu = true })
                .padding(horizontal = 4.dp, vertical = 6.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            com.ziribrowser.ui.SiteIcon(container, item.url, item.title, 56.dp)
            Text(item.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp))
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text(stringResource(R.string.edit)) }, { menu = false; onEdit() })
            DropdownMenuItem({ Text(stringResource(R.string.remove)) }, { menu = false; onRemove() })
        }
    }
}

@Composable
private fun AddTile(onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlassSurface(shape = CircleShape, modifier = Modifier.size(56.dp)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, stringResource(R.string.add_site), tint = MaterialTheme.colorScheme.primary) }
        }
        Text(stringResource(R.string.add), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickAccessDialog(
    container: AppContainer, initial: QuickAccessItem?, bookmarks: List<QuickAccessItem>,
    onDismiss: () -> Unit, onSave: (QuickAccessItem) -> Unit,
) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var nameTouched by remember { mutableStateOf(initial != null) }
    val normalized = url.trim().let { if (it.isEmpty() || UrlUtils.isHttp(it)) it else "https://$it" }
    val valid = UrlUtils.isHttp(normalized) && normalized.substringAfter("://").isNotBlank()

    // Check the site as it is typed: read its title, description, image and icon from its own meta tags.
    var checking by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<com.ziribrowser.web.PagePreview?>(null) }
    var checked by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(normalized, valid) {
        preview = null; checked = false; checking = false
        if (!valid || !normalized.substringAfter("://").contains('.')) return@LaunchedEffect
        checking = true
        kotlinx.coroutines.delay(700) // wait for a pause in typing
        val p = container.siteMeta.preview(normalized)
        preview = p; checked = true; checking = false
        if (p != null && !nameTouched && p.title.isNotBlank()) title = p.siteName.ifBlank { p.title }.take(40)
    }
    val previewImage by com.ziribrowser.ui.rememberRemoteImage(container, preview?.image)
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) stringResource(R.string.add_quick_access) else stringResource(R.string.edit_quick_access)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it; nameTouched = true }, singleLine = true, label = { Text(stringResource(R.string.name)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it }, singleLine = true, label = { Text(stringResource(R.string.website_e_g_youtube_com)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())

                // ---- link card (from the page's own meta tags) ----
                if (checking) Text(stringResource(R.string.checking_the_site), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val p = preview
                if (p != null) {
                    GlassSurface(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column {
                            previewImage?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().height(120.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                com.ziribrowser.ui.SiteIcon(container, normalized, title.ifBlank { p.title }, 40.dp)
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(p.title.ifBlank { UrlUtils.host(normalized) }, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(stringResource(R.string.is_reachable, UrlUtils.host(p.finalUrl)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }
                } else if (checked && valid) {
                    Text(stringResource(R.string.couldn_t_read_this_site_check_the_addr), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                if (bookmarks.isNotEmpty()) {
                    Text(stringResource(R.string.or_pick_from_your_bookmarks), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        bookmarks.take(12).forEach { b ->
                            AssistChip({ title = b.title; url = b.url; nameTouched = true }, { Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Keep the icon and details we already fetched so the new tile shows its favicon right away.
                    preview?.let { p -> scope.launch { container.siteMeta.save(container.siteMeta.key(normalized), p) } }
                    onSave(QuickAccessItem(title.trim().ifEmpty { UrlUtils.host(normalized) }, normalized))
                },
                enabled = valid,
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private data class Visited(val host: String, val origin: String, val count: Int)

/** The sites visited most often in the last 500 history entries, most visited first. */
private fun mostVisited(visits: List<com.ziribrowser.data.HistoryEntity>): List<Visited> =
    visits.groupBy { UrlUtils.rawHost(it.url).removePrefix("www.") }
        .filterKeys { it.isNotEmpty() }
        .map { (host, list) ->
            val origin = runCatching { java.net.URI(list.first().url).let { "${it.scheme}://${it.host}" } }.getOrDefault(list.first().url)
            Visited(host, origin, list.size)
        }
        .sortedByDescending { it.count }
        .take(8)

private val tilePalette = listOf(0xFF2563EB, 0xFF16A34A, 0xFFEA580C, 0xFFDB2777, 0xFF7C3AED, 0xFF0D9488, 0xFFCA8A04, 0xFFDC2626)

/** A stable colour per host, so a site keeps the same tile colour every time. */
private fun tileColor(host: String) = androidx.compose.ui.graphics.Color(tilePalette[kotlin.math.abs(host.hashCode()) % tilePalette.size])
