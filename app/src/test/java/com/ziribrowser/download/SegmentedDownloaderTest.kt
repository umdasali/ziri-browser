package com.ziribrowser.download

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.CopyOnWriteArrayList

class SegmentedDownloaderTest {
    private val data = ByteArray(3_000_017) { (it * 31 + 7).toByte() }
    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<String?>()
    @Volatile private var honourRanges = true
    @Volatile private var status = 0
    private val url get() = "http://127.0.0.1:${server.address.port}/file.bin"

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file.bin") { ex: HttpExchange ->
            val range = ex.requestHeaders.getFirst("Range")
            requests += range
            if (status != 0) { ex.sendResponseHeaders(status, -1); ex.close(); return@createContext }
            if (range != null && honourRanges) {
                val (a, b) = range.removePrefix("bytes=").split("-")
                val from = a.toInt(); val to = minOf(b.toInt(), data.size - 1)
                ex.responseHeaders.add("Content-Range", "bytes $from-$to/${data.size}")
                ex.sendResponseHeaders(206, (to - from + 1).toLong())
                ex.responseBody.use { it.write(data, from, to - from + 1) }
            } else {
                ex.sendResponseHeaders(200, data.size.toLong())
                ex.responseBody.use { it.write(data) }
            }
        }
        server.start()
    }

    @After fun stop() { server.stop(0) }

    private fun downloader() = SegmentedDownloader({ OkHttpClient() })

    private fun withChannel(block: suspend (FileChannel, File) -> Unit) {
        val f = File.createTempFile("seg", ".bin").also { it.deleteOnExit() }
        FileChannel.open(f.toPath(), StandardOpenOption.WRITE, StandardOpenOption.READ).use { ch -> runBlocking { block(ch, f) } }
    }

    @Test fun splitCoversEveryByteWithoutGapsOrOverlap() {
        for (size in listOf(4_000_000L, 4_000_001L, 10_485_760L, 99_999_999L)) for (n in 2..6) {
            val parts = PartCodec.split(size, n)
            assertEquals(0L, parts.first().start)
            assertEquals(size - 1, parts.last().end)
            parts.zipWithNext().forEach { (a, b) -> assertEquals(a.end + 1, b.start) }
            assertEquals(size, parts.sumOf { it.length })
        }
    }

    @Test fun encodeAndParseRoundTrip() {
        val parts = listOf(Part(0, 99, 40), Part(100, 199, 100), Part(200, 299, 0))
        val back = PartCodec.parse(PartCodec.encode(parts))!!
        assertEquals(parts.map { Triple(it.start, it.end, it.done) }, back.map { Triple(it.start, it.end, it.done) })
        assertNull(PartCodec.parse(""))
        assertNull(PartCodec.parse("not;valid"))
    }

    @Test fun contiguousPrefixStopsAtTheFirstGap() {
        assertEquals(100L + 60L, PartCodec.contiguous(listOf(Part(0, 99, 100), Part(100, 199, 60), Part(200, 299, 100))))
        assertEquals(30L, PartCodec.contiguous(listOf(Part(0, 99, 30), Part(100, 199, 100))))
        assertEquals(300L, PartCodec.contiguous(listOf(Part(0, 99, 100), Part(100, 199, 100), Part(200, 299, 100))))
    }

    @Test fun downloadsEveryPartAndTheFileIsIdentical() = withChannel { ch, file ->
        val parts = PartCodec.split(data.size.toLong(), 4)
        downloader().fetch(url, "", Headers.headersOf(), parts, ch)
        assertTrue(parts.all { it.complete })
        ch.force(true)
        assertArrayEquals(data, file.readBytes())
        assertEquals(4, requests.size)
    }

    @Test fun resumeOnlyAsksForTheMissingBytes() = withChannel { ch, file ->
        val parts = PartCodec.split(data.size.toLong(), 3)
        // Pretend the first part is complete and the second is half done.
        ch.write(java.nio.ByteBuffer.wrap(data, 0, parts[0].length.toInt()), 0)
        val half = parts[1].length / 2
        ch.write(java.nio.ByteBuffer.wrap(data, parts[1].start.toInt(), half.toInt()), parts[1].start)
        parts[0].done = parts[0].length; parts[1].done = half
        downloader().fetch(url, "", Headers.headersOf(), parts, ch)
        ch.force(true)
        assertArrayEquals(data, file.readBytes())
        assertEquals(2, requests.size)
        assertTrue(requests.contains("bytes=${parts[1].start + half}-${parts[1].end}"))
        assertTrue(requests.contains("bytes=${parts[2].start}-${parts[2].end}"))
    }

    @Test fun aServerThatIgnoresRangesIsDetected() = withChannel { ch, _ ->
        honourRanges = false
        val parts = PartCodec.split(data.size.toLong(), 2)
        try { downloader().fetch(url, "", Headers.headersOf(), parts, ch); throw AssertionError("expected RangeIgnoredException") }
        catch (e: RangeIgnoredException) { /* expected */ }
    }

    @Test fun httpErrorsCarryTheirCodeAndOnlyTransientOnesRetry() = withChannel { ch, _ ->
        status = 404
        val parts = PartCodec.split(data.size.toLong(), 2)
        val e = try { downloader().fetch(url, "", Headers.headersOf(), parts, ch); null } catch (e: HttpStatusException) { e }
        assertEquals(404, e!!.code)
        assertFalse(isRetryableDownloadError(e))
        assertTrue(isRetryableDownloadError(HttpStatusException(503)))
        assertTrue(isRetryableDownloadError(HttpStatusException(429)))
        assertTrue(isRetryableDownloadError(IOException("connection reset")))
    }
}
