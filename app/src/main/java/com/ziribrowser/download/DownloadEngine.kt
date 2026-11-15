package com.ziribrowser.download

import com.ziribrowser.R
import com.ziribrowser.ziriString
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import com.ziribrowser.data.DownloadDao
import com.ziribrowser.data.DownloadEntity
import com.ziribrowser.data.DownloadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Headers
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class DownloadRequest(
    val url: String,
    val userAgent: String = "",
    val contentDisposition: String? = null,
    val mimeType: String? = null,
    val contentLength: Long = -1,
    val cookie: String = "",
    val referer: String = "",
    /** A readable name for the file (e.g. the page's title); used instead of the URL's file name. */
    val title: String? = null,
    /** From a private tab: the session cookie and referer are kept in memory only, never written to the database. */
    val isPrivate: Boolean = false,
)

/** True for HLS streaming playlists (.m3u8), which are downloaded segment by segment. */
fun isHlsUrl(url: String, mime: String? = null): Boolean =
    url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) || mime?.contains("mpegurl", ignoreCase = true) == true

sealed interface EnqueueResult {
    data class Started(val id: Long) : EnqueueResult
    data class Duplicate(val existing: DownloadEntity) : EnqueueResult
    data class Rejected(val message: String) : EnqueueResult
}

/** Transient numbers for the UI; the database only stores state changes, not every progress tick. */
data class LiveProgress(val downloaded: Long, val total: Long, val speed: Long, val etaSeconds: Long)

class DownloadEngine(
    private val context: Context,
    private val dao: DownloadDao,
    private val scope: CoroutineScope,
) {
    // ---- scheduling and limits (set from Settings) -------------------------------------------------
    /** Parts per file; 1 turns multi-connection downloads off. */
    @Volatile var maxParts = 4
    @Volatile var wifiOnly = false
    /** Bytes per second across all downloads; 0 = unlimited. */
    @Volatile var speedLimitBps = 0L
    private val limit = MutableStateFlow(3)
    private val active = MutableStateFlow(0)
    private val waiting = MutableStateFlow<List<Long>>(emptyList())
    private val order = ConcurrentHashMap<Long, Long>()
    private val nextSlot = AtomicLong(0)
    private val forceSingle = java.util.Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val segmentedState = ConcurrentHashMap<Long, List<Part>>()

    fun setMaxConcurrent(n: Int) { limit.value = n.coerceIn(1, 5) }

    private val jobs = ConcurrentHashMap<Long, Job>()
    /** Cookie + referer of private downloads, memory only (lost on process death, so they can't resume after that). */
    private val privateAuth = ConcurrentHashMap<Long, Pair<String, String>>()
    private val calls = ConcurrentHashMap<Long, MutableSet<okhttp3.Call>>()
    private val stopReason = ConcurrentHashMap<Long, Stop>()
    private enum class Stop { PAUSE, CANCEL }

    private val _live = MutableStateFlow<Map<Long, LiveProgress>>(emptyMap())
    val live: StateFlow<Map<Long, LiveProgress>> = _live

    /** Downloads currently holding a slot or waiting for one. */
    private val _running = MutableStateFlow(0)
    val running: StateFlow<Int> = _running

    /** Set while Tor is on: the SOCKS proxy to use, or null if Tor isn't connected yet (downloads then wait/fail rather than go direct). */
    @Volatile var torGate: (() -> java.net.Proxy?)? = null
    @Volatile var torWanted: () -> Boolean = { false }

    private val client: OkHttpClient get() {
        if (!torWanted()) return baseClient
        val p = torGate?.invoke() ?: throw IOException("Tor isn't connected, so the download can't start safely.")
        return baseClient.newBuilder().proxy(p).build()
    }

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        // Refuse a redirect from https down to plain http.
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (chain.request().isHttps && !response.request.isHttps) {
                response.close()
                throw IOException("Blocked a redirect from a secure to an insecure connection")
            }
            response
        }
        .build()

    /** Call once at startup: downloads cut off by a process death are made resumable or retryable. */
    suspend fun recover() {
        dao.clearCookies() // older versions stored login cookies here
        dao.deletePrivate() // private downloads leave no entry once the app restarts (the file itself stays in Downloads)
        for (d in dao.active()) {
            dao.update(
                if (d.canResume && d.downloadedSize > 0) d.copy(status = DownloadStatus.PAUSED)
                else d.copy(status = DownloadStatus.FAILED, error = ziriString(R.string.interrupted_when_the_app_closed)),
            )
        }
    }

    suspend fun enqueue(req: DownloadRequest, allowDuplicate: Boolean = false): EnqueueResult {
        val uri = runCatching { Uri.parse(req.url) }.getOrNull()
        if (uri == null || (uri.scheme != "http" && uri.scheme != "https") || uri.host.isNullOrEmpty()) {
            return EnqueueResult.Rejected(ziriString(R.string.only_http_and_https_downloads_are_suppor))
        }
        if (torWanted() && torGate?.invoke() == null) return EnqueueResult.Rejected(ziriString(R.string.tor_isn_t_connected_yet_try_again_in_a_m))
        if (!allowDuplicate) dao.findByUrl(req.url)?.let { return EnqueueResult.Duplicate(it) }

        var filename: String
        var mime: String
        if (isHlsUrl(req.url, req.mimeType)) {
            // Look at the playlist first: it decides the file type, and tells us early if the stream can't be saved.
            val plan = try {
                withContext(Dispatchers.IO) { HlsDownloader.prepare(client, req.url, requestHeaders(req.cookie, req.userAgent, req.referer)) }
            } catch (e: IOException) {
                return EnqueueResult.Rejected(e.message ?: ziriString(R.string.this_video_can_t_be_downloaded))
            }
            val base = req.title?.let { FileUtils.sanitizeFilename(it, "video").take(80) } ?: "video"
            filename = if (plan.fragmentedMp4) "$base.mp4" else "$base.ts"
            mime = if (plan.fragmentedMp4) "video/mp4" else "video/mp2t"
        } else {
            val guessed = URLUtil.guessFileName(req.url, req.contentDisposition, req.mimeType)
            filename = FileUtils.sanitizeFilename(guessed)
            mime = req.mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            if (mime.isEmpty() || mime == "application/octet-stream") {
                mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(FileUtils.extOf(filename)) ?: "application/octet-stream"
            }
            if (!req.title.isNullOrBlank()) {
                val ext = FileUtils.extOf(filename).ifEmpty { MimeTypeMap.getSingleton().getExtensionFromMimeType(mime).orEmpty() }
                filename = FileUtils.sanitizeFilename(req.title, "video").take(80) + if (ext.isNotEmpty()) ".$ext" else ""
            }
        }
        val category = FileUtils.categoryOf(filename, mime)

        if (req.contentLength > 0) {
            val free = StatFs(Environment.getExternalStorageDirectory().path).availableBytes
            if (free < req.contentLength + 16L * 1024 * 1024) {
                return EnqueueResult.Rejected(ziriString(R.string.not_enough_free_storage_for_this_file))
            }
        }

        val target = withContext(Dispatchers.IO) { createPendingFile(filename, mime, category) }
            ?: return EnqueueResult.Rejected(ziriString(R.string.could_not_create_the_file_in_your_downlo))

        val id = dao.insert(
            DownloadEntity(
                filename = target.second, url = req.url, mimeType = mime, category = category.name,
                fileSize = req.contentLength, fileUri = target.first.toString(), cookie = "", // login cookies are never written to the database
                userAgent = req.userAgent, referer = if (req.isPrivate) "" else req.referer, isPrivate = req.isPrivate,
            ),
        )
        // The cookie (and a private download's referer) stay in memory only: a download that must resume after the app
        // was closed may have to be restarted, which is the price of not keeping logins on disk.
        privateAuth[id] = req.cookie to (if (req.isPrivate) req.referer else req.referer)
        launchJob(id)
        return EnqueueResult.Started(id)
    }

    /** Creates a hidden "pending" file under Downloads/Ziri/<Category>. MediaStore picks a free name if needed. */
    private fun createPendingFile(filename: String, mime: String, category: Category): Pair<Uri, String>? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Ziri/${category.folder}")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val finalName = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: filename
        return uri to finalName
    }

    private fun launchJob(id: Long) {
        _running.update { it + 1 }
        val job = scope.launch(Dispatchers.IO) {
            try {
                val d0 = dao.get(id)
                order[id] = d0?.queueOrder?.takeIf { it != 0L } ?: id
                d0?.let { dao.update(it.copy(status = DownloadStatus.QUEUED, error = "")) }
                // "Wi-Fi only": wait (without holding a download slot) until the connection isn't metered.
                while (wifiOnly && isMetered()) {
                    dao.get(id)?.let { dao.update(it.copy(status = DownloadStatus.QUEUED, error = ziriString(R.string.waiting_for_wi_fi))) }
                    delay(4000)
                }
                dao.get(id)?.let { if (it.error == ziriString(R.string.waiting_for_wi_fi)) dao.update(it.copy(error = "")) }
                inSlot(id) { download(id) }
            } finally {
                _live.update { it - id }
                jobs.remove(id)
                order.remove(id)
                forceSingle.remove(id)
                _running.update { it - 1 }
            }
        }
        jobs[id] = job
        if (_running.value == 1) DownloadService.start(context)
    }

    /** Runs [block] once a download slot is free and this download is the next in the queue. */
    private suspend fun <T> inSlot(id: Long, block: suspend () -> T): T {
        waiting.update { it + id }
        try {
            combine(waiting, active, limit) { w, a, l ->
                a < l && w.minWithOrNull(compareBy({ order[it] ?: Long.MAX_VALUE }, { it })) == id
            }.first { it }
            active.update { it + 1 }
        } finally {
            waiting.update { it - id }
        }
        try { return block() } finally { active.update { it - 1 } }
    }

    /** Moves a queued download to the front of the queue. */
    suspend fun moveToTop(id: Long) {
        val first = (order.values.minOrNull() ?: 0L) - 1
        order[id] = first
        dao.get(id)?.let { dao.update(it.copy(queueOrder = first)) }
        waiting.update { it.toList() } // wake the scheduler so it re-reads the order
    }

    suspend fun pauseAll() { dao.active().forEach { pause(it.id) } }
    suspend fun resumeAll() { dao.pausedOrFailed().forEach { if (it.status == DownloadStatus.PAUSED) resume(it.id) else retry(it.id) } }

    private fun isMetered() = (context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered

    private fun hasNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun awaitNetwork() { while (!hasNetwork()) { currentCoroutineContext().ensureActive(); delay(2000) } }

    private fun cancelCalls(id: Long) { calls[id]?.toList()?.forEach { it.cancel() } }
    private fun addCall(id: Long, c: okhttp3.Call) { calls.getOrPut(id) { java.util.concurrent.CopyOnWriteArraySet() }.add(c) }
    private fun dropCall(id: Long, c: okhttp3.Call) { calls[id]?.remove(c) }

    private suspend fun throttle(n: Int) {
        val bps = speedLimitBps
        if (bps <= 0) return
        val cost = n * 1_000_000_000L / bps
        val end = nextSlot.updateAndGet { maxOf(it, System.nanoTime()) + cost }
        val wait = (end - cost - System.nanoTime()) / 1_000_000
        if (wait > 0) delay(wait)
    }


    /** Bytes of a download that are safe to read from the start of the file (the media player plays only this much). */
    fun availableBytes(d: DownloadEntity): Long? {
        val parts = segmentedState[d.id] ?: PartCodec.parse(d.parts)
        return if (parts.isNullOrEmpty()) liveSnapshot(d.id)?.downloaded else PartCodec.contiguous(parts)
    }

    private suspend fun download(id: Long) {
        try {
            downloadInner(id)
        } finally {
            calls.remove(id)
            segmentedState.remove(id)
            if (stopReason[id] != Stop.PAUSE && dao.get(id)?.status != DownloadStatus.PAUSED) privateAuth.remove(id)
        }
    }

    private suspend fun downloadInner(id: Long) {
        var d = dao.get(id) ?: return
        val resolver = context.contentResolver
        val uri = Uri.parse(d.fileUri)
        d = d.copy(status = DownloadStatus.DOWNLOADING, error = "")
        dao.update(d)

        try {
            var attempt = 0
            var written: Long
            while (true) {
                try {
                    awaitNetwork()
                    written = transfer(id, uri)
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RangeIgnoredException) {
                    // The server turned out not to honour ranges: start over as one plain connection.
                    forceSingle.add(id)
                    dao.get(id)?.let { dao.update(it.copy(parts = "", canResume = false, downloadedSize = 0, error = "")) }
                } catch (e: IOException) {
                    if (stopReason.containsKey(id) || !isRetryableDownloadError(e) || attempt >= MAX_RETRIES) throw e
                    attempt++
                    val wait = minOf(30, 2 shl (attempt - 1))
                    dao.get(id)?.let { dao.update(it.copy(status = DownloadStatus.DOWNLOADING, error = ziriString(R.string.retrying_in_s_of, wait, attempt, MAX_RETRIES))) }
                    delay(wait * 1000L)
                    dao.get(id)?.let { dao.update(it.copy(error = "")) }
                }
            }
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            val finished = (dao.get(id) ?: d).copy(
                status = DownloadStatus.COMPLETED, downloadedSize = written, fileSize = written,
                completedAt = System.currentTimeMillis(), error = "", parts = "",
            )
            dao.update(finished)
            DownloadNotifications.completed(context, finished)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                when (stopReason.remove(id)) {
                    Stop.CANCEL -> Unit // the row and file are removed by remove()
                    else -> dao.get(id)?.let { dao.update(it.copy(status = DownloadStatus.PAUSED, error = "")) }
                }
            }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) {
                // A user-initiated cancel surfaces as an IOException ("Canceled"); don't report it as a failure.
                if (stopReason.containsKey(id)) {
                    when (stopReason.remove(id)) {
                        Stop.CANCEL -> Unit
                        else -> dao.get(id)?.let { dao.update(it.copy(status = DownloadStatus.PAUSED, error = "")) }
                    }
                } else {
                    val failed = (dao.get(id) ?: d).copy(
                        status = DownloadStatus.FAILED,
                        error = if (e.message?.contains("ENOSPC") == true || e.message?.contains("No space left", true) == true) "Not enough free storage" else e.message?.take(200) ?: "Download failed",
                    )
                    dao.update(failed)
                    DownloadNotifications.failed(context, failed)
                }
            }
        }
    }

    /** One attempt at moving the bytes. Saves its progress to the database whether it finishes, fails or is stopped. */
    private suspend fun transfer(id: Long, uri: Uri): Long {
        var d = dao.get(id) ?: throw IOException("This download was removed")
        val cookie = privateAuth[id]?.first ?: d.cookie
        val referer = privateAuth[id]?.second ?: d.referer
        val headers = requestHeaders(cookie, d.userAgent, referer)

        if (isHlsUrl(d.url, d.mimeType.takeIf { it.contains("mpegurl", true) })) {
            var w = 0L
            try { return hlsDownload(id, d, uri) { w = it } }
            finally { withContext(NonCancellable) { dao.get(id)?.let { dao.update(it.copy(downloadedSize = w)) } } }
        }

        // Resume a multi-connection download from where each part stopped.
        val saved = PartCodec.parse(d.parts)
        if (saved != null && d.canResume && d.fileSize > 0) return segmented(id, d, uri, saved, headers)

        val resumeFrom = if (d.canResume && d.downloadedSize > 0 && d.parts.isEmpty()) d.downloadedSize else 0L
        if (resumeFrom == 0L && maxParts > 1 && id !in forceSingle && !torWanted()) {
            val info = probeRanges(d.url, headers)
            if (info != null && info.first >= MIN_SEGMENTED) {
                val n = (info.first / PART_TARGET).toInt().coerceIn(2, maxParts)
                val size = info.first
                val parts = PartCodec.split(size, n)
                d = d.copy(fileSize = size, etag = info.second, canResume = true, parts = PartCodec.encode(parts))
                dao.update(d)
                return segmented(id, d, uri, parts, headers)
            }
        }
        return single(id, d, uri, resumeFrom, headers)
    }

    /** Asks for the first byte only: a 206 answer with a total size means the server serves byte ranges. */
    private suspend fun probeRanges(url: String, headers: Headers): Pair<Long, String>? = runCatching {
        client.newCall(Request.Builder().url(url).headers(headers).header("Range", "bytes=0-0").build()).execute().use { r ->
            if (r.code != 206) return@use null
            val total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: return@use null
            total to (r.header("ETag") ?: r.header("Last-Modified") ?: "")
        }
    }.getOrNull()

    /** The original one-connection download (used for small files and servers without range support). */
    private suspend fun single(id: Long, d0: DownloadEntity, uri: Uri, resumeFrom: Long, headers: Headers): Long {
        var d = d0
        val resolver = context.contentResolver
        var written = resumeFrom
        val rb = Request.Builder().url(d.url).headers(headers)
        if (resumeFrom > 0) {
            rb.header("Range", "bytes=$resumeFrom-")
            if (d.etag.isNotEmpty()) rb.header("If-Range", d.etag)
        }
        val call = client.newCall(rb.build())
        addCall(id, call) // pause()/remove() cancel the call directly so a blocked read ends at once
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw HttpStatusException(resp.code)
                val body = resp.body
                // A server that ignores Range answers 200 with the whole file: start again from zero.
                val startAt = if (resumeFrom > 0 && resp.code == 206) resumeFrom else 0L
                written = startAt
                val len = body.contentLength()
                val total = if (len > 0) startAt + len else -1L
                val etag = resp.header("ETag") ?: resp.header("Last-Modified") ?: ""
                val canResume = resp.code == 206 || resp.header("Accept-Ranges")?.contains("bytes", true) == true
                d = d.copy(fileSize = total, etag = etag, canResume = canResume)
                dao.update(d)

                var lastUi = 0L
                var lastSave = 0L
                var sampleT = System.currentTimeMillis()
                var sampleB = written
                var speed = 0L
                val buf = ByteArray(64 * 1024)
                val out = resolver.openOutputStream(uri, if (startAt > 0) "wa" else "wt")
                    ?: throw IOException("Cannot write to the Downloads folder")
                out.use { os ->
                    body.byteStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            written += n
                            throttle(n)
                            val now = System.currentTimeMillis()
                            if (now - lastUi >= 250) {
                                lastUi = now
                                val dt = now - sampleT
                                if (dt >= 1000) {
                                    val inst = (written - sampleB) * 1000 / dt
                                    speed = if (speed == 0L) inst else (speed * 6 + inst * 4) / 10
                                    sampleT = now; sampleB = written
                                }
                                val eta = if (speed > 0 && total > 0) (total - written) / speed else 0
                                _live.update { it + (id to LiveProgress(written, total, speed, eta)) }
                            }
                            // Persist occasionally (not per chunk) so a crash loses little.
                            if (now - lastSave >= 3000) {
                                lastSave = now
                                dao.update(d.copy(downloadedSize = written))
                            }
                        }
                    }
                }
                if (total > 0 && written != total) throw IOException("Download incomplete ($written of $total bytes)")
            }
        } finally {
            dropCall(id, call)
            withContext(NonCancellable) { dao.get(id)?.let { dao.update(it.copy(downloadedSize = written)) } }
        }
        return written
    }

    /**
     * Several connections, each filling its own part of the file (positional writes into one file). Progress is saved per
     * part, so pausing, a dropped connection or an app restart continues each part where it stopped.
     */
    private suspend fun segmented(id: Long, d0: DownloadEntity, uri: Uri, parts: List<Part>, headers: Headers): Long {
        val total = d0.fileSize
        val pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: throw IOException("Cannot write to the Downloads folder")
        val stream = android.os.ParcelFileDescriptor.AutoCloseOutputStream(pfd)
        val channel = stream.channel
        segmentedState[id] = parts
        var completed = false
        try {
            coroutineScope {
                val reporter = launch {
                    var sampleT = System.currentTimeMillis()
                    var sampleB = parts.sumOf { it.done }
                    var speed = 0L
                    var lastSave = 0L
                    while (true) {
                        delay(250)
                        val done = parts.sumOf { it.done }
                        val now = System.currentTimeMillis()
                        val dt = now - sampleT
                        if (dt >= 1000) {
                            val inst = (done - sampleB) * 1000 / dt
                            speed = if (speed == 0L) inst else (speed * 6 + inst * 4) / 10
                            sampleT = now; sampleB = done
                        }
                        val eta = if (speed > 0) (total - done) / speed else 0
                        _live.update { it + (id to LiveProgress(done, total, speed, eta)) }
                        if (now - lastSave >= 3000) {
                            lastSave = now
                            dao.get(id)?.let { dao.update(it.copy(downloadedSize = done, parts = PartCodec.encode(parts))) }
                        }
                    }
                }
                try {
                    SegmentedDownloader({ client }, { n -> throttle(n) }) { call, started -> if (started) addCall(id, call) else dropCall(id, call) }
                        .fetch(d0.url, d0.etag, headers, parts, channel)
                } finally { reporter.cancel() }
            }
            val sum = parts.sumOf { it.done }
            if (sum != total) throw IOException("Download incomplete ($sum of $total bytes)")
            completed = true
            return total
        } finally {
            withContext(NonCancellable) {
                runCatching { channel.force(false) }
                runCatching { stream.close() }
                val done = parts.sumOf { it.done }
                dao.get(id)?.let { dao.update(it.copy(downloadedSize = done, parts = if (completed) "" else PartCodec.encode(parts))) }
            }
        }
    }

    private fun requestHeaders(cookie: String, userAgent: String, referer: String): okhttp3.Headers = okhttp3.Headers.Builder().apply {
        if (cookie.isNotEmpty()) add("Cookie", cookie)
        if (userAgent.isNotEmpty()) add("User-Agent", userAgent)
        if (referer.isNotEmpty()) add("Referer", referer)
    }.build()

    /**
     * Rebuilds an HLS video as one file by downloading its segments in order (a few ahead at a time).
     * Returns the bytes written. [onProgress] reports bytes written so the caller can save state on a stop.
     */
    private suspend fun hlsDownload(id: Long, d: DownloadEntity, uri: Uri, onProgress: (Long) -> Unit): Long {
        val headers = requestHeaders(privateAuth[id]?.first ?: d.cookie, d.userAgent, privateAuth[id]?.second ?: d.referer)
        val plan = HlsDownloader.prepare(client, d.url, headers)
        val items = buildList {
            plan.initUrl?.let { add(HlsSegment(it, 0, 0.0, plan.initKey)) }
            addAll(plan.segments)
        }
        dao.update(d.copy(fileSize = -1, canResume = false))
        val keys = HashMap<String, ByteArray>()
        var written = 0L
        var done = 0
        var sampleT = System.currentTimeMillis()
        var sampleB = 0L
        var speed = 0L
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot write to the Downloads folder")
        out.use { os ->
            coroutineScope {
                val window = 4
                val pending = ArrayDeque<kotlinx.coroutines.Deferred<ByteArray>>()
                var next = 0
                fun launchMore() {
                    while (pending.size < window && next < items.size) {
                        val seg = items[next++]
                        pending += async(Dispatchers.IO) { HlsDownloader.fetchSegment(client, seg, headers, keys) }
                    }
                }
                launchMore()
                while (pending.isNotEmpty()) {
                    val bytes = pending.removeFirst().await()
                    os.write(bytes)
                    written += bytes.size
                    done++
                    onProgress(written)
                    val now = System.currentTimeMillis()
                    val dt = now - sampleT
                    if (dt >= 1000) {
                        val inst = (written - sampleB) * 1000 / dt
                        speed = if (speed == 0L) inst else (speed * 6 + inst * 4) / 10
                        sampleT = now; sampleB = written
                    }
                    // The final size isn't known up front, so estimate it from the segments finished so far.
                    val estTotal = written * items.size / done
                    val eta = if (speed > 0) (estTotal - written) / speed else 0
                    _live.update { it + (id to LiveProgress(written, estTotal, speed, eta)) }
                    launchMore()
                }
            }
        }
        return written
    }

    data class Probe(val length: Long, val mime: String?)

    /** Asks the server about [url] (a HEAD request, falling back to a one-byte range request). Null if it can't be reached. */
    suspend fun probe(url: String, cookie: String, userAgent: String, referer: String): Probe? = withContext(Dispatchers.IO) {
        val headers = requestHeaders(cookie, userAgent, referer)
        runCatching {
            client.newCall(Request.Builder().url(url).headers(headers).head().build()).await().use { r ->
                if (r.isSuccessful) return@runCatching Probe(r.header("Content-Length")?.toLongOrNull() ?: -1L, r.header("Content-Type")?.substringBefore(';')?.trim())
                null
            }
        }.getOrNull() ?: runCatching {
            client.newCall(Request.Builder().url(url).headers(headers).header("Range", "bytes=0-0").build()).await().use { r ->
                if (!r.isSuccessful) return@runCatching null
                val total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
                Probe(total, r.header("Content-Type")?.substringBefore(';')?.trim())
            }
        }.getOrNull()
    }

    /** Reads an HLS playlist to learn its length and whether it can be saved. */
    suspend fun probeStream(url: String, cookie: String, userAgent: String, referer: String): Result<HlsPlan> = withContext(Dispatchers.IO) {
        runCatching { HlsDownloader.prepare(client, url, requestHeaders(cookie, userAgent, referer)) }
    }

    /** Current state of a download for the media player, which reads a file while it is still being written. */
    fun liveSnapshot(id: Long): LiveProgress? = _live.value[id]
    suspend fun entity(id: Long): DownloadEntity? = dao.get(id)

    /** Only possible when the server supports byte ranges (spec 8.2). */
    suspend fun pause(id: Long) {
        val d = dao.get(id) ?: return
        val pausable = (d.status == DownloadStatus.DOWNLOADING && d.canResume) || (d.status == DownloadStatus.QUEUED && (d.downloadedSize == 0L || d.canResume))
        if (!pausable) return
        stopReason[id] = Stop.PAUSE
        cancelCalls(id)
        jobs[id]?.cancel()
    }

    suspend fun resume(id: Long) {
        val d = dao.get(id) ?: return
        if (d.status != DownloadStatus.PAUSED || jobs.containsKey(id)) return
        launchJob(id)
    }

    suspend fun retry(id: Long) {
        val d = dao.get(id) ?: return
        if (d.status != DownloadStatus.FAILED || jobs.containsKey(id)) return
        // Resume where possible; otherwise download() restarts from zero.
        dao.update(d.copy(status = DownloadStatus.QUEUED, error = ""))
        launchJob(id)
    }

    /** Cancels an active download or deletes a finished one, including its file. */
    suspend fun remove(id: Long) {
        val d = dao.get(id) ?: return
        stopReason[id] = Stop.CANCEL
        cancelCalls(id)
        jobs[id]?.cancelAndJoinQuietly()
        stopReason.remove(id)
        runCatching { context.contentResolver.delete(Uri.parse(d.fileUri), null, null) }
        dao.delete(d)
    }

    private suspend fun Job.cancelAndJoinQuietly() {
        cancel()
        runCatching { join() }
    }

    fun openIntent(d: DownloadEntity): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(d.fileUri), d.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    private companion object {
        const val MAX_RETRIES = 5
        /** Files smaller than this are fetched over one connection (extra connections wouldn't help). */
        const val MIN_SEGMENTED = 4L * 1024 * 1024
        /** About this many bytes per part when choosing how many parts to use. */
        const val PART_TARGET = 6L * 1024 * 1024
    }
}
