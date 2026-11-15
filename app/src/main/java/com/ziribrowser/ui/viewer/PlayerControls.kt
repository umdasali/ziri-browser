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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.union
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

// Building blocks of the player's on-screen controls (split out of MxPlayer.kt to keep that file readable).

@OptIn(UnstableApi::class)
@Composable
internal fun TrackDialog(player: ExoPlayer, tracks: Tracks, onPickFile: () -> Unit, onDismiss: () -> Unit) {
    val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
    val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
    val textOff = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || text.none { it.isSelected }

    fun label(g: Tracks.Group, i: Int, n: Int): String {
        val f = g.getTrackFormat(i)
        val lang = f.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage.takeIf { l -> l.isNotBlank() } }
        return listOfNotNull(f.label, lang, if (f.channelCount > 0) "${f.channelCount}ch" else null).distinct().joinToString(" · ").ifEmpty { ziriString(R.string.track, n) }
    }

    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(stringResource(R.string.audio_subtitles)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.audio), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                if (audio.isEmpty()) Text(stringResource(R.string.only_one_audio_track), color = MaterialTheme.colorScheme.onSurfaceVariant)
                audio.forEachIndexed { n, g ->
                    Text(
                        label(g, 0, n + 1), fontWeight = if (g.isSelected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.RadioButton) {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build(); onDismiss()
                        }.padding(vertical = 10.dp),
                    )
                }
                Text(stringResource(R.string.subtitles), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp))
                Text(
                    stringResource(R.string.off), fontWeight = if (textOff) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.RadioButton) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build(); onDismiss()
                    }.padding(vertical = 10.dp),
                )
                text.forEachIndexed { n, g ->
                    Text(
                        label(g, 0, n + 1), fontWeight = if (g.isSelected && !textOff) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.RadioButton) {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build(); onDismiss()
                        }.padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = { Button(onPickFile) { Text(stringResource(R.string.load_subtitle_file)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** The look shared by every control over the video: dark frosted glass with a thin light edge. */
internal fun Modifier.glass(shape: androidx.compose.ui.graphics.Shape): Modifier =
    this.clip(shape).background(Color.Black.copy(alpha = 0.42f)).border(1.dp, Color.White.copy(alpha = 0.14f), shape)

/**
 * Fades a block of controls in and out while keeping it composed. AnimatedVisibility removes its content when hidden, so every
 * tap that brought the controls back had to build the slider, buttons and glass cards from scratch, which is what felt slow.
 * Here showing is only a fade; while fully hidden the block is not placed, so it costs nothing and cannot catch touches.
 */
@Composable
internal fun Modifier.fadeVisible(visible: Boolean): Modifier {
    val a by androidx.compose.animation.core.animateFloatAsState(
        if (visible) 1f else 0f, androidx.compose.animation.core.tween(if (visible) 90 else 150), label = "controls",
    )
    return this
        .graphicsLayer { alpha = a }
        .layout { m, c -> val p = m.measure(c); layout(p.width, p.height) { if (a > 0f) p.place(0, 0) } }
}

/** Keeps controls clear of the status bar, the navigation bar (also when it sits at the side in landscape) and camera cut-outs. */
@Composable
internal fun Modifier.safeEdges(sides: androidx.compose.foundation.layout.WindowInsetsSides): Modifier =
    this.windowInsetsPadding(
        androidx.compose.foundation.layout.WindowInsets.systemBars.union(androidx.compose.foundation.layout.WindowInsets.displayCutout).only(sides),
    )

@Composable
internal fun GlassCircle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, size: androidx.compose.ui.unit.Dp, tint: Color = Color.White, onClick: () -> Unit) {
    Box(Modifier.size(size).glass(CircleShape).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** A small rounded button: an icon, a label, or both. [active] tints it with the accent colour. */
@Composable
internal fun GlassChip(icon: androidx.compose.ui.graphics.vector.ImageVector?, text: String, active: Boolean = false, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.clip(CircleShape).background(if (active) accent.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.10f)).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(if (icon != null) "  $text" else text, color = if (active) accent else Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
    }
}

/** Vertical level meter shown at the edge while dragging for brightness or volume. */
@Composable
internal fun SideMeter(icon: androidx.compose.ui.graphics.vector.ImageVector, level: Float, modifier: Modifier = Modifier) {
    Column(modifier.glass(RoundedCornerShape(26.dp)).padding(vertical = 14.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Box(Modifier.padding(vertical = 10.dp).size(width = 6.dp, height = 130.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(level.coerceIn(0f, 1f)).background(MaterialTheme.colorScheme.primary))
        }
        Text("${(level * 100).roundToInt()}", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** The "−10 s" / "+10 s" badge shown at the side that was double-tapped. */
@Composable
internal fun SkipBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier) {
    Column(modifier.glass(CircleShape).padding(horizontal = 22.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(30.dp))
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
    }
}


internal fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** A small picture of the video at the spot being dragged to on the seek bar (local files only). */
@Composable
internal fun SeekPreview(item: PlayItem?, fraction: Float?, durationMs: Long) {
    val context = LocalContext.current
    if (item == null || item.downloadId != null || durationMs <= 0) return
    val retriever = remember(item.uri) {
        runCatching { android.media.MediaMetadataRetriever().apply { setDataSource(context, item.uri) } }.getOrNull()
    }
    DisposableEffect(retriever) { onDispose { runCatching { retriever?.release() } } }
    var frame by remember(item.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(fraction) {
        val f = fraction ?: return@LaunchedEffect
        delay(120) // wait for the finger to settle so we don't decode on every pixel
        frame = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                retriever?.getScaledFrameAtTime((f * durationMs * 1000).toLong(), android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
            }.getOrNull()
        } ?: frame
    }
    val bmp = frame
    if (fraction != null && bmp != null) {
        Box(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Image(
                bmp.asImageBitmap(), null,
                Modifier.size(width = 160.dp, height = 90.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        }
    }
}

/** Moves one video frame forward or back (the player must be paused). */
@OptIn(UnstableApi::class)
internal fun stepFrame(player: androidx.media3.exoplayer.ExoPlayer, direction: Int) {
    val fps = player.videoFormat?.frameRate?.takeIf { it > 0 } ?: 25f
    player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
    player.seekTo((player.currentPosition + direction * (1000f / fps).toLong().coerceAtLeast(1)).coerceAtLeast(0))
}

/** Volume boost above 100%, equaliser presets and bass boost. */
@Composable
internal fun AudioEffectsDialog(holder: PlayerHolder, onDismiss: () -> Unit) {
    val boost by holder.boostDb.collectAsStateWithLifecycle()
    val preset by holder.eqPreset.collectAsStateWithLifecycle()
    val bass by holder.bassBoost.collectAsStateWithLifecycle()
    val names by holder.eqPresetNames.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(stringResource(R.string.audio)) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.volume_boost), fontWeight = FontWeight.SemiBold)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to stringResource(R.string.off), 3 to "+3 dB", 6 to "+6 dB", 9 to "+9 dB").forEach { (v, l) ->
                        androidx.compose.material3.FilterChip(boost == v, { holder.boostDb.value = v; holder.applyEffects() }, { Text(l) })
                    }
                }
                Text(stringResource(R.string.louder_than_the_phone_s_maximum_can_di), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.equaliser), fontWeight = FontWeight.SemiBold)
                if (names.isEmpty()) Text(stringResource(R.string.not_available_on_this_device), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(preset < 0, { holder.eqPreset.value = -1; holder.applyEffects() }, { Text(stringResource(R.string.off)) })
                    names.forEachIndexed { i, n -> androidx.compose.material3.FilterChip(preset == i, { holder.eqPreset.value = i; holder.applyEffects() }, { Text(n) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.bass_boost), Modifier.weight(1f))
                    androidx.compose.material3.Switch(bass, { holder.bassBoost.value = it; holder.applyEffects() })
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.done)) } },
    )
}
