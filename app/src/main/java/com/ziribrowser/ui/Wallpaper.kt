package com.ziribrowser.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.ziribrowser.R
import com.ziribrowser.ziriString
import com.ziribrowser.data.WallpaperConfig
import com.ziribrowser.data.WallpaperKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One soft coloured glow in a built-in wallpaper; position and size are fractions of the screen width/height. */
data class WallpaperBlob(val color: Long, val x: Float, val y: Float, val radius: Float)

/** A built-in wallpaper, drawn in code (no image files), so it is sharp at any screen size. */
data class WallpaperPreset(val id: String, @androidx.annotation.StringRes val labelRes: Int, val base: Long, val blobs: List<WallpaperBlob>) {
    val label: String get() = ziriString(labelRes)
}

val WallpaperPresets = listOf(
    WallpaperPreset("aurora", R.string.aurora, 0xFF0B1B2B, listOf(WallpaperBlob(0xFF2DD4BF, 0.2f, 0.1f, 0.9f), WallpaperBlob(0xFF7C3AED, 0.9f, 0.4f, 0.8f), WallpaperBlob(0xFF22C55E, 0.1f, 0.9f, 0.8f))),
    WallpaperPreset("sunset", R.string.sunset, 0xFF2B1235, listOf(WallpaperBlob(0xFFFB923C, 0.1f, 0.95f, 1.0f), WallpaperBlob(0xFFEC4899, 0.9f, 0.5f, 0.8f), WallpaperBlob(0xFFFACC15, 0.5f, 1.0f, 0.7f))),
    WallpaperPreset("ocean", R.string.ocean, 0xFF06233A, listOf(WallpaperBlob(0xFF38BDF8, 0.9f, 0.1f, 1.0f), WallpaperBlob(0xFF2563EB, 0.1f, 0.6f, 0.9f), WallpaperBlob(0xFF14B8A6, 0.7f, 0.95f, 0.7f))),
    WallpaperPreset("forest", R.string.forest, 0xFF0E2A1C, listOf(WallpaperBlob(0xFF4ADE80, 0.1f, 0.15f, 0.9f), WallpaperBlob(0xFF166534, 0.9f, 0.6f, 0.9f), WallpaperBlob(0xFFA3E635, 0.5f, 1.0f, 0.6f))),
    WallpaperPreset("midnight", R.string.midnight, 0xFF0A0A14, listOf(WallpaperBlob(0xFF4F46E5, 0.2f, 0.2f, 0.9f), WallpaperBlob(0xFF9333EA, 0.9f, 0.8f, 0.9f), WallpaperBlob(0xFF0EA5E9, 0.9f, 0.1f, 0.5f))),
    WallpaperPreset("ember", R.string.ember, 0xFF1C0A08, listOf(WallpaperBlob(0xFFEF4444, 0.1f, 0.9f, 1.0f), WallpaperBlob(0xFFF97316, 0.9f, 0.2f, 0.9f), WallpaperBlob(0xFFFBBF24, 0.6f, 0.6f, 0.5f))),
    WallpaperPreset("slate", R.string.slate, 0xFF1E293B, listOf(WallpaperBlob(0xFF64748B, 0.1f, 0.1f, 1.0f), WallpaperBlob(0xFF0EA5E9, 0.9f, 0.9f, 0.8f), WallpaperBlob(0xFF94A3B8, 0.9f, 0.2f, 0.5f))),
    WallpaperPreset("candy", R.string.candy, 0xFFFFE4F1, listOf(WallpaperBlob(0xFFF472B6, 0.1f, 0.1f, 0.9f), WallpaperBlob(0xFF93C5FD, 0.9f, 0.5f, 0.9f), WallpaperBlob(0xFFFDE68A, 0.3f, 1.0f, 0.8f))),
    WallpaperPreset("lavender", R.string.lavender, 0xFFEDE9FE, listOf(WallpaperBlob(0xFFA78BFA, 0.9f, 0.05f, 1.0f), WallpaperBlob(0xFFF0ABFC, 0.1f, 0.7f, 0.9f), WallpaperBlob(0xFF93C5FD, 0.8f, 1.0f, 0.7f))),
    WallpaperPreset("mint", R.string.mint, 0xFFE6FFF5, listOf(WallpaperBlob(0xFF34D399, 0.1f, 0.2f, 1.0f), WallpaperBlob(0xFF67E8F9, 0.9f, 0.6f, 0.9f), WallpaperBlob(0xFFFDE047, 0.4f, 1.0f, 0.7f))),
    WallpaperPreset("rose", R.string.rose, 0xFFFFE9EC, listOf(WallpaperBlob(0xFFFB7185, 0.9f, 0.1f, 1.0f), WallpaperBlob(0xFFFDBA74, 0.1f, 0.6f, 0.9f), WallpaperBlob(0xFFC4B5FD, 0.6f, 1.0f, 0.7f))),
    WallpaperPreset("sand", R.string.sand, 0xFFFEF3C7, listOf(WallpaperBlob(0xFFFBBF24, 0.1f, 0.1f, 1.0f), WallpaperBlob(0xFFFB923C, 0.9f, 0.7f, 0.9f), WallpaperBlob(0xFFFDA4AF, 0.3f, 1.0f, 0.7f))),
)

fun wallpaperPreset(id: String): WallpaperPreset = WallpaperPresets.firstOrNull { it.id == id } ?: WallpaperPresets.first()

/** Draws a built-in wallpaper filling its box. */
@Composable
fun PresetPicture(preset: WallpaperPreset, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        drawRect(Color(preset.base))
        preset.blobs.forEach { b ->
            val center = Offset(size.width * b.x, size.height * b.y)
            val radius = size.width * b.radius
            drawCircle(Brush.radialGradient(listOf(Color(b.color).copy(alpha = 0.85f), Color.Transparent), center = center, radius = radius), radius, center)
        }
    }
}

/** The picture for the current wallpaper choice (a preset or the user's photo), without any fading. */
@Composable
fun WallpaperPicture(config: WallpaperConfig, photo: ImageBitmap?, modifier: Modifier = Modifier) {
    val blurred = if (config.blur > 0) Modifier.blur(config.blur.dp).graphicsLayer(scaleX = 1.12f, scaleY = 1.12f) else Modifier
    when {
        config.kind == WallpaperKind.PRESET -> PresetPicture(wallpaperPreset(config.presetId), modifier.then(blurred))
        config.kind == WallpaperKind.CUSTOM && photo != null ->
            Image(photo, null, modifier.fillMaxSize().then(blurred), contentScale = ContentScale.Crop)
    }
}

// ---- the user's own photo -----------------------------------------------------------------------

private const val PHOTO_FILE = "wallpaper.jpg"
private const val MAX_SIDE = 2400

/** Loads the saved wallpaper photo; reloads when [stamp] changes. Null if there is none. */
@Composable
fun rememberWallpaperPhoto(context: Context, stamp: Long, enabled: Boolean): State<ImageBitmap?> =
    produceState<ImageBitmap?>(null, stamp, enabled) {
        value = if (!enabled) null else withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(File(context.filesDir, PHOTO_FILE).path)?.asImageBitmap() }.getOrNull()
        }
    }

/**
 * Copies the picked photo into the app's private storage, scaled down and with its rotation applied,
 * so the wallpaper keeps working even if the original is moved or deleted. Returns true on success.
 */
suspend fun importWallpaperPhoto(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: error("decode failed")
        val rotation = resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        var bmp: Bitmap = decoded
        if (rotation != 0f) bmp = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation) }, true)
        val scale = MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
        if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        File(context.filesDir, PHOTO_FILE).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        true
    }.getOrDefault(false)
}

fun deleteWallpaperPhoto(context: Context) { File(context.filesDir, PHOTO_FILE).delete() }
