package com.ziribrowser.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPageTest {
    private val article = Article("Hello <b>& \"world\"</b>", "Ann <ann@x>", "Site", "ltr", "<p>Body</p><img src=\"https://a/b.png\">", "https://example.com/a")

    @Test fun escapesHtmlSpecialCharacters() {
        assertEquals("&lt;a&gt; &amp; &quot;b&quot; &#39;c&#39;", ReaderPage.escape("<a> & \"b\" 'c'"))
    }

    @Test fun titleAndBylineAreEscapedButBodyIsKept() {
        val h = ReaderPage.html(article, ReaderStyle())
        assertTrue(h.contains("<h1>Hello &lt;b&gt;&amp; &quot;world&quot;&lt;/b&gt;</h1>"))
        assertTrue(h.contains("Ann &lt;ann@x&gt; · Site"))
        assertTrue(h.contains("<p>Body</p>"))
    }

    @Test fun pageForbidsScriptsAndConnections() {
        val h = ReaderPage.html(article, ReaderStyle())
        assertTrue(h.contains("Content-Security-Policy"))
        assertTrue(ReaderPage.CSP.startsWith("default-src 'none'"))
        assertFalse(ReaderPage.CSP.contains("script-src"))
        assertFalse(ReaderPage.CSP.contains("connect-src"))
        assertTrue(ReaderPage.CSP.contains("img-src https: data:"))
    }

    @Test fun themeSizeAndFontAreApplied() {
        val dark = ReaderPage.html(article, ReaderStyle(sizePercent = 130, theme = 2, serif = false))
        assertTrue(dark.contains("background:#121416"))
        assertTrue(dark.contains("font-size:130%"))
        assertTrue(dark.contains("system-ui"))
        val sepia = ReaderPage.html(article, ReaderStyle(theme = 1))
        assertTrue(sepia.contains("#f4ecd8"))
        assertTrue(sepia.contains("Georgia"))
    }

    @Test fun transparentImagesGetALightBackingOnlyInTheDarkTheme() {
        assertTrue(ReaderPage.html(article, ReaderStyle(theme = 2)).contains("border-radius:6px;background:#ffffff"))
        assertTrue(ReaderPage.html(article, ReaderStyle(theme = 0)).contains("border-radius:6px;background:transparent"))
    }

    @Test fun outOfRangeSettingsAreClamped() {
        val h = ReaderPage.html(article, ReaderStyle(sizePercent = 9999, theme = 77))
        assertTrue(h.contains("font-size:200%"))
        assertTrue(h.contains("background:#ffffff"))
    }

    @Test fun rtlArticlesKeepTheirDirection() {
        assertTrue(ReaderPage.html(article.copy(dir = "rtl"), ReaderStyle()).contains("<html dir=\"rtl\">"))
        assertTrue(ReaderPage.html(article.copy(dir = "weird"), ReaderStyle()).contains("<html dir=\"ltr\">"))
    }
}
