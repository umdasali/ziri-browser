package com.ziribrowser.data

/**
 * Bookmark import/export in the "Netscape bookmark file" HTML format that Chrome, Firefox, Edge and
 * Safari all read and write, so Ziri can exchange bookmarks with any other browser.
 */
object BookmarkIo {
    fun toHtml(bookmarks: List<BookmarkEntity>): String = buildString {
        appendLine("<!DOCTYPE NETSCAPE-Bookmark-file-1>")
        appendLine("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">")
        appendLine("<TITLE>Bookmarks</TITLE>")
        appendLine("<H1>Bookmarks</H1>")
        appendLine("<DL><p>")
        val (loose, foldered) = bookmarks.partition { it.folder.isBlank() }
        loose.forEach { appendLine("    ${entry(it)}") }
        foldered.groupBy { it.folder }.forEach { (folder, items) ->
            appendLine("    <DT><H3>${escape(folder)}</H3>")
            appendLine("    <DL><p>")
            items.forEach { appendLine("        ${entry(it)}") }
            appendLine("    </DL><p>")
        }
        appendLine("</DL><p>")
    }

    private fun entry(b: BookmarkEntity) =
        "<DT><A HREF=\"${escape(b.url)}\" ADD_DATE=\"${b.createdAt / 1000}\">${escape(b.title.ifEmpty { b.url })}</A>"

    private val tokenRegex = Regex(
        "<H3[^>]*>(.*?)</H3>|<A\\s[^>]*?HREF\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')[^>]*>(.*?)</A>|</DL>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** Reads bookmarks from a Netscape-format file. Only http(s) links are kept; folders are flattened to one level. */
    fun parseHtml(html: String): List<BookmarkEntity> {
        val result = ArrayList<BookmarkEntity>()
        val folders = ArrayList<String>()
        var pendingFolder: String? = null
        for (m in tokenRegex.findAll(html)) {
            val text = m.value
            when {
                text.startsWith("<H3", ignoreCase = true) -> pendingFolder = unescape(m.groupValues[1]).trim()
                text.startsWith("</DL", ignoreCase = true) -> if (folders.isNotEmpty()) folders.removeAt(folders.lastIndex)
                else -> {
                    val url = unescape(m.groupValues[2].ifEmpty { m.groupValues[3] }).trim()
                    if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                        val title = unescape(m.groupValues[4].replace(Regex("<[^>]*>"), "")).trim()
                        result += BookmarkEntity(title = title.ifEmpty { url }, url = url, folder = folders.lastOrNull().orEmpty())
                    }
                }
            }
            // A folder's <DL> opens right after its <H3>; track it so links inside get that folder name.
            if (pendingFolder != null && text.startsWith("<H3", true)) { folders += pendingFolder; pendingFolder = null }
        }
        return result
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
}
