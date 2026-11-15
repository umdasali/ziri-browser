package com.ziribrowser.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaParserTest {
    private val html = """
        <html><head>
        <title>  Fallback &amp; Title </title>
        <meta property="og:title" content="Example &quot;Site&quot;">
        <meta property='og:description' content='A short   description'>
        <meta property="og:image" content="/img/card.png">
        <meta property="og:site_name" content="Example">
        <link rel="icon" href="/favicon-32.png" sizes="32x32">
        <link rel="apple-touch-icon" href="https://cdn.example.com/touch.png" sizes="180x180">
        <link rel="mask-icon" href="/mask.svg">
        </head><body><meta property="og:title" content="ignored, in body"></body></html>
    """.trimIndent()

    @Test fun readsOpenGraphTags() {
        val p = MetaParser.parse(html, "https://www.example.com/page")
        assertEquals("Example \"Site\"", p.title)
        assertEquals("A short description", p.description)
        assertEquals("Example", p.siteName)
        assertEquals("https://www.example.com/img/card.png", p.image)
    }

    @Test fun picksBiggestIconFirstAndFallsBackToFaviconIco() {
        val p = MetaParser.parse(html, "https://www.example.com/page")
        assertEquals("https://cdn.example.com/touch.png", p.icons.first())
        assertTrue(p.icons.contains("https://www.example.com/favicon-32.png"))
        assertEquals("https://www.example.com/favicon.ico", p.icons.last())
        assertTrue(p.icons.none { it.endsWith("mask.svg") })
    }

    @Test fun usesTitleTagWhenNoOpenGraph() {
        val p = MetaParser.parse("<head><title>Just a title</title></head>", "https://a.org/")
        assertEquals("Just a title", p.title)
        assertNull(p.image)
    }

    @Test fun resolveRejectsNonHttpAndDataUrls() {
        assertNull(MetaParser.resolve("https://a.org/", "javascript:alert(1)"))
        assertNull(MetaParser.resolve("https://a.org/", "data:image/png;base64,AAAA"))
        assertEquals("https://a.org/x.png", MetaParser.resolve("https://a.org/dir/", "/x.png"))
    }
}
