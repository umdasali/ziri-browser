package com.ziribrowser.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileUtilsTest {
    @Test fun `sanitizeFilename blocks path traversal and bad characters`() {
        assertEquals("passwd", FileUtils.sanitizeFilename("../../etc/passwd").substringAfterLast("_"))
        assertFalse(FileUtils.sanitizeFilename("../../etc/passwd").contains("/"))
        assertEquals("a_b_c.txt", FileUtils.sanitizeFilename("a:b*c.txt"))
        assertEquals("download", FileUtils.sanitizeFilename("..."))
        assertEquals("download", FileUtils.sanitizeFilename(""))
        assertEquals("custom", FileUtils.sanitizeFilename("   ", fallback = "custom"))
    }

    @Test fun `sanitizeFilename strips control characters and decodes percent escapes`() {
        assertEquals("ab.txt", FileUtils.sanitizeFilename("a\u0000b\u001f.txt"))
        assertEquals("my file.pdf", FileUtils.sanitizeFilename("my%20file.pdf"))
    }

    @Test fun `sanitizeFilename shortens long names but keeps the extension`() {
        val name = FileUtils.sanitizeFilename("a".repeat(300) + ".pdf")
        assertTrue(name.length <= 120)
        assertTrue(name.endsWith(".pdf"))
    }

    @Test fun `categoryOf prefers the extension then the mime type`() {
        assertEquals(Category.DOCUMENTS, FileUtils.categoryOf("report.PDF", "application/octet-stream"))
        assertEquals(Category.IMAGES, FileUtils.categoryOf("noext", "image/png"))
        assertEquals(Category.VIDEOS, FileUtils.categoryOf("clip", "video/mp4"))
        assertEquals(Category.APPS, FileUtils.categoryOf("x.apk", ""))
        assertEquals(Category.ARCHIVES, FileUtils.categoryOf("data", "application/x-zip-compressed"))
        assertEquals(Category.OTHERS, FileUtils.categoryOf("thing", "application/unknown"))
    }

    @Test fun `isDangerous flags executables only`() {
        assertTrue(FileUtils.isDangerous("setup.APK"))
        assertTrue(FileUtils.isDangerous("run.sh"))
        assertFalse(FileUtils.isDangerous("photo.jpg"))
        assertFalse(FileUtils.isDangerous("apk"))
    }

    @Test fun `formatBytes picks a sensible unit`() {
        assertEquals("—", FileUtils.formatBytes(-1))
        assertEquals("512 B", FileUtils.formatBytes(512))
        assertEquals("1.0 KB", FileUtils.formatBytes(1024))
        assertEquals("1.5 MB", FileUtils.formatBytes(1_572_864))
        assertEquals("2.0 GB", FileUtils.formatBytes(2L * 1024 * 1024 * 1024))
    }

    @Test fun `formatEta and formatSpeed`() {
        assertEquals("", FileUtils.formatEta(0))
        assertEquals("45s left", FileUtils.formatEta(45))
        assertEquals("2m left", FileUtils.formatEta(61))
        assertEquals("1h 30m left", FileUtils.formatEta(5400))
        assertEquals("", FileUtils.formatSpeed(0))
        assertEquals("1.0 KB/s", FileUtils.formatSpeed(1024))
    }

    @Test fun dangerousByMimeEvenWithoutExtension() {
        assertTrue(FileUtils.isDangerous("update", "application/vnd.android.package-archive"))
        assertTrue(FileUtils.isDangerous("setup.xapk"))
        assertFalse(FileUtils.isDangerous("photo.jpg", "image/jpeg"))
    }
}
