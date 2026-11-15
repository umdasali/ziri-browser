package com.ziribrowser.ui.viewer

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.ziribrowser.data.DownloadStatus
import com.ziribrowser.download.DownloadEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.FileInputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** The URI the player uses for a download: "ziridl://<download id>". */
fun downloadPlayerUri(id: Long): Uri = Uri.parse("ziridl://$id")

/**
 * Lets the video player play a file that is still being downloaded (like UC Browser's "watch while
 * downloading"). It reads the partly written file and, when the player asks for bytes that haven't
 * arrived yet, waits for the download to catch up instead of failing. When the download finishes it
 * behaves like a normal file.
 *
 * Limits: a video whose index is stored at the END of the file (some MP4s) can only start once the
 * download reaches the end; seeking ahead of the download waits for it.
 */
@UnstableApi
class GrowingFileDataSource(private val context: Context, private val engine: DownloadEngine) : BaseDataSource(false) {
    private var uri: Uri? = null
    private var id = -1L
    private var pfd: ParcelFileDescriptor? = null
    private var channel: FileChannel? = null
    private var position = 0L
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var opened = false
    private var knownWritten = 0L

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        id = dataSpec.uri.host?.toLongOrNull() ?: throw IOException("Not a download address")
        transferInitializing(dataSpec)
        val d = runBlocking(Dispatchers.IO) { engine.entity(id) } ?: throw IOException("This download was removed")
        val p = context.contentResolver.openFileDescriptor(Uri.parse(d.fileUri), "r") ?: throw IOException("Can't open the file")
        pfd = p
        channel = FileInputStream(p.fileDescriptor).channel
        position = dataSpec.position
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            d.fileSize > 0 -> (d.fileSize - position).coerceAtLeast(0)
            else -> C.LENGTH_UNSET.toLong() // size unknown (e.g. a stream being rebuilt): read until the download ends
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val ch = channel ?: throw IOException("Not open")
        // Wait until the download has written past the position being read.
        while (position >= knownWritten) {
            val d = runBlocking(Dispatchers.IO) { engine.entity(id) } ?: throw IOException("This download was removed")
            val completed = d.status == DownloadStatus.COMPLETED
            knownWritten = if (completed) ch.size() else engine.availableBytes(d) ?: d.downloadedSize
            if (position < knownWritten) break
            if (completed) return C.RESULT_END_OF_INPUT
            if (d.status == DownloadStatus.FAILED) throw IOException(d.error.ifEmpty { "The download failed" })
            try { Thread.sleep(150) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw InterruptedIOException() }
        }
        var n = minOf(length.toLong(), knownWritten - position)
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) n = minOf(n, bytesRemaining)
        val read = ch.read(ByteBuffer.wrap(buffer, offset, n.toInt()), position)
        if (read <= 0) return C.RESULT_END_OF_INPUT
        position += read
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        runCatching { channel?.close() }
        runCatching { pfd?.close() }
        channel = null; pfd = null
        if (opened) { opened = false; transferEnded() }
    }

    class Factory(private val context: Context, private val engine: DownloadEngine) : DataSource.Factory {
        override fun createDataSource(): DataSource = GrowingFileDataSource(context, engine)
    }
}
