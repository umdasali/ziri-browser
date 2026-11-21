package com.ziribrowser.ui.settings

import androidx.compose.material.icons.rounded.Key
import androidx.compose.foundation.lazy.items
import com.ziribrowser.ziriString
import com.ziribrowser.R
import androidx.compose.ui.res.stringResource
import android.app.role.RoleManager
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Wallpaper
import com.ziribrowser.data.WallpaperConfig
import com.ziribrowser.data.WallpaperKind
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ziribrowser.AppContainer
import com.ziribrowser.data.AppFont
import com.ziribrowser.data.SearchEngine
import com.ziribrowser.data.TextSize
import com.ziribrowser.data.ThemeColor
import com.ziribrowser.data.ThemeMode
import com.ziribrowser.ui.GlassSurface
import com.ziribrowser.ui.LocalBarClearance
import com.ziribrowser.ui.ZiriHeader
import com.ziribrowser.ui.theme.isDark
import com.ziribrowser.ui.theme.seed
import kotlinx.coroutines.launch

/** Settings home: default-browser card, live theme picker, and a list of categories that open their own page. */
@Composable
fun SettingsScreen(container: AppContainer, onNavigate: (String) -> Unit) {
    val s = container.settings
    val scope = rememberCoroutineScope()
    val mode by s.themeMode.collectAsStateWithLifecycle(ThemeMode.LIGHT)
    val amoled by s.amoled.collectAsStateWithLifecycle(false)
    val darkWeb by s.darkWeb.collectAsStateWithLifecycle(false)
    val color by s.themeColor.collectAsStateWithLifecycle(ThemeColor.TEAL)
    val font by s.appFont.collectAsStateWithLifecycle(AppFont.DEFAULT)
    val textSize by s.textSize.collectAsStateWithLifecycle(TextSize.DEFAULT)
    val engine by s.searchEngine.collectAsStateWithLifecycle(SearchEngine.GOOGLE)
    val httpsFirst by s.httpsFirst.collectAsStateWithLifecycle(true)
    val rememberVideo by s.rememberVideo.collectAsStateWithLifecycle(true)
    val autoPip by s.autoPip.collectAsStateWithLifecycle(true)
    val wallpaper by s.wallpaper.collectAsStateWithLifecycle(WallpaperConfig())
    val dark = isDark(mode)
    val context = LocalContext.current

    // ---- default browser ---------------------------------------------------------------------
    val roleManager = remember { context.getSystemService(RoleManager::class.java) }
    fun checkDefault() = roleManager?.isRoleHeld(RoleManager.ROLE_BROWSER) == true
    var isDefault by remember { mutableStateOf(checkDefault()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { isDefault = checkDefault() }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { isDefault = checkDefault() }
    fun requestDefault() {
        val rm = roleManager
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_BROWSER)) roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_BROWSER))
        else context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
    }

    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ZiriHeader(stringResource(R.string.settings))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
                .padding(bottom = 24.dp + LocalBarClearance.current),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- default browser hero ------------------------------------------------------------
            GlassSurface(
                Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = if (isDefault) 0.08f else 0.16f),
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(if (isDefault) Icons.Rounded.Check else Icons.Rounded.Language, null, tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(if (isDefault) stringResource(R.string.ziri_is_your_default_browser) else stringResource(R.string.make_ziri_your_default_browser), fontWeight = FontWeight.Bold)
                        Text(
                            if (isDefault) stringResource(R.string.links_from_other_apps_open_here) else stringResource(R.string.open_links_from_messages_email_and_other),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!isDefault) Button(::requestDefault) { Text(stringResource(R.string.set)) }
                }
            }
            if (!isDefault) {
                TextButton({ context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }, Modifier.padding(start = 6.dp)) {
                    Text(stringResource(R.string.nothing_happened_open_system_settings))
                }
            }

            // ---- theme (changes the whole app live) -------------------------------------------------
            SectionLabel(stringResource(R.string.theme))
            SettingsCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ThemeMode.entries.forEach { m ->
                            ModeTile(m, m == mode, color.seed(dark), Modifier.weight(1f)) { scope.launch { s.setThemeMode(m) } }
                        }
                    }
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ThemeColor.entries.forEach { c ->
                            ColorDot(c.seed(dark), c.label, c == color) { scope.launch { s.setThemeColor(c) } }
                        }
                    }
                }
                RowDivider()
                ToggleRow(stringResource(R.string.pure_black), stringResource(R.string.pure_black_desc), amoled) { scope.launch { s.setAmoled(it) } }
                RowDivider()
                ToggleRow(stringResource(R.string.dark_web_pages), stringResource(R.string.dark_web_pages_desc), darkWeb) { scope.launch { s.setDarkWeb(it) } }
            }

            // ---- categories -----------------------------------------------------------------------------
            SectionLabel(stringResource(R.string.appearance))
            SettingsCard {
                SettingRow(
                    stringResource(R.string.wallpaper), icon = Icons.Rounded.Wallpaper,
                    subtitle = when (wallpaper.kind) {
                        WallpaperKind.NONE -> stringResource(R.string.default_text)
                        WallpaperKind.CUSTOM -> stringResource(R.string.your_photo)
                        WallpaperKind.PRESET -> com.ziribrowser.ui.wallpaperPreset(wallpaper.presetId).label
                    },
                    onClick = { onNavigate("settings/wallpaper") },
                )
                RowDivider()
                SettingRow(stringResource(R.string.text_amp_fonts), icon = Icons.Rounded.TextFields, subtitle = "${font.label} · ${textSize.label}", onClick = { onNavigate("settings/text") })
                RowDivider()
                val context = androidx.compose.ui.platform.LocalContext.current
                var langOpen by remember { mutableStateOf(false) }
                val langName = com.ziribrowser.AppLanguage.supported.firstOrNull { it.tag == com.ziribrowser.AppLanguage.currentSupported(context) }?.name
                SettingRow(stringResource(R.string.language), icon = Icons.Rounded.Language, subtitle = langName ?: stringResource(R.string.system_default), onClick = { langOpen = true })
                if (langOpen) LanguageDialog(onDismiss = { langOpen = false })
            }

            SectionLabel(stringResource(R.string.browsing))
            SettingsCard {
                SettingRow(stringResource(R.string.search_amp_browsing), icon = Icons.Rounded.Search, subtitle = "${engine.label} · ${stringResource(R.string.web_page_text_size)}", onClick = { onNavigate("settings/browsing") })
                RowDivider()
                SettingRow(
                    stringResource(R.string.video), icon = Icons.Rounded.PlayArrow,
                    subtitle = stringResource(R.string.video_summary, stringResource(if (rememberVideo) R.string.on else R.string.off), stringResource(if (autoPip) R.string.on else R.string.off)),
                    onClick = { onNavigate("settings/video") },
                )
                RowDivider()
                SettingRow(stringResource(R.string.downloads), icon = Icons.Rounded.Download, subtitle = stringResource(R.string.speed_queue_wi_fi_only), onClick = { onNavigate("settings/downloads") })
                RowDivider()
                SettingRow(stringResource(R.string.interests_amp_headlines), icon = Icons.Rounded.Shield, subtitle = stringResource(R.string.topics_for_popular_and_latest_on_home), onClick = { onNavigate("settings/interests") })
            }

            SectionLabel(stringResource(R.string.privacy_amp_security))
            SettingsCard {
                val pwCount by container.passwords.count.collectAsStateWithLifecycle(0)
                SettingRow(stringResource(R.string.pw_title), icon = Icons.Rounded.Key, subtitle = stringResource(R.string.pw_subtitle, pwCount), onClick = { onNavigate("settings/passwords") })
                RowDivider()
                SettingRow(stringResource(R.string.privacy_shield_amp_promise), icon = Icons.Rounded.Shield, subtitle = stringResource(R.string.fingerprint_protection_zero_telemetry), onClick = { onNavigate("settings/shield") })
                RowDivider()
                SettingRow(stringResource(R.string.ad_amp_tracker_blocking), icon = Icons.Rounded.Shield, subtitle = stringResource(R.string.easylist_easyprivacy_per_site_controls), onClick = { onNavigate("settings/blocking") })
                RowDivider()
                SettingRow(stringResource(R.string.privacy_amp_security), icon = Icons.Rounded.Lock, subtitle = if (httpsFirst) stringResource(R.string.https_first_on) else stringResource(R.string.https_first_off), onClick = { onNavigate("settings/privacy") })
            }

            SectionLabel(stringResource(R.string.more))
            SettingsCard {
                SettingRow(stringResource(R.string.history), icon = Icons.Rounded.History, subtitle = stringResource(R.string.pages_you_ve_visited), onClick = { onNavigate("history") })
                RowDivider()
                SettingRow(stringResource(R.string.about_ziri), icon = Icons.Rounded.Info, subtitle = stringResource(R.string.version_2, version), onClick = { onNavigate("settings/about") })
            }
        }
    }
}

/** A small preview of the light, dark or system look, so the choice can be seen before it is made. */
@Composable
private fun ModeTile(mode: ThemeMode, selected: Boolean, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    val ring = if (selected) BorderStroke(2.5.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    Column(modifier.clip(RoundedCornerShape(16.dp)).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(14.dp)).border(ring, RoundedCornerShape(14.dp))) {
            Canvas(Modifier.fillMaxSize()) {
                fun page(bg: Color, bar: Color, dot: Color, half: Int) {
                    // half: 0 = whole tile, 1 = left half, 2 = right half
                    val area = when (half) {
                        1 -> Rect(0f, 0f, size.width / 2, size.height)
                        2 -> Rect(size.width / 2, 0f, size.width, size.height)
                        else -> Rect(0f, 0f, size.width, size.height)
                    }
                    clipPath(Path().apply { addRect(area) }) {
                        drawRect(bg)
                        drawRoundRect(bar, Offset(size.width * 0.12f, size.height * 0.16f), Size(size.width * 0.76f, size.height * 0.2f), CornerRadius(size.height * 0.1f))
                        drawCircle(accent, size.height * 0.1f, Offset(size.width * 0.28f, size.height * 0.64f))
                        drawCircle(dot, size.height * 0.1f, Offset(size.width * 0.5f, size.height * 0.64f))
                        drawCircle(dot, size.height * 0.1f, Offset(size.width * 0.72f, size.height * 0.64f))
                    }
                }
                val lightBg = Color(0xFFF7FAF8); val lightBar = Color(0xFFE3E9E5); val lightDot = Color(0xFFD0D7D3)
                val darkBg = Color(0xFF14171B); val darkBar = Color(0xFF2A2F36); val darkDot = Color(0xFF3A404A)
                when (mode) {
                    ThemeMode.LIGHT -> page(lightBg, lightBar, lightDot, 0)
                    ThemeMode.DARK -> page(darkBg, darkBar, darkDot, 0)
                    ThemeMode.SYSTEM -> { page(lightBg, lightBar, lightDot, 1); page(darkBg, darkBar, darkDot, 2) }
                }
            }
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp)) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(when (mode) { ThemeMode.LIGHT -> R.string.light; ThemeMode.DARK -> R.string.dark; ThemeMode.SYSTEM -> R.string.system }), style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ColorDot(color: Color, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(4.dp)) {
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(color)
                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) { if (selected) Icon(Icons.Rounded.Check, label, tint = Color.White) }
        Text(
            label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp),
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}


/** Lets the person pick the app language; each language is listed in its own script so it is always readable. */
@Composable
private fun LanguageDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val selected = com.ziribrowser.AppLanguage.currentSupported(context)
    fun choose(tag: String) {
        onDismiss()
        com.ziribrowser.AppLanguage.set(context, tag)
        // Android 13+ recreates the activity itself; older versions need a nudge.
        if (android.os.Build.VERSION.SDK_INT < 33) (context as? android.app.Activity)?.recreate()
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language)) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn {
                item { LanguageRow(stringResource(R.string.system_default), selected.isEmpty()) { choose("") } }
                items(com.ziribrowser.AppLanguage.supported.size) { i ->
                    val l = com.ziribrowser.AppLanguage.supported[i]
                    LanguageRow(l.name, l.tag == selected) { choose(l.tag) }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun LanguageRow(name: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        androidx.compose.ui.Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(selected, onClick = null)
        Text(name, modifier = androidx.compose.ui.Modifier.padding(start = 16.dp))
    }
}
