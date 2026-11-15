package com.ziribrowser.ui.browser

import com.ziribrowser.ziriString
import com.ziribrowser.R
import androidx.compose.ui.res.stringResource
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ziribrowser.AppContainer
import com.ziribrowser.ui.GlassSurface
import com.ziribrowser.ui.viewer.fadeVisible
import com.ziribrowser.ui.viewer.safeEdges
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * The page's fullscreen video plus Ziri's extras on top: rotation, playback speed, Picture-in-Picture and exit, and gestures (double-tap to seek, drag the left side for brightness and the
 * right side for volume). Controls fade out after a few seconds and return on touch.
 */
@Composable
fun FullscreenVideo(container: AppContainer, video: View, onExit: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val controller = container.tabs.video
    val st by controller.state.collectAsStateWithLifecycle()
    val inPip by container.pipMode.collectAsStateWithLifecycle()

    // ---- orientation: landscape by default for wide video, with a rotate toggle ---------------
    var landscape by remember { mutableStateOf(true) }
    var orientationChosen by remember { mutableStateOf(false) }
    LaunchedEffect(st.width, st.height) {
        if (!orientationChosen && st.width > 0 && st.height > 0) { landscape = st.width >= st.height; orientationChosen = true }
    }
    LaunchedEffect(landscape) {
        activity?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            // Give brightness back to the system setting.
            activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
        }
    }

    // ---- brightness / volume -------------------------------------------------------------------
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var brightness by remember {
        mutableFloatStateOf(
            activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0 }
                ?: (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f),
        )
    }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) }

    // ---- UI state ----------------------------------------------------------------------------
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var controlsVisible by remember { mutableStateOf(true) }
    var hud by remember { mutableStateOf<String?>(null) }
    var speedMenu by remember { mutableStateOf(false) }

    LaunchedEffect(lastTouch) { controlsVisible = true; delay(3500); controlsVisible = false }
    LaunchedEffect(hud) { if (hud != null) { delay(800); hud = null } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { _ -> (video.parent as? ViewGroup)?.removeView(video); video },
            modifier = Modifier.fillMaxSize().videoGestures(
                enabled = !inPip,
                onTouch = { lastTouch = System.currentTimeMillis() },
                onDoubleTap = { right ->
                    controller.seekBy(if (right) 10.0 else -10.0)
                    hud = if (right) "+10 s" else "−10 s"
                },
                onDrag = { left, fraction ->
                    if (left) {
                        brightness = (brightness + fraction * 1.2f).coerceIn(0.02f, 1f)
                        activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = brightness } }
                        hud = ziriString(R.string.brightness, (brightness * 100).roundToInt())
                    } else {
                        volume = (volume + fraction * 1.2f).coerceIn(0f, 1f)
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volume * max).roundToInt(), 0)
                        hud = ziriString(R.string.volume, (volume * 100).roundToInt())
                    }
                },
            ),
        )

        if (!inPip) {
            hud?.let { text ->
                GlassSurface(Modifier.align(Alignment.Center), shape = CircleShape, strong = true) {
                    Text(text, Modifier.padding(horizontal = 22.dp, vertical = 12.dp), fontWeight = FontWeight.SemiBold)
                }
            }

            Box(Modifier.align(Alignment.TopEnd).safeEdges(androidx.compose.foundation.layout.WindowInsetsSides.Horizontal + androidx.compose.foundation.layout.WindowInsetsSides.Top).padding(12.dp).fadeVisible(controlsVisible)) {
                GlassSurface(shape = CircleShape, strong = true) {
                    Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ landscape = !landscape; lastTouch = System.currentTimeMillis() }) { Icon(Icons.Rounded.ScreenRotation, stringResource(R.string.rotate_screen)) }
                        Box {
                            TextButton({ speedMenu = true; lastTouch = System.currentTimeMillis() }) {
                                Text("${speedLabel(st.rate)}×", fontWeight = FontWeight.Bold)
                            }
                            DropdownMenu(speedMenu, { speedMenu = false }) {
                                SPEEDS.forEach { s ->
                                    DropdownMenuItem(
                                        { Text("${speedLabel(s)}×", fontWeight = if (s == st.rate) FontWeight.Bold else FontWeight.Normal) },
                                        { controller.setSpeed(s); speedMenu = false },
                                    )
                                }
                            }
                        }
                        IconButton({ container.enterPip?.invoke() }) { Icon(Icons.Rounded.PictureInPictureAlt, stringResource(R.string.picture_in_picture)) }
                        IconButton(onExit) { Icon(Icons.Rounded.FullscreenExit, stringResource(R.string.exit_fullscreen)) }
                    }
                }
            }

        }
    }
}

private fun speedLabel(rate: Float): String = if (rate % 1f == 0f) rate.toInt().toString() else rate.toString().trimEnd('0')

/**
 * Watches touches on the video without taking them away from the page (it observes in the Initial
 * pass), so the site's own controls keep working. A recognised vertical drag is consumed.
 */
private fun Modifier.videoGestures(
    enabled: Boolean,
    onTouch: () -> Unit,
    onDoubleTap: (right: Boolean) -> Unit,
    onDrag: (left: Boolean, fraction: Float) -> Unit,
): Modifier = pointerInput(enabled) {
    if (!enabled) return@pointerInput
    var lastUpTime = 0L
    var lastUpX = 0f
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        onTouch()
        val slop = viewConfiguration.touchSlop
        val left = down.position.x < size.width / 2f
        var dragging = false
        var dx = 0f
        var dy = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                val quick = change.uptimeMillis - down.uptimeMillis < 250 && abs(dx) < slop && abs(dy) < slop
                if (!dragging && quick) {
                    if (change.uptimeMillis - lastUpTime < 300 && abs(change.position.x - lastUpX) < 120.dp.toPx()) {
                        onDoubleTap(change.position.x > size.width / 2f)
                        lastUpTime = 0L
                    } else {
                        lastUpTime = change.uptimeMillis
                        lastUpX = change.position.x
                    }
                }
                break
            }
            val d = change.positionChange()
            dx += d.x
            dy += d.y
            if (!dragging && abs(dy) > slop * 2 && abs(dy) > abs(dx) * 1.5f) dragging = true
            if (dragging) {
                onDrag(left, -d.y / size.height)
                change.consume()
            }
        }
    }
}
