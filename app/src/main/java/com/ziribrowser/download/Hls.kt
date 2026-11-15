package com.ziribrowser.download

import java.io.IOException
import java.net.URI

/** Something about a stream that means Ziri can't save it, phrased for the user. */
class HlsException(message: String) : IOException(message)

/** AES-128 key for a run of segments. [iv] is null when the segment's sequence number is used instead. */
data class HlsKey(val keyUrl: String, val iv: ByteArray?)

data class HlsSegment(val url: String, val sequence: Long, val duration: Double, val key: HlsKey?)

/**
 * What to fetch to rebuild an HLS (.m3u8) video as one file. Segments are appended in order; if the
 * stream uses fragmented MP4 ([initUrl] set) the result is an .mp4, otherwise an MPEG-TS (.ts) file.
 */
data class HlsPlan(val segments: List<HlsSegment>, val initUrl: String?, val initKey: HlsKey?) {
    val fragmentedMp4: Boolean get() = initUrl != null
    val totalSeconds: Double get() = segments.sumOf { it.duration }
}

/** Reads HLS playlists. Pure text handling, so it can be unit-tested without a network. */
object HlsParser {
    fun isMaster(text: String) = text.lineSequence().any { it.startsWith("#EXT-X-STREAM-INF") }

    /** In a master playlist, the URL of the highest-bandwidth stream. */
    fun bestVariant(text: String, baseUrl: String): String {
        val lines = text.lines().map { it.trim() }
        var best: String? = null
        var bestBw = -1L
        for (i in lines.indices) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
            val bw = attributes(lines[i].substringAfter(':'))["BANDWIDTH"]?.toLongOrNull() ?: 0L
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            if (bw > bestBw) { bestBw = bw; best = uri }
        }
        return resolve(baseUrl, best ?: throw HlsException("This video has no playable streams."))
    }

    fun parseMedia(text: String, baseUrl: String): HlsPlan {
        val lines = text.lines().map { it.trim() }
        if (lines.firstOrNull() != "#EXTM3U") throw HlsException("This isn't a video playlist.")
        if (lines.none { it == "#EXT-X-ENDLIST" }) throw HlsException("Live streams can't be downloaded.")
        if (lines.any { it.startsWith("#EXT-X-BYTERANGE") }) throw HlsException("This stream type isn't supported yet.")

        var sequence = 0L
        var key: HlsKey? = null
        var initUrl: String? = null
        var initKey: HlsKey? = null
        var duration = 0.0
        val segments = ArrayList<HlsSegment>()
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-KEY:") -> {
                    val a = attributes(line.substringAfter(':'))
                    key = when (a["METHOD"]) {
                        null, "NONE" -> null
                        "AES-128" -> HlsKey(resolve(baseUrl, a["URI"] ?: throw HlsException("Missing decryption key.")), a["IV"]?.let(::hexToBytes))
                        else -> throw HlsException("This video is protected and can't be downloaded.")
                    }
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    initUrl = resolve(baseUrl, attributes(line.substringAfter(':'))["URI"] ?: continue)
                    initKey = key
                }
                line.startsWith("#EXTINF:") -> duration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                line.isNotEmpty() && !line.startsWith("#") -> {
                    segments += HlsSegment(resolve(baseUrl, line), sequence, duration, key)
                    sequence++
                    duration = 0.0
                }
            }
        }
        if (segments.isEmpty()) throw HlsException("This video has no segments to download.")
        return HlsPlan(segments, initUrl, initKey)
    }

    /** "KEY=value,KEY2="quoted, value"" → map. */
    fun attributes(s: String): Map<String, String> =
        Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)").findAll(s).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    fun resolve(base: String, ref: String): String =
        runCatching { URI(base).resolve(ref.trim()).toString() }.getOrDefault(ref)

    private fun hexToBytes(hex: String): ByteArray {
        val h = hex.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** The 16-byte CBC IV HLS uses when a playlist doesn't give one: the segment number, big-endian. */
    fun sequenceIv(sequence: Long): ByteArray =
        ByteArray(16).also { for (i in 0 until 8) it[15 - i] = (sequence shr (8 * i)).toByte() }
}
