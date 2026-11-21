package com.ziribrowser.passwords

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.ziribrowser.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.SecureRandom

data class FillOption(val id: Long, val username: String)

/** Saved logins that fit the page on screen. Nothing is filled until the person taps. */
data class FillOffer(val tabId: String, val origin: String, val options: List<FillOption>)

/** "Save password?" waiting for an answer. [updateId] is set when this changes the password of an existing login. */
data class SavePrompt(val tabId: String, val origin: String, val username: String, val password: String, val updateId: Long?)

/**
 * Connects pages to the saved logins. Pages talk to it through a message channel that only the app side can read,
 * every message is checked against the tab's real address, and a login is only ever filled on the exact site it
 * was saved for, never on private or Tor tabs (no bridge is attached to those).
 */
class PasswordBridge(
    private val store: PasswordStore,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val token = ByteArray(6).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    private val channel = "zpw$token"
    private val fillFn = "__zf$token"
    private val script = PageScript.build(channel, fillFn)

    private val scripts = HashMap<String, ScriptHandler>()
    private val _offers = MutableStateFlow<Map<String, FillOffer>>(emptyMap())
    val offers: StateFlow<Map<String, FillOffer>> = _offers
    private val _prompts = MutableStateFlow<Map<String, SavePrompt>>(emptyMap())
    val prompts: StateFlow<Map<String, SavePrompt>> = _prompts

    /** When a sign-in was last submitted on a tab, and when a sign-in form was last seen again after that. */
    private val submittedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val formSeenAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val pending = java.util.concurrent.ConcurrentHashMap<String, SavePrompt>()

    val supported: Boolean get() =
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    fun attach(tabId: String, wv: WebView, currentUrl: () -> String) {
        if (!supported) return
        runCatching {
            scripts[tabId] = WebViewCompat.addDocumentStartJavaScript(wv, script, setOf("*"))
            WebViewCompat.addWebMessageListener(wv, channel, setOf("*")) { _, message, sourceOrigin, isMainFrame, _ ->
                if (isMainFrame) onMessage(tabId, message, sourceOrigin.toString().trimEnd('/'), currentUrl())
            }
        }
    }

    fun detach(tabId: String, wv: WebView?) {
        runCatching { scripts.remove(tabId)?.remove() }
        runCatching { wv?.let { WebViewCompat.removeWebMessageListener(it, channel) } }
        forget(tabId)
    }

    /** A new page started loading: the old offer no longer applies. A pending save survives the redirect after sign-in. */
    fun onNavigation(tabId: String) {
        _offers.update { it - tabId }
    }

    /** The tab is gone: forget everything held for it. */
    fun forget(tabId: String) {
        _offers.update { it - tabId }
        _prompts.update { it - tabId }
        pending.remove(tabId); submittedAt.remove(tabId); formSeenAt.remove(tabId)
    }

    private fun onMessage(tabId: String, message: WebMessageCompat, sourceOrigin: String, pageUrl: String) {
        val origin = PasswordPolicy.originOf(sourceOrigin)
        // The message must come from the very site the tab is showing, over a connection we trust.
        if (origin == null || !PasswordPolicy.eligible(origin) || PasswordPolicy.originOf(pageUrl) != origin) return
        val json = runCatching { JSONObject(message.data ?: return) }.getOrNull() ?: return
        when (json.optString("t")) {
            "form" -> {
                formSeenAt[tabId] = System.currentTimeMillis()
                scope.launch {
                    val found = store.forOrigin(origin)
                    val offer = FillOffer(tabId, origin, found.map { FillOption(it.id, it.username) })
                    _offers.update { cur -> if (found.isEmpty()) cur - tabId else cur + (tabId to offer) }
                }
            }
            "submit" -> onSubmit(tabId, origin, json.optString("user"), json.optString("pass"))
        }
    }

    private fun onSubmit(tabId: String, origin: String, user: String, pass: String) {
        scope.launch {
            if (!settings.passwordsOffer.first() || store.isNeverSaved(origin)) return@launch
            val decision = PasswordPolicy.decide(store.savedLogins(origin), user, pass)
            val now = System.currentTimeMillis()
            val prompt = when (decision) {
                SaveDecision.Ignore -> return@launch
                SaveDecision.Save -> SavePrompt(tabId, origin, user, pass, null)
                is SaveDecision.Update -> SavePrompt(tabId, origin, user, pass, decision.id)
            }
            submittedAt[tabId] = now
            pending[tabId] = prompt
            // Wait to see whether the sign-in worked: if the form is back, the password was probably wrong.
            delay(1500)
            if (pending[tabId] === prompt && (formSeenAt[tabId] ?: 0L) <= now) {
                _prompts.update { it + (tabId to prompt) }
                pending.remove(tabId)
            } else if (pending[tabId] === prompt) pending.remove(tabId)
        }
    }

    fun fill(tabId: String, wv: WebView, id: Long) {
        scope.launch {
            val offer = _offers.value[tabId] ?: return@launch
            // Database and decryption off the main thread; every WebView call back on it.
            val entry = store.forOrigin(offer.origin).firstOrNull { it.id == id } ?: return@launch
            val password = store.reveal(entry) ?: return@launch
            withContext(Dispatchers.Main) {
                // Check again that the page is still the site this login belongs to.
                val now = PasswordPolicy.originOf(wv.url.orEmpty())
                if (now == null || !PasswordPolicy.matches(entry.origin, now) || !PasswordPolicy.eligible(now)) return@withContext
                wv.evaluateJavascript("window.$fillFn(${JsString.quote(entry.username)},${JsString.quote(password)})", null)
            }
            store.markUsed(id)
        }
    }

    fun save(p: SavePrompt) {
        _prompts.update { it - p.tabId }
        scope.launch { runCatching { if (p.updateId != null) store.update(p.updateId, p.username, p.password) else store.add(p.origin, p.username, p.password) } }
    }

    fun never(p: SavePrompt) {
        _prompts.update { it - p.tabId }
        scope.launch { runCatching { store.neverSave(p.origin) } }
    }

    fun dismissPrompt(tabId: String) { _prompts.update { it - tabId } }
    fun dismissOffer(tabId: String) { _offers.update { it - tabId } }
}
