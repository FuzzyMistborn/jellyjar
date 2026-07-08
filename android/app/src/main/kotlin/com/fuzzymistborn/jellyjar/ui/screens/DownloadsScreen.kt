package com.fuzzymistborn.jellyjar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.fuzzymistborn.jellyjar.data.local.DownloadEntity
import com.fuzzymistborn.jellyjar.ui.theme.*
import com.fuzzymistborn.jellyjar.ui.viewmodel.DownloadsViewModel

@Composable
fun DownloadsScreen(
    onPlayClick: (localPath: String, jellyfinId: String) -> Unit,
    onBack: () -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hasContent = state.active.isNotEmpty() || state.completed.isNotEmpty() || state.failed.isNotEmpty()

    Box(modifier = Modifier.fillMaxSize().background(Background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = OnSurface)
                }
                Spacer(Modifier.width(4.dp))
                Text("Downloads", style = MaterialTheme.typography.headlineMedium, color = OnSurface)
            }

            if (!hasContent) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Default.DownloadDone,
                            contentDescription = null,
                            tint = OnSurfaceMuted,
                            modifier = Modifier.size(64.dp),
                        )
                        Text(
                            "No downloads yet",
                            style = MaterialTheme.typography.headlineMedium,
                            color = OnSurfaceMuted,
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                        // Smart storage suggestion
                    state.storageStats?.let { stats ->
                        item {
                            Surface(
                                color = androidx.compose.ui.graphics.Color(0xFF1A2A1A),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(Icons.Default.Storage, contentDescription = null,
                                        tint = androidx.compose.ui.graphics.Color(0xFF88CC88),
                                        modifier = Modifier.size(20.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "${stats.watchedCount} watched item${if (stats.watchedCount != 1) "s" else ""} · ${stats.watchedMb} MB",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = androidx.compose.ui.graphics.Color(0xFF88CC88),
                                        )
                                        Text(
                                            "Total offline: ${stats.totalMb} MB",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = OnSurfaceMuted,
                                        )
                                    }
                                    TextButton(onClick = { viewModel.deleteWatched() }) {
                                        Text("Clean up", style = MaterialTheme.typography.labelSmall,
                                            color = androidx.compose.ui.graphics.Color(0xFF88CC88))
                                    }
                                }
                            }
                        }
                    }

                    if (state.active.isNotEmpty()) {
                        item {
                            SectionHeader("In Progress") {
                                TextButton(onClick = { viewModel.cancelAllActive() }) {
                                    Text(
                                        "Cancel All",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Error,
                                    )
                                }
                            }
                        }
                        items(state.active, key = { it.jellyfinId }) { entity ->
                            ActiveDownloadCard(
                                entity = entity,
                                etaMinutes = state.etaByJellyfinId[entity.jellyfinId],
                                onCancel = { viewModel.cancelDownload(entity.jellyfinId) },
                            )
                        }
                    }
                    if (state.completed.isNotEmpty()) {
                        item {
                            SectionHeader("Available Offline") {
                                TextButton(onClick = { viewModel.deleteAllCompleted() }) {
                                    Text(
                                        "Delete All",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Error,
                                    )
                                }
                            }
                        }
                        items(state.completed, key = { it.jellyfinId }) { entity ->
                            CompletedDownloadCard(
                                entity = entity,
                                thumbnailUrl = entity.thumbnailPath?.let { java.io.File(it) }
                    ?: viewModel.thumbnailUrl(entity.jellyfinId),
                                onPlay = { onPlayClick(entity.localPath, entity.jellyfinId) },
                                onDelete = { viewModel.removeDownload(entity.jellyfinId) },
                            )
                        }
                    }
                    if (state.failed.isNotEmpty()) {
                        item {
                            SectionHeader("Failed") {
                                Row {
                                    TextButton(onClick = { viewModel.retryAllFailed() }) {
                                        Text(
                                            "Retry All",
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                    TextButton(onClick = { viewModel.clearAllFailed() }) {
                                        Text(
                                            "Clear",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Error,
                                        )
                                    }
                                }
                            }
                        }
                        items(state.failed, key = { it.jellyfinId }) { entity ->
                            FailedDownloadCard(
                                entity = entity,
                                onRemove = { viewModel.removeDownload(entity.jellyfinId) },
                                onRetry = { viewModel.retryDownload(entity.jellyfinId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = Primary)
        action?.invoke()
    }
}

@Composable
private fun ActiveDownloadCard(entity: DownloadEntity, etaMinutes: Int?, onCancel: () -> Unit) {
    Surface(color = SurfaceVariant, shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        entity.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = OnSurface,
                        maxLines = 2,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when (entity.status) {
                            "TRANSCODING" -> "Transcoding · ${entity.preset}"
                            "DOWNLOADING" -> "Downloading to device"
                            else -> "Queued · ${entity.preset}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceMuted,
                    )
                }
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel", tint = OnSurfaceMuted)
                }
            }
            Spacer(Modifier.height(8.dp))
            val progress = entity.progress / 100f
            val phasePrefix = when (entity.status) {
                "TRANSCODING" -> "Transcoding"
                "DOWNLOADING" -> "Downloading"
                else -> null
            }
            if (progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = Primary,
                    trackColor = Background,
                )
                Spacer(Modifier.height(4.dp))
                val progressLabel = phasePrefix?.let { "$it ${entity.progress.toInt()}%" } ?: "${entity.progress.toInt()}%"
                val etaLabel = etaMinutes?.let { " · ~${it}m left" } ?: ""
                Text(
                    "$progressLabel$etaLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceMuted,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Primary,
                    trackColor = Background,
                )
                Spacer(Modifier.height(4.dp))
                Text("Starting…", style = MaterialTheme.typography.labelSmall, color = OnSurfaceMuted)
            }
        }
    }
}

@Composable
private fun CompletedDownloadCard(
    entity: DownloadEntity,
    thumbnailUrl: Any,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = SurfaceVariant, shape = RoundedCornerShape(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 80.dp, height = 120.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Background),
            ) {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                val watchFraction = run {
                    val runtimeMs = entity.runtimeMinutes?.let { it * 60_000L } ?: 0L
                    val posMs = entity.playbackPositionMs
                    if (runtimeMs > 0L && posMs > 0L) (posMs.toFloat() / runtimeMs).coerceIn(0f, 0.99f) else 0f
                }
                if (watchFraction > 0.01f) {
                    LinearProgressIndicator(
                        progress = { watchFraction },
                        modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),
                        color = Primary,
                        trackColor = androidx.compose.ui.graphics.Color.Transparent,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entity.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = OnSurface,
                    maxLines = 2,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    entity.year?.let {
                        Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                    }
                    entity.runtimeMinutes?.let {
                        Text("${it}m", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                    }
                    Text(entity.preset, style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onPlay,
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Play Offline", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Error),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun FailedDownloadCard(entity: DownloadEntity, onRemove: () -> Unit, onRetry: () -> Unit) {
    Surface(color = SurfaceVariant, shape = RoundedCornerShape(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entity.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Error,
                    maxLines = 2,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Transcode failed · ${entity.preset}",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted,
                )
                if (entity.mediaSourcePath != null) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onRetry,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Retry", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Error)
            }
        }
    }
}
