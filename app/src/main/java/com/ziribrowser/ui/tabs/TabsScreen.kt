package com.ziribrowser.ui.tabs

import com.ziribrowser.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ziribrowser.AppContainer
import com.ziribrowser.browser.UrlUtils
import com.ziribrowser.data.SearchEngine
import com.ziribrowser.ui.EmptyState
import com.ziribrowser.ui.GlassSurface
import com.ziribrowser.ui.LocalBarClearance
import com.ziribrowser.ui.SiteAvatar
import com.ziribrowser.ui.ZiriHeader
import com.ziribrowser.ui.home.EngineIcon

/**
 * Open tabs, split into Normal and Private. The address bar at the top works even with no tabs open:
 * type a website or a search and it opens in a new tab of the mode you're looking at.
 */
@Composable
fun TabsScreen(container: AppContainer, onOpenBrowser: () -> Unit, onHome: () -> Unit = {}) {
    val mgr = container.tabs
    val tabs by mgr.tabs.collectAsStateWithLifecycle()
    val activeId by mgr.activeId.collectAsStateWithLifecycle()
    val thumbs by mgr.thumbnails.collectAsStateWithLifecycle()
    val engine by container.settings.searchEngine.collectAsStateWithLifecycle(SearchEngine.GOOGLE)

    val activeTab = tabs.firstOrNull { it.id == activeId }
    var torMode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(activeTab?.isTor == true) }
    var privateMode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(activeTab?.isPrivate == true) }
    val ofMode = tabs.filter { if (torMode) it.isTor else it.isPrivate == privateMode && !it.isTor }
    var query by remember { mutableStateOf("") }
    // Private and Tor tabs are searched too: the filter only runs on this screen and nothing is stored.
    // The search box only exists with two or more tabs; a leftover query must never keep hiding tabs once the box is gone.
    val searching = ofMode.size > 1 && query.isNotBlank()
    androidx.compose.runtime.LaunchedEffect(privateMode, torMode) { query = "" }
    val shown = if (!searching) ofMode else ofMode.filter { it.title.contains(query, true) || it.url.contains(query, true) }
    var url by remember { mutableStateOf("") }

    fun visit() {
        if (url.isBlank()) return
        mgr.openTab(UrlUtils.resolve(url, engine), isPrivate = privateMode, isTor = torMode)
        url = ""
        onOpenBrowser()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ZiriHeader(stringResource(R.string.tabs)) {
            TextButton(
                { ofMode.forEach { mgr.closeTab(it.id) }; query = ""; if (mgr.tabs.value.isEmpty()) onHome() }, // nothing left anywhere: go Home
                enabled = ofMode.isNotEmpty(),
            ) { Text(if (torMode) stringResource(R.string.close_tor) else if (privateMode) stringResource(R.string.close_private) else stringResource(R.string.close_all)) }
        }

        // ---- Normal / Private switch ------------------------------------------------------------
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            SegmentedButton(!privateMode && !torMode, { privateMode = false; torMode = false }, SegmentedButtonDefaults.itemShape(0, 3)) {
                Text(stringResource(R.string.tabs_2, tabs.count { !it.isPrivate }), maxLines = 1)
            }
            SegmentedButton(privateMode && !torMode, { privateMode = true; torMode = false }, SegmentedButtonDefaults.itemShape(1, 3), icon = {
                Icon(Icons.Rounded.VisibilityOff, null, Modifier.size(18.dp))
            }) { Text(stringResource(R.string.private_text_3, tabs.count { it.isPrivate && !it.isTor }), maxLines = 1) }
            SegmentedButton(torMode, { privateMode = true; torMode = true }, SegmentedButtonDefaults.itemShape(2, 3), icon = {
                Icon(Icons.Rounded.Shield, null, Modifier.size(18.dp))
            }) { Text(stringResource(R.string.tor_2, tabs.count { it.isTor }), maxLines = 1) }
        }

        // ---- address bar: works with zero tabs ------------------------------------------------------
        GlassSurface(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), shape = CircleShape, strong = true,
            tint = if (privateMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Unspecified,
        ) {
            Row(Modifier.padding(start = 8.dp, end = 6.dp).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                if (privateMode || torMode) {
                    Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(if (torMode) Icons.Rounded.Shield else Icons.Rounded.VisibilityOff, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                    }
                } else EngineIcon(engine, 36.dp)
                BasicTextField(
                    value = url, onValueChange = { url = it }, singleLine = true,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { visit() }),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (url.isEmpty()) Text(
                                if (torMode) stringResource(R.string.search_or_type_url_over_tor) else if (privateMode) stringResource(R.string.search_privately_or_type_url) else stringResource(R.string.search_or_type_url),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            inner()
                        }
                    },
                )
                IconButton({ visit() }, enabled = url.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.go), tint = if (url.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ---- search the open tabs, and close all but the current one --------------------------------------------
        if (ofMode.size > 1) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).heightIn(min = 40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) Text(stringResource(R.string.search_tabs), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                                inner()
                            }
                        },
                    )
                    if (query.isNotEmpty()) IconButton({ query = "" }, Modifier.size(28.dp)) { Icon(Icons.Rounded.Close, stringResource(R.string.clear), Modifier.size(16.dp)) }
                }
                val keep = ofMode.firstOrNull { it.id == activeId } ?: ofMode.first()
                TextButton({ mgr.closeOthers(keep.id); query = "" }) { Text(stringResource(R.string.close_other_tabs), maxLines = 1) }
            }
        }

        // ---- tabs (or an empty state) -----------------------------------------------------------------
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (shown.isEmpty() && searching) {
                EmptyState(stringResource(R.string.no_matching_tabs), Icons.Rounded.Search)
            } else if (shown.isEmpty()) {
                EmptyState(
                    if (torMode) stringResource(R.string.no_tor_tabs_ntype_an_address_above_to_br) else if (privateMode) stringResource(R.string.no_private_tabs) else stringResource(R.string.no_open_tabs_ntype_an_address_above_to_s),
                    if (torMode) Icons.Rounded.Shield else if (privateMode) Icons.Rounded.VisibilityOff else Icons.Rounded.Tab,
                )
            } else {
                LazyVerticalGrid(
                    GridCells.Fixed(2), Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(shown, key = { it.id }) { t ->
                        GlassSurface(
                            shape = RoundedCornerShape(20.dp), highlight = t.id == activeId,
                            tint = if (t.isPrivate) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Unspecified,
                            modifier = Modifier.heightIn(min = 110.dp).animateItem(),
                            onClick = { mgr.setActive(t.id); onOpenBrowser() },
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Box(
                                    Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val thumb = thumbs[t.id]
                                    if (thumb != null && !t.isPrivate) {
                                        Image(thumb.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                                    } else if (t.isPrivate) {
                                        Icon(if (t.isTor) Icons.Rounded.Shield else Icons.Rounded.VisibilityOff, null, tint = MaterialTheme.colorScheme.primary)
                                    } else {
                                        SiteAvatar(t.title.ifEmpty { t.url }, 40.dp)
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (t.isPrivate) Icon(Icons.Rounded.VisibilityOff, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 6.dp).size(18.dp))
                                    Text(
                                        t.title.ifEmpty { t.url.ifEmpty { stringResource(R.string.new_tab) } }, fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                    )
                                    IconButton({ mgr.closeTab(t.id) }) { Icon(Icons.Rounded.Close, stringResource(R.string.close_tab)) }
                                }
                                Text(
                                    t.url, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- always visible, even with no tabs ----------------------------------------------------------
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp + LocalBarClearance.current),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button({ mgr.openTab(null, isPrivate = privateMode, isTor = torMode); onOpenBrowser() }, Modifier.weight(1f)) {
                Icon(Icons.Rounded.Add, null); Text(if (torMode) " " + stringResource(R.string.new_tor_tab) else if (privateMode) " " + stringResource(R.string.new_private_tab) else " " + stringResource(R.string.new_tab))
            }
            FilledTonalButton({ torMode = false; privateMode = !privateMode }, Modifier.weight(1f)) {
                Icon(if (privateMode) Icons.Rounded.Tab else Icons.Rounded.VisibilityOff, null)
                Text(if (privateMode) " " + stringResource(R.string.normal_tabs) else " " + stringResource(R.string.private_tabs))
            }
        }
    }
}
