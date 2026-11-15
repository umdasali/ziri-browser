package com.ziribrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/** True when the current colour scheme is the light one. */
@Composable
fun isLightScheme(): Boolean = MaterialTheme.colorScheme.background.luminance() > 0.5f

/**
 * App-wide backdrop: the theme background with a few big, soft blobs of the accent colour.
 * Glass panels sit on top of this, so the colour shows through them like frosted glass.
 */
@Composable
fun GlassBackground(
    modifier: Modifier = Modifier,
    wallpaper: com.ziribrowser.data.WallpaperConfig = com.ziribrowser.data.WallpaperConfig(),
    photo: androidx.compose.ui.graphics.ImageBitmap? = null,
    /** False on screens where the user chose not to show the wallpaper (Settings → Wallpaper → Home only). */
    wallpaperActive: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val light = isLightScheme()
    val strength = if (light) 1f else 1.25f
    val show by androidx.compose.animation.core.animateFloatAsState(
        if (wallpaperActive && wallpaper.kind != com.ziribrowser.data.WallpaperKind.NONE) 1f else 0f,
        androidx.compose.animation.core.tween(350), label = "wallpaper",
    )
    Box(modifier.fillMaxSize().background(scheme.background)) {
        // The default look: a few soft blobs of the accent colour. They fade out when a wallpaper fades in.
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = 1f - show }.drawBehind {
                fun blob(color: Color, cx: Float, cy: Float, radius: Float) = drawCircle(
                    brush = Brush.radialGradient(listOf(color, Color.Transparent), center = Offset(size.width * cx, size.height * cy), radius = size.width * radius),
                    radius = size.width * radius, center = Offset(size.width * cx, size.height * cy),
                )
                blob(scheme.primary.copy(alpha = 0.26f * strength), 0.08f, 0.04f, 0.85f)
                blob(scheme.tertiary.copy(alpha = 0.20f * strength), 1.0f, 0.42f, 0.75f)
                blob(scheme.primary.copy(alpha = 0.16f * strength), 0.0f, 1.0f, 0.85f)
            },
        )
        if (show > 0f) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = show }) {
                WallpaperPicture(wallpaper, photo)
                // Blend toward the theme background so text keeps its contrast on any picture.
                Box(Modifier.fillMaxSize().background(scheme.background.copy(alpha = wallpaper.fade / 100f)))
            }
        }
        content()
    }
}

/**
 * Frosted-glass panel: translucent fill, a light rim that fades from top-left to bottom-right,
 * and a soft accent-coloured shadow. Pass [onClick] to make it tappable.
 *
 * Android can't blur what is *behind* a view without extra libraries, so the glass look comes from
 * translucency over the [GlassBackground] gradient rather than a true backdrop blur.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    strong: Boolean = false,
    tint: Color = Color.Unspecified,
    highlight: Boolean = false,
    /** When set, blurs whatever [hazeSource] content is drawn behind this panel (real backdrop blur). */
    haze: HazeState? = null,
    onClick: (() -> Unit)? = null,
    /** Optional long-press action (used for selecting items). */
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val light = isLightScheme()
    val background = scheme.background
    val blurStyle = remember(background) {
        HazeBlurStyle {
            blurRadius(28.dp)
            noiseFactor(0.03f)
            backgroundColor(background)
        }
    }
    // With a real blur behind it the panel needs less white to stay readable.
    val fill = if (light) Color.White.copy(alpha = if (haze != null) 0.45f else if (strong) 0.78f else 0.58f)
    else Color.White.copy(alpha = if (haze != null) 0.07f else if (strong) 0.14f else 0.08f)
    val rim = if (highlight) Brush.linearGradient(listOf(scheme.primary, scheme.primary.copy(alpha = 0.4f)))
    else Brush.linearGradient(
        listOf(Color.White.copy(alpha = if (light) 0.95f else 0.32f), Color.White.copy(alpha = if (light) 0.25f else 0.05f)),
    )
    Box(
        modifier
            .softShadow(shape, if (strong) 16.dp else 10.dp, scheme.primary.copy(alpha = if (light) 0.22f else 0.30f))
            .clip(shape)
            .then(
                if (haze != null) Modifier.hazeBlur(HazeInput.Sources(haze), blurStyle) else Modifier,
            )
            .then(if (!light && haze != null) Modifier.background(scheme.surfaceContainerHigh.copy(alpha = 0.86f)) else Modifier)
            .background(fill)
            .then(if (tint.isSpecified) Modifier.background(tint) else Modifier)
            .border(if (highlight) 2.dp else 1.dp, rim, shape)
            .then(
                if (onLongClick != null) Modifier.combinedClickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick ?: {}, onLongClick = onLongClick)
                else if (onClick != null) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick) else Modifier,
            ),
    ) {
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface) { content() }
    }
}

/** Space a screen must leave at its bottom so content isn't hidden by the floating navigation bar. */
val LocalBarClearance = compositionLocalOf<Dp> { 0.dp }

/** Round frosted button holding a single icon. */
@Composable
fun GlassIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassSurface(modifier.androidSize(44.dp), shape = androidx.compose.foundation.shape.CircleShape, onClick = onClick) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            androidx.compose.material3.Icon(icon, description, tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

private fun Modifier.androidSize(size: androidx.compose.ui.unit.Dp) = this.then(Modifier.size(size))

/**
 * A soft drop shadow drawn only OUTSIDE the shape. The built-in elevation shadow also shows through a
 * translucent fill, which looks like a faint box inside glass panels; clipping the interior out avoids that.
 */
private fun Modifier.softShadow(shape: Shape, radius: androidx.compose.ui.unit.Dp, color: Color, dy: androidx.compose.ui.unit.Dp = 4.dp): Modifier =
    this.drawBehind {
        val path = androidx.compose.ui.graphics.Path()
        when (val o = shape.createOutline(size, layoutDirection, this)) {
            is androidx.compose.ui.graphics.Outline.Rectangle -> path.addRect(o.rect)
            is androidx.compose.ui.graphics.Outline.Rounded -> path.addRoundRect(o.roundRect)
            is androidx.compose.ui.graphics.Outline.Generic -> path.addPath(o.path)
        }
        clipPath(path, androidx.compose.ui.graphics.ClipOp.Difference) {
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    this.color = android.graphics.Color.BLACK // hidden: this fill sits inside the clipped-out interior
                    setShadowLayer(radius.toPx(), 0f, dy.toPx(), color.toArgb())
                }
                canvas.nativeCanvas.drawPath(path.asAndroidPath(), paint)
            }
        }
    }
