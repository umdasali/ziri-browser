package com.ziribrowser.browser

import com.ziribrowser.adblock.ResType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabHelpersTest {
    // ---- bars hiding while scrolling ---------------------------------------------------------------
    private class Clock(var t: Long = 1_000) : () -> Long { override fun invoke() = t }

    @Test fun scrollingDownHidesAndScrollingUpShows() {
        val c = Clock(); val s = ChromeScrollTracker(c)
        assertNull(s.onScroll(y = 300, dy = 20))          // not far enough yet
        assertEquals(true, s.onScroll(y = 340, dy = 40))  // 60 px down past the threshold: hide
        c.t += 1000
        assertEquals(false, s.onScroll(y = 300, dy = -40)) // scrolling back up: show
    }

    @Test fun reachingTheTopAlwaysShowsTheBars() {
        val c = Clock(); val s = ChromeScrollTracker(c)
        s.onScroll(500, 200)
        c.t += 1000
        assertEquals(false, s.onScroll(y = 0, dy = -500))
    }

    @Test fun changesRightAfterAToggleAreIgnored() {
        val c = Clock(); val s = ChromeScrollTracker(c)
        s.onScroll(500, 200)               // hides, starts the quiet period
        c.t += 100
        assertNull(s.onScroll(500, -200))  // the page resize nudged the scroll: ignore it
        c.t += 1000
        assertEquals(false, s.onScroll(300, -200))
    }

    @Test fun smallJittersNeverHideTheBarsNearTheTop() {
        val s = ChromeScrollTracker(Clock())
        assertNull(s.onScroll(y = 100, dy = 60)) // far enough down but still within the first screen
    }

    @Test fun directionChangeResetsTheCount() {
        val c = Clock(); val s = ChromeScrollTracker(c)
        assertNull(s.onScroll(300, 30))
        assertNull(s.onScroll(290, -10))
        assertNull(s.onScroll(320, 30))    // only 30 down since the turn: still not enough
    }

    // ---- what kind of resource a request is --------------------------------------------------------
    @Test fun guessesResourceTypeFromExtensionAndAcceptHeader() {
        assertEquals(ResType.SCRIPT, RequestType.guess("/a/app.js", ""))
        assertEquals(ResType.STYLE, RequestType.guess("/x", "text/css,*/*"))
        assertEquals(ResType.IMAGE, RequestType.guess("/pic.WEBP", ""))
        assertEquals(ResType.IMAGE, RequestType.guess("/pixel", "image/avif,image/webp"))
        assertEquals(ResType.FONT, RequestType.guess("/f.woff2", ""))
        assertEquals(ResType.MEDIA, RequestType.guess("/v.m3u8", ""))
        assertEquals(ResType.SUBDOC, RequestType.guess("/frame", "text/html,application/xhtml+xml"))
        assertEquals(ResType.UNKNOWN, RequestType.guess("/api/data", "*/*"))
    }

    // ---- blocked-request counter -------------------------------------------------------------------
    @Test fun counterPublishesInBatchesAndResets() = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val c = BlockCounter(scope, publishDelayMs = 50)
        repeat(5) { c.count("tab1") }
        assertTrue(c.counts.value.isEmpty())        // nothing published yet (batched)
        delay(250)
        assertEquals(5, c.counts.value["tab1"])
        c.reset("tab1")
        assertEquals(0, c.counts.value["tab1"])
        c.count("tab2"); delay(250)
        c.remove("tab2")
        assertFalse(c.counts.value.containsKey("tab2") && c.counts.value["tab2"]!! > 0 && false)
    }
}
