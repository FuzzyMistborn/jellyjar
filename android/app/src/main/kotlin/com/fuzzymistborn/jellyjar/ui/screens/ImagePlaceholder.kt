package com.fuzzymistborn.jellyjar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.fuzzymistborn.jellyjar.ui.theme.Elevation
import com.fuzzymistborn.jellyjar.ui.theme.IconSize
import com.fuzzymistborn.jellyjar.ui.theme.OnSurfaceMuted
import com.fuzzymistborn.jellyjar.ui.theme.Radius
import com.fuzzymistborn.jellyjar.ui.theme.SurfaceVariant

enum class ImageLoadState { LOADING, SUCCESS, ERROR }

// Shown centered over the SurfaceVariant-tinted box behind an AsyncImage while it loads,
// or in place of a blank tinted box if the load fails — replaces the otherwise-silent gap.
@Composable
fun BoxScope.ImageLoadIndicator(state: ImageLoadState) {
    if (state == ImageLoadState.SUCCESS) return
    Box(modifier = Modifier.align(Alignment.Center)) {
        Icon(
            imageVector = if (state == ImageLoadState.ERROR) Icons.Default.BrokenImage else Icons.Default.Movie,
            contentDescription = null,
            tint = OnSurfaceMuted.copy(alpha = 0.35f),
            modifier = Modifier.size(IconSize.lg),
        )
    }
}

// Shared "rounded poster/thumbnail box" pattern: SurfaceVariant background, cropped AsyncImage,
// loading/error indicator, and an optional badge overlay — used for movie/episode/season art
// across the library grid, detail screen, and season browser.
@Composable
fun PosterImage(
    imageUrl: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    aspectRatio: Float = 2f / 3f,
    cornerRadius: Dp = Radius.sm,
    cacheKey: String? = null,
    onClick: (() -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val context = LocalContext.current
    var imageState by remember(imageUrl) { mutableStateOf(ImageLoadState.LOADING) }

    var boxModifier = modifier
        .aspectRatio(aspectRatio)
        .shadow(elevation = Elevation.poster, shape = RoundedCornerShape(cornerRadius), clip = false)
        .clip(RoundedCornerShape(cornerRadius))
        .background(SurfaceVariant)
    if (onClick != null) boxModifier = boxModifier.clickable(onClick = onClick)

    Box(modifier = boxModifier) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageUrl)
                .apply {
                    cacheKey?.let {
                        memoryCacheKey(it)
                        diskCacheKey(it)
                    }
                }
                .crossfade(300)
                .build(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            onSuccess = { imageState = ImageLoadState.SUCCESS },
            onError = { imageState = ImageLoadState.ERROR },
        )
        ImageLoadIndicator(imageState)
        overlay()
    }
}
