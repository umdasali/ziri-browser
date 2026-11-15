package com.ziribrowser.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HlsParserTest {
    private val base = "https://cdn.example.com/video/index.m3u8"

    @Test fun `master playlist picks the highest bandwidth variant`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720
            high/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=854x480
            mid/index.m3u8
        """.trimIndent()
        assertTrue(HlsParser.isMaster(master))
        assertEquals("https://cdn.example.com/video/high/index.m3u8", HlsParser.bestVariant(master, base))
    }

    @Test fun `media playlist yields ordered absolute segments`() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:7
            #EXTINF:4.0,
            seg7.ts
            #EXTINF:3.5,
            https://other.example.com/seg8.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val plan = HlsParser.parseMedia(text, base)
        assertFalse(HlsParser.isMaster(text))
        assertEquals(2, plan.segments.size)
        assertEquals("https://cdn.example.com/video/seg7.ts", plan.segments[0].url)
        assertEquals(7L, plan.segments[0].sequence)
        assertEquals("https://other.example.com/seg8.ts", plan.segments[1].url)
        assertEquals(8L, plan.segments[1].sequence)
        assertEquals(7.5, plan.totalSeconds, 0.001)
        assertFalse(plan.fragmentedMp4)
    }

    @Test fun `fragmented mp4 streams keep their init segment`() {
        val text = "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:2,\na.m4s\n#EXT-X-ENDLIST"
        val plan = HlsParser.parseMedia(text, base)
        assertTrue(plan.fragmentedMp4)
        assertEquals("https://cdn.example.com/video/init.mp4", plan.initUrl)
    }

    @Test fun `aes-128 key and iv are read`() {
        val text = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k.bin\",IV=0x000102030405060708090A0B0C0D0E0F\n#EXTINF:2,\na.ts\n#EXT-X-ENDLIST"
        val key = HlsParser.parseMedia(text, base).segments.single().key
        assertNotNull(key)
        assertEquals("https://cdn.example.com/video/k.bin", key!!.keyUrl)
        assertArrayEquals(ByteArray(16) { it.toByte() }, key.iv)
    }

    @Test fun `no key means plain segments`() {
        val text = "#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:2,\na.ts\n#EXT-X-ENDLIST"
        assertNull(HlsParser.parseMedia(text, base).segments.single().key)
    }

    @Test fun `live streams and protected video are refused with a clear message`() {
        fun message(text: String) = try { HlsParser.parseMedia(text, base); fail("expected HlsException"); "" } catch (e: HlsException) { e.message.orEmpty() }
        assertTrue(message("#EXTM3U\n#EXTINF:2,\na.ts").contains("Live"))
        assertTrue(message("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"k\"\n#EXTINF:2,\na.ts\n#EXT-X-ENDLIST").contains("protected"))
        assertTrue(message("not a playlist").contains("playlist"))
    }

    @Test fun `default iv is the segment number big-endian`() {
        val iv = HlsParser.sequenceIv(258)
        assertEquals(16, iv.size)
        assertEquals(1, iv[14].toInt())
        assertEquals(2, iv[15].toInt())
        assertEquals(0, iv[0].toInt())
    }
}
