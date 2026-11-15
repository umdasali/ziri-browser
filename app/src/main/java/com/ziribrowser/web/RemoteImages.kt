package com.ziribrowser.web

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * A small image loader for article pictures: a memory cache sized to the device, a capped disk cache, and
 * decoding at no more than ~720 px wide so big photos never fill memory. https only (http links are upgraded).
 */
class RemoteImages(context: Context, private val http: OkHttpClient) {
    private val dir = File(context.cacheDir, "img").also { it.mkdirs() }
    private val gate = Semaphore(4)
    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 12).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    suspend fun load(rawUrl: String, maxWidth: Int = 720): Bitmap? {
        val url = if (rawUrl.startsWith("http://")) "https://" + rawUrl.removePrefix("http://") else rawUrl
        if (!url.startsWith("https://")) return null
        memory.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = File(dir, sha(url))
            try {
                if (!file.isFile) gate.withPermit { download(url, file) }
                decode(file, maxWidth)?.also { memory.put(url, it) } ?: run { file.delete(); null }
            } catch (_: Exception) { file.delete(); null }
        }
    }

    private fun download(url: String, out: File) {
        val tmp = File(dir, out.name + ".tmp")
        http.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 14) Chrome/124.0 Mobile Safari/537.36").build()).execute().use { r ->
            if (!r.isSuccessful) return
            var total = 0L
            r.body.byteStream().use { input ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) { tmp.delete(); return } // refuse huge files
                        o.write(buf, 0, n)
                    }
                }
            }
        }
        tmp.renameTo(out)
        prune()
    }

    private fun decode(file: File, maxWidth: Int): Bitmap? {
        if (!file.isFile) return null
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, b)
        if (b.outWidth <= 0) return null
        val o = BitmapFactory.Options().apply { inSampleSize = (b.outWidth / maxWidth).coerceAtLeast(1) }
        return BitmapFactory.decodeFile(file.path, o)
    }

    private fun prune() {
        val files = dir.listFiles() ?: return
        var size = files.sumOf { it.length() }
        if (size <= DISK_CAP) return
        for (f in files.sortedBy { it.lastModified() }) { size -= f.length(); f.delete(); if (size <= DISK_CAP * 3 / 4) break }
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object { const val MAX_BYTES = 6L * 1024 * 1024; const val DISK_CAP = 40L * 1024 * 1024 }
}
