package com.ziribrowser.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.ziribrowser.data.AppFont
import com.ziribrowser.data.TextSize
import com.ziribrowser.data.ThemeColor
import com.ziribrowser.data.ThemeMode

/** True when [mode] resolves to the dark palette. */
@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

/** Accent colour as it appears in the given mode (used for the colour swatches in Settings). */
fun ThemeColor.seed(dark: Boolean): Color = Color(if (dark) this.dark else this.light)

private val White = Color.White
private val Black = Color.Black

/** Text/icon colour that stays readable on top of [bg]. */
private fun onColor(bg: Color): Color = if (bg.luminance() > 0.4f) Color(0xFF16181D) else Color.White

/** The background colour a theme will use, so the window can be painted correctly before Compose starts. */
fun themeBackground(color: ThemeColor, dark: Boolean): Color = buildScheme(color.seed(dark), dark).background

/** Builds a full Material 3 scheme from one accent: every surface carries a faint tint of it. */
private fun buildScheme(accent: Color, dark: Boolean): ColorScheme {
    return if (!dark) {
        val bg = lerp(White, accent, 0.025f)
        lightColorScheme(
            primary = accent,
            onPrimary = onColor(accent),
            primaryContainer = lerp(White, accent, 0.16f),
            onPrimaryContainer = lerp(accent, Black, 0.55f),
            secondary = lerp(accent, Color(0xFF475569), 0.45f),
            onSecondary = White,
            secondaryContainer = lerp(White, accent, 0.20f),
            onSecondaryContainer = lerp(accent, Black, 0.55f),
            tertiary = lerp(accent, Color(0xFF7C3AED), 0.5f),
            background = bg,
            onBackground = Color(0xFF16181D),
            surface = bg,
            onSurface = Color(0xFF16181D),
            surfaceVariant = lerp(White, accent, 0.08f),
            onSurfaceVariant = Color(0xFF5B6270),
            surfaceTint = accent,
            surfaceContainerLowest = White,
            surfaceContainerLow = lerp(White, accent, 0.035f),
            surfaceContainer = lerp(White, accent, 0.055f),
            surfaceContainerHigh = lerp(White, accent, 0.08f),
            surfaceContainerHighest = lerp(White, accent, 0.11f),
            outline = Color(0xFF8A91A0),
            outlineVariant = lerp(Color(0xFFE2E5EA), accent, 0.12f),
            error = Color(0xFFDC2626),
        )
    } else {
        val base = Color(0xFF0F1115)
        val bg = lerp(base, accent, 0.035f)
        darkColorScheme(
            primary = accent,
            onPrimary = onColor(accent),
            primaryContainer = lerp(base, accent, 0.28f),
            onPrimaryContainer = lerp(accent, White, 0.65f),
            secondary = lerp(accent, Color(0xFFCBD5E1), 0.45f),
            onSecondary = Color(0xFF16181D),
            secondaryContainer = lerp(base, accent, 0.26f),
            onSecondaryContainer = lerp(accent, White, 0.7f),
            tertiary = lerp(accent, Color(0xFFA78BFA), 0.5f),
            background = bg,
            onBackground = Color(0xFFEDEEF2),
            surface = bg,
            onSurface = Color(0xFFEDEEF2),
            surfaceVariant = lerp(base, accent, 0.10f),
            onSurfaceVariant = Color(0xFFA7ADBA),
            surfaceTint = accent,
            surfaceContainerLowest = lerp(base, Black, 0.4f),
            surfaceContainerLow = lerp(base, accent, 0.05f),
            surfaceContainer = lerp(base, accent, 0.075f),
            surfaceContainerHigh = lerp(base, accent, 0.10f),
            surfaceContainerHighest = lerp(base, accent, 0.13f),
            outline = Color(0xFF6B7280),
            outlineVariant = lerp(Color(0xFF2A2E37), accent, 0.15f),
            error = Color(0xFFF87171),
        )
    }
}

fun AppFont.fontFamily(): FontFamily = when (this) {
    AppFont.DEFAULT -> FontFamily.Default
    AppFont.SANS -> FontFamily.SansSerif
    AppFont.SERIF -> FontFamily.Serif
    AppFont.MONO -> FontFamily.Monospace
    AppFont.CASUAL -> FontFamily.Cursive
}

private fun typography(font: FontFamily): Typography {
    val t = Typography()
    fun TextStyle.f() = copy(fontFamily = font)
    return Typography(
        displayLarge = t.displayLarge.f(), displayMedium = t.displayMedium.f(), displaySmall = t.displaySmall.f(),
        headlineLarge = t.headlineLarge.f(), headlineMedium = t.headlineMedium.f(), headlineSmall = t.headlineSmall.f(),
        titleLarge = t.titleLarge.f(), titleMedium = t.titleMedium.f(), titleSmall = t.titleSmall.f(),
        bodyLarge = t.bodyLarge.f(), bodyMedium = t.bodyMedium.f(), bodySmall = t.bodySmall.f(),
        labelLarge = t.labelLarge.f(), labelMedium = t.labelMedium.f(), labelSmall = t.labelSmall.f(),
    )
}

/** Cross-fades every colour when the mode or accent changes, so switching themes feels smooth. */
@Composable
private fun ColorScheme.animated(): ColorScheme {
    @Composable fun a(c: Color) = animateColorAsState(c, tween(450), label = "themeColor").value
    return copy(
        primary = a(primary), onPrimary = a(onPrimary),
        primaryContainer = a(primaryContainer), onPrimaryContainer = a(onPrimaryContainer),
        secondary = a(secondary), secondaryContainer = a(secondaryContainer), onSecondaryContainer = a(onSecondaryContainer),
        tertiary = a(tertiary),
        background = a(background), onBackground = a(onBackground),
        surface = a(surface), onSurface = a(onSurface),
        surfaceVariant = a(surfaceVariant), onSurfaceVariant = a(onSurfaceVariant),
        surfaceTint = a(surfaceTint),
        surfaceContainerLowest = a(surfaceContainerLowest), surfaceContainerLow = a(surfaceContainerLow),
        surfaceContainer = a(surfaceContainer), surfaceContainerHigh = a(surfaceContainerHigh),
        surfaceContainerHighest = a(surfaceContainerHighest),
        outline = a(outline), outlineVariant = a(outlineVariant),
    )
}

private val ZiriShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun ZiriTheme(
    mode: ThemeMode,
    color: ThemeColor = ThemeColor.TEAL,
    font: AppFont = AppFont.DEFAULT,
    textSize: TextSize = TextSize.DEFAULT,
    content: @Composable () -> Unit,
) {
    val dark = isDark(mode)
    val scheme = buildScheme(color.seed(dark), dark).animated()
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * textSize.scale)) {
        MaterialTheme(colorScheme = scheme, typography = typography(font.fontFamily()), shapes = ZiriShapes, content = content)
    }
}
