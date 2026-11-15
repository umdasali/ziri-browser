package com.ziribrowser.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class FilterEngineTest {
    companion object {
        lateinit var ads: FilterSet
        lateinit var trackers: FilterSet
        private fun load(vararg names: String) = FilterSet().also { set ->
            names.forEach { File("src/main/assets/filters/$it").bufferedReader().use(set::addAll) }
        }
        @BeforeClass @JvmStatic fun setup() { ads = load("easylist.txt"); trackers = load("easyprivacy.txt", "analytics.txt") }
    }

    private fun small(vararg rules: String) = FilterSet().also { s -> rules.forEach(s::add) }
    private fun blocked(set: FilterSet, url: String, page: String, type: Int = ResType.UNKNOWN) = set.blocks(Request(url, page, type))

    // ---- syntax ------------------------------------------------------------------------------------
    @Test fun hostAnchorMatchesSubdomainsButNotLookalikes() {
        val s = small("||ads.example.com^")
        assertTrue(blocked(s, "https://ads.example.com/x.js", "https://site.org/"))
        assertTrue(blocked(s, "https://eu.ads.example.com/x.js", "https://site.org/"))
        assertFalse(blocked(s, "https://notads.example.com/x.js", "https://site.org/"))
        assertFalse(blocked(s, "https://example.com/ads.example.com", "https://site.org/"))
    }

    @Test fun separatorAndWildcard() {
        val s = small("/banner/*/img^")
        assertTrue(blocked(s, "https://a.com/banner/foo/img?x=1", "https://b.com/"))
        assertTrue(blocked(s, "https://a.com/banner/foo/img", "https://b.com/"))
        assertFalse(blocked(s, "https://a.com/banner/foo/images", "https://b.com/"))
    }

    @Test fun exceptionWins() {
        val s = small("||tracker.com^", "@@||tracker.com/allowed.js")
        assertTrue(blocked(s, "https://tracker.com/t.js", "https://x.com/"))
        assertFalse(blocked(s, "https://tracker.com/allowed.js", "https://x.com/"))
    }

    @Test fun thirdPartyOption() {
        val s = small("||cdn.example.com^\$third-party")
        assertTrue(blocked(s, "https://cdn.example.com/a.js", "https://other.com/"))
        assertFalse(blocked(s, "https://cdn.example.com/a.js", "https://www.example.com/"))
    }

    @Test fun typeOptions() {
        val s = small("||x.com^\$image", "||y.com^\$~script")
        assertTrue(blocked(s, "https://x.com/a.png", "https://p.com/", ResType.IMAGE))
        assertFalse(blocked(s, "https://x.com/a.js", "https://p.com/", ResType.SCRIPT))
        assertTrue(blocked(s, "https://y.com/a.png", "https://p.com/", ResType.IMAGE))
        assertFalse(blocked(s, "https://y.com/a.js", "https://p.com/", ResType.SCRIPT))
    }

    @Test fun domainOption() {
        val s = small("||ads.net^\$domain=a.com|~b.a.com")
        assertTrue(blocked(s, "https://ads.net/x", "https://a.com/"))
        assertFalse(blocked(s, "https://ads.net/x", "https://b.a.com/"))
        assertFalse(blocked(s, "https://ads.net/x", "https://c.com/"))
    }

    @Test fun endAnchorAndStartAnchor() {
        val s = small("|https://a.com/ad.gif|")
        assertTrue(blocked(s, "https://a.com/ad.gif", "https://p.com/"))
        assertFalse(blocked(s, "https://a.com/ad.gif?x", "https://p.com/"))
        assertFalse(blocked(s, "https://x.https://a.com/ad.gif", "https://p.com/"))
    }

    @Test fun unsupportedRulesAreSkippedNotGuessed() {
        val s = small("||a.com^\$popup", "||b.com^\$csp=script-src 'none'", "/regex.*/", "example.com##.ad", "||c.com^\$redirect=noop.js")
        assertEquals(0, s.blockCount)
    }

    @Test fun documentExceptionAllowsWholeSite() {
        val s = small("||ads.com^", "@@||news.example^\$document")
        assertTrue(blocked(s, "https://ads.com/x", "https://other.org/"))
        assertFalse(blocked(s, "https://ads.com/x", "https://news.example/story"))
    }

    // ---- real lists --------------------------------------------------------------------------------
    @Test fun listsLoadWithPlentyOfRules() {
        assertTrue("EasyList rules: ${ads.blockCount}", ads.blockCount > 20_000)
        assertTrue("EasyPrivacy rules: ${trackers.blockCount}", trackers.blockCount > 10_000)
    }

    @Test fun knownAdAndTrackerEndpointsAreBlocked() {
        val page = "https://www.example-news.com/story"
        assertTrue(blocked(ads, "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", page, ResType.SCRIPT))
        assertTrue(blocked(ads, "https://securepubads.g.doubleclick.net/tag/js/gpt.js", page, ResType.SCRIPT))
        assertTrue(blocked(trackers, "https://www.google-analytics.com/analytics.js", page, ResType.SCRIPT))
        assertTrue(blocked(trackers, "https://www.google-analytics.com/g/collect?v=2", page, ResType.XHR))
        assertTrue(blocked(trackers, "https://connect.facebook.net/en_US/fbevents.js", page, ResType.SCRIPT))
        assertTrue(blocked(trackers, "https://static.hotjar.com/c/hotjar-123.js", page, ResType.SCRIPT))
        assertTrue(blocked(trackers, "https://api.mixpanel.com/track", page, ResType.XHR))
        assertTrue(blocked(trackers, "https://cdn.segment.com/analytics.js/v1/abc/analytics.min.js", page, ResType.SCRIPT))
    }

    /** Resources that sites need to work. A blocked entry here means the filter would break real pages. */
    @Test fun commonSiteResourcesAreNotBlocked() {
        val cases = listOf(
            "https://www.wikipedia.org/" to "https://www.wikipedia.org/",
            "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a9/Example.jpg/220px-Example.jpg" to "https://en.wikipedia.org/wiki/Example",
            "https://en.wikipedia.org/w/load.php?lang=en&modules=startup&only=scripts" to "https://en.wikipedia.org/wiki/Example",
            "https://github.githubassets.com/assets/app-4f2c.js" to "https://github.com/",
            "https://avatars.githubusercontent.com/u/1?v=4" to "https://github.com/",
            "https://cdn.jsdelivr.net/npm/react@18.3.1/umd/react.production.min.js" to "https://example.com/",
            "https://unpkg.com/react@18/umd/react.production.min.js" to "https://example.com/",
            "https://code.jquery.com/jquery-3.6.0.min.js" to "https://example.com/",
            "https://cdnjs.cloudflare.com/ajax/libs/vue/3.4.0/vue.global.min.js" to "https://example.com/",
            "https://fonts.googleapis.com/css2?family=Roboto:wght@400;700" to "https://example.com/",
            "https://fonts.gstatic.com/s/roboto/v30/KFOmCnqEu92Fr1Mu4mxK.woff2" to "https://example.com/",
            "https://www.gstatic.com/recaptcha/releases/abc/recaptcha__en.js" to "https://example.com/login",
            "https://www.google.com/recaptcha/api.js" to "https://example.com/login",
            "https://hcaptcha.com/1/api.js" to "https://example.com/login",
            "https://challenges.cloudflare.com/turnstile/v0/api.js" to "https://example.com/login",
            "https://accounts.google.com/gsi/client" to "https://example.com/login",
            "https://www.youtube.com/s/player/abc/player_ias.vflset/en_US/base.js" to "https://www.youtube.com/watch?v=x",
            "https://i.ytimg.com/vi/abc/hqdefault.jpg" to "https://www.youtube.com/",
            "https://rr1---sn-abc.googlevideo.com/videoplayback?expire=1" to "https://www.youtube.com/watch?v=x",
            "https://cdn.sstatic.net/Js/stub.en.js" to "https://stackoverflow.com/questions/1",
            "https://static.xx.fbcdn.net/rsrc.php/v3/y1/r/a.js" to "https://www.facebook.com/",
            "https://abs.twimg.com/responsive-web/client-web/main.js" to "https://twitter.com/home",
            "https://i.redd.it/abc.jpg" to "https://www.reddit.com/",
            "https://www.redditstatic.com/desktop2x/chunk.js" to "https://www.reddit.com/",
            "https://m.media-amazon.com/images/I/81abc.jpg" to "https://www.amazon.com/dp/B0",
            "https://images-na.ssl-images-amazon.com/images/G/01/x.js" to "https://www.amazon.com/",
            "https://www.paypalobjects.com/web/res/abc/js/paypal.js" to "https://www.paypal.com/signin",
            "https://js.stripe.com/v3/" to "https://example.com/checkout",
            "https://www.googletagmanager.com/gtag/js?id=G-1" to "https://www.googletagmanager.com/",
            "https://static.cloudflareinsights.com/beacon.min.js" to "https://static.cloudflareinsights.com/",
            "https://example.com/js/app.js" to "https://example.com/",
            "https://example.com/images/banner.png" to "https://example.com/",
            "https://news.ycombinator.com/y18.gif" to "https://news.ycombinator.com/",
            "https://res.cloudinary.com/demo/image/upload/sample.jpg" to "https://example.com/",
            "https://images.unsplash.com/photo-1?w=800" to "https://example.com/",
            "https://cdn.shopify.com/s/files/1/0000/theme.js" to "https://shop.example.com/",
            "https://maps.googleapis.com/maps/api/js?key=x" to "https://example.com/contact",
            "https://www.bbc.co.uk/news" to "https://www.bbc.co.uk/",
            "https://ichef.bbci.co.uk/news/976/cpsprodpb/abc.jpg" to "https://www.bbc.co.uk/news",
        )
        val bad = cases.filter { (url, page) ->
            val type = when { url.endsWith(".js") || url.contains(".js?") -> ResType.SCRIPT; url.matches(Regex(".*\\.(jpg|png|gif)(\\?.*)?$")) -> ResType.IMAGE; else -> ResType.UNKNOWN }
            blocked(ads, url, page, type) || blocked(trackers, url, page, type)
        }
        // Resources on a page that IS the tracker (googletagmanager/cloudflareinsights loaded first-party) are skipped above by design.
        val allowedToBlock = setOf("https://www.googletagmanager.com/gtag/js?id=G-1", "https://static.cloudflareinsights.com/beacon.min.js")
        val unexpected = bad.map { it.first }.filterNot { it in allowedToBlock }
        assertTrue("Wrongly blocked: $unexpected", unexpected.isEmpty())
    }

    @Test fun loadTimeAndMemory() {
        System.gc()
        val before = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        val t = System.nanoTime()
        val a = FilterSet().also { s -> File("src/main/assets/filters/easylist.txt").bufferedReader().use(s::addAll) }
        val b = FilterSet().also { s -> File("src/main/assets/filters/easyprivacy.txt").bufferedReader().use(s::addAll) }
        val ms = (System.nanoTime() - t) / 1_000_000
        System.gc()
        val mb = (Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } - before) / 1_048_576
        println("FILTER_LOAD ms=$ms rules=${a.blockCount + b.blockCount} approxMB=$mb")
        assertTrue("load took $ms ms", ms < 5000)
        assertTrue(a.blockCount > 0 && b.blockCount > 0)
    }

    @Test fun matchingIsFast() {
        val urls = (1..2000).map { "https://cdn$it.example.org/assets/v$it/app.bundle.js?x=$it" }
        val t = System.nanoTime()
        urls.forEach { blocked(ads, it, "https://www.example.org/", ResType.SCRIPT); blocked(trackers, it, "https://www.example.org/", ResType.SCRIPT) }
        val ms = (System.nanoTime() - t) / 1_000_000
        assertTrue("4000 lookups took $ms ms", ms < 3000)
    }
}
