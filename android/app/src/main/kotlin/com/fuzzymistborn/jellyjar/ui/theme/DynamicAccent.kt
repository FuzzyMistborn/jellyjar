package com.fuzzymistborn.jellyjar.ui.theme

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts a dominant accent color from [imageUrl] (typically a title's backdrop art) so
 * buttons/chips can read as "this title's color" instead of always the fixed theme blue.
 * Falls back to [fallback] while loading, on failure, or when the swatch is too dark/desaturated
 * to read well against the app's dark UI.
 */
private val accentColorCache = mutableMapOf<String, Color>()

@Composable
fun rememberDynamicAccentColor(imageUrl: String?, fallback: Color = Primary): Color {
    val cached = imageUrl?.let { accentColorCache[it] }
    var accent by remember(imageUrl) { mutableStateOf(cached ?: fallback) }
    val context = LocalContext.current

    androidx.compose.runtime.LaunchedEffect(imageUrl) {
        if (imageUrl.isNullOrBlank()) {
            accent = fallback
            return@LaunchedEffect
        }
        accentColorCache[imageUrl]?.let {
            accent = it
            return@LaunchedEffect
        }
        val extracted = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .allowHardware(false) // Palette needs a software bitmap
                    .build()
                val result = context.imageLoader.execute(request)
                val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return@runCatching null
                extractAccentColor(bitmap)
            }.getOrNull()
        }
        val resolved = extracted ?: fallback
        if (extracted != null) accentColorCache[imageUrl] = resolved
        accent = resolved
    }

    // Animate rather than snap so a same-screen color change (extraction finishing after the
    // fallback blue is already visible) reads as a smooth shift instead of a jarring pop.
    val animated by animateColorAsState(targetValue = accent, animationSpec = tween(400), label = "accentColor")
    return animated
}

private fun extractAccentColor(bitmap: Bitmap): Color? {
    val palette = Palette.from(bitmap).generate()
    val swatch = palette.vibrantSwatch
        ?: palette.lightVibrantSwatch
        ?: palette.mutedSwatch
        ?: palette.dominantSwatch
        ?: return null

    // Guard against near-black/near-white or muddy swatches reading poorly as a button fill on
    // the app's dark background — nudge lightness/saturation into a legible range rather than
    // rejecting the whole title's color outright.
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(swatch.rgb, hsl)
    hsl[1] = hsl[1].coerceAtLeast(0.35f)
    hsl[2] = hsl[2].coerceIn(0.45f, 0.75f)
    return Color(ColorUtils.HSLToColor(hsl))
}
