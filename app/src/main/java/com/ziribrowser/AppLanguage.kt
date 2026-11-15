package com.ziribrowser

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** A language Ziri has translations for. [tag] is a BCP-47 tag; [name] is written in that language itself. */
data class AppLang(val tag: String, val name: String)

/**
 * The in-app language choice. Android 13+ has a built-in per-app language setting, so we use that there
 * (it also shows up in system Settings). On older versions the choice is stored here and applied by
 * wrapping the base context of the app and its activity.
 */
object AppLanguage {
    val supported = listOf(
        AppLang("en", "English"), AppLang("hi", "हिन्दी"), AppLang("ur", "اردو"),
        AppLang("zh-CN", "中文（简体）"), AppLang("ja", "日本語"), AppLang("es", "Español"),
        AppLang("ar", "العربية"), AppLang("fr", "Français"), AppLang("de", "Deutsch"),
        AppLang("pt", "Português"), AppLang("ru", "Русский"), AppLang("bn", "বাংলা"),
        AppLang("ko", "한국어"), AppLang("in", "Bahasa Indonesia"), AppLang("tr", "Türkçe"),
        AppLang("vi", "Tiếng Việt"), AppLang("it", "Italiano"), AppLang("fa", "فارسی"),
        AppLang("th", "ไทย"), AppLang("ta", "தமிழ்"), AppLang("te", "తెలుగు"), AppLang("mr", "मराठी"),
    )

    private const val PREFS = "language"
    private const val KEY = "tag"

    private fun stored(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    /** The chosen language tag, or "" for "follow the system". */
    fun current(context: Context): String =
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.let { if (it.isEmpty) "" else it[0].toLanguageTag() }
        } else stored(context)

    /** Matches the current choice to an entry of [supported] (e.g. "zh-Hans-CN" → "zh-CN"). */
    fun currentSupported(context: Context): String {
        val tag = current(context)
        if (tag.isEmpty()) return ""
        val l = Locale.forLanguageTag(tag)
        return supported.firstOrNull { it.tag.equals(tag, true) }?.tag
            ?: supported.firstOrNull { Locale.forLanguageTag(it.tag).language == l.language }?.tag ?: ""
    }

    /** Saves the choice. On Android 13+ the system recreates the activity; below that the caller should recreate it. */
    fun set(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        }
    }

    /** A context whose resources use the chosen language (only needed below Android 13). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = stored(base)
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
