package com.ziribrowser.browser

import com.ziribrowser.download.Category
import com.ziribrowser.download.FileUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for bugs found in the end-to-end audit. */
class AuditFixesTest {
    @Test fun hostsThatOnlyStartLikeAnIpAreNotLocal() {
        // "10.evil.com" used to count as a private address and skip HTTPS-only / HTTPS-first.
        assertFalse(UrlUtils.isLocalHost("10.evil.com"))
        assertFalse(UrlUtils.isLocalHost("192.168.evil.org"))
        assertFalse(UrlUtils.isLocalHost("127.attacker.net"))
        assertTrue(UrlUtils.isLocalHost("192.168.1.20"))
        assertTrue(UrlUtils.isLocalHost("localhost"))
        assertTrue(UrlUtils.isLocalHost("printer.local"))
    }

    @Test fun backslashIsReadAsSlashLikeTheBrowserDoes() {
        // Chromium loads "http://evil.com\@10.0.0.1/" from evil.com, so the host check must see evil.com too.
        assertEquals("evil.com", UrlUtils.rawHost("http://evil.com\\@10.0.0.1/"))
    }

    @Test fun fileNamesAreCutByBytesNotCharacters() {
        val long = "视".repeat(200) + ".mp4" // 600 bytes of Chinese
        val name = FileUtils.sanitizeFilename(long)
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 120)
        assertTrue(name.endsWith(".mp4"))
        // never splits a character
        assertEquals(name, String(name.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        assertEquals("ab", FileUtils.takeBytes("abc", 2))
        assertEquals("视", FileUtils.takeBytes("视视", 4)) // 3 bytes each
    }

    @Test fun bidiControlsAreStrippedFromNames() {
        assertEquals("invoiceexe.txt", FileUtils.sanitizeFilename("invoice‮exe.txt"))
    }

    @Test fun moreVideoTypesAreRecognised() {
        for (ext in listOf("ts", "m4v", "flv", "wmv", "mpg", "mts", "ogv")) assertEquals(ext, Category.VIDEOS, FileUtils.categoryOf("clip.$ext", "application/octet-stream"))
        for (ext in listOf("opus", "wma", "mka", "amr")) assertEquals(ext, Category.AUDIO, FileUtils.categoryOf("song.$ext", "application/octet-stream"))
    }

    @Test fun typescriptSourceIsNotAVideo() {
        assertEquals(Category.DOCUMENTS, FileUtils.categoryOf("app.ts", "text/typescript"))
        assertEquals(Category.VIDEOS, FileUtils.categoryOf("stream.ts", "video/mp2t"))
    }
}
