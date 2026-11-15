package com.ziribrowser.ui.settings

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.ziribrowser.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ziribrowser.AppContainer
import com.ziribrowser.browser.UrlUtils
import com.ziribrowser.ui.GlassSurface
import com.ziribrowser.ui.IconBadge
import com.ziribrowser.ui.LocalBarClearance
import com.ziribrowser.ui.ZiriHeader
import kotlinx.coroutines.launch

/** Frosted card that holds a group of related rows. */
@Composable
fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) { Column(content = content) }
}

/** Small caption above a card. */
@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 12.dp, top = 8.dp).semantics { heading() },
    )
}

/** One row: optional icon badge, title, optional subtitle, and a trailing control (a chevron when tappable). */
@Composable
fun SettingRow(
    title: String, modifier: Modifier = Modifier, icon: ImageVector? = null, subtitle: String? = null,
    onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) IconBadge(icon, Modifier.padding(end = 14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String, checked: Boolean, icon: ImageVector? = null, onChange: (Boolean) -> Unit) {
    SettingRow(title, icon = icon, subtitle = subtitle, onClick = { onChange(!checked) }, trailing = { Switch(checked, onChange) })
}

@Composable
fun RowDivider() {
    HorizontalDivider(Modifier.padding(start = 70.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}

/** Screen shell for a settings page: header with back arrow, scrolling body that clears the floating bar. */
@Composable
fun SettingsPageScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ZiriHeader(title, onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
                .padding(bottom = 24.dp + LocalBarClearance.current),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

// ---- dialogs ----------------------------------------------------------------------------------

@Composable
fun ClearDataDialog(onDismiss: () -> Unit, onConfirm: (history: Boolean, cookies: Boolean, cache: Boolean) -> Unit) {
    var history by remember { mutableStateOf(true) }
    var cookies by remember { mutableStateOf(true) }
    var cache by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clear_browsing_data)) },
        text = {
            Column {
                CheckRow(stringResource(R.string.browsing_history), stringResource(R.string.pages_you_ve_visited), history) { history = it }
                CheckRow(stringResource(R.string.cookies_and_site_data), stringResource(R.string.signs_you_out_of_most_sites), cookies) { cookies = it }
                CheckRow(stringResource(R.string.cached_images_and_files), stringResource(R.string.frees_up_storage), cache) { cache = it }
            }
        },
        confirmButton = { TextButton({ onConfirm(history, cookies, cache) }, enabled = history || cookies || cache) { Text(stringResource(R.string.clear)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CheckRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = androidx.compose.ui.semantics.Role.Checkbox) { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onChange)
        Column {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SitePermissionsDialog(container: AppContainer, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val saved by container.settings.sitePermissions.collectAsStateWithLifecycle(emptyMap())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.site_permissions)) },
        text = {
            if (saved.isEmpty()) {
                Text(stringResource(R.string.no_saved_choices_when_a_site_asks_to_u))
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    saved.entries.sortedBy { it.key }.forEach { (key, allowed) ->
                        val origin = key.substringBefore('|')
                        val kind = key.substringAfter('|')
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(UrlUtils.host(origin), fontWeight = FontWeight.Medium)
                                Text(
                                    stringResource(if (allowed) R.string.permission_allowed else R.string.permission_blocked, kind.replaceFirstChar { it.uppercase() }),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton({ scope.launch { container.settings.removeSitePermission(key) } }) { Text(stringResource(R.string.reset)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.done)) } },
        dismissButton = {
            if (saved.isNotEmpty()) TextButton({ scope.launch { container.settings.clearSitePermissions() } }) { Text(stringResource(R.string.reset_all)) }
        },
    )
}
