package com.ziribrowser.data

import com.ziribrowser.R
import com.ziribrowser.ziriString
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class ThemeMode { LIGHT, DARK, SYSTEM }

/** Accent colour of the whole app. [light]/[dark] are ARGB seeds tuned for each mode's contrast. */
enum class ThemeColor(@androidx.annotation.StringRes val labelRes: Int, val light: Long, val dark: Long) {
    GREEN(R.string.green, 0xFF16A34A, 0xFF4ADE80),
    BLUE(R.string.blue, 0xFF2563EB, 0xFF60A5FA),
    ORANGE(R.string.orange, 0xFFEA580C, 0xFFFB923C),
    PINK(R.string.pink, 0xFFDB2777, 0xFFF472B6),
    YELLOW(R.string.yellow, 0xFFCA8A04, 0xFFFACC15),
    PURPLE(R.string.purple, 0xFF7C3AED, 0xFFA78BFA),
    RED(R.string.red, 0xFFDC2626, 0xFFF87171),
    TEAL(R.string.teal, 0xFF0D9488, 0xFF2DD4BF),
    INDIGO(R.string.indigo, 0xFF4F46E5, 0xFF818CF8),
    SKY(R.string.sky, 0xFF0284C7, 0xFF38BDF8),
    CYAN(R.string.cyan, 0xFF0891B2, 0xFF22D3EE),
    EMERALD(R.string.emerald, 0xFF059669, 0xFF34D399),
    LIME(R.string.lime, 0xFF65A30D, 0xFFA3E635),
    AMBER(R.string.amber, 0xFFD97706, 0xFFFBBF24),
    CORAL(R.string.coral, 0xFFE5533C, 0xFFFF8A73),
    ROSE(R.string.rose, 0xFFE11D48, 0xFFFB7185),
    FUCHSIA(R.string.fuchsia, 0xFFC026D3, 0xFFE879F9),
    BROWN(R.string.brown, 0xFF92603A, 0xFFC79A72),
    SLATE(R.string.slate, 0xFF475569, 0xFF94A3B8),
    ;

    val label: String get() = ziriString(labelRes)
}

enum class AppFont(@androidx.annotation.StringRes val labelRes: Int) {
    DEFAULT(R.string.default_text),
    SANS(R.string.sans),
    SERIF(R.string.serif),
    MONO(R.string.mono),
    CASUAL(R.string.casual),
    ;

    val label: String get() = ziriString(labelRes)
}

/** Multiplier applied to all text in the app UI. */
enum class TextSize(@androidx.annotation.StringRes val labelRes: Int, val scale: Float) {
    SMALL(R.string.small, 0.88f),
    DEFAULT(R.string.default_text, 1f),
    LARGE(R.string.large, 1.15f),
    XLARGE(R.string.xlarge, 1.3f),
    ;

    val label: String get() = ziriString(labelRes)
}


enum class SearchEngine(val label: String, val queryUrl: String) {
    GOOGLE("Google", "https://www.google.com/search?q="),
    BING("Bing", "https://www.bing.com/search?q="),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q="),
    BRAVE("Brave Search", "https://search.brave.com/search?q="),
    ECOSIA("Ecosia", "https://www.ecosia.org/search?q="),
    YAHOO("Yahoo", "https://search.yahoo.com/search?p="),
}

enum class WallpaperKind { NONE, PRESET, CUSTOM }

/**
 * Background picture settings. [fade] blends the picture toward the theme background (0-90%) so text
 * stays readable; [blur] is in dp (needs Android 12+); [stamp] changes when the user's own photo changes.
 */
data class WallpaperConfig(
    val kind: WallpaperKind = WallpaperKind.NONE,
    val presetId: String = "aurora",
    val fade: Int = 55,
    val blur: Int = 0,
    val everywhere: Boolean = false,
    val stamp: Long = 0L,
)

/** A site the user pinned to the home screen. */
data class QuickAccessItem(val title: String, val url: String)

class SettingsRepository(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme_mode")
    private val colorKey = stringPreferencesKey("theme_color")
    private val fontKey = stringPreferencesKey("app_font")
    private val textSizeKey = stringPreferencesKey("text_size")
    private val engineKey = stringPreferencesKey("search_engine")
    private val webZoomKey = intPreferencesKey("web_text_zoom")
    private val quickKey = stringPreferencesKey("quick_access")

    /**
     * A synchronous copy of the theme choice. DataStore loads asynchronously, so without this the first
     * frame would use default colours and flash before the saved theme appears.
     */
    private val boot = context.getSharedPreferences("boot_theme", Context.MODE_PRIVATE)
    fun bootMode(): ThemeMode = boot.getString("mode", null).toEnum(ThemeMode.SYSTEM)
    fun bootAmoled(): Boolean = boot.getBoolean("amoled", false)
    fun bootColor(): ThemeColor = boot.getString("color", null).toEnum(ThemeColor.TEAL)

    private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
        this?.let { s -> runCatching { enumValueOf<E>(s) }.getOrNull() } ?: default

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { it[themeKey].toEnum(ThemeMode.SYSTEM) }
    val themeColor: Flow<ThemeColor> = context.dataStore.data.map { it[colorKey].toEnum(ThemeColor.TEAL) }
    val appFont: Flow<AppFont> = context.dataStore.data.map { it[fontKey].toEnum(AppFont.DEFAULT) }
    val textSize: Flow<TextSize> = context.dataStore.data.map { it[textSizeKey].toEnum(TextSize.DEFAULT) }
    val searchEngine: Flow<SearchEngine> = context.dataStore.data.map { it[engineKey].toEnum(SearchEngine.GOOGLE) }
    /** Text zoom inside web pages, in percent (100 = page default). */
    val webTextZoom: Flow<Int> = context.dataStore.data.map { it[webZoomKey] ?: 100 }

    val quickAccess: Flow<List<QuickAccessItem>> = context.dataStore.data.map { decode(it[quickKey]) }

    suspend fun setThemeMode(m: ThemeMode) { boot.edit().putString("mode", m.name).apply(); context.dataStore.edit { it[themeKey] = m.name } }
    private val amoledKey = booleanPreferencesKey("theme_amoled")
    private val darkWebKey = booleanPreferencesKey("dark_web_pages")
    /** Pure-black backgrounds in dark mode (saves power on OLED screens). */
    val amoled: Flow<Boolean> = context.dataStore.data.map { it[amoledKey] ?: false }
    suspend fun setAmoled(on: Boolean) { boot.edit().putBoolean("amoled", on).apply(); context.dataStore.edit { it[amoledKey] = on } }
    /** Darken web pages while the app is in dark mode. */
    val darkWeb: Flow<Boolean> = context.dataStore.data.map { it[darkWebKey] ?: false }
    suspend fun setDarkWeb(on: Boolean) { context.dataStore.edit { it[darkWebKey] = on } }
    suspend fun setThemeColor(c: ThemeColor) { boot.edit().putString("color", c.name).apply(); context.dataStore.edit { it[colorKey] = c.name } }
    suspend fun setAppFont(f: AppFont) { context.dataStore.edit { it[fontKey] = f.name } }
    suspend fun setTextSize(s: TextSize) { context.dataStore.edit { it[textSizeKey] = s.name } }
    // ---- wallpaper ----------------------------------------------------------------------------------
    private val wpKindKey = stringPreferencesKey("wallpaper_kind")
    private val wpPresetKey = stringPreferencesKey("wallpaper_preset")
    private val wpFadeKey = intPreferencesKey("wallpaper_fade")
    private val wpBlurKey = intPreferencesKey("wallpaper_blur")
    private val wpEverywhereKey = booleanPreferencesKey("wallpaper_everywhere")
    private val wpStampKey = androidx.datastore.preferences.core.longPreferencesKey("wallpaper_stamp")

    val wallpaper: Flow<WallpaperConfig> = context.dataStore.data.map { p ->
        WallpaperConfig(
            kind = p[wpKindKey].toEnum(WallpaperKind.NONE),
            presetId = p[wpPresetKey] ?: "aurora",
            fade = p[wpFadeKey] ?: 55,
            blur = p[wpBlurKey] ?: 0,
            everywhere = p[wpEverywhereKey] ?: false,
            stamp = p[wpStampKey] ?: 0L,
        )
    }

    suspend fun setWallpaperNone() { context.dataStore.edit { it[wpKindKey] = WallpaperKind.NONE.name } }
    suspend fun setWallpaperPreset(id: String) { context.dataStore.edit { it[wpKindKey] = WallpaperKind.PRESET.name; it[wpPresetKey] = id } }
    /** The photo itself is stored by the caller; [stamp] changes whenever it does, so the UI reloads it. */
    suspend fun setWallpaperCustom(stamp: Long) { context.dataStore.edit { it[wpKindKey] = WallpaperKind.CUSTOM.name; it[wpStampKey] = stamp } }
    /** Tells the UI the saved photo changed (or was deleted) without changing which wallpaper is selected. */
    suspend fun touchWallpaperPhoto() { context.dataStore.edit { it[wpStampKey] = System.currentTimeMillis() } }
    suspend fun setWallpaperFade(percent: Int) { context.dataStore.edit { it[wpFadeKey] = percent.coerceIn(0, 90) } }
    suspend fun setWallpaperBlur(dp: Int) { context.dataStore.edit { it[wpBlurKey] = dp.coerceIn(0, 25) } }
    suspend fun setWallpaperEverywhere(on: Boolean) { context.dataStore.edit { it[wpEverywhereKey] = on } }

    // ---- privacy shield (every switch defaults to on) ------------------------------------------------
    private val fpKey = booleanPreferencesKey("fp_shield")
    private val cookies3Key = booleanPreferencesKey("block_3p_cookies")
    private val stripKey = booleanPreferencesKey("strip_params")
    private val gpcKey = booleanPreferencesKey("send_gpc")
    private val safeBrowsingKey = booleanPreferencesKey("safe_browsing")
    private val autoListsKey = booleanPreferencesKey("auto_update_lists")
    private val iconsKey = booleanPreferencesKey("fetch_icons")

    val fingerprintShield: Flow<Boolean> = context.dataStore.data.map { it[fpKey] ?: true }
    val blockThirdPartyCookies: Flow<Boolean> = context.dataStore.data.map { it[cookies3Key] ?: true }
    val stripTrackingParams: Flow<Boolean> = context.dataStore.data.map { it[stripKey] ?: true }
    val sendGpc: Flow<Boolean> = context.dataStore.data.map { it[gpcKey] ?: true }
    val safeBrowsing: Flow<Boolean> = context.dataStore.data.map { it[safeBrowsingKey] ?: true }
    val autoUpdateLists: Flow<Boolean> = context.dataStore.data.map { it[autoListsKey] ?: true }
    val fetchIcons: Flow<Boolean> = context.dataStore.data.map { it[iconsKey] ?: true }

    suspend fun setFingerprintShield(on: Boolean) { context.dataStore.edit { it[fpKey] = on } }
    suspend fun setBlockThirdPartyCookies(on: Boolean) { context.dataStore.edit { it[cookies3Key] = on } }
    suspend fun setStripTrackingParams(on: Boolean) { context.dataStore.edit { it[stripKey] = on } }
    suspend fun setSendGpc(on: Boolean) { context.dataStore.edit { it[gpcKey] = on } }
    suspend fun setSafeBrowsing(on: Boolean) { context.dataStore.edit { it[safeBrowsingKey] = on } }
    suspend fun setAutoUpdateLists(on: Boolean) { context.dataStore.edit { it[autoListsKey] = on } }
    suspend fun setFetchIcons(on: Boolean) { context.dataStore.edit { it[iconsKey] = on } }

    // ---- download manager ---------------------------------------------------------------------------
    private val dlConcurrentKey = intPreferencesKey("dl_concurrent")
    private val dlSpeedKey = intPreferencesKey("dl_speed_kb")
    private val dlWifiKey = booleanPreferencesKey("dl_wifi_only")
    private val dlPartsKey = intPreferencesKey("dl_parts")

    /** How many downloads run at once (1-5). */
    val dlConcurrent: Flow<Int> = context.dataStore.data.map { (it[dlConcurrentKey] ?: 3).coerceIn(1, 5) }
    /** Speed limit in KB/s for all downloads together; 0 = unlimited. */
    val dlSpeedKb: Flow<Int> = context.dataStore.data.map { it[dlSpeedKey] ?: 0 }
    val dlWifiOnly: Flow<Boolean> = context.dataStore.data.map { it[dlWifiKey] ?: false }
    /** Connections per file: 1 = off, up to 6. */
    val dlParts: Flow<Int> = context.dataStore.data.map { (it[dlPartsKey] ?: 4).coerceIn(1, 6) }

    suspend fun setDlConcurrent(n: Int) { context.dataStore.edit { it[dlConcurrentKey] = n.coerceIn(1, 5) } }
    suspend fun setDlSpeedKb(kb: Int) { context.dataStore.edit { it[dlSpeedKey] = kb } }
    suspend fun setDlWifiOnly(on: Boolean) { context.dataStore.edit { it[dlWifiKey] = on } }
    suspend fun setDlParts(n: Int) { context.dataStore.edit { it[dlPartsKey] = n.coerceIn(1, 6) } }

    // ---- subtitle style ----------------------------------------------------------------------------
    private val subSizeKey = intPreferencesKey("sub_size")
    private val subColorKey = intPreferencesKey("sub_color")
    private val subBgKey = booleanPreferencesKey("sub_bg")
    /** Subtitle size as a percentage of the normal size (60-200). */
    val subSize: Flow<Int> = context.dataStore.data.map { it[subSizeKey] ?: 100 }
    /** Subtitle colour as an ARGB int. */
    val subColor: Flow<Int> = context.dataStore.data.map { it[subColorKey] ?: 0xFFFFFFFF.toInt() }
    val subBackground: Flow<Boolean> = context.dataStore.data.map { it[subBgKey] ?: false }
    suspend fun setSubSize(p: Int) { context.dataStore.edit { it[subSizeKey] = p } }
    suspend fun setSubColor(c: Int) { context.dataStore.edit { it[subColorKey] = c } }
    suspend fun setSubBackground(on: Boolean) { context.dataStore.edit { it[subBgKey] = on } }

    // ---- first-run interests and the Home feed -------------------------------------------------------
    private val onboardingKey = booleanPreferencesKey("onboarding_done")
    private val interestsKey = stringSetPreferencesKey("interests")
    private val showFeedKey = booleanPreferencesKey("show_feed")

    /** Null until stored; false means the first-run interests screen should still be shown. */
    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[onboardingKey] ?: false }
    val interests: Flow<Set<Interest>> = context.dataStore.data.map { Interest.parse(it[interestsKey].orEmpty()) }
    val showFeed: Flow<Boolean> = context.dataStore.data.map { it[showFeedKey] ?: true }

    /** True once the choice has been made (or skipped for an existing user). */
    suspend fun onboardingSeen(): Boolean = context.dataStore.data.first().contains(onboardingKey)

    suspend fun finishOnboarding(chosen: Set<Interest>) {
        context.dataStore.edit { it[interestsKey] = chosen.map { i -> i.name }.toSet(); it[onboardingKey] = true }
    }
    suspend fun setInterests(chosen: Set<Interest>) { context.dataStore.edit { it[interestsKey] = chosen.map { i -> i.name }.toSet() } }
    suspend fun setShowFeed(on: Boolean) { context.dataStore.edit { it[showFeedKey] = on } }

    // ---- ad and tracker blocking ----------------------------------------------------------------------
    private val blockAdsKey = booleanPreferencesKey("block_ads")
    private val blockTrackersKey = booleanPreferencesKey("block_trackers")
    private val blockSitesKey = stringPreferencesKey("block_sites")

    val blocking: Flow<com.ziribrowser.adblock.BlockingConfig> = context.dataStore.data.map { p ->
        val sites = HashMap<String, com.ziribrowser.adblock.SiteBlock>()
        runCatching {
            val o = JSONObject(p[blockSitesKey] ?: "{}")
            o.keys().forEach { k -> o.optJSONObject(k)?.let { sites[k] = com.ziribrowser.adblock.SiteBlock(it.optBoolean("a", true), it.optBoolean("t", true), it.optBoolean("s", true)) } }
        }
        com.ziribrowser.adblock.BlockingConfig(p[blockAdsKey] ?: true, p[blockTrackersKey] ?: true, sites)
    }
    suspend fun setBlockAds(on: Boolean) { context.dataStore.edit { it[blockAdsKey] = on } }
    suspend fun setBlockTrackers(on: Boolean) { context.dataStore.edit { it[blockTrackersKey] = on } }
    /** Saves how [site] is filtered; null (or both on) removes the override. */
    suspend fun setBlockSite(site: String, value: com.ziribrowser.adblock.SiteBlock?) {
        context.dataStore.edit { p ->
            val o = runCatching { JSONObject(p[blockSitesKey] ?: "{}") }.getOrDefault(JSONObject())
            if (value == null || (value.ads && value.trackers && value.shield)) o.remove(site) else o.put(site, JSONObject().put("a", value.ads).put("t", value.trackers).put("s", value.shield))
            p[blockSitesKey] = o.toString()
        }
    }

    // ---- security / video switches (each defaults to on) --------------------------------------
    private val httpsFirstKey = booleanPreferencesKey("https_first")
    private val rememberVideoKey = booleanPreferencesKey("remember_video")
    private val autoPipKey = booleanPreferencesKey("auto_pip")

    /** Try https:// first for http:// addresses, falling back to http only if the site has no HTTPS. */
    val httpsFirst: Flow<Boolean> = context.dataStore.data.map { it[httpsFirstKey] ?: true }
    /** Resume videos where the user stopped watching. */
    val rememberVideo: Flow<Boolean> = context.dataStore.data.map { it[rememberVideoKey] ?: true }
    /** Shrink a playing fullscreen video into a floating window when leaving the app. */
    val autoPip: Flow<Boolean> = context.dataStore.data.map { it[autoPipKey] ?: true }

    private val httpsOnlyKey = booleanPreferencesKey("https_only")
    /** Show a warning page before opening a site that has no HTTPS. */
    val httpsOnly: Flow<Boolean> = context.dataStore.data.map { it[httpsOnlyKey] ?: true }
    suspend fun setHttpsOnly(on: Boolean) { context.dataStore.edit { it[httpsOnlyKey] = on } }

    // ---- passwords ---------------------------------------------------------------------------------
    private val pwOfferKey = booleanPreferencesKey("passwords_offer")
    private val pwUnlockKey = booleanPreferencesKey("passwords_unlock")
    /** Ask "Save password?" after a sign-in on a normal tab. */
    val passwordsOffer: Flow<Boolean> = context.dataStore.data.map { it[pwOfferKey] ?: true }
    suspend fun setPasswordsOffer(on: Boolean) { context.dataStore.edit { it[pwOfferKey] = on } }
    /** Ask for fingerprint / face / screen lock before filling a login or showing the list. */
    val passwordsUnlock: Flow<Boolean> = context.dataStore.data.map { it[pwUnlockKey] ?: true }
    suspend fun setPasswordsUnlock(on: Boolean) { context.dataStore.edit { it[pwUnlockKey] = on } }

    // ---- reader mode -------------------------------------------------------------------------------
    private val readerSizeKey = intPreferencesKey("reader_size")
    private val readerThemeKey = intPreferencesKey("reader_theme")
    private val readerSerifKey = booleanPreferencesKey("reader_serif")
    /** Reading text size in percent (80..200). */
    val readerSize: Flow<Int> = context.dataStore.data.map { it[readerSizeKey] ?: 100 }
    suspend fun setReaderSize(v: Int) { context.dataStore.edit { it[readerSizeKey] = v.coerceIn(80, 200) } }
    /** 0 light, 1 sepia, 2 dark. */
    val readerTheme: Flow<Int> = context.dataStore.data.map { it[readerThemeKey] ?: 0 }
    suspend fun setReaderTheme(v: Int) { context.dataStore.edit { it[readerThemeKey] = v.coerceIn(0, 2) } }
    val readerSerif: Flow<Boolean> = context.dataStore.data.map { it[readerSerifKey] ?: true }
    suspend fun setReaderSerif(on: Boolean) { context.dataStore.edit { it[readerSerifKey] = on } }
    suspend fun setHttpsFirst(on: Boolean) { context.dataStore.edit { it[httpsFirstKey] = on } }
    suspend fun setRememberVideo(on: Boolean) { context.dataStore.edit { it[rememberVideoKey] = on } }
    suspend fun setAutoPip(on: Boolean) { context.dataStore.edit { it[autoPipKey] = on } }

    // ---- remembered video positions: page URL -> seconds, newest 60 kept ---------------------------
    private val videoPosKey = stringPreferencesKey("video_positions")

    suspend fun videoPosition(pageKey: String): Double? {
        val arr = runCatching { JSONArray(context.dataStore.data.first()[videoPosKey] ?: "[]") }.getOrNull() ?: return null
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("k") == pageKey) return o.optDouble("p")
        }
        return null
    }

    /** Stores [seconds] for [pageKey]; a null value forgets it (video finished). */
    suspend fun setVideoPosition(pageKey: String, seconds: Double?) {
        context.dataStore.edit { p ->
            val old = runCatching { JSONArray(p[videoPosKey] ?: "[]") }.getOrDefault(JSONArray())
            val kept = ArrayList<JSONObject>()
            for (i in 0 until old.length()) old.optJSONObject(i)?.let { if (it.optString("k") != pageKey) kept += it }
            if (seconds != null) kept += JSONObject().put("k", pageKey).put("p", seconds)
            p[videoPosKey] = JSONArray(kept.takeLast(60)).toString()
        }
    }

    suspend fun setSearchEngine(e: SearchEngine) { context.dataStore.edit { it[engineKey] = e.name } }
    suspend fun setWebTextZoom(percent: Int) { context.dataStore.edit { it[webZoomKey] = percent.coerceIn(50, 200) } }

    /** Adds a site, or updates its title if the URL is already pinned. */
    suspend fun addQuickAccess(item: QuickAccessItem) {
        context.dataStore.edit { p ->
            val list = decode(p[quickKey]).filterNot { it.url == item.url }
            p[quickKey] = encode(list + item)
        }
    }

    /** Replaces [old] with [new] in place, keeping its position. */
    suspend fun replaceQuickAccess(old: QuickAccessItem, new: QuickAccessItem) {
        context.dataStore.edit { p ->
            val list = decode(p[quickKey])
            p[quickKey] = encode(list.map { if (it.url == old.url) new else it })
        }
    }

    suspend fun removeQuickAccess(url: String) {
        context.dataStore.edit { p -> p[quickKey] = encode(decode(p[quickKey]).filterNot { it.url == url }) }
    }

    // ---- site permissions (camera, microphone, location) remembered per site -------------------------
    private val permKey = stringPreferencesKey("site_permissions")

    /** Saved decisions keyed "origin|kind" → allowed. */
    val sitePermissions: Flow<Map<String, Boolean>> = context.dataStore.data.map { decodePerms(it[permKey]) }

    suspend fun sitePermission(origin: String, kind: String): Boolean? =
        decodePerms(context.dataStore.data.first()[permKey])["$origin|$kind"]

    suspend fun setSitePermission(origin: String, kind: String, allowed: Boolean) {
        context.dataStore.edit { p -> p[permKey] = encodePerms(decodePerms(p[permKey]) + ("$origin|$kind" to allowed)) }
    }

    suspend fun removeSitePermission(key: String) {
        context.dataStore.edit { p -> p[permKey] = encodePerms(decodePerms(p[permKey]) - key) }
    }

    suspend fun clearSitePermissions() { context.dataStore.edit { it.remove(permKey) } }

    private fun encodePerms(m: Map<String, Boolean>): String =
        JSONObject().also { o -> m.forEach { (k, v) -> o.put(k, v) } }.toString()

    private fun decodePerms(json: String?): Map<String, Boolean> {
        if (json.isNullOrEmpty()) return emptyMap()
        return runCatching {
            val o = JSONObject(json)
            o.keys().asSequence().associateWith { o.getBoolean(it) }
        }.getOrDefault(emptyMap())
    }

    private fun encode(list: List<QuickAccessItem>): String =
        JSONArray(list.map { JSONObject().put("t", it.title).put("u", it.url) }).toString()

    private fun decode(json: String?): List<QuickAccessItem> {
        if (json.isNullOrEmpty()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            List(arr.length()) { i -> arr.getJSONObject(i).let { QuickAccessItem(it.getString("t"), it.getString("u")) } }
        }.getOrDefault(emptyList())
    }
}
