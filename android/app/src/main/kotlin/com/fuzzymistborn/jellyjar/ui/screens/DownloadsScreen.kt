package com.fuzzymistborn.jellyjar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fuzzymistborn.jellyjar.data.local.DownloadEntity
import com.fuzzymistborn.jellyjar.ui.theme.*
import com.fuzzymistborn.jellyjar.ui.viewmodel.DownloadsViewModel

@Composable
fun DownloadsScreen(
    onPlayClick: (localPath: String, jellyfinId: String) -> Unit,
    onStorageClick: () -> Unit,
    onBack: () -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hasContent = state.active.isNotEmpty() || state.queued.isNotEmpty() ||
        state.completed.isNotEmpty() || state.failed.isNotEmpty()

    Box(modifier = Modifier.fillMaxSize().background(BackgroundGradient)) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(
                title = "Downloads",
                onBack = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    // Extra vertical clearance beyond the status bar itself — on gesture-nav
                    // devices the swipe-down-for-notifications zone can extend a bit past the
                    // physical status bar, making a trailing icon button sitting right at that
                    // edge hard to tap reliably.
                    .padding(horizontal = Spacing.md, vertical = Spacing.lg),
                trailingContent = {
                    IconButton(onClick = onStorageClick) {
                        Icon(Icons.Default.Storage, contentDescription = "Manage Storage", tint = OnSurface)
                    }
                },
            )

            if (!hasContent) {
                EmptyState(
                    icon = Icons.Default.DownloadDone,
                    title = "No downloads yet",
                    subtitle = "Download movies and shows to watch offline.",
                    actionLabel = "Browse Library",
                    onAction = onBack,
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = Spacing.xl, end = Spacing.xl, bottom = Spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.fillMaxSize(),
                ) {
                        // Smart storage suggestion
                    state.storageStats?.let { stats ->
                        item {
                            Surface(
                                color = Success.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(Radius.md),
                                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                            ) {
                                Row(
                                    modifier = Modifier.padding(Spacing.lg),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                                ) {
                                    Icon(Icons.Default.Storage, contentDescription = null,
                                        tint = Success,
                                        modifier = Modifier.size(IconSize.md))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "${stats.watchedCount} watched item${if (stats.watchedCount != 1) "s" else ""} · ${stats.watchedMb} MB",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Success,
                                        )
                                        Text(
                                            "Total offline: ${stats.totalMb} MB",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = OnSurfaceMuted,
                                        )
                                    }
                                    TextButton(onClick = { viewModel.deleteWatched() }) {
                                        Text("Clean up", style = MaterialTheme.typography.labelSmall,
                                            color = Success)
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
                    if (state.queued.isNotEmpty() || (state.queuePaused && state.active.isEmpty())) {
                        item {
                            SectionHeader("Queue (${state.queued.size})") {
                                TextButton(onClick = {
                                    if (state.queuePaused) viewModel.resumeQueue() else viewModel.pauseQueue()
                                }) {
                                    Icon(
                                        if (state.queuePaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                        contentDescription = null,
                                        modifier = Modifier.size(IconSize.sm),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        if (state.queuePaused) "Resume" else "Pause",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                        if (state.queuePaused) {
                            item {
                                Text(
                                    "Queue paused — items already in progress will finish",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurfaceMuted,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                        } else if (state.active.isNotEmpty()) {
                            item {
                                Text(
                                    "Waiting for Press — ${state.active.size} " +
                                        "item${if (state.active.size != 1) "s" else ""} transcoding",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurfaceMuted,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                        }
                        itemsIndexed(state.queued, key = { _, d -> d.jellyfinId }) { index, entity ->
                            QueuedDownloadCard(
                                entity = entity,
                                position = index + 1,
                                isFirst = index == 0,
                                isLast = index == state.queued.lastIndex,
                                onPrioritize = { viewModel.prioritize(entity.jellyfinId) },
                                onMoveUp = { viewModel.moveUp(entity.jellyfinId) },
                                onMoveDown = { viewModel.moveDown(entity.jellyfinId) },
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
                                thumbnailUrl = entity.thumbnailPath
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

        DownloadErrorSnackbar(state.downloadError) { viewModel.clearDownloadError() }
    }
}

private fun formatFileSize(bytes: Long): String =
    if (bytes >= 1_073_741_824L) "%.1f GB".format(bytes / 1_073_741_824.0)
    else "%.0f MB".format(bytes / 1_048_576.0)

@Composable
private fun SectionHeader(title: String, action: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.lg, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = Primary)
        action?.invoke()
    }
}

@Composable
private fun ActiveDownloadCard(entity: DownloadEntity, etaMinutes: Int?, onCancel: () -> Unit) {
    Surface(color = SurfaceVariant, shape = RoundedCornerShape(Radius.md)) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
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
            Spacer(Modifier.height(Spacing.sm))
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
private fun QueuedDownloadCard(
    entity: DownloadEntity,
    position: Int,
    isFirst: Boolean,
    isLast: Boolean,
    onPrioritize: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(color = SurfaceVariant, shape = RoundedCornerShape(Radius.md)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                "$position",
                style = MaterialTheme.typography.titleMedium,
                color = OnSurfaceMuted,
                modifier = Modifier.widthIn(min = Spacing.xl),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entity.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = OnSurface,
                    maxLines = 2,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Waiting · ${entity.preset}",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted,
                )
            }
            IconButton(onClick = onPrioritize, enabled = !isFirst) {
                Icon(
                    Icons.Default.KeyboardDoubleArrowUp, contentDescription = "Move to top",
                    tint = if (isFirst) OnSurfaceMuted.copy(alpha = 0.3f) else Primary,
                )
            }
            IconButton(onClick = onMoveUp, enabled = !isFirst) {
                Icon(
                    Icons.Default.KeyboardArrowUp, contentDescription = "Move up",
                    tint = if (isFirst) OnSurfaceMuted.copy(alpha = 0.3f) else OnSurfaceMuted,
                )
            }
            IconButton(onClick = onMoveDown, enabled = !isLast) {
                Icon(
                    Icons.Default.KeyboardArrowDown, contentDescription = "Move down",
                    tint = if (isLast) OnSurfaceMuted.copy(alpha = 0.3f) else OnSurfaceMuted,
                )
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = OnSurfaceMuted)
            }
        }
    }
}

@Composable
private fun CompletedDownloadCard(
    entity: DownloadEntity,
    thumbnailUrl: String,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = SurfaceVariant, shape = RoundedCornerShape(Radius.md)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            PosterImage(
                imageUrl = thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.size(width = 80.dp, height = 120.dp),
            ) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    entity.year?.let {
                        Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                    }
                    entity.runtimeMinutes?.let {
                        Text("${it}m", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                    }
                    Text(entity.preset, style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                    if (entity.sizeBytes > 0) {
                        Text(formatFileSize(entity.sizeBytes), style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                    }
                }
                Spacer(Modifier.height(Spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Button(
                        onClick = onPlay,
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(IconSize.sm),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Play Offline", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Error),
                        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(IconSize.sm))
                    }
                }
            }
        }
    }
}

@Composable
private fun FailedDownloadCard(entity: DownloadEntity, onRemove: () -> Unit, onRetry: () -> Unit) {
    Surface(color = SurfaceVariant, shape = RoundedCornerShape(Radius.md)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
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
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedButton(
                        onClick = onRetry,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(IconSize.sm))
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
