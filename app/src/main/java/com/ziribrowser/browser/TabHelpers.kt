package com.ziribrowser.browser

import com.ziribrowser.adblock.ResType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Decides when the browser bars should slide away: scrolling down (past the first screen) hides them, scrolling up or
 * reaching the top shows them. Right after a change it ignores scrolling for a moment, because showing or hiding the bars
 * resizes the page, which can nudge the scroll position by itself.
 */
class ChromeScrollTracker(private val now: () -> Long = System::currentTimeMillis) {
    private var accum = 0
    private var quietUntil = 0L
    private var hidden = false

    /** The new "hidden" state if this scroll changes it, otherwise null. */
    fun onScroll(y: Int, dy: Int): Boolean? {
        if (now() < quietUntil) return null
        if (y <= 8) return change(false)
        if (dy == 0) return null
        if (accum != 0 && (dy > 0) != (accum > 0)) accum = 0
        accum += dy
        return if (accum > 48 && y > 160) change(true) else if (accum < -32) change(false) else null
    }

    /** The bars were shown or hidden by something other than scrolling (a tap, a new page). */
    fun reset(hiddenNow: Boolean) { hidden = hiddenNow; accum = 0; quietUntil = now() + QUIET_MS }

    private fun change(to: Boolean): Boolean? {
        if (hidden == to) return null
        hidden = to; accum = 0; quietUntil = now() + QUIET_MS
        return to
    }

    private companion object { const val QUIET_MS = 450L }
}

/** Guesses what kind of resource a request is for the ad filters, from its file extension and Accept header. */
object RequestType {
    private val images = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "avif")
    private val fonts = setOf("woff", "woff2", "ttf", "otf", "eot")
    private val media = setOf("mp4", "webm", "mp3", "m4a", "ogg", "m3u8", "ts")

    fun guess(path: String, acceptHeader: String): Int {
        val ext = path.lowercase().substringAfterLast('.', "")
        val accept = acceptHeader.lowercase()
        return when {
            ext == "js" || ext == "mjs" -> ResType.SCRIPT
            ext == "css" || accept.startsWith("text/css") -> ResType.STYLE
            ext in images || accept.startsWith("image/") -> ResType.IMAGE
            ext in fonts -> ResType.FONT
            ext in media -> ResType.MEDIA
            accept.startsWith("text/html") -> ResType.SUBDOC
            else -> ResType.UNKNOWN
        }
    }
}

/**
 * Counts blocked requests per tab. The number shown is published at most every [publishDelayMs], so a page that blocks
 * hundreds of requests doesn't make the screen redraw for each one.
 */
class BlockCounter(private val scope: CoroutineScope, private val publishDelayMs: Long = 400) {
    private val counts_ = ConcurrentHashMap<String, AtomicInteger>()
    private val dirty = AtomicBoolean(false)
    private val _counts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val counts: StateFlow<Map<String, Int>> = _counts

    fun count(id: String) {
        counts_.getOrPut(id) { AtomicInteger() }.incrementAndGet()
        if (dirty.compareAndSet(false, true)) scope.launch {
            delay(publishDelayMs); dirty.set(false); publish()
        }
    }

    fun reset(id: String) { if ((counts_[id]?.getAndSet(0) ?: 0) > 0) publish() }
    fun remove(id: String) { counts_.remove(id) }

    private fun publish() { _counts.value = counts_.mapValues { it.value.get() } }
}
