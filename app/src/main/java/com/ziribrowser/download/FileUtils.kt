package com.ziribrowser.download

import com.ziribrowser.R
import com.ziribrowser.ziriString
import java.util.Locale

enum class Category(@androidx.annotation.StringRes val labelRes: Int, val folder: String) {
    DOCUMENTS(R.string.documents, "Documents"),
    IMAGES(R.string.images, "Images"),
    VIDEOS(R.string.videos, "Videos"),
    AUDIO(R.string.audio, "Audio"),
    ARCHIVES(R.string.archives, "Archives"),
    APPS(R.string.applications, "Apps"),
    OTHERS(R.string.others, "Others");

    /** Translated name for display; the on-disk [folder] never changes with the language. */
    val label: String get() = ziriString(labelRes)
}

object FileUtils {
    private val byExt: Map<String, Category> = buildMap {
        listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "epub", "rtf", "odt").forEach { put(it, Category.DOCUMENTS) }
        listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg").forEach { put(it, Category.IMAGES) }
        listOf("mp4", "webm", "mkv", "mov", "avi", "3gp").forEach { put(it, Category.VIDEOS) }
        listOf("mp3", "wav", "m4a", "ogg", "flac", "aac").forEach { put(it, Category.AUDIO) }
        listOf("zip", "rar", "7z", "gz", "tar", "bz2").forEach { put(it, Category.ARCHIVES) }
        put("apk", Category.APPS)
    }

    /** File types that must never be opened without an explicit warning. */
    private val dangerous = setOf("apk", "exe", "bat", "cmd", "sh", "msi", "jar", "js", "vbs", "scr", "com", "dex", "xapk", "apks", "apkm", "ps1", "lnk", "pkg", "dmg", "app", "hta", "reg", "so")

    fun extOf(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i > 0 && i < name.length - 1) name.substring(i + 1).lowercase(Locale.ROOT) else ""
    }

    /** True for videos, audio, pictures and PDFs, which Ziri opens in its own viewer. */
    fun isViewableInApp(category: String, mime: String, name: String): Boolean =
        category == Category.VIDEOS.name || category == Category.AUDIO.name || category == Category.IMAGES.name ||
            mime == "application/pdf" || extOf(name) == "pdf"

    fun isDangerous(name: String, mime: String = "") =
        extOf(name) in dangerous || mime.lowercase(Locale.ROOT).let {
            it == "application/vnd.android.package-archive" || it == "application/x-msdownload" || it == "application/x-sh" || it == "application/java-archive"
        }

    fun categoryOf(name: String, mime: String): Category {
        byExt[extOf(name)]?.let { return it }
        val m = mime.lowercase(Locale.ROOT)
        return when {
            m.startsWith("image/") -> Category.IMAGES
            m.startsWith("video/") -> Category.VIDEOS
            m.startsWith("audio/") -> Category.AUDIO
            m == "application/pdf" || m.startsWith("text/") -> Category.DOCUMENTS
            m.contains("zip") || m.contains("compressed") -> Category.ARCHIVES
            m == "application/vnd.android.package-archive" -> Category.APPS
            else -> Category.OTHERS
        }
    }

    /** Makes a server- or user-supplied name safe as a single path segment (no traversal, no control chars). */
    fun sanitizeFilename(raw: String, fallback: String = "download"): String {
        var n = runCatching { java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)
        n = n.replace(Regex("[\\u0000-\\u001f\\u007f]"), "")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\.{2,}"), ".")
            .trim().trimStart('.').trimEnd('.', ' ')
        if (n.isEmpty()) return fallback
        if (n.length > 120) {
            val e = extOf(n)
            n = n.take(120 - if (e.isEmpty()) 0 else e.length + 1) + if (e.isEmpty()) "" else ".$e"
        }
        return n
    }

    fun formatBytes(n: Long): String {
        if (n < 0) return "—"
        if (n < 1024) return "$n B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = n / 1024.0
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }

    fun formatSpeed(bps: Long) = if (bps > 0) "${formatBytes(bps)}/s" else ""

    fun formatEta(seconds: Long): String = when {
        seconds <= 0 -> ""
        seconds < 60 -> "${seconds}s left"
        seconds < 3600 -> "${(seconds + 59) / 60}m left"
        else -> "${seconds / 3600}h ${(seconds % 3600 + 59) / 60}m left"
    }
}
