package com.ziribrowser.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PrivacyTest {
    @Test fun stripsTrackingTagsAndKeepsTheRest() {
        assertEquals("https://a.com/p?id=7&page=2", TrackingParams.clean("https://a.com/p?utm_source=x&id=7&fbclid=abc&page=2"))
        assertEquals("https://a.com/p", TrackingParams.clean("https://a.com/p?utm_medium=x&gclid=1"))
        assertEquals("https://a.com/p#top", TrackingParams.clean("https://a.com/p?utm_campaign=x#top"))
        assertEquals("https://a.com/p?q=hello", TrackingParams.clean("https://a.com/p?q=hello"))
        assertEquals("https://a.com/p", TrackingParams.clean("https://a.com/p"))
    }

    @Test fun youtubeShareIdOnlyRemovedOnYoutube() {
        assertEquals("https://youtu.be/abc", TrackingParams.clean("https://youtu.be/abc?si=XYZ"))
        assertEquals("https://shop.com/x?si=5", TrackingParams.clean("https://shop.com/x?si=5"))
    }

    @Test fun reducesUserAgent() {
        val ua = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro Build/UQ1A) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"
        val r = FingerprintShield.reduceUserAgent(ua)
        assertTrue(r.contains("Android 10; K"))
        assertFalse(r.contains("Pixel"))
    }

    @Test fun scriptIsSeededAndHonoursExemptions() {
        val js = FingerprintShield.script("abc123", setOf("example.com"))
        assertTrue(js.contains("'abc123'"))
        assertTrue(js.contains("[\"example.com\"]"))
        assertTrue(js.contains("toDataURL") && js.contains("getChannelData") && js.contains("37446"))
    }

    /** Zero telemetry, checked in the build: no analytics, crash-reporting or ad SDK may be added as a dependency. */
    @Test fun noTrackingLibrariesInDependencies() {
        val banned = listOf("firebase", "crashlytics", "analytics", "sentry", "appsflyer", "adjust", "amplitude", "mixpanel", "segment", "flurry", "bugsnag", "admob", "play-services-ads", "facebook", "branch.io", "braze", "onesignal", "datadog", "newrelic")
        val files = listOf("../gradle/libs.versions.toml", "build.gradle.kts", "../build.gradle.kts").map { File(it) }.filter { it.isFile }
        assertTrue("dependency files not found", files.isNotEmpty())
        val text = files.joinToString("\n") { it.readText().lowercase() }
        banned.forEach { assertFalse("Tracking library found: $it", text.contains(it)) }
    }

    @Test fun manifestHasNoBackupAndOptsOutOfWebViewMetrics() {
        val m = File("src/main/AndroidManifest.xml").readText()
        assertTrue(m.contains("android:allowBackup=\"false\""))
        assertTrue(m.contains("android.webkit.WebView.MetricsOptOut"))
    }
}
