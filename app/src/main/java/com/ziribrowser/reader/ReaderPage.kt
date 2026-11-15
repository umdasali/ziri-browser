package com.ziribrowser.reader

/** An article pulled out of a page. [html] comes from the page and is therefore untrusted. */
data class Article(
    val title: String,
    val byline: String,
    val siteName: String,
    val dir: String,
    val html: String,
    val baseUrl: String,
)

/** How the article is shown: text size in percent, theme 0 light / 1 sepia / 2 dark, serif or sans. */
data class ReaderStyle(val sizePercent: Int = 100, val theme: Int = 0, val serif: Boolean = true)

/** Builds the page the reader shows. */
object ReaderPage {
    /**
     * The article text is page content and could contain anything, so the reader page is locked down three ways:
     * the Content-Security-Policy below allows no script, frame, form or connection at all (only images and the
     * built-in style), the WebView that shows it has JavaScript off, and the title and byline are escaped.
     */
    const val CSP = "default-src 'none'; img-src https: data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'"

    fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '"' -> append("&quot;"); '\'' -> append("&#39;"); else -> append(c)
        }
    }

    private data class Palette(val bg: String, val fg: String, val muted: String, val link: String, val rule: String, val imgBg: String = "transparent")

    private fun palette(theme: Int) = when (theme) {
        1 -> Palette("#f4ecd8", "#3b2f22", "#7a6a58", "#8a4b12", "#d9ccb0")
        2 -> Palette("#121416", "#e4e6e8", "#9aa0a6", "#6fd0c4", "#2a2e32", imgBg = "#ffffff")
        else -> Palette("#ffffff", "#1c1e20", "#656a70", "#0b7a70", "#e3e5e8")
    }

    fun html(a: Article, s: ReaderStyle): String {
        val p = palette(s.theme)
        val font = if (s.serif) "Georgia, 'Noto Serif', serif" else "system-ui, 'Roboto', sans-serif"
        val size = s.sizePercent.coerceIn(80, 200)
        val dir = if (a.dir.equals("rtl", true)) "rtl" else "ltr"
        return """<!doctype html>
<html dir="$dir"><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="$CSP">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<style>
html{-webkit-text-size-adjust:none}
body{margin:0;padding:24px 20px 96px;background:${p.bg};color:${p.fg};font-family:$font;font-size:${size}%;line-height:1.65;word-wrap:break-word;overflow-wrap:anywhere}
main{max-width:42rem;margin:0 auto}
h1{font-size:1.65em;line-height:1.25;margin:.2em 0 .4em}
.meta{color:${p.muted};font-size:.85em;margin-bottom:1.6em;padding-bottom:1em;border-bottom:1px solid ${p.rule}}
a{color:${p.link}}
img,video,svg,iframe,table{max-width:100%;height:auto}
img{display:block;margin:1em auto;border-radius:6px;background:${p.imgBg}}
pre,code{font-family:ui-monospace,monospace;font-size:.9em;background:${p.rule};border-radius:4px}
pre{padding:12px;overflow-x:auto}
code{padding:1px 4px}
blockquote{margin:1em 0;padding-left:1em;border-left:3px solid ${p.rule};color:${p.muted}}
table{border-collapse:collapse;display:block;overflow-x:auto}
td,th{border:1px solid ${p.rule};padding:4px 8px}
figcaption{color:${p.muted};font-size:.85em;text-align:center}
</style></head><body><main>
<h1>${escape(a.title)}</h1>
<div class="meta">${escape(listOf(a.byline, a.siteName).filter { it.isNotBlank() }.joinToString(" · "))}</div>
${a.html}
</main></body></html>"""
    }
}
