package com.ziribrowser.browser

import com.ziribrowser.data.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlUtilsTest {
    private val google = SearchEngine.GOOGLE

    @Test fun `full http and https urls are kept as typed`() {
        assertEquals("https://example.com/a?b=1", UrlUtils.resolve("https://example.com/a?b=1", google))
        assertEquals("http://example.com", UrlUtils.resolve("  http://example.com ", google))
    }

    @Test fun `bare domains get https`() {
        assertEquals("https://example.com", UrlUtils.resolve("example.com", google))
        assertEquals("https://sub.example.co.uk/path", UrlUtils.resolve("sub.example.co.uk/path", google))
        assertEquals("https://example.com:8080/x", UrlUtils.resolve("example.com:8080/x", google))
    }

    @Test fun `localhost and ip addresses get https`() {
        assertEquals("https://localhost:3000", UrlUtils.resolve("localhost:3000", google))
        assertEquals("https://192.168.1.1/admin", UrlUtils.resolve("192.168.1.1/admin", google))
    }

    @Test fun `plain words are searched`() {
        assertEquals("https://www.google.com/search?q=hello%20world", UrlUtils.resolve("hello world", google))
        assertEquals("https://duckduckgo.com/?q=kotlin", UrlUtils.resolve("kotlin", SearchEngine.DUCKDUCKGO))
    }

    @Test fun `dangerous schemes are searched and never loaded`() {
        for (input in listOf("javascript:alert(1)", "file:///etc/passwd", "intent://x#Intent;end", "data:text/html,hi")) {
            assertTrue(input, UrlUtils.resolve(input, google).startsWith(google.queryUrl))
        }
    }

    @Test fun `search text is percent-encoded`() {
        val url = UrlUtils.resolve("a&b=c d#e", google)
        assertEquals("https://www.google.com/search?q=a%26b%3Dc%20d%23e", url)
    }

    @Test fun `isHttp only accepts http and https`() {
        assertTrue(UrlUtils.isHttp("https://a.com"))
        assertTrue(UrlUtils.isHttp("HTTP://a.com"))
        assertFalse(UrlUtils.isHttp("ftp://a.com"))
        assertFalse(UrlUtils.isHttp("javascript:1"))
        assertFalse(UrlUtils.isHttp(null))
    }

    @Test fun `host strips www and falls back to the input`() {
        assertEquals("example.com", UrlUtils.host("https://www.example.com/page"))
        assertEquals("news.example.com", UrlUtils.host("https://news.example.com"))
        assertEquals("not a url", UrlUtils.host("not a url"))
    }

    @Test fun `likeEscape escapes sql wildcards`() {
        assertEquals("100\\%\\_done\\\\", UrlUtils.likeEscape("100%_done\\"))
    }

    @Test fun `upgradeToHttps upgrades plain http`() {
        assertEquals("https://example.com/a?b=1", UrlUtils.upgradeToHttps("http://example.com/a?b=1"))
        assertEquals("https://example.com", UrlUtils.upgradeToHttps("http://example.com"))
    }

    @Test fun `upgradeToHttps leaves https ports and local hosts alone`() {
        assertEquals(null, UrlUtils.upgradeToHttps("https://example.com"))
        assertEquals(null, UrlUtils.upgradeToHttps("http://example.com:8080/x"))
        assertEquals(null, UrlUtils.upgradeToHttps("http://localhost/app"))
        assertEquals(null, UrlUtils.upgradeToHttps("http://192.168.1.10/admin"))
        assertEquals(null, UrlUtils.upgradeToHttps("http://8.8.8.8"))
        assertEquals(null, UrlUtils.upgradeToHttps("http://printer.local"))
        assertEquals(null, UrlUtils.upgradeToHttps("ftp://example.com"))
    }

    @Test fun `isLocalHost recognises private networks`() {
        assertTrue(UrlUtils.isLocalHost("localhost"))
        assertTrue(UrlUtils.isLocalHost("10.0.0.5"))
        assertTrue(UrlUtils.isLocalHost("172.20.1.1"))
        assertTrue(UrlUtils.isLocalHost("nas.lan"))
        assertFalse(UrlUtils.isLocalHost("example.com"))
        assertFalse(UrlUtils.isLocalHost("myhost10.example.org"))
    }
}
