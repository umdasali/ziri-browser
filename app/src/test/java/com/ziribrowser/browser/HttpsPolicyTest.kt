package com.ziribrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpsPolicyTest {
    @Test fun parsesHostEvenWhenJavaUriRejectsTheAddress() {
        // java.net.URI throws on "|", "{", "^" or a space; the host must still be found so HTTPS-only can't be skipped.
        assertEquals("example.com", UrlUtils.rawHost("http://example.com/a|b"))
        assertEquals("example.com", UrlUtils.rawHost("http://user@example.com:8080/a b?x={1}"))
        assertEquals("example.com", UrlUtils.rawHost("http://example.com/path"))
    }

    @Test fun holdsBackPlainHttpWithUnparsablePath() {
        assertTrue(HttpsPolicy.holdsBack("http://example.com/a|b", httpsOnly = true, allowed = emptySet()))
        assertTrue(!HttpsPolicy.holdsBack("http://example.com/a|b", httpsOnly = true, allowed = setOf("example.com")))
        assertTrue(!HttpsPolicy.holdsBack("http://192.168.1.5/a|b", httpsOnly = true, allowed = emptySet()))
    }
}
