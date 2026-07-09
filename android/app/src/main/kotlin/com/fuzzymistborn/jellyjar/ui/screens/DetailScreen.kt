package com.fuzzymistborn.jellyjar.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.fuzzymistborn.jellyjar.model.DownloadStatus
import com.fuzzymistborn.jellyjar.model.JellyfinItem
import com.fuzzymistborn.jellyjar.ui.theme.*
import com.fuzzymistborn.jellyjar.ui.viewmodel.DetailViewModel

@Composable
fun DetailScreen(
    itemId: String,
    onPlayClick: (localPath: String, jellyfinId: String, startMs: Long) -> Unit,
    onStreamClick: (streamUrl: String, jellyfinId: String, startMs: Long) -> Unit,
    onEpisodeClick: (itemId: String) -> Unit = {},
    onSeasonClick: (seasonId: String, seriesId: String) -> Unit = { _, _ -> },
    onBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(itemId) { viewModel.loadItem(itemId) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, itemId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshStreamPosition(itemId)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    var showPresetDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(BackgroundGradient)) {

        // Backdrop — episodes fall back to series backdrop, then episode primary
        state.item?.let { item ->
            val backdropId = if (item.type == "Episode") item.seriesId ?: item.id else item.id
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(viewModel.backdropUrl(backdropId))
                    .crossfade(300)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.65f)
                    .blur(2.dp),
            )
        }

        // Gradient fade to background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.35f to Color(0xCC000000),
                        0.55f to Background,
                        1f to Background,
                    )
                )
        )

        val item = state.item

        LazyColumn(
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        ) {
            // Back button
            item(key = "back") {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.statusBarsPadding().padding(12.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = OnSurface)
                }
            }

            item(key = "hero_spacer") { Spacer(Modifier.height(120.dp)) }

            // Metadata block
            if (item != null) {
                item(key = "metadata") {
                    Column(modifier = Modifier.padding(horizontal = 32.dp)) {
                        var logoLoaded by remember { mutableStateOf(false) }
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(viewModel.logoUrl(item.id))
                                .crossfade(300)
                                .build(),
                            contentDescription = item.name,
                            modifier = Modifier.heightIn(max = 80.dp).widthIn(max = 300.dp),
                            onSuccess = { logoLoaded = true },
                        )
                        if (!logoLoaded) {
                            Text(
                                text = item.displayTitle,
                                style = MaterialTheme.typography.displayMedium,
                                color = OnSurface,
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            item.year?.let {
                                Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                            }
                            item.runtimeMinutes?.let {
                                Text("${it}m", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                            }
                            item.communityRating?.let {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Star, contentDescription = null,
                                        tint = Color(0xFFFFCC44), modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("${"%.1f".format(it)}", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        item.overview?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = OnSurface.copy(alpha = 0.85f),
                                modifier = Modifier.widthIn(max = 600.dp),
                            )
                        }

                        Spacer(Modifier.height(28.dp))

                        if (item.type != "Series") {
                            val dl = state.download
                            val offlinePositionMs = dl?.playbackPositionMs ?: 0L
                            val streamPositionMs = state.streamPositionMs
                            val hasPosition = offlinePositionMs > 0L || streamPositionMs > 0L
                            val resumeAction: (() -> Unit)? = when {
                                dl?.status == DownloadStatus.COMPLETE.name && offlinePositionMs > 0L ->
                                    { { onPlayClick(dl.localPath, dl.jellyfinId, offlinePositionMs) } }
                                state.isOnline && streamPositionMs > 0L ->
                                    { { onStreamClick(viewModel.streamUrl(item.id), item.id, streamPositionMs) } }
                                else -> null
                            }
                            val playFromStartAction: (() -> Unit)? = when {
                                dl?.status == DownloadStatus.COMPLETE.name ->
                                    { { onPlayClick(dl.localPath, dl.jellyfinId, 0L) } }
                                state.isOnline ->
                                    { { onStreamClick(viewModel.streamUrl(item.id), item.id, 0L) } }
                                else -> null
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (hasPosition && resumeAction != null) {
                                    Button(
                                        onClick = resumeAction,
                                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Resume")
                                    }
                                }
                                if (playFromStartAction != null) {
                                    val isSecondary = hasPosition && resumeAction != null
                                    if (isSecondary) {
                                        OutlinedButton(
                                            onClick = playFromStartAction,
                                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                                            Spacer(Modifier.width(8.dp))
                                            Text("Play")
                                        }
                                    } else {
                                        Button(
                                            onClick = playFromStartAction,
                                            colors = ButtonDefaults.buttonColors(containerColor = Primary),
                                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                                            Spacer(Modifier.width(8.dp))
                                            Text("Play")
                                        }
                                    }
                                }
                                IconButton(onClick = { viewModel.toggleFavorite() }) {
                                    Icon(
                                        if (state.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        contentDescription = if (state.isFavorite) "Remove from My List" else "Add to My List",
                                        tint = if (state.isFavorite) Error else OnSurfaceMuted,
                                    )
                                }
                                IconButton(onClick = { viewModel.togglePlayed() }) {
                                    Icon(
                                        if (state.isPlayed) Icons.Default.CheckCircle else Icons.Default.CheckCircleOutline,
                                        contentDescription = if (state.isPlayed) "Mark Unplayed" else "Mark Played",
                                        tint = if (state.isPlayed) Primary else OnSurfaceMuted,
                                    )
                                }
                                when {
                                    dl == null && state.isOnline -> OutlinedButton(
                                        onClick = { showPresetDialog = true },
                                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Download")
                                    }
                                    dl != null && dl.status in listOf(
                                        DownloadStatus.QUEUED.name,
                                        DownloadStatus.TRANSCODING.name,
                                        DownloadStatus.DOWNLOADING.name,
                                    ) ->
                                        DownloadProgressButton(dl.status, dl.progress)
                                    dl?.status == DownloadStatus.COMPLETE.name ->
                                        OutlinedButton(
                                            onClick = { viewModel.deleteDownload(item.id) },
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Error),
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = null)
                                            Spacer(Modifier.width(8.dp))
                                            Text("Remove")
                                        }
                                    dl?.status == DownloadStatus.FAILED.name -> {
                                        if (state.isOnline) {
                                            OutlinedButton(
                                                onClick = { viewModel.retryDownload(item.id) },
                                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                                            ) {
                                                Icon(Icons.Default.Refresh, contentDescription = null)
                                                Spacer(Modifier.width(8.dp))
                                                Text("Retry")
                                            }
                                        }
                                        OutlinedButton(
                                            onClick = { viewModel.deleteDownload(item.id) },
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Error),
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = null)
                                        }
                                    }
                                    else -> {}
                                }
                            }
                        }
                    }
                }

                // Seasons section — tap navigates to dedicated SeasonScreen
                if (item.type == "Series" && state.seasons.isNotEmpty()) {
                    item(key = "seasons_header") {
                        Column {
                            Spacer(Modifier.height(24.dp))
                            Text(
                                text = "Seasons",
                                style = MaterialTheme.typography.titleMedium,
                                color = Primary,
                                modifier = Modifier.padding(horizontal = 32.dp),
                            )
                            Spacer(Modifier.height(10.dp))
                            androidx.compose.foundation.lazy.LazyRow(
                                contentPadding = PaddingValues(horizontal = 32.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                itemsIndexed(state.seasons) { index, season ->
                                    Column(modifier = Modifier.width(110.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(2f / 3f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(SurfaceVariant)
                                                .clickable { onSeasonClick(season.id, item.id) },
                                        ) {
                                            AsyncImage(
                                                model = ImageRequest.Builder(LocalContext.current)
                                                    .data(viewModel.posterUrl(season.id))
                                                    .crossfade(300)
                                                    .build(),
                                                contentDescription = season.name,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = season.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = OnSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(40.dp))
                        }
                    }
                    item(key = "bottom_spacer") { Spacer(Modifier.height(0.dp)) }
                } else {
                    item(key = "bottom_spacer") { Spacer(Modifier.height(40.dp)) }
                }
            }
        } // end LazyColumn

        // Loading/error overlays when item hasn't loaded yet
        if (state.isLoading && state.item == null) {
            FullScreenLoading()
        } else if (state.error != null && state.item == null && !state.isLoading) {
            FullScreenError(
                message = state.error!!,
                onRetry = { viewModel.loadItem(itemId) },
            )
        }
    } // end Box

    // Preset selection dialog
    if (showPresetDialog) {
        PresetPickerDialog(
            onPresetSelected = { preset ->
                showPresetDialog = false
                viewModel.queueDownload(preset)
            },
            onDismiss = { showPresetDialog = false },
        )
    }
}

@Composable
private fun DownloadProgressButton(status: String, progress: Float) {
    Surface(
        color = SurfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = Primary,
            )
            Text(
                text = when (status) {
                    "TRANSCODING" -> "Transcoding ${"%.0f".format(progress)}%"
                    "DOWNLOADING" -> "Downloading ${"%.0f".format(progress)}%"
                    else -> "Working..."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurface,
            )
        }
    }
}

@Composable
internal fun EpisodeRow(
    episode: JellyfinItem,
    thumbnailUrl: String,
    download: com.fuzzymistborn.jellyjar.data.local.DownloadEntity?,
    isOnline: Boolean,
    onClick: () -> Unit,
    onStreamClick: ((startMs: Long) -> Unit)? = null,
    onDownloadClick: (preset: String) -> Unit,
    onDeleteClick: () -> Unit,
    onPlayOfflineClick: (path: String, jellyfinId: String, startMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPresetDialog by remember { mutableStateOf(false) }

    Surface(
        color = Color(0x88000000),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Thumbnail
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Background),
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(thumbnailUrl)
                        .crossfade(300)
                        .build(),
                    contentDescription = episode.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                episode.indexNumber?.let { epNum ->
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(3.dp),
                        color = Color(0xCC000000),
                        shape = RoundedCornerShape(3.dp),
                    ) {
                        Text(
                            "E${epNum.toString().padStart(2, '0')}",
                            style = MaterialTheme.typography.labelSmall,
                            color = OnSurface,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
                if (episode.userData?.played == true) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopEnd).padding(3.dp),
                        color = Color(0xCC000000),
                        shape = RoundedCornerShape(50),
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Watched",
                            tint = Primary,
                            modifier = Modifier.padding(2.dp).size(14.dp),
                        )
                    }
                }
                // Watch progress bar
                val watchFraction = watchProgressFraction(episode, download)
                if (watchFraction > 0.01f) {
                    LinearProgressIndicator(
                        progress = { watchFraction },
                        modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),
                        color = Primary,
                        trackColor = Color.Transparent,
                    )
                }
            }

            // Metadata + actions
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = episode.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = OnSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    episode.runtimeMinutes?.let {
                        Text("${it}m", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                    }
                    episode.communityRating?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Star, contentDescription = null,
                                tint = Color(0xFFFFCC44), modifier = Modifier.size(11.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("${"%.1f".format(it)}", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                        }
                    }
                }
                episode.overview?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))

                // Play + download controls
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val offlinePos = download?.playbackPositionMs ?: 0L
                    val streamPos = episode.userData?.playbackPositionTicks?.div(10_000L) ?: 0L
                    val hasPos = offlinePos > 0L || streamPos > 0L
                    if (hasPos) {
                        val resumeAction: (() -> Unit)? = when {
                            download?.status == DownloadStatus.COMPLETE.name && offlinePos > 0L ->
                                { { onPlayOfflineClick(download.localPath, download.jellyfinId, offlinePos) } }
                            isOnline && onStreamClick != null && streamPos > 0L ->
                                { { onStreamClick(streamPos) } }
                            else -> null
                        }
                        if (resumeAction != null) {
                            OutlinedButton(
                                onClick = resumeAction,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Resume", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    val playFromStartAction: (() -> Unit)? = when {
                        download?.status == DownloadStatus.COMPLETE.name ->
                            { { onPlayOfflineClick(download.localPath, download.jellyfinId, 0L) } }
                        isOnline && onStreamClick != null -> { { onStreamClick(0L) } }
                        else -> null
                    }
                    if (playFromStartAction != null) {
                        OutlinedButton(
                            onClick = playFromStartAction,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Play", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    when {
                        download == null && isOnline -> OutlinedButton(
                            onClick = { showPresetDialog = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Download", style = MaterialTheme.typography.labelSmall)
                        }
                        download != null && download.status in listOf(
                            DownloadStatus.QUEUED.name,
                            DownloadStatus.TRANSCODING.name,
                            DownloadStatus.DOWNLOADING.name,
                        ) -> {
                            CircularProgressIndicator(
                                progress = { download.progress / 100f },
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = Primary,
                            )
                            Text(
                                if (download.status == DownloadStatus.QUEUED.name) "Queued"
                                else "${"%.0f".format(download.progress)}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = OnSurface,
                            )
                        }
                        download?.status == DownloadStatus.COMPLETE.name ->
                            OutlinedButton(
                                onClick = onDeleteClick,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Error),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                        download?.status == DownloadStatus.FAILED.name -> {
                            if (isOnline) {
                                OutlinedButton(
                                    onClick = { onDownloadClick("retry") },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Retry", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            OutlinedButton(
                                onClick = onDeleteClick,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Error),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    if (showPresetDialog) {
        PresetPickerDialog(
            onPresetSelected = { preset ->
                showPresetDialog = false
                onDownloadClick(preset)
            },
            onDismiss = { showPresetDialog = false },
        )
    }
}

@Composable
internal fun EpisodeThumb(
    episode: JellyfinItem,
    thumbnailUrl: String,
    download: com.fuzzymistborn.jellyjar.data.local.DownloadEntity?,
    isOnline: Boolean,
    onClick: () -> Unit,
    onStreamClick: ((startMs: Long) -> Unit)? = null,
    onDownloadClick: (preset: String) -> Unit,
    onDeleteClick: () -> Unit,
    onPlayOfflineClick: (path: String, jellyfinId: String, startMs: Long) -> Unit,
) {
    var showPresetDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.width(200.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceVariant)
                .clickable(onClick = onClick),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(thumbnailUrl)
                    .crossfade(300)
                    .build(),
                contentDescription = episode.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(
                    color = Color(0x88000000),
                    shape = RoundedCornerShape(50),
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = OnSurface,
                        modifier = Modifier.padding(6.dp).size(22.dp),
                    )
                }
            }
            episode.indexNumber?.let { epNum ->
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                    color = Color(0xCC000000),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        "E${epNum.toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurface,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
            if (episode.userData?.played == true) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    color = Color(0xCC000000),
                    shape = RoundedCornerShape(50),
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Watched",
                        tint = Primary,
                        modifier = Modifier.padding(2.dp).size(16.dp),
                    )
                }
            }
            // Watch progress bar
            val watchFraction = watchProgressFraction(episode, download)
            if (watchFraction > 0.01f) {
                LinearProgressIndicator(
                    progress = { watchFraction },
                    modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),
                    color = Primary,
                    trackColor = Color.Transparent,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = episode.name,
            style = MaterialTheme.typography.titleMedium,
            color = OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        episode.runtimeMinutes?.let {
            Text("${it}m", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
        }
        Spacer(Modifier.height(6.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val offlinePos = download?.playbackPositionMs ?: 0L
            val streamPos = episode.userData?.playbackPositionTicks?.div(10_000L) ?: 0L
            val hasPos = offlinePos > 0L || streamPos > 0L
            if (hasPos) {
                val resumeAction: (() -> Unit)? = when {
                    download?.status == DownloadStatus.COMPLETE.name && offlinePos > 0L ->
                        { { onPlayOfflineClick(download.localPath, download.jellyfinId, offlinePos) } }
                    isOnline && onStreamClick != null && streamPos > 0L ->
                        { { onStreamClick(streamPos) } }
                    else -> null
                }
                if (resumeAction != null) {
                    OutlinedButton(
                        onClick = resumeAction,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Resume", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            val playFromStartAction: (() -> Unit)? = when {
                download?.status == DownloadStatus.COMPLETE.name ->
                    { { onPlayOfflineClick(download.localPath, download.jellyfinId, 0L) } }
                isOnline && onStreamClick != null -> { { onStreamClick(0L) } }
                else -> null
            }
            if (playFromStartAction != null) {
                OutlinedButton(
                    onClick = playFromStartAction,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play", style = MaterialTheme.typography.labelSmall)
                }
            }
            when {
                download == null && isOnline -> OutlinedButton(
                    onClick = { showPresetDialog = true },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Download", style = MaterialTheme.typography.labelSmall)
                }
                download != null && download.status in listOf(
                    DownloadStatus.QUEUED.name,
                    DownloadStatus.TRANSCODING.name,
                    DownloadStatus.DOWNLOADING.name,
                ) -> {
                    CircularProgressIndicator(
                        progress = { download.progress / 100f },
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Primary,
                    )
                    Text(
                        if (download.status == DownloadStatus.QUEUED.name) "Queued"
                        else "${"%.0f".format(download.progress)}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurface,
                    )
                }
                download?.status == DownloadStatus.COMPLETE.name ->
                    OutlinedButton(
                        onClick = onDeleteClick,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Error),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                download?.status == DownloadStatus.FAILED.name -> {
                    if (isOnline) {
                        OutlinedButton(
                            onClick = { onDownloadClick("retry") },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                    }
                    OutlinedButton(
                        onClick = onDeleteClick,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Error),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                }
                else -> {}
            }
        }
    }

    if (showPresetDialog) {
        PresetPickerDialog(
            onPresetSelected = { preset ->
                showPresetDialog = false
                onDownloadClick(preset)
            },
            onDismiss = { showPresetDialog = false },
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

private fun watchProgressFraction(
    episode: com.fuzzymistborn.jellyjar.model.JellyfinItem,
    download: com.fuzzymistborn.jellyjar.data.local.DownloadEntity?,
): Float {
    val runtimeMs = (episode.runtimeMinutes ?: download?.runtimeMinutes)?.let { it * 60_000L } ?: return 0f
    val positionMs = download?.playbackPositionMs?.takeIf { it > 0L }
        ?: episode.userData?.playbackPositionTicks?.div(10_000L)?.takeIf { it > 0L }
        ?: return 0f
    return (positionMs.toFloat() / runtimeMs).coerceIn(0f, 0.99f)
}
