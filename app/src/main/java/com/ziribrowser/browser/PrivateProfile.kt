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

    val isSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    /** Must be called before the WebView loads anything. Returns true if the tab is truly isolated. */
    fun apply(webView: WebView): Boolean {
        if (!isSupported) return false
        return runCatching {
            ProfileStore.getInstance().getOrCreateProfile(NAME)
            WebViewCompat.setProfile(webView, NAME)
            true
        }.getOrDefault(false)
    }

    fun cookieFor(url: String, isPrivate: Boolean): String {
        if (isPrivate && isSupported) {
            runCatching {
                ProfileStore.getInstance().getProfile(NAME)?.cookieManager?.getCookie(url)
            }.getOrNull()?.let { return it }
        }
        return CookieManager.getInstance().getCookie(url).orEmpty()
    }

    /** Wipes everything the private session stored. Call when the last private tab closes. */
    fun clear() {
        if (!isSupported) return
        runCatching { ProfileStore.getInstance().deleteProfile(NAME) }
    }
}
