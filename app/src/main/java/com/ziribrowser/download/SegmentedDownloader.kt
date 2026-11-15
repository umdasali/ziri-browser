package com.ziribrowser.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** A piece of a file fetched over its own connection. [end] is inclusive. */
class Part(val start: Long, val end: Long, done: Long = 0) {
    @Volatile var done: Long = done
    val length get() = end - start + 1
    val complete get() = done >= length
}

class HttpStatusException(val code: Int) : IOException("Server returned HTTP $code")
class RangeIgnoredException : IOException("The server doesn't support partial downloads")

/** Network trouble is worth retrying; a clear "no" from the server (404, 403, …) is not. */
fun isRetryableDownloadError(e: IOException) = e !is HttpStatusException || e.code == 408 || e.code == 429 || e.code >= 500

/** Splitting a file into parts and saving/reading their progress ("start:end:done;start:end:done"). */
object PartCodec {
    fun split(size: Long, n: Int): List<Part> {
        val chunk = (size + n - 1) / n
        return (0 until n).map { i -> Part(i * chunk, minOf(size - 1, (i + 1) * chunk - 1)) }.filter { it.start < size }
    }

    fun encode(parts: List<Part>) = parts.joinToString(";") { "${it.start}:${it.end}:${it.done}" }

    fun parse(s: String): List<Part>? = if (s.isEmpty()) null else runCatching {
        s.split(';').map { v -> v.split(':').let { Part(it[0].toLong(), it[1].toLong(), it[2].toLong()) } }
    }.getOrNull()

    /** Bytes that are safe to read from the start of the file: whole parts in order, then the next part's finished portion. */
    fun contiguous(parts: List<Part>): Long {
        var sum = 0L
        for (p in parts.sortedBy { it.start }) { sum += p.done; if (!p.complete) break }
        return sum
    }
}

/**
 * Fetches the incomplete [Part]s of a file at the same time, each with its own range request, writing straight into
 * [FileChannel] at the right position. It knows nothing about Android, the database or notifications (so it can be
 * tested on its own). [onCall] is told when a request starts (true) and ends (false) so a pause can cancel them at once.
 */
class SegmentedDownloader(
    private val client: () -> OkHttpClient,
    private val throttle: suspend (Int) -> Unit = {},
    private val onCall: (Call, Boolean) -> Unit = { _, _ -> },
) {
    suspend fun fetch(url: String, etag: String, headers: Headers, parts: List<Part>, channel: FileChannel) = coroutineScope {
        parts.filter { !it.complete }.map { p -> async(Dispatchers.IO) { fetchPart(url, etag, headers, p, channel) } }.awaitAll()
        Unit
    }

    private suspend fun fetchPart(url: String, etag: String, headers: Headers, p: Part, channel: FileChannel) {
        val rb = Request.Builder().url(url).headers(headers).header("Range", "bytes=${p.start + p.done}-${p.end}")
        if (etag.isNotEmpty()) rb.header("If-Range", etag)
        val call = client().newCall(rb.build())
        onCall(call, true)
        try {
            call.execute().use { resp ->
                if (resp.code == 200) throw RangeIgnoredException()
                if (resp.code != 206) throw HttpStatusException(resp.code)
                val buf = ByteArray(64 * 1024)
                resp.body.byteStream().use { input ->
                    while (!p.complete) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        val w = minOf(n.toLong(), p.length - p.done).toInt()
                        val bb = ByteBuffer.wrap(buf, 0, w)
                        var pos = p.start + p.done
                        while (bb.hasRemaining()) pos += channel.write(bb, pos)
                        p.done += w
                        throttle(w)
                    }
                }
            }
        } finally { onCall(call, false) }
        if (!p.complete) throw IOException("A connection ended early")
    }
}
