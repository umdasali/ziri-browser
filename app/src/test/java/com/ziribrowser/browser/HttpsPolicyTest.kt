package com.ziribrowser.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpsPolicyTest {
    @Test fun plainHttpToAPublicSiteIsHeldBack() {
        assertTrue(HttpsPolicy.holdsBack("http://example.com/page", httpsOnly = true, allowed = emptySet()))
        assertTrue(HttpsPolicy.holdsBack("HTTP://Example.com", httpsOnly = true, allowed = emptySet()))
        assertTrue(HttpsPolicy.holdsBack("http://example.com:8080/x", httpsOnly = true, allowed = emptySet()))
    }

    @Test fun httpsIsNeverHeldBack() {
        assertFalse(HttpsPolicy.holdsBack("https://example.com", httpsOnly = true, allowed = emptySet()))
    }

    @Test fun localAddressesAreExempt() {
        assertFalse(HttpsPolicy.holdsBack("http://localhost:8765/", httpsOnly = true, allowed = emptySet()))
        assertFalse(HttpsPolicy.holdsBack("http://192.168.1.10/router", httpsOnly = true, allowed = emptySet()))
        assertFalse(HttpsPolicy.holdsBack("http://printer.local/", httpsOnly = true, allowed = emptySet()))
    }

    @Test fun aSiteTheUserAllowedIsNotHeldBackAgain() {
        assertFalse(HttpsPolicy.holdsBack("http://old-site.org/a", httpsOnly = true, allowed = setOf("old-site.org")))
        assertTrue(HttpsPolicy.holdsBack("http://other.org/a", httpsOnly = true, allowed = setOf("old-site.org")))
    }

    @Test fun switchedOffNothingIsHeldBack() {
        assertFalse(HttpsPolicy.holdsBack("http://example.com", httpsOnly = false, allowed = emptySet()))
    }
}
