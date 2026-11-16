package com.ziribrowser.ui.viewer

import com.ziribrowser.ziriString
import com.ziribrowser.R
import androidx.compose.ui.res.stringResource
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.border
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.LightMode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import com.ziribrowser.AppContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
// AspectRatioFrameLayout resize modes: 0 = fit, 3 = fill (stretch), 4 = zoom (crop).
private val resizeModes get() = listOf(
    0 to ziriString(R.string.fit),
    4 to ziriString(R.string.crop),
    3 to ziriString(R.string.stretch),
)

/**
 * A full video player in the style of MX Player. Plays device files, links from other apps and
 * downloads (including ones still downloading). Gestures: tap to show controls, double-tap left/right
 * to jump 10 s, drag sideways to scrub, drag up/down on the left for brightness and the right for volume.
 * Also: playback speed, audio and subtitle tracks, subtitle files, screen lock, aspect ratio, rotation,
 * and remembered position.
 *
 * [downloadId] is set when the file is a Ziri download, so the growing-file reader is used.
 * [positionKey] stores/restores the playback position (null = don't remember).
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MxPlayer(
    container: AppContainer,
    queue: PlayQueue,
    onBack: () -> Unit,
    banner: @Composable () -> Unit = {},
    errorExtra: @Composable (retry: () -> Unit) -> Unit = {},
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val holder = container.players
    var error by remember(queue) { mutableStateOf<String?>(null) }
    // Changing the decoder rebuilds the player; the generation makes this screen pick up the new one instead of the released one.
    val generation by holder.generation.collectAsStateWithLifecycle()
    val player = remember(queue, generation) { holder.start(queue) }
    var index by remember(queue) { mutableIntStateOf(player.currentMediaItemIndex) }
    val current = queue.items.getOrNull(index)
    val title = current?.title.orEmpty()
    val downloadId = current?.downloadId
    val inPip by container.pipMode.collectAsStateWithLifecycle()
    val keepBackground by holder.keepBackground.collectAsStateWithLifecycle()
    val sleepEndsAt by holder.sleepEndsAt.collectAsStateWithLifecycle()

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) { error = e.message ?: ziriString(R.string.this_video_can_t_be_played) }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) { index = player.currentMediaItemIndex }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); holder.screenClosed() }
    }
    LaunchedEffect(player) { while (true) { delay(5000); if (player.isPlaying) holder.saveCurrent() } }

    // Going to the background pauses, unless background play is on or the video is in a Picture-in-Picture window.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP && !holder.keepBackground.value && !container.pipMode.value) { player.pause(); holder.saveCurrent() }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // ---- UI state --------------------------------------------------------------------------------
    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var isPlaying by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    var resize by remember { mutableIntStateOf(0) }
    var landscape by remember { mutableStateOf<Boolean?>(null) }
    var hud by remember { mutableStateOf<String?>(null) }
    var speedDialog by remember { mutableStateOf(false) }
    var trackDialog by remember { mutableStateOf(false) }
    var sleepDialog by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var audioDialog by remember { mutableStateOf(false) }
    var subStyleDialog by remember { mutableStateOf(false) }
    var loopA by remember { mutableStateOf<Long?>(null) }
    var loopB by remember { mutableStateOf<Long?>(null) }
    val subSize by container.settings.subSize.collectAsStateWithLifecycle(100)
    val subColor by container.settings.subColor.collectAsStateWithLifecycle(0xFFFFFFFF.toInt())
    val subBg by container.settings.subBackground.collectAsStateWithLifecycle(false)
    val softwareDecoding by holder.softwareDecoding.collectAsStateWithLifecycle()
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
            override fun onTracksChanged(t: Tracks) { tracks = t }
        }
        player.addListener(l)
        tracks = player.currentTracks
        onDispose { player.removeListener(l) }
    }
    // Only while the controls are showing: they stay composed when hidden, and updating them every 250 ms for nothing wastes frames.
    LaunchedEffect(player, controlsVisible) {
        if (!controlsVisible) return@LaunchedEffect
        while (true) {
            position = player.currentPosition
            duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            buffered = player.bufferedPosition
            delay(250)
        }
    }
    LaunchedEffect(lastTouch, isPlaying, locked) {
        controlsVisible = true
        if (isPlaying) { delay(3500); controlsVisible = false }
    }
    LaunchedEffect(hud) { if (hud != null) { delay(800); hud = null } }
    // A-B loop: when playback reaches B, jump back to A.
    LaunchedEffect(loopA, loopB) {
        val a = loopA ?: return@LaunchedEffect
        val b = loopB ?: return@LaunchedEffect
        while (true) { delay(60); if (player.currentPosition >= b || player.currentPosition < a - 500) player.seekTo(a) }
    }

    DisposableEffect(landscape) {
        activity?.requestedOrientation = when (landscape) {
            true -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            false -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            null -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose {}
    }

    // ---- brightness / volume ---------------------------------------------------------------------
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var brightness by remember {
        mutableFloatStateOf(
            activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0 }
                ?: (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f),
        )
    }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) }
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = WindowManagerBrightnessDefault } }
        }
    }

    // ---- subtitle file picker --------------------------------------------------------------------
    val pickSubtitle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            val name = picked.lastPathSegment.orEmpty().lowercase()
            val subMime = when {
                name.endsWith(".vtt") -> MimeTypes.TEXT_VTT
                name.endsWith(".ass") || name.endsWith(".ssa") -> MimeTypes.TEXT_SSA
                name.endsWith(".ttml") || name.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            val sub = MediaItem.SubtitleConfiguration.Builder(picked).setMimeType(subMime).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
            val at = player.currentPosition
            val idx = player.currentMediaItemIndex
            val item = player.currentMediaItem?.buildUpon()?.setSubtitleConfigurations(listOf(sub))?.build()
            if (item != null) {
                player.replaceMediaItem(idx, item)
                player.seekTo(idx, at)
                player.play()
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
            }
            trackDialog = false
        }
    }

    fun toggle() { if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() } }
    fun seekBy(ms: Long) { player.seekTo((player.currentPosition + ms).coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE)) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    keepScreenOn = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            update = {
                it.resizeMode = resizeModes[resize].first
                it.subtitleView?.apply {
                    setApplyEmbeddedStyles(false)
                    setApplyEmbeddedFontSizes(false)
                    setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subSize / 100f)
                    setStyle(
                        androidx.media3.ui.CaptionStyleCompat(
                            subColor, if (subBg) 0xB3000000.toInt() else 0, 0, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE, 0xFF000000.toInt(), null,
                        ),
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // ---- gesture layer ------------------------------------------------------------------------
        Box(
            Modifier.fillMaxSize()
                .pointerInput(locked) {
                    var shownOnPress = false
                    detectTapGestures(
                        onPress = {
                            // Reveal the controls the moment a finger lands; waiting for the
                            // double-tap window made them feel slow.
                            shownOnPress = false
                            if (!controlsVisible) { controlsVisible = true; shownOnPress = true; lastTouch = System.currentTimeMillis() }
                            // Hold to play at 2x; let go to go back to the chosen speed.
                            if (!locked && kotlinx.coroutines.withTimeoutOrNull(450) { tryAwaitRelease() } == null) {
                                player.setPlaybackSpeed(2f); hud = "2x speed"
                                tryAwaitRelease()
                                player.setPlaybackSpeed(speed); hud = null
                            }
                        },
                        onTap = { if (shownOnPress) shownOnPress = false else if (controlsVisible) controlsVisible = false else lastTouch = System.currentTimeMillis() },
                        onDoubleTap = { o ->
                            if (locked) return@detectTapGestures
                            when {
                                o.x < size.width * 0.4f -> { seekBy(-10_000); hud = "−10 s" }
                                o.x > size.width * 0.6f -> { seekBy(10_000); hud = "+10 s" }
                                else -> toggle()
                            }
                        },
                    )
                }
                .pointerInput(locked) {
                    // Pinch: spread two fingers to fill the screen (crop), pinch together to fit.
                    if (locked) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var start = 0f
                        var done = false
                        do {
                            val ev = awaitPointerEvent()
                            if (ev.changes.size >= 2) {
                                val d = (ev.changes[0].position - ev.changes[1].position).let { kotlin.math.hypot(it.x, it.y) }
                                if (start == 0f) start = d
                                else if (!done) {
                                    val r = d / start
                                    if (r > 1.15f) { resize = 1; hud = resizeModes[1].second; done = true }
                                    else if (r < 0.87f) { resize = 0; hud = resizeModes[0].second; done = true }
                                }
                                ev.changes.forEach { it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                }
                .pointerInput(locked) {
                    if (locked) return@pointerInput
                    var mode = 0 // 0 undecided, 1 seek, 2 brightness, 3 volume
                    var startX = 0f
                    var seekStart = 0L
                    var seekTarget = 0L
                    var total = androidx.compose.ui.geometry.Offset.Zero
                    detectDragGestures(
                        onDragStart = { o -> mode = 0; startX = o.x; total = androidx.compose.ui.geometry.Offset.Zero; seekStart = player.currentPosition; seekTarget = seekStart },
                        onDragEnd = { if (mode == 1) player.seekTo(seekTarget); mode = 0 },
                        onDragCancel = { mode = 0 },
                    ) { change, drag ->
                        change.consume()
                        total += drag
                        if (mode == 0 && (abs(total.x) > 24f || abs(total.y) > 24f)) {
                            mode = if (abs(total.x) > abs(total.y)) 1 else if (startX < size.width / 2f) 2 else 3
                        }
                        when (mode) {
                            1 -> {
                                val dur = if (duration > 0) duration else 0L
                                val span = minOf(dur, 120_000L).coerceAtLeast(30_000L)
                                seekTarget = (seekStart + (total.x / size.width * span).toLong()).coerceIn(0, if (dur > 0) dur else Long.MAX_VALUE)
                                val diff = (seekTarget - seekStart) / 1000
                                hud = "${fmt(seekTarget)}  (${if (diff >= 0) "+" else "−"}${abs(diff)} s)"
                            }
                            2 -> {
                                brightness = (brightness - drag.y / size.height * 1.4f).coerceIn(0.02f, 1f)
                                activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = brightness } }
                                hud = ziriString(R.string.brightness_2, (brightness * 100).roundToInt())
                            }
                            3 -> {
                                volume = (volume - drag.y / size.height * 1.4f).coerceIn(0f, 1f)
                                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volume * max).roundToInt(), 0)
                                hud = ziriString(R.string.volume_2, (volume * 100).roundToInt())
                            }
                        }
                    }
                },
        )

        // ---- on-screen feedback: side meters for brightness/volume, a big pill for seeking ---------------
        var lastHud by remember { mutableStateOf("") }
        if (hud != null) lastHud = hud.orEmpty()
        val hudAlign = when {
            lastHud.startsWith(stringResource(R.string.brightness_3)) -> Alignment.CenterStart
            lastHud.startsWith(stringResource(R.string.volume_3)) -> Alignment.CenterEnd
            lastHud.startsWith("−") && lastHud.endsWith(" s") -> Alignment.CenterStart
            lastHud.startsWith("+") && lastHud.endsWith(" s") -> Alignment.CenterEnd
            else -> Alignment.Center
        }
        AnimatedVisibility(hud != null && !inPip, Modifier.align(hudAlign), enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut()) {
            when {
                lastHud.startsWith(stringResource(R.string.brightness_3)) -> SideMeter(Icons.Rounded.LightMode, brightness, Modifier.padding(start = 28.dp))
                lastHud.startsWith(stringResource(R.string.volume_3)) -> SideMeter(if (volume <= 0.01f) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, volume, Modifier.padding(end = 28.dp))
                lastHud.startsWith("−") && lastHud.endsWith(" s") -> SkipBadge(Icons.Rounded.Replay10, lastHud, Modifier.padding(start = 56.dp))
                lastHud.startsWith("+") && lastHud.endsWith(" s") -> SkipBadge(Icons.Rounded.Forward10, lastHud, Modifier.padding(end = 56.dp))
                else -> Text(
                    lastHud, color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.glass(RoundedCornerShape(20.dp)).padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
        }

        if (inPip) {
            // The small Picture-in-Picture window shows just the video.
        } else if (locked) {
            Box(Modifier.align(Alignment.CenterEnd).safeEdges(androidx.compose.foundation.layout.WindowInsetsSides.Horizontal).padding(24.dp).fadeVisible(controlsVisible)) {
                GlassCircle(Icons.Rounded.LockOpen, stringResource(R.string.unlock), 56.dp, tint = MaterialTheme.colorScheme.primary) { locked = false; lastTouch = System.currentTimeMillis() }
            }
        } else {
            // ---- top: back, title, actions ------------------------------------------------------------
            Box(Modifier.align(Alignment.TopStart).fadeVisible(controlsVisible)) {
                Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))) {
                    Row(Modifier.fillMaxWidth().safeEdges(androidx.compose.foundation.layout.WindowInsetsSides.Horizontal + androidx.compose.foundation.layout.WindowInsetsSides.Top).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassCircle(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), 44.dp, onClick = onBack)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp).glass(RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 7.dp)) {
                            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            val sub = listOfNotNull(
                                if (queue.items.size > 1) "${index + 1} of ${queue.items.size}" else null,
                                if (keepBackground) stringResource(R.string.background_play) else null,
                                sleepEndsAt?.let { if (it < 0) stringResource(R.string.sleep_after_this_video) else stringResource(R.string.sleep_timer_on_2) },
                            ).joinToString(" · ")
                            if (sub.isNotEmpty()) Text(sub, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                        Row(Modifier.glass(CircleShape).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            // Portrait <-> landscape, flipping from whatever the screen is showing right now.
                            val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                            IconButton({ landscape = !isLandscape; lastTouch = System.currentTimeMillis() }, Modifier.size(42.dp)) {
                                Icon(Icons.Rounded.ScreenRotation, stringResource(R.string.rotate_screen), tint = Color.White)
                            }
                            IconButton({ trackDialog = true }, Modifier.size(42.dp)) { Icon(Icons.Rounded.Subtitles, stringResource(R.string.audio_and_subtitles), tint = Color.White) }
                            IconButton({ container.enterPip?.invoke() }, Modifier.size(42.dp)) { Icon(Icons.Rounded.PictureInPictureAlt, stringResource(R.string.picture_in_picture), tint = Color.White) }
                            Box {
                                IconButton({ moreOpen = true }, Modifier.size(42.dp)) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = Color.White) }
                                DropdownMenu(moreOpen, { moreOpen = false }, shape = RoundedCornerShape(20.dp)) {
                                    DropdownMenuItem(
                                        { Text(if (sleepEndsAt != null) stringResource(R.string.sleep_timer_on) else stringResource(R.string.sleep_timer)) },
                                        { moreOpen = false; sleepDialog = true },
                                        leadingIcon = { Icon(Icons.Rounded.Bedtime, null) },
                                    )
                                    DropdownMenuItem(
                                        { Text(if (keepBackground) stringResource(R.string.background_play_on) else stringResource(R.string.play_in_background)) },
                                        { moreOpen = false; holder.keepBackground.value = !keepBackground; hud = if (!keepBackground) ziriString(R.string.plays_in_the_background) else ziriString(R.string.stops_in_the_background) },
                                        leadingIcon = { Icon(Icons.Rounded.Headphones, null) },
                                    )
                                    DropdownMenuItem(
                                        { Text(stringResource(R.string.aspect, resizeModes[resize].second)) },
                                        { moreOpen = false; resize = (resize + 1) % resizeModes.size; hud = resizeModes[resize].second },
                                        leadingIcon = { Icon(Icons.Rounded.AspectRatio, null) },
                                    )
                                    DropdownMenuItem(
                                        { Text(stringResource(R.string.subtitle_style)) },
                                        { moreOpen = false; subStyleDialog = true },
                                        leadingIcon = { Icon(Icons.Rounded.Subtitles, null) },
                                    )
                                    DropdownMenuItem(
                                        { Text(if (softwareDecoding) stringResource(R.string.decoder_software) else stringResource(R.string.decoder_hardware)) },
                                        { moreOpen = false; holder.setSoftwareDecoding(!softwareDecoding); hud = ziriString(if (!softwareDecoding) R.string.restarting_software else R.string.restarting_hardware) },
                                        leadingIcon = { Icon(Icons.Rounded.Memory, null) },
                                    )
                                    DropdownMenuItem(
                                        { Text(stringResource(R.string.rotate_screen)) },
                                        { moreOpen = false; landscape = if (landscape == true) false else true },
                                        leadingIcon = { Icon(Icons.Rounded.ScreenRotation, null) },
                                    )
                                }
                            }
                        }
                    }
                    banner()
                }
            }

            // ---- centre: big play / pause with skip buttons around it --------------------------------------
            Box(Modifier.align(Alignment.Center).fadeVisible(controlsVisible)) {
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (queue.items.size > 1) GlassCircle(Icons.Rounded.SkipPrevious, stringResource(R.string.previous_video), 48.dp) { player.seekToPreviousMediaItem(); lastTouch = System.currentTimeMillis() }
                    GlassCircle(Icons.Rounded.Replay10, stringResource(R.string.back_10_seconds), 54.dp) { seekBy(-10_000); lastTouch = System.currentTimeMillis() }
                    Box(
                        Modifier.size(80.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.92f))
                            .clickable(role = androidx.compose.ui.semantics.Role.Button) { toggle(); lastTouch = System.currentTimeMillis() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (isPlaying) stringResource(R.string.pause) else stringResource(R.string.play),
                            tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(44.dp),
                        )
                    }
                    GlassCircle(Icons.Rounded.Forward10, stringResource(R.string.forward_10_seconds), 54.dp) { seekBy(10_000); lastTouch = System.currentTimeMillis() }
                    if (queue.items.size > 1) GlassCircle(Icons.Rounded.SkipNext, stringResource(R.string.next_video), 48.dp) { player.seekToNextMediaItem(); lastTouch = System.currentTimeMillis() }
                }
            }

            // ---- bottom: seek bar and quick settings in one glass card ------------------------------------------
            Box(Modifier.align(Alignment.BottomStart).fadeVisible(controlsVisible)) {
                Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))).safeEdges(androidx.compose.foundation.layout.WindowInsetsSides.Horizontal + androidx.compose.foundation.layout.WindowInsetsSides.Bottom).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    SeekPreview(current, scrub, duration)
                    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(fmt(scrub?.let { (it * duration).toLong() } ?: position), color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Slider(
                                value = scrub ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                                onValueChange = { scrub = it; lastTouch = System.currentTimeMillis() },
                                onValueChangeFinished = { scrub?.let { player.seekTo((it * duration).toLong()) }; scrub = null },
                                modifier = Modifier.weight(1f).padding(horizontal = 6.dp).semantics {
                                    contentDescription = ziriString(R.string.seek_bar)
                                    stateDescription = "${fmt(position)} of ${fmt(duration)}"
                                },
                                thumb = {
                                    Box(
                                        Modifier.size(if (scrub != null) 22.dp else 15.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                                            .border(2.dp, Color.White, CircleShape),
                                    )
                                },
                                track = { st ->
                                    SliderDefaults.Track(
                                        st, Modifier.height(if (scrub != null) 7.dp else 5.dp),
                                        colors = SliderDefaults.colors(activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = Color.White.copy(alpha = 0.28f)),
                                        thumbTrackGapSize = 0.dp, trackInsideCornerSize = 0.dp, drawStopIndicator = {},
                                    )
                                },
                            )
                            Text(fmt(duration), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
                        }
                        if (!isPlaying) Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            GlassChip(null, "◀ Frame") { stepFrame(player, -1); lastTouch = System.currentTimeMillis() }
                            Box(Modifier.width(10.dp))
                            GlassChip(null, stringResource(R.string.frame)) { stepFrame(player, 1); lastTouch = System.currentTimeMillis() }
                        }
                        FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            GlassChip(Icons.Rounded.Lock, stringResource(R.string.lock)) { locked = true; controlsVisible = true; lastTouch = System.currentTimeMillis() }
                            GlassChip(null, if (speed == 1f) "1x" else "${speed}x", active = speed != 1f) { speedDialog = true; lastTouch = System.currentTimeMillis() }
                            GlassChip(Icons.Rounded.AspectRatio, resizeModes[resize].second) { resize = (resize + 1) % resizeModes.size; lastTouch = System.currentTimeMillis() }
                            GlassChip(
                                null, if (loopA == null) "A–B" else if (loopB == null) "A→" else stringResource(R.string.loop), active = loopA != null,
                            ) {
                                val pos = player.currentPosition
                                when {
                                    loopA == null -> { loopA = pos; hud = ziriString(R.string.loop_start_set) }
                                    loopB == null -> { if (pos > loopA!! + 500) { loopB = pos; hud = ziriString(R.string.looping) } else hud = ziriString(R.string.pick_a_later_point) }
                                    else -> { loopA = null; loopB = null; hud = ziriString(R.string.loop_off) }
                                }
                                lastTouch = System.currentTimeMillis()
                            }
                            GlassChip(Icons.Rounded.Tune, stringResource(R.string.audio)) { audioDialog = true; lastTouch = System.currentTimeMillis() }
                        }
                    }
                }
            }
        }

        error?.let { msg ->
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.this_video_can_t_be_played), color = Color.White, textAlign = TextAlign.Center)
                Text(msg, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
                errorExtra { error = null; player.prepare(); player.play() }
            }
        }
    }

    // ---- dialogs -----------------------------------------------------------------------------------
    if (speedDialog) {
        AlertDialog(
            onDismissRequest = { speedDialog = false }, title = { Text(stringResource(R.string.playback_speed)) },
            text = {
                Column {
                    speeds.forEach { s ->
                        Text(
                            if (s == 1f) stringResource(R.string.normal) else "${s}x",
                            color = if (s == speed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (s == speed) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.RadioButton) { speed = s; player.setPlaybackSpeed(s); speedDialog = false }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton({ speedDialog = false }) { Text(stringResource(R.string.close)) } },
        )
    }
    if (audioDialog) AudioEffectsDialog(holder) { audioDialog = false }
    if (subStyleDialog) {
        val scope2 = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { subStyleDialog = false }, title = { Text(stringResource(R.string.subtitle_style)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.size), fontWeight = FontWeight.SemiBold)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(70 to stringResource(R.string.small), 100 to stringResource(R.string.normal), 140 to stringResource(R.string.large), 190 to stringResource(R.string.huge)).forEach { (v, l) ->
                            androidx.compose.material3.FilterChip(subSize == v, { scope2.launch { container.settings.setSubSize(v) } }, { Text(l) })
                        }
                    }
                    Text(stringResource(R.string.colour), fontWeight = FontWeight.SemiBold)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0xFFFFFFFF.toInt() to stringResource(R.string.white), 0xFFFFEB3B.toInt() to stringResource(R.string.yellow), 0xFF80DEEA.toInt() to stringResource(R.string.cyan), 0xFFA5D6A7.toInt() to stringResource(R.string.green)).forEach { (c, l) ->
                            androidx.compose.material3.FilterChip(subColor == c, { scope2.launch { container.settings.setSubColor(c) } }, { Text(l) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.dark_background), Modifier.weight(1f))
                        androidx.compose.material3.Switch(subBg, { scope2.launch { container.settings.setSubBackground(it) } })
                    }
                }
            },
            confirmButton = { TextButton({ subStyleDialog = false }) { Text(stringResource(R.string.done)) } },
        )
    }
    if (sleepDialog) {
        AlertDialog(
            onDismissRequest = { sleepDialog = false }, title = { Text(stringResource(R.string.sleep_timer)) },
            text = {
                Column {
                    listOf(null to stringResource(R.string.off), 15 to "15 minutes", 30 to "30 minutes", 45 to "45 minutes", 60 to "1 hour", PlayerHolder.END_OF_VIDEO to stringResource(R.string.after_this_video)).forEach { (m, label) ->
                        Text(label, Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.RadioButton) { holder.setSleep(m); sleepDialog = false; hud = if (m == null) ziriString(R.string.sleep_timer_off) else ziriString(R.string.sleep_timer_2, label) }.padding(vertical = 12.dp))
                    }
                }
            },
            confirmButton = { TextButton({ sleepDialog = false }) { Text(stringResource(R.string.close)) } },
        )
    }
    if (trackDialog) {
        TrackDialog(player, tracks, onPickFile = { pickSubtitle.launch(arrayOf("*/*")) }, onDismiss = { trackDialog = false })
    }
}

private const val WindowManagerBrightnessDefault = -1f // BRIGHTNESS_OVERRIDE_NONE
