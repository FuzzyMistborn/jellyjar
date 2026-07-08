package com.fuzzymistborn.jellyjar.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

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
val Overlay = Color(0xCC000000)         // semi-transparent overlay for backdrops

// Full-screen background — diagonal gradient from dark indigo to near-black
val BackgroundGradient = Brush.linearGradient(
    colors = listOf(
        Color(0xFF12162E),  // dark indigo / navy
        Color(0xFF060609),  // deep near-black
    )
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
