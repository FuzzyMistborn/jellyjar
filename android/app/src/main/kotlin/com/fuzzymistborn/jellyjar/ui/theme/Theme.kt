package com.fuzzymistborn.jellyjar.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// JellyJar palette — dark, cinematic, minimal
val Background = Color(0xFF0A0A0F)
val Surface = Color(0xFF13131A)
val SurfaceVariant = Color(0xFF1E1E28)
val Primary = Color(0xFF7B9EFF)         // cool blue accent
val PrimaryVariant = Color(0xFF4A6FD4)
val OnPrimary = Color(0xFF000000)
val OnSurface = Color(0xFFEEEEEE)
val OnSurfaceMuted = Color(0xFF888899)
val Accent = Color(0xFFB4C8FF)
val Error = Color(0xFFCF6679)

// Static section headings (Continue Watching, Seasons, …) — reserves Primary
// for interactive/selected states (chips, buttons, active icons) only.
val SectionHeading = Color(0xFFAEB8D6)

// Semantic status colors, used instead of one-off hex literals scattered per screen
val Success = Color(0xFF4CAF50)
val Warning = Color(0xFFCCAA44)

// Scrims for badges/text drawn over image content
val ScrimStrong = Color(0xCC000000)
val ScrimSoft = Color(0x88000000)

// Corner radius scale — pick the tier closest to an element's current size rather
// than inventing a new one-off value.
object Radius {
    val sm = 8.dp   // small badges, thumbnails, media/poster cards
    val md = 12.dp  // text fields, chips, tiles, sub-cards
    val lg = 16.dp  // top-level settings cards
    val pill = 50   // percent-based fully-rounded shape (avatars, status pills)
}

// Spacing scale for padding/gaps — pick the tier closest to the current value rather
// than inventing a new one-off. Small badge/chip paddings (2-6dp) are left as literals;
// they're tight, purpose-specific spacing rather than layout rhythm.
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

// Icon/spinner size scale — pick the tier closest to an element's current size rather
// than inventing a new one-off value.
object IconSize {
    val xs = 12.dp   // tiny inline badges (rating star)
    val sm = 16.dp   // standard inline/badge icons
    val md = 20.dp   // button and header icons
    val lg = 32.dp   // play overlay, feature icons
    val xl = 48.dp   // lock icon, downloads tile icon
    val xxl = 64.dp  // empty state icon
}

// Shadow elevation for poster/media cards — gives them depth against the dark background
// instead of reading as flat rounded rectangles.
object Elevation {
    val poster = 4.dp
}

// Canonical poster card widths — pick the tier closest to a row's current card size rather
// than inventing a new one-off value (e.g. Continue Watching cards read slightly larger than
// standard poster rows).
object PosterSize {
    val standard = 110.dp
    val large = 130.dp
}

// Full-screen background — diagonal gradient from dark indigo to near-black
val BackgroundGradient = Brush.linearGradient(
    colors = listOf(
        Color(0xFF12162E),  // dark indigo / navy
        Color(0xFF060609),  // deep near-black
    )
)

// Fades a full-bleed hero image down into the solid background (Detail/Season screen backdrops).
fun heroBackdropScrim(scrimStop: Float = 0.35f, solidStop: Float = 0.55f): Brush = Brush.verticalGradient(
    0f to Color.Transparent,
    scrimStop to ScrimStrong,
    solidStop to Background,
    1f to Background,
)

// Dark overlay fading to background, used behind content drawn atop a full-screen featured backdrop.
fun featuredBackdropScrim(): Brush = Brush.verticalGradient(
    0f to ScrimStrong,
    0.3f to ScrimStrong,
    1f to Background,
)

// Simple top-to-bottom darkening scrim for text legibility over tile/poster art.
fun tileScrim(): Brush = Brush.verticalGradient(0f to ScrimSoft, 1f to ScrimStrong)

// Darkens the corners/edges of a full-bleed backdrop while leaving the center bright —
// layered on top of the vertical scrims above to make hero art read as a vignette rather
// than a flat gradient (Netflix/Apple TV-style backdrop treatment).
fun vignetteScrim(): Brush = Brush.radialGradient(
    0f to Color.Transparent,
    0.6f to Color.Transparent,
    1f to ScrimStrong,
)

private val DarkColors = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryVariant,
    background = Background,
    surface = Surface,
    surfaceVariant = SurfaceVariant,
    onBackground = OnSurface,
    onSurface = OnSurface,
    onSurfaceVariant = OnSurfaceMuted,
    error = Error,
)

@Composable
fun JellyJarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = JellyJarTypography,
        content = content,
    )
}
