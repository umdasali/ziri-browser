package com.ziribrowser.ui.settings

import com.ziribrowser.ziriString
import com.ziribrowser.R
import androidx.compose.ui.res.stringResource
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import com.ziribrowser.data.WallpaperConfig
import com.ziribrowser.data.WallpaperKind
import com.ziribrowser.ui.GlassSurface
import com.ziribrowser.ui.PresetPicture
import com.ziribrowser.ui.WallpaperPicture
import com.ziribrowser.ui.WallpaperPresets
import com.ziribrowser.ui.deleteWallpaperPhoto
import com.ziribrowser.ui.rememberWallpaperPhoto
import com.ziribrowser.ui.importWallpaperPhoto
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ziribrowser.AppContainer
import com.ziribrowser.data.AppFont
import com.ziribrowser.data.SearchEngine
import com.ziribrowser.data.TextSize
import com.ziribrowser.ui.home.EngineIcon
import com.ziribrowser.ui.theme.fontFamily
import kotlinx.coroutines.launch

/** The page for one settings category ([page] is the part after "settings/"). */
@Composable
fun SettingsPage(container: AppContainer, page: String, onBack: () -> Unit) {
    when (page) {
        "text" -> TextPage(container, onBack)
        "browsing" -> BrowsingPage(container, onBack)
        "privacy" -> PrivacyPage(container, onBack)
        "passwords" -> PasswordsPage(container, onBack)
        "blocking" -> BlockingPage(container, onBack)
        "shield" -> ShieldPage(container, onBack)
        "downloads" -> DownloadsSettingsPage(container, onBack)
        "interests" -> InterestsPage(container, onBack)
        "video" -> VideoPage(container, onBack)
        "wallpaper" -> WallpaperPage(container, onBack)
        "about" -> AboutPage(onBack)
        else -> SettingsPageScaffold(stringResource(R.string.settings), onBack) { Text(stringResource(R.string.nothing_here)) }
    }
}

@Composable
private fun TextPage(container: AppContainer, onBack: () -> Unit) {
    val s = container.settings
    val scope = rememberCoroutineScope()
    val font by s.appFont.collectAsStateWithLifecycle(AppFont.DEFAULT)
    val size by s.textSize.collectAsStateWithLifecycle(TextSize.DEFAULT)
    SettingsPageScaffold(stringResource(R.string.text_amp_fonts), onBack) {
        SectionLabel(stringResource(R.string.font))
        SettingsCard {
            AppFont.entries.forEachIndexed { i, f ->
                if (i > 0) RowDivider()
                Row(
                    Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.RadioButton) { scope.launch { s.setAppFont(f) } }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        // Each font is shown in itself so the choice can be judged before it is made.
                        Text(f.label, fontFamily = f.fontFamily(), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            stringResource(R.string.the_quick_brown_fox_jumps_over_the_lazy), fontFamily = f.fontFamily(),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (f == font) Icon(Icons.Rounded.Check, stringResource(R.string.selected), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        SectionLabel(stringResource(R.string.text_size))
        SettingsCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val sizes = TextSize.entries
                    sizes.forEachIndexed { i, t ->
                        SegmentedButton(
                            selected = t == size, onClick = { scope.launch { s.setTextSize(t) } },
                            shape = SegmentedButtonDefaults.itemShape(i, sizes.size),
                        ) { Text(t.label, maxLines = 1) }
                    }
                }
                Text(
                    stringResource(R.string.everything_in_ziri_resizes_right_away_so),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BrowsingPage(container: AppContainer, onBack: () -> Unit) {
    val s = container.settings
    val scope = rememberCoroutineScope()
    val engine by s.searchEngine.collectAsStateWithLifecycle(SearchEngine.GOOGLE)
    val webZoom by s.webTextZoom.collectAsStateWithLifecycle(100)
    var zoom by remember(webZoom) { mutableFloatStateOf(webZoom.toFloat()) }
    SettingsPageScaffold(stringResource(R.string.search_amp_browsing), onBack) {
        SectionLabel(stringResource(R.string.search_engine))
        SettingsCard {
            SearchEngine.entries.forEachIndexed { i, e ->
                if (i > 0) RowDivider()
                Row(
                    Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.RadioButton) { scope.launch { s.setSearchEngine(e) } }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EngineIcon(e, 36.dp, modifier = Modifier.padding(end = 14.dp))
                    Text(e.label, fontWeight = if (e == engine) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.weight(1f))
                    if (e == engine) Icon(Icons.Rounded.Check, stringResource(R.string.selected), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        SectionLabel(stringResource(R.string.web_page_text_size))
        SettingsCard {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.text_zoom), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text("${zoom.toInt()}%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = zoom, onValueChange = { zoom = it },
                    onValueChangeFinished = { scope.launch { s.setWebTextZoom(zoom.toInt()) } },
                    valueRange = 50f..200f, steps = 14,
                )
                Text(stringResource(R.string.sample_text_on_a_web_page), fontSize = (16f * zoom / 100f).sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PrivacyPage(container: AppContainer, onBack: () -> Unit) {
    val s = container.settings
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val browsingDataClearedText = stringResource(R.string.browsing_data_cleared)
    val httpsFirst by s.httpsFirst.collectAsStateWithLifecycle(true)
    val httpsOnly by s.httpsOnly.collectAsStateWithLifecycle(true)
    val tabs by container.tabs.tabs.collectAsStateWithLifecycle()
    var clearing by remember { mutableStateOf(false) }
    var showPermissions by remember { mutableStateOf(false) }
    var closeTabs by remember { mutableStateOf(false) }

    SettingsPageScaffold(stringResource(R.string.privacy_amp_security), onBack) {
        SectionLabel(stringResource(R.string.connections))
        SettingsCard {
            ToggleRow(
                stringResource(R.string.https_first), stringResource(R.string.try_the_secure_version_of_every_site_fir),
                httpsFirst, Icons.Rounded.Lock,
            ) { scope.launch { s.setHttpsFirst(it) } }
            RowDivider()
            ToggleRow(
                stringResource(R.string.warn_before_sites_without_https), stringResource(R.string.show_a_warning_page_instead_of_opening_a),
                httpsOnly, Icons.Rounded.LockOpen,
            ) { scope.launch { s.setHttpsOnly(it) } }
        }
        SectionLabel(stringResource(R.string.site_data))
        SettingsCard {
            SettingRow(stringResource(R.string.clear_browsing_data), icon = Icons.Rounded.Delete, subtitle = stringResource(R.string.history_cookies_and_cache), onClick = { clearing = true })
            RowDivider()
            SettingRow(stringResource(R.string.site_permissions), icon = Icons.Rounded.LockOpen, subtitle = stringResource(R.string.camera_microphone_and_location_choices), onClick = { showPermissions = true })
            RowDivider()
            SettingRow(stringResource(R.string.close_all_tabs_2), icon = Icons.Rounded.Tab, subtitle = "${tabs.size} open", onClick = { if (tabs.isNotEmpty()) closeTabs = true })
        }
    }

    if (clearing) ClearDataDialog(
        onDismiss = { clearing = false },
        onConfirm = { history, cookies, cache ->
            clearing = false
            if (history) scope.launch { container.db.history().clearAll() }
            if (cookies) container.tabs.clearCookiesAndSiteData()
            if (cache) container.tabs.clearCache()
            Toast.makeText(context, browsingDataClearedText, Toast.LENGTH_SHORT).show()
        },
    )
    if (showPermissions) SitePermissionsDialog(container) { showPermissions = false }
    if (closeTabs) AlertDialog(
        onDismissRequest = { closeTabs = false },
        title = { Text(stringResource(R.string.close_all_tabs)) },
        text = { Text(stringResource(R.string.every_open_tab_will_be_closed)) },
        confirmButton = { TextButton({ container.tabs.closeAll(); closeTabs = false }) { Text(stringResource(R.string.close_all)) } },
        dismissButton = { TextButton({ closeTabs = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun VideoPage(container: AppContainer, onBack: () -> Unit) {
    val s = container.settings
    val scope = rememberCoroutineScope()
    val rememberVideo by s.rememberVideo.collectAsStateWithLifecycle(true)
    val autoPip by s.autoPip.collectAsStateWithLifecycle(true)
    SettingsPageScaffold(stringResource(R.string.video), onBack) {
        SettingsCard {
            ToggleRow(
                stringResource(R.string.remember_video_position), stringResource(R.string.pick_up_where_you_stopped_watching_not_u),
                rememberVideo, Icons.Rounded.PlayArrow,
            ) { scope.launch { s.setRememberVideo(it) } }
            RowDivider()
            ToggleRow(
                stringResource(R.string.picture_in_picture_2), stringResource(R.string.keep_a_fullscreen_video_playing_in_a_sma),
                autoPip, Icons.Rounded.PictureInPictureAlt,
            ) { scope.launch { s.setAutoPip(it) } }
        }
        SectionLabel(stringResource(R.string.good_to_know))
        SettingsCard {
            Text(
                stringResource(R.string.in_fullscreen_you_can_double_tap_to_jump) + " " +
                    stringResource(R.string.these_controls_work_with_videos_on_the_p),
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AboutPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    SettingsPageScaffold(stringResource(R.string.about_ziri), onBack) {
        SettingsCard {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.z), color = MaterialTheme.colorScheme.onPrimary, fontSize = 34.sp, fontWeight = FontWeight.Black)
                }
                Text(stringResource(R.string.ziri_browser), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.version, version), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.browse_download_enjoy), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        val policyUrl = stringResource(R.string.privacy_policy_url)
        SettingsCard {
            Text(
                stringResource(R.string.privacy_policy),
                Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button) { runCatching { uriHandler.openUri(policyUrl) } }.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
            )
        }
        SectionLabel(stringResource(R.string.notes))
        SettingsCard {
            Text(
                stringResource(R.string.site_logos_shown_in_ziri_belong_to_their) + " " +
                    stringResource(R.string.the_logos_are_stored_in_the_app_so_showi) + " " +
                    stringResource(R.string.reader_credit),
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---- wallpaper ------------------------------------------------------------------------------------

@Composable
private fun WallpaperPage(container: AppContainer, onBack: () -> Unit) {
    val s = container.settings
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val couldnTUseThatPictureText = stringResource(R.string.couldn_t_use_that_picture)
    val wp by s.wallpaper.collectAsStateWithLifecycle(WallpaperConfig())
    val photo by rememberWallpaperPhoto(context, wp.stamp, enabled = true)
    var fade by remember(wp.fade) { mutableFloatStateOf(wp.fade.toFloat()) }
    var blur by remember(wp.blur) { mutableFloatStateOf(wp.blur.toFloat()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            if (importWallpaperPhoto(context, uri)) s.setWallpaperCustom(System.currentTimeMillis())
            else Toast.makeText(context, couldnTUseThatPictureText, Toast.LENGTH_SHORT).show()
        }
    }
    fun pickPhoto() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    SettingsPageScaffold(stringResource(R.string.wallpaper), onBack) {
        // ---- live preview ----------------------------------------------------------------------------
        val scheme = MaterialTheme.colorScheme
        Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(28.dp)).background(scheme.background)) {
            if (wp.kind != WallpaperKind.NONE) {
                WallpaperPicture(wp.copy(fade = fade.toInt(), blur = blur.toInt()), photo)
                Box(Modifier.fillMaxSize().background(scheme.background.copy(alpha = fade / 100f)))
            }
            GlassSurface(Modifier.align(Alignment.Center).fillMaxWidth(0.8f), shape = CircleShape, strong = true) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Search, null, tint = scheme.primary)
                    Text("  " + stringResource(R.string.search_or_type_url), color = scheme.onSurfaceVariant)
                }
            }
        }

        SectionLabel(stringResource(R.string.choose_a_wallpaper))
        // None + your photo + the built-in set, three to a row.
        val tiles: List<@Composable (Modifier) -> Unit> = buildList {
            add { m -> WallpaperTile(stringResource(R.string.default_text), wp.kind == WallpaperKind.NONE, m, { scope.launch { s.setWallpaperNone() } }) {
                Box(Modifier.fillMaxSize().background(scheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Wallpaper, null, tint = scheme.onSurfaceVariant)
                }
            } }
            add { m -> WallpaperTile(
                stringResource(R.string.your_photo), wp.kind == WallpaperKind.CUSTOM, m,
                { if (photo != null) scope.launch { s.setWallpaperCustom(wp.stamp.takeIf { it != 0L } ?: System.currentTimeMillis()) } else pickPhoto() },
            ) {
                val p = photo
                if (p != null) Image(p, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Box(Modifier.fillMaxSize().background(scheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.AddPhotoAlternate, null, tint = scheme.primary)
                }
            } }
            WallpaperPresets.forEach { preset ->
                add { m -> WallpaperTile(preset.label, wp.kind == WallpaperKind.PRESET && wp.presetId == preset.id, m, { scope.launch { s.setWallpaperPreset(preset.id) } }) {
                    PresetPicture(preset)
                } }
            }
        }
        tiles.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { tile -> tile(Modifier.weight(1f)) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        SettingsCard {
            SettingRow(
                stringResource(R.string.choose_from_gallery), icon = Icons.Rounded.AddPhotoAlternate,
                subtitle = if (photo != null) stringResource(R.string.replace_your_photo) else stringResource(R.string.use_one_of_your_own_pictures),
                onClick = ::pickPhoto,
            )
            if (photo != null) {
                RowDivider()
                SettingRow(stringResource(R.string.remove_my_photo), icon = Icons.Rounded.Delete, subtitle = stringResource(R.string.delete_the_saved_copy), onClick = {
                    deleteWallpaperPhoto(context)
                    scope.launch { if (wp.kind == WallpaperKind.CUSTOM) s.setWallpaperNone(); s.touchWallpaperPhoto() }
                })
            }
        }

        SectionLabel(stringResource(R.string.adjust))
        SettingsCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.fade), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text("${fade.toInt()}%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                Text(stringResource(R.string.blends_the_picture_into_the_theme_so_t), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(fade, { fade = it }, onValueChangeFinished = { scope.launch { s.setWallpaperFade(fade.toInt()) } }, valueRange = 0f..90f)
                if (Build.VERSION.SDK_INT >= 31) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.blur), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text("${blur.toInt()}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    Slider(blur, { blur = it }, onValueChangeFinished = { scope.launch { s.setWallpaperBlur(blur.toInt()) } }, valueRange = 0f..25f)
                }
            }
        }
        SectionLabel(stringResource(R.string.show_on))
        SettingsCard {
            Column(Modifier.padding(16.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(!wp.everywhere, { scope.launch { s.setWallpaperEverywhere(false) } }, SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.home_only)) }
                    SegmentedButton(wp.everywhere, { scope.launch { s.setWallpaperEverywhere(true) } }, SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.every_screen)) }
                }
                Text(
                    stringResource(R.string.the_browser_itself_always_stays_plain_so),
                    Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WallpaperTile(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit, picture: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier.height(132.dp).clip(shape).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        picture()
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))).padding(8.dp),
        ) { Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        if (selected) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(15.dp)) }
        }
    }
}

@Composable
private fun BlockingPage(container: AppContainer, onBack: () -> Unit) {
    val s = container.settings
    val scope = rememberCoroutineScope()
    val cfg by s.blocking.collectAsStateWithLifecycle(com.ziribrowser.adblock.BlockingConfig())
    val status by container.blocking.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SettingsPageScaffold(stringResource(R.string.ad_amp_tracker_blocking), onBack) {
        SectionLabel(stringResource(R.string.block))
        SettingsCard {
            ToggleRow(stringResource(R.string.advertisements), stringResource(R.string.banners_pop_in_ads_and_ad_scripts_easyli), cfg.ads, Icons.Rounded.Shield) { scope.launch { s.setBlockAds(it) } }
            RowDivider()
            ToggleRow(stringResource(R.string.trackers_amp_analytics), stringResource(R.string.cross_site_tracking_and_analytics_endpoi), cfg.trackers, Icons.Rounded.Lock) { scope.launch { s.setBlockTrackers(it) } }
        }
        SectionLabel(stringResource(R.string.filter_lists))
        SettingsCard {
            SettingRow(
                stringResource(R.string.update_filter_lists), icon = Icons.Rounded.Download,
                subtitle = when {
                    status.updating -> stringResource(R.string.updating)
                    !status.loaded -> stringResource(R.string.loading)
                    else -> "${status.adRules + status.trackerRules} rules" + if (status.updatedAt > 0) " · updated " + android.text.format.DateUtils.getRelativeTimeSpanString(status.updatedAt) else " · built-in copy"
                },
                onClick = { if (!status.updating) scope.launch {
                    val ok = container.blocking.update()
                    Toast.makeText(context, if (ok) ziriString(R.string.filter_lists_updated) else container.blocking.status.value.message ?: ziriString(R.string.update_failed), Toast.LENGTH_SHORT).show()
                } },
            )
        }
        SectionLabel(stringResource(R.string.sites_with_their_own_settings))
        if (cfg.sites.isEmpty()) {
            Text(
                stringResource(R.string.none_yet_open_the_browser_menu_on_any_pa),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp),
            )
        } else SettingsCard {
            cfg.sites.entries.sortedBy { it.key }.forEachIndexed { i, (site, v) ->
                if (i > 0) RowDivider()
                SettingRow(
                    site, subtitle = if (v.allowListed) stringResource(R.string.blocking_off) else listOfNotNull(if (!v.ads) "ads allowed" else null, if (!v.trackers) "trackers allowed" else null).joinToString(" · "),
                    trailing = { TextButton({ scope.launch { s.setBlockSite(site, null) } }) { Text(stringResource(R.string.reset)) } },
                )
            }
        }
        Text(
            stringResource(R.string.lists_easylist_and_easyprivacy_cc_by_sa),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun InterestsPage(container: AppContainer, onBack: () -> Unit) {
    val s = container.settings
    val scope = rememberCoroutineScope()
    val chosen by s.interests.collectAsStateWithLifecycle(emptySet())
    val showFeed by s.showFeed.collectAsStateWithLifecycle(true)
    SettingsPageScaffold(stringResource(R.string.interests_amp_headlines), onBack) {
        SectionLabel(stringResource(R.string.your_topics))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        ) {
            com.ziribrowser.data.Interest.entries.forEach { i ->
                com.ziribrowser.ui.onboarding.InterestChip(i, i in chosen) { scope.launch { s.setInterests(if (i in chosen) chosen - i else chosen + i) } }
            }
        }
        Text(
            stringResource(R.string.home_shows_popular_sites_for_these_topic),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp),
        )
        SectionLabel(stringResource(R.string.headlines))
        SettingsCard {
            ToggleRow(
                stringResource(R.string.latest_for_you), stringResource(R.string.show_recent_articles_and_pictures_on_hom),
                showFeed, Icons.Rounded.Download,
            ) { scope.launch { s.setShowFeed(it) } }
        }
    }
}

@Composable
private fun ShieldPage(container: AppContainer, onBack: () -> Unit) {
    val s = container.settings
    val scope = rememberCoroutineScope()
    val fp by s.fingerprintShield.collectAsStateWithLifecycle(true)
    val cookies by s.blockThirdPartyCookies.collectAsStateWithLifecycle(true)
    val strip by s.stripTrackingParams.collectAsStateWithLifecycle(true)
    val gpc by s.sendGpc.collectAsStateWithLifecycle(true)
    val safe by s.safeBrowsing.collectAsStateWithLifecycle(true)
    val autoLists by s.autoUpdateLists.collectAsStateWithLifecycle(true)
    val icons by s.fetchIcons.collectAsStateWithLifecycle(true)
    val feed by s.showFeed.collectAsStateWithLifecycle(true)
    SettingsPageScaffold(stringResource(R.string.privacy_shield_amp_promise), onBack) {
        SectionLabel(stringResource(R.string.anti_fingerprinting))
        SettingsCard {
            ToggleRow(
                stringResource(R.string.randomise_my_browser_signature),
                stringResource(R.string.changes_what_sites_can_read_from_your_de),
                fp, Icons.Rounded.Shield,
            ) { scope.launch { s.setFingerprintShield(it) } }
        }
        Text(
            stringResource(R.string.this_reduces_fingerprinting_it_can_t_rem),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp),
        )
        SectionLabel(stringResource(R.string.tracking))
        SettingsCard {
            ToggleRow(stringResource(R.string.block_third_party_cookies), stringResource(R.string.stops_ad_and_analytics_sites_from_follow), cookies, Icons.Rounded.Lock) { scope.launch { s.setBlockThirdPartyCookies(it) } }
            RowDivider()
            ToggleRow(stringResource(R.string.clean_tracking_tags_from_links), stringResource(R.string.removes_utm_fbclid_gclid_and_similar_tag), strip, Icons.Rounded.Delete) { scope.launch { s.setStripTrackingParams(it) } }
            RowDivider()
            ToggleRow(stringResource(R.string.send_do_not_track_signals), stringResource(R.string.tells_sites_not_to_sell_or_share_your_da), gpc, Icons.Rounded.LockOpen) { scope.launch { s.setSendGpc(it) } }
        }
        SectionLabel(stringResource(R.string.our_promise_zero_data_collection))
        SettingsCard {
            Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                for (line in listOf(
                    stringResource(R.string.no_analytics_no_crash_reporting_no_adver),
                    stringResource(R.string.no_account_no_sign_in_no_device_or_insta),
                    stringResource(R.string.your_history_bookmarks_tabs_and_download),
                    stringResource(R.string.the_system_web_engine_is_told_not_to_sen),
                    stringResource(R.string.pw_promise),
                    stringResource(R.string.ziri_never_sells_or_shares_anything_beca),
                )) Text(stringResource(R.string.text, line), style = MaterialTheme.typography.bodyMedium)
            }
        }
        SectionLabel(stringResource(R.string.the_only_things_ziri_contacts_by_itself))
        SettingsCard {
            ToggleRow(stringResource(R.string.safe_browsing), stringResource(R.string.warns_about_dangerous_sites_your_browser), safe, Icons.Rounded.Shield) { scope.launch { s.setSafeBrowsing(it) } }
            RowDivider()
            ToggleRow(stringResource(R.string.update_ad_block_lists), stringResource(R.string.downloads_easylist_and_easyprivacy_from), autoLists, Icons.Rounded.Download) { scope.launch { s.setAutoUpdateLists(it) } }
            RowDivider()
            ToggleRow(stringResource(R.string.look_up_site_icons), stringResource(R.string.reads_each_site_s_own_icon_and_title_whe), icons, Icons.Rounded.Search) { scope.launch { s.setFetchIcons(it) } }
            RowDivider()
            ToggleRow(stringResource(R.string.latest_headlines_on_home), stringResource(R.string.loads_public_news_feeds_from_the_sites_f), feed, Icons.Rounded.Download) { scope.launch { s.setShowFeed(it) } }
        }
        Text(
            stringResource(R.string.everything_else_such_as_the_sites_you_op),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsSettingsPage(container: AppContainer, onBack: () -> Unit) {
    val s = container.settings
    val scope = rememberCoroutineScope()
    val concurrent by s.dlConcurrent.collectAsStateWithLifecycle(3)
    val speed by s.dlSpeedKb.collectAsStateWithLifecycle(0)
    val wifi by s.dlWifiOnly.collectAsStateWithLifecycle(false)
    val parts by s.dlParts.collectAsStateWithLifecycle(4)
    val speeds = listOf(0 to stringResource(R.string.no_limit), 512 to "512 KB/s", 1024 to "1 MB/s", 2048 to "2 MB/s", 5120 to "5 MB/s", 10240 to "10 MB/s")
    SettingsPageScaffold(stringResource(R.string.downloads), onBack) {
        SectionLabel(stringResource(R.string.speed))
        SettingsCard {
            Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.connections_per_file), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.big_files_are_split_into_parts_fetched), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(1, 2, 4, 6).forEachIndexed { i, n ->
                        SegmentedButton(parts == n, { scope.launch { s.setDlParts(n) } }, androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, 4)) { Text("$n") }
                    }
                }
            }
            RowDivider()
            Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.speed_limit), fontWeight = FontWeight.SemiBold)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    speeds.forEach { (kb, label) -> androidx.compose.material3.FilterChip(speed == kb, { scope.launch { s.setDlSpeedKb(kb) } }, { Text(label) }) }
                }
            }
        }
        SectionLabel(stringResource(R.string.queue))
        SettingsCard {
            Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.downloads_at_the_same_time), fontWeight = FontWeight.SemiBold)
                androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    (1..5).forEachIndexed { i, n ->
                        SegmentedButton(concurrent == n, { scope.launch { s.setDlConcurrent(n) } }, androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, 5)) { Text("$n") }
                    }
                }
            }
            RowDivider()
            ToggleRow(stringResource(R.string.wi_fi_only), stringResource(R.string.wait_for_wi_fi_before_starting_or_contin), wifi, Icons.Rounded.Download) { scope.launch { s.setDlWifiOnly(it) } }
        }
        Text(
            stringResource(R.string.downloads_retry_on_their_own_after_a_dro),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
