package com.ziribrowser.passwords

import java.net.URI

/** A saved login as the rules below see it (the password is already decrypted). */
data class SavedLogin(val id: Long, val username: String, val password: String)

/** What to do after a person submits a login form. */
sealed interface SaveDecision {
    /** A login we haven't seen for this site. */
    data object Save : SaveDecision
    /** Same username, different password: offer to update entry [id]. */
    data class Update(val id: Long) : SaveDecision
    /** Nothing new, so don't ask. */
    data object Ignore : SaveDecision
}

/** The rules for when logins are saved and offered. Pure functions, so they are easy to test. */
object PasswordPolicy {
    const val MAX_FIELD = 512

    /** `scheme://host[:port]` in lower case, or null if the address isn't a web page we handle. */
    fun originOf(url: String): String? {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        val host = u.host?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val defaultPort = if (scheme == "https") 443 else 80
        return if (u.port == -1 || u.port == defaultPort) "$scheme://$host" else "$scheme://$host:${u.port}"
    }

    /** Only https pages (and your own machine) may have passwords saved or filled; plain http would leak them. */
    fun eligible(origin: String?): Boolean {
        origin ?: return false
        if (origin.startsWith("https://")) return true
        val host = origin.removePrefix("http://").substringBefore(':')
        return origin.startsWith("http://") && (host == "localhost" || host == "127.0.0.1")
    }

    /** True when [pageOrigin] is the origin the login was saved for; no sub-domain or look-alike matching. */
    fun matches(savedOrigin: String, pageOrigin: String): Boolean = savedOrigin.equals(pageOrigin, ignoreCase = true)

    fun decide(existing: List<SavedLogin>, username: String, password: String): SaveDecision {
        if (password.isEmpty() || password.length > MAX_FIELD || username.length > MAX_FIELD) return SaveDecision.Ignore
        val sameUser = existing.firstOrNull { it.username == username }
        return when {
            sameUser == null -> SaveDecision.Save
            sameUser.password == password -> SaveDecision.Ignore
            else -> SaveDecision.Update(sameUser.id)
        }
    }

    /** The short site name shown in lists: host without a leading "www.". */
    fun displayHost(origin: String): String =
        origin.substringAfter("://").substringBefore('/').removePrefix("www.")
}
