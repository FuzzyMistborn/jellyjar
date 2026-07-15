package com.fuzzymistborn.jellyjar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fuzzymistborn.jellyjar.data.local.DownloadEntity
import com.fuzzymistborn.jellyjar.ui.theme.*
import com.fuzzymistborn.jellyjar.ui.viewmodel.StorageSort
import com.fuzzymistborn.jellyjar.ui.viewmodel.StorageViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun StorageScreen(
    onBack: () -> Unit,
    viewModel: StorageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDeleteWatched by remember { mutableStateOf(false) }
    var showDeleteOldest by remember { mutableStateOf(false) }
    var showDeleteAll by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(BackgroundGradient)) {
        ScreenHeader(
            title = "Storage",
            onBack = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        )

        LazyColumn(
            contentPadding = PaddingValues(start = Spacing.xl, end = Spacing.xl, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                StorageSummaryCard(
                    usedBytes = state.totalBytes,
                    freeBytes = state.freeBytes,
                    deviceTotalBytes = state.deviceTotalBytes,
                    movieCount = state.movieCount,
                    movieBytes = state.movieBytes,
                    episodeCount = state.episodeCount,
                    episodeBytes = state.episodeBytes,
                )
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                ) {
                    OutlinedButton(
                        onClick = { showDeleteWatched = true },
                        enabled = state.watchedItems.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = 6.dp),
                    ) {
                        Text("Delete Watched", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = { showDeleteOldest = true },
                        enabled = state.items.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = 6.dp),
                    ) {
                        Text("Delete Oldest…", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = { showDeleteAll = true },
                        enabled = state.items.isNotEmpty(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Error),
                        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = 6.dp),
                    ) {
                        Text("Delete Everything", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            if (state.items.isNotEmpty()) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        modifier = Modifier.padding(top = Spacing.sm),
                    ) {
                        SortChip("Largest", state.sort == StorageSort.SIZE) { viewModel.setSort(StorageSort.SIZE) }
                        SortChip("Newest", state.sort == StorageSort.NEWEST) { viewModel.setSort(StorageSort.NEWEST) }
                        SortChip("Oldest", state.sort == StorageSort.OLDEST) { viewModel.setSort(StorageSort.OLDEST) }
                        SortChip("Name", state.sort == StorageSort.NAME) { viewModel.setSort(StorageSort.NAME) }
                    }
                }
                items(state.sortedItems, key = { it.jellyfinId }) { entity ->
                    StorageItemRow(
                        entity = entity,
                        isWatched = state.watchedItems.any { it.jellyfinId == entity.jellyfinId },
                        onDelete = { viewModel.delete(entity.jellyfinId) },
                    )
                }
            } else {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                        Text("No downloads on device", color = OnSurfaceMuted,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    if (showDeleteWatched) {
        ConfirmDialog(
            title = "Delete watched downloads?",
            message = "${state.watchedItems.size} watched item${if (state.watchedItems.size != 1) "s" else ""} " +
                "will be deleted, freeing ${formatBytes(state.watchedBytes)}.",
            confirmLabel = "Delete",
            onConfirm = viewModel::deleteWatched,
            onDismiss = { showDeleteWatched = false },
        )
    }

    if (showDeleteOldest) {
        DeleteOldestDialog(
            items = state.items,
            onConfirm = { count -> viewModel.deleteOldest(count) },
            onDismiss = { showDeleteOldest = false },
        )
    }

    if (showDeleteAll) {
        ConfirmDialog(
            title = "Delete everything?",
            message = "All ${state.items.size} downloads (${formatBytes(state.totalBytes)}) will be " +
                "removed from this device. Media on the server is not affected.",
            confirmLabel = "Delete All",
            onConfirm = viewModel::deleteEverything,
            onDismiss = { showDeleteAll = false },
        )
    }
}

@Composable
private fun StorageSummaryCard(
    usedBytes: Long,
    freeBytes: Long,
    deviceTotalBytes: Long,
    movieCount: Int,
    movieBytes: Long,
    episodeCount: Int,
    episodeBytes: Long,
) {
    Surface(color = SurfaceVariant, shape = RoundedCornerShape(Radius.md), modifier = Modifier.padding(top = Spacing.sm)) {
        Column(modifier = Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            // Device storage bar: JellyJar downloads · everything else · free
            val otherBytes = (deviceTotalBytes - freeBytes - usedBytes).coerceAtLeast(0)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp)),
            ) {
                val total = deviceTotalBytes.coerceAtLeast(1)
                val jellyJarWeight = (usedBytes.toFloat() / total).coerceAtLeast(0.001f)
                val otherWeight = (otherBytes.toFloat() / total).coerceAtLeast(0.001f)
                val freeWeight = (freeBytes.toFloat() / total).coerceAtLeast(0.001f)
                Box(Modifier.weight(jellyJarWeight).fillMaxHeight().background(Primary))
                Box(Modifier.weight(otherWeight).fillMaxHeight().background(OnSurfaceMuted.copy(alpha = 0.5f)))
                Box(Modifier.weight(freeWeight).fillMaxHeight().background(Background))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                LegendDot(Primary, "JellyJar ${formatBytes(usedBytes)}")
                LegendDot(OnSurfaceMuted.copy(alpha = 0.5f), "Other ${formatBytes(otherBytes)}")
                LegendDot(Background, "Free ${formatBytes(freeBytes)}")
            }
            HorizontalDivider(color = Background)
            SummaryRow(Icons.Default.Movie, "Movies", "$movieCount · ${formatBytes(movieBytes)}")
            SummaryRow(Icons.Default.Tv, "Episodes", "$episodeCount · ${formatBytes(episodeBytes)}")
            SummaryRow(Icons.Default.Storage, "Total downloaded", formatBytes(usedBytes))
            SummaryRow(Icons.Default.SdStorage, "Free on device", formatBytes(freeBytes))
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(Spacing.sm).clip(RoundedCornerShape(4.dp)).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceMuted)
    }
}

@Composable
private fun SummaryRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Primary, modifier = Modifier.size(IconSize.md))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = OnSurface)
        }
        Text(value, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted)
    }
}

@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        colors = themedChipColors(),
    )
}

@Composable
private fun StorageItemRow(entity: DownloadEntity, isWatched: Boolean, onDelete: () -> Unit) {
    Surface(color = SurfaceVariant, shape = RoundedCornerShape(Radius.md)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Icon(
                if (entity.type == "Movie") Icons.Default.Movie else Icons.Default.Tv,
                contentDescription = entity.type,
                tint = OnSurfaceMuted,
                modifier = Modifier.size(IconSize.md),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entity.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurface,
                    maxLines = 2,
                )
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        formatBytes(entity.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceMuted,
                    )
                    Text(
                        SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(entity.addedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceMuted,
                    )
                    if (isWatched) {
                        Text("Watched", style = MaterialTheme.typography.labelSmall, color = Primary)
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Error)
            }
        }
    }
}

@Composable
private fun DeleteOldestDialog(
    items: List<DownloadEntity>,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var count by remember { mutableIntStateOf(minOf(5, items.size)) }
    val oldest = remember(items) { items.sortedBy { it.addedAt } }
    val freedBytes = oldest.take(count).sumOf { it.sizeBytes }

    ConfirmDialog(
        title = "Delete oldest downloads",
        message = "Delete the $count oldest download${if (count != 1) "s" else ""}, freeing ${formatBytes(freedBytes)}.",
        confirmLabel = "Delete",
        onConfirm = { onConfirm(count) },
        onDismiss = onDismiss,
        content = if (items.size > 1) {
            {
                Slider(
                    value = count.toFloat(),
                    onValueChange = { count = it.roundToInt().coerceIn(1, items.size) },
                    valueRange = 1f..items.size.toFloat(),
                    steps = (items.size - 2).coerceAtLeast(0),
                    colors = SliderDefaults.colors(thumbColor = Primary, activeTrackColor = Primary),
                )
            }
        } else null,
    )
}

private fun formatBytes(bytes: Long): String =
    if (bytes >= 1_073_741_824L) "%.1f GB".format(bytes / 1_073_741_824.0)
    else "%.0f MB".format(bytes / 1_048_576.0)
