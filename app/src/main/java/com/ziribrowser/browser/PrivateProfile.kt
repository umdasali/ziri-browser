package com.ziribrowser.browser

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Private tabs use a separate WebView profile (own cookies, storage and cache) where the system
 * WebView supports it. This is the only file that touches the Profile API.
 */
object PrivateProfile {
    private const val NAME = "ziri_private"
    /** Tor tabs get their own profile: cookies a private tab collected on the normal connection must never reach a site over Tor. */
    private const val TOR_NAME = "ziri_tor"
    private fun name(tor: Boolean) = if (tor) TOR_NAME else NAME

    val isSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    /** Must be called before the WebView loads anything. Returns true if the tab is truly isolated. */
    fun apply(webView: WebView, tor: Boolean = false): Boolean {
        if (!isSupported) return false
        return runCatching {
            ProfileStore.getInstance().getOrCreateProfile(name(tor))
            WebViewCompat.setProfile(webView, name(tor))
            true
        }.getOrDefault(false)
    }

    fun cookieFor(url: String, isPrivate: Boolean, isTor: Boolean = false): String {
        if (isPrivate && isSupported) {
            // Never fall back to the normal jar: that would send your signed-in cookie from a private tab.
            return runCatching { ProfileStore.getInstance().getProfile(name(isTor))?.cookieManager?.getCookie(url) }.getOrNull().orEmpty()
        }
        return CookieManager.getInstance().getCookie(url).orEmpty()
    }

    /**
     * Wipes what a private session stored: the clearnet-private profile, the Tor profile, or both (the default).
     * Call when the last tab of that kind closes. If the profile is still in use the delete fails, so it is retried a little later.
     */
    fun clear(tor: Boolean? = null) {
        if (!isSupported) return
        listOfNotNull(if (tor != true) NAME else null, if (tor != false) TOR_NAME else null).forEach { n ->
            if (runCatching { ProfileStore.getInstance().deleteProfile(n) }.isFailure) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { ProfileStore.getInstance().deleteProfile(n) } }, 1500)
            }
        }
    }
}
