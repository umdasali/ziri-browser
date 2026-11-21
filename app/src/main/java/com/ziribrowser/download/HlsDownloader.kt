package com.ziribrowser.download

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Network side of HLS downloads: reads playlists and fetches (and decrypts) segments. */
object HlsDownloader {
    private const val MAX_PLAYLIST_BYTES = 4L * 1024 * 1024

    /** Reads the playlist at [url] (choosing the best stream if it is a master playlist) and returns the segments to fetch. */
    suspend fun prepare(client: OkHttpClient, url: String, headers: Headers): HlsPlan {
        var text = fetchText(client, url, headers)
        var playlistUrl = url
        if (HlsParser.isMaster(text)) {
            playlistUrl = HlsParser.bestVariant(text, url)
            text = fetchText(client, playlistUrl, headers)
        }
        return HlsParser.parseMedia(text, playlistUrl)
    }

    private suspend fun fetchText(client: OkHttpClient, url: String, headers: Headers): String =
        client.newCall(Request.Builder().url(url).headers(headers).build()).await().use { resp ->
            if (!resp.isSuccessful) throw HlsException("The video's playlist couldn't be loaded (HTTP ${resp.code}).")
            val body = resp.body
            if (body.contentLength() > MAX_PLAYLIST_BYTES) throw HlsException("This playlist is too large.")
            body.string()
        }

    private const val MAX_SEGMENT = 48L * 1024 * 1024

    /** Downloads one segment, decrypting it if the playlist says it is AES-128 encrypted. */
    suspend fun fetchSegment(client: OkHttpClient, seg: HlsSegment, headers: Headers, keys: MutableMap<String, ByteArray>): ByteArray {
        val bytes = client.newCall(Request.Builder().url(seg.url).headers(headers).build()).await().use { resp ->
            if (!resp.isSuccessful) throw IOException("A video segment failed (HTTP ${resp.code}).")
            // A hostile or broken server must not be able to fill memory with one "segment" (four are in flight at a time).
            val src = resp.body.source()
            src.request(MAX_SEGMENT + 1)
            if (src.buffer.size > MAX_SEGMENT) throw IOException("A video segment is too large.")
            src.readByteArray()
        }
        val key = seg.key ?: return bytes
        val keyBytes = synchronized(keys) { keys[key.keyUrl] }
            ?: client.newCall(Request.Builder().url(key.keyUrl).headers(headers).build()).await().use { resp ->
                if (!resp.isSuccessful) throw HlsException("The video's decryption key couldn't be loaded.")
                resp.body.bytes().also { synchronized(keys) { keys[key.keyUrl] = it } }
            }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(key.iv ?: HlsParser.sequenceIv(seg.sequence)))
        return cipher.doFinal(bytes)
    }
}

/** Suspends until the response arrives; cancelling the coroutine cancels the HTTP call. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
}
