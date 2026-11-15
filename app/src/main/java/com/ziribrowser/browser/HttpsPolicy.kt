package com.ziribrowser.browser

/** The "HTTPS-only" rule: which plain-http addresses get a warning page instead of loading. */
object HttpsPolicy {
    /**
     * True when [url] is plain http to a public site that the person hasn't already chosen to open. Local and LAN
     * addresses never have a public certificate, so they are always allowed.
     */
    fun holdsBack(url: String, httpsOnly: Boolean, allowed: Set<String>): Boolean {
        if (!httpsOnly || !url.startsWith("http://", ignoreCase = true)) return false
        val host = UrlUtils.rawHost(url).lowercase()
        if (host.isEmpty() || UrlUtils.isLocalHost(host)) return false
        return host !in allowed
    }
}
