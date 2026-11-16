package com.ziribrowser.ui.viewer

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.MediaSession
import com.ziribrowser.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One thing to play. [key] stores the resume position, [downloadId] means it is a Ziri download (maybe still downloading). */
data class PlayItem(
    val uri: Uri, val title: String, val key: String?, val downloadId: Long? = null, val mime: String? = null,
    /** Subtitle files found next to the video (same name, .srt/.vtt/.ass). */
    val subtitles: List<Uri> = emptyList(),
)

/** A list of videos or songs to play one after another, starting at [start]. */
data class PlayQueue(val items: List<PlayItem>, val start: Int = 0) {
    fun sameAs(o: PlayQueue?) = o != null && o.items.map { it.uri } == items.map { it.uri }
}

/** Sends "ziridl://" addresses (downloads still being written) to the growing-file reader and everything else to the normal reader. */
@androidx.annotation.OptIn(UnstableApi::class)
class RoutingDataSource(private val context: Context, private val container: AppContainer) : DataSource {
    private var delegate: DataSource? = null
    private val listeners = ArrayList<TransferListener>()

    override fun addTransferListener(l: TransferListener) { listeners += l }

    override fun open(dataSpec: DataSpec): Long {
        val d = if (dataSpec.uri.scheme == "ziridl") GrowingFileDataSource(context, container.downloads) else DefaultDataSource.Factory(context).createDataSource()
        listeners.forEach(d::addTransferListener)
        delegate = d
        return d.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int) = delegate!!.read(buffer, offset, length)
    override fun getUri(): Uri? = delegate?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()
    override fun close() { try { delegate?.close() } finally { delegate = null } }
}

/**
 * Owns the one video/audio player so playback can carry on when the player screen is closed (background audio),
 * show lock-screen controls, and be controlled from Picture-in-Picture. Released as soon as nobody needs it.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerHolder(private val app: Application, private val container: AppContainer) {
    var player: ExoPlayer? = null
        private set
    private var session: MediaSession? = null
    var queue: PlayQueue? = null
        private set

    /** Counts the players created so far, so the screen can switch to the new one after the decoder setting rebuilds it. */
    val generation = MutableStateFlow(0)
    /** Length of each item once known: the player only reports the current item's, which is wrong right after a skip. */
    private val durations = HashMap<Int, Long>()

    /** True while the full-screen player is on screen. */
    @Volatile var screenOpen = false

    /** Keep playing when the player screen is closed or the app goes to the background. */
    val keepBackground = MutableStateFlow(false)

    /** When the sleep timer fires (epoch ms), [END_OF_VIDEO] for "after this video", or null when off. */
    val sleepEndsAt = MutableStateFlow<Long?>(null)
    private var sleepJob: Job? = null

    fun mediaSession(): MediaSession? = session

    /** Returns the player for [q], reusing the running one if it is already playing this same list. */
    fun start(q: PlayQueue): ExoPlayer {
        val existing = player
        if (existing != null && queue.sameAs0(q)) {
            // Opened again from the list while it plays in the background: honour the item that was tapped.
            if (!screenOpen && q.start in 0 until existing.mediaItemCount && q.start != existing.currentMediaItemIndex) existing.seekTo(q.start, C.TIME_UNSET)
            screenOpen = true
            return existing
        }
        release()
        durations.clear()
        val renderers = DefaultRenderersFactory(app).setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setMediaCodecSelector { mime, secure, tunneling ->
                val all = androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                // "Software decoder" puts software decoders first (they play some files hardware ones can't).
                if (softwareDecoding.value) all.sortedByDescending { it.softwareOnly } else all
            }
        val factory = DataSource.Factory { RoutingDataSource(app, container) }
        val p = ExoPlayer.Builder(app, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory, DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        p.setMediaItems(q.items.mapIndexed { i, it ->
            MediaItem.Builder().setUri(it.uri).setMediaId(i.toString())
                .setMimeType(it.mime?.takeIf { m -> m.startsWith("video/") || m.startsWith("audio/") })
                .setSubtitleConfigurations(it.subtitles.mapIndexed { n, u -> subtitleConfig(u, default = n == 0) })
                .setMediaMetadata(MediaMetadata.Builder().setTitle(it.title).build()).build()
        }, q.start, C.TIME_UNSET)
        p.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (old.mediaItemIndex != new.mediaItemIndex) {
                    saveFor(old.mediaItemIndex, old.positionMs, durations[old.mediaItemIndex], finished = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
                    restoreFor(new.mediaItemIndex)
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && p.duration > 0) durations[p.currentMediaItemIndex] = p.duration
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // The sleep timer's "after this video" has done its job.
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) clearSleep()
            }
        })
        p.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) { bindEffects(audioSessionId) }
        })
        p.playWhenReady = true
        p.prepare()
        if (p.audioSessionId != C.AUDIO_SESSION_ID_UNSET) bindEffects(p.audioSessionId)
        player = p
        queue = q
        generation.update { it + 1 }
        session = MediaSession.Builder(app, p).build()
        screenOpen = true
        restoreFor(q.start)
        // The service puts itself in the foreground (with the playback notification) once playback is running.
        runCatching { app.startService(Intent(app, PlaybackService::class.java)) }
        return p
    }

    private fun PlayQueue?.sameAs0(q: PlayQueue) = this != null && sameAs(q)

    /** Remembers where the current item is, so it can be resumed later. Safe to call often. */
    fun saveCurrent() {
        val p = player ?: return
        saveFor(p.currentMediaItemIndex, p.currentPosition, p.duration.takeIf { it > 0 }, finished = false)
    }

    private fun saveFor(index: Int, posMs: Long, durMs: Long?, finished: Boolean) {
        val key = queue?.items?.getOrNull(index)?.key ?: return
        if (durMs == null && !finished) return
        val done = finished || (durMs != null && (posMs >= durMs - 5000 || posMs < 3000))
        container.scope.launch { container.settings.setVideoPosition(key, if (done) null else posMs / 1000.0) }
    }

    private fun restoreFor(index: Int) {
        val key = queue?.items?.getOrNull(index)?.key ?: return
        container.scope.launch {
            val saved = container.settings.videoPosition(key) ?: return@launch
            if (saved > 3) withContext(Dispatchers.Main) { player?.let { if (it.currentMediaItemIndex == index && it.currentPosition < 3000) it.seekTo(index, (saved * 1000).toLong()) } }
        }
    }

    /** Called when the player screen closes. Keeps going in the background only if asked to and still playing. */
    fun screenClosed() {
        screenOpen = false
        saveCurrent()
        val p = player ?: return
        if (!(keepBackground.value && p.isPlaying)) release()
    }

    fun release() {
        clearSleep()
        releaseEffects()
        saveCurrent()
        runCatching { session?.release() }
        runCatching { player?.release() }
        session = null; player = null; queue = null
        screenOpen = false
        runCatching { app.stopService(Intent(app, PlaybackService::class.java)) }
    }

    // ---- decoder -----------------------------------------------------------------------------------------
    /** Prefer software decoders. Changing it restarts playback of the current item from where it was. */
    val softwareDecoding = MutableStateFlow(false)

    fun setSoftwareDecoding(on: Boolean) {
        if (softwareDecoding.value == on) return
        softwareDecoding.value = on
        val p = player ?: return
        val q = queue ?: return
        val index = p.currentMediaItemIndex
        val pos = p.currentPosition
        val wasPlaying = p.playWhenReady
        release()
        start(q.copy(start = index)).apply { seekTo(index, pos); playWhenReady = wasPlaying }
    }

    // ---- audio effects: volume boost, equaliser presets, bass boost --------------------------------------------
    /** Extra volume above 100% in dB (0, 3, 6, 9). */
    val boostDb = MutableStateFlow(0)
    /** Equaliser preset number, or -1 for off. */
    val eqPreset = MutableStateFlow(-1)
    val bassBoost = MutableStateFlow(false)
    val eqPresetNames = MutableStateFlow<List<String>>(emptyList())
    private var loudness: android.media.audiofx.LoudnessEnhancer? = null
    private var equalizer: android.media.audiofx.Equalizer? = null
    private var bass: android.media.audiofx.BassBoost? = null

    private fun bindEffects(session: Int) {
        if (session == C.AUDIO_SESSION_ID_UNSET || session == 0) return
        releaseEffects()
        runCatching { loudness = android.media.audiofx.LoudnessEnhancer(session) }
        runCatching {
            equalizer = android.media.audiofx.Equalizer(0, session).also { e ->
                eqPresetNames.value = (0 until e.numberOfPresets).map { e.getPresetName(it.toShort()) }
            }
        }
        runCatching { bass = android.media.audiofx.BassBoost(0, session) }
        applyEffects()
    }

    /** Pushes the current boost / equaliser / bass settings to the audio effects. */
    fun applyEffects() {
        runCatching { loudness?.apply { setTargetGain(boostDb.value * 100); enabled = boostDb.value > 0 } }
        runCatching {
            equalizer?.apply {
                val p = eqPreset.value
                if (p in 0 until numberOfPresets) { usePreset(p.toShort()); enabled = true } else enabled = false
            }
        }
        runCatching { bass?.apply { if (strengthSupported) setStrength(800); enabled = bassBoost.value } }
    }

    private fun releaseEffects() {
        runCatching { loudness?.release() }; runCatching { equalizer?.release() }; runCatching { bass?.release() }
        loudness = null; equalizer = null; bass = null
    }

    // ---- sleep timer ---------------------------------------------------------------------------------------
    /** [minutes] = null turns the timer off, [END_OF_VIDEO] stops after the current video. */
    fun setSleep(minutes: Int?) {
        clearSleep()
        val p = player ?: return
        when (minutes) {
            null -> {}
            END_OF_VIDEO -> { p.pauseAtEndOfMediaItems = true; sleepEndsAt.value = END_OF_VIDEO.toLong() }
            else -> {
                sleepEndsAt.value = System.currentTimeMillis() + minutes * 60_000L
                sleepJob = container.scope.launch {
                    delay(minutes * 60_000L)
                    withContext(Dispatchers.Main) { player?.pause() }
                    sleepEndsAt.value = null
                }
            }
        }
    }

    private fun clearSleep() {
        sleepJob?.cancel(); sleepJob = null
        player?.pauseAtEndOfMediaItems = false
        sleepEndsAt.value = null
    }

    companion object { const val END_OF_VIDEO = -1 }
}

/** The subtitle format Exo needs, guessed from the file name. */
@UnstableApi
fun subtitleConfig(uri: Uri, default: Boolean): MediaItem.SubtitleConfiguration {
    val name = (uri.lastPathSegment ?: uri.toString()).lowercase()
    val mime = when {
        name.endsWith(".vtt") -> androidx.media3.common.MimeTypes.TEXT_VTT
        name.endsWith(".ass") || name.endsWith(".ssa") -> androidx.media3.common.MimeTypes.TEXT_SSA
        name.endsWith(".ttml") || name.endsWith(".xml") -> androidx.media3.common.MimeTypes.APPLICATION_TTML
        else -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
    }
    return MediaItem.SubtitleConfiguration.Builder(uri).setMimeType(mime).setSelectionFlags(if (default) C.SELECTION_FLAG_DEFAULT else 0).build()
}
