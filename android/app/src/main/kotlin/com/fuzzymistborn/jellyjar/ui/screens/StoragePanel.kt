package com.fuzzymistborn.jellyjar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import com.fuzzymistborn.jellyjar.data.local.DownloadEntity
import com.fuzzymistborn.jellyjar.data.repository.FolderStorage
import com.fuzzymistborn.jellyjar.ui.theme.*
import kotlin.math.roundToInt

// Collapsible storage summary at the top of Downloads: used/free, per-folder bars and the
// bulk-delete shortcuts (watched, oldest). It replaced the separate Storage screen.
@Composable
fun StoragePanel(
    completed: List<DownloadEntity>,
    freeBytes: Long,
    deviceTotalBytes: Long,
    folders: List<FolderStorage>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDeleteWatched: () -> Unit,
    onDeleteOldest: () -> Unit,
) {
    val usedBytes = completed.sumOf { it.sizeBytes }
    val movies = completed.filter { it.type == "Movie" }
    val episodes = completed.filter { it.type == "Episode" }
    val watched = completed.filter { it.isWatched }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = Spacing.sm)) {
        Surface(color = SurfaceVariant, shape = RoundedCornerShape(Radius.md), onClick = onToggle) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Icon(Icons.Default.Storage, contentDescription = null, tint = Primary, modifier = Modifier.size(IconSize.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Storage", style = MaterialTheme.typography.titleSmall, color = OnSurface)
                    Text(
                        "${formatSize(usedBytes)} downloaded · ${formatSize(freeBytes)} free",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceMuted,
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = OnSurfaceMuted,
                )
            }
        }
        if (expanded) {
            StorageSummaryCard(
                usedBytes = usedBytes,
                freeBytes = freeBytes,
                deviceTotalBytes = deviceTotalBytes,
                movieCount = movies.size,
                movieBytes = movies.sumOf { it.sizeBytes },
                episodeCount = episodes.size,
                episodeBytes = episodes.sumOf { it.sizeBytes },
                // With several folders the combined bar would mix volumes and files that are out
                // of reach; each folder gets its own bar below instead.
                showDeviceBar = folders.size <= 1,
            )
            if (folders.size > 1) folders.forEach { FolderStorageCard(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(
                    onClick = onDeleteWatched,
                    enabled = watched.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = Spacing.md, vertical = 6.dp),
                ) {
                    Text(
                        if (watched.isEmpty()) "Delete watched" else "Delete watched (${formatSize(watched.sumOf { it.sizeBytes })})",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                OutlinedButton(
                    onClick = onDeleteOldest,
                    enabled = completed.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = Spacing.md, vertical = 6.dp),
                ) {
                    Text("Delete oldest…", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun StorageSummaryCard(
    usedBytes: Long,
    freeBytes: Long,
    deviceTotalBytes: Long,
    movieCount: Int,
    movieBytes: Long,
    episodeCount: Int,
    episodeBytes: Long,
    showDeviceBar: Boolean,
) {
    Surface(color = SurfaceVariant, shape = RoundedCornerShape(Radius.md), modifier = Modifier.padding(top = Spacing.sm)) {
        Column(modifier = Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            // Device storage bar: JellyJar downloads · everything else · free
            val otherBytes = (deviceTotalBytes - freeBytes - usedBytes).coerceAtLeast(0)
            if (showDeviceBar) Row(
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
            if (showDeviceBar) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    LegendDot(Primary, "JellyJar ${formatSize(usedBytes)}")
                    LegendDot(OnSurfaceMuted.copy(alpha = 0.5f), "Other ${formatSize(otherBytes)}")
                    LegendDot(Background, "Free ${formatSize(freeBytes)}")
                }
                HorizontalDivider(color = Background)
            }
            SummaryRow(Icons.Default.Movie, "Movies", "$movieCount · ${formatSize(movieBytes)}")
            SummaryRow(Icons.Default.Tv, "Episodes", "$episodeCount · ${formatSize(episodeBytes)}")
            SummaryRow(Icons.Default.Storage, "Total downloaded", formatSize(usedBytes))
            SummaryRow(Icons.Default.SdStorage, "Free on device", formatSize(freeBytes))
        }
    }
}

// One download folder: its own used/other/free bar, or a notice when its storage is out.
@Composable
fun FolderStorageCard(folder: com.fuzzymistborn.jellyjar.data.repository.FolderStorage) {
    val info = folder.info
    Surface(color = SurfaceVariant, shape = RoundedCornerShape(Radius.md)) {
        Column(modifier = Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SdStorage, contentDescription = null, tint = Primary, modifier = Modifier.size(IconSize.md))
                Text(info.label, style = MaterialTheme.typography.titleSmall, color = OnSurface)
            }
            if (!info.available) {
                Text(
                    "Not available — ${folder.downloadCount} download(s) here can't be played until it's back",
                    style = MaterialTheme.typography.bodySmall,
                    color = Error,
                )
            } else {
                val other = (info.totalBytes - info.freeBytes - folder.usedByJellyJarBytes).coerceAtLeast(0)
                val total = info.totalBytes.coerceAtLeast(1)
                Row(modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
                    Box(Modifier.weight((folder.usedByJellyJarBytes.toFloat() / total).coerceAtLeast(0.001f)).fillMaxHeight().background(Primary))
                    Box(Modifier.weight((other.toFloat() / total).coerceAtLeast(0.001f)).fillMaxHeight().background(OnSurfaceMuted.copy(alpha = 0.5f)))
                    Box(Modifier.weight((info.freeBytes.toFloat() / total).coerceAtLeast(0.001f)).fillMaxHeight().background(Background))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    LegendDot(Primary, "JellyJar ${formatSize(folder.usedByJellyJarBytes)} (${folder.downloadCount})")
                    LegendDot(OnSurfaceMuted.copy(alpha = 0.5f), "Other ${formatSize(other)}")
                    LegendDot(Background, "Free ${formatSize(info.freeBytes)}")
                }
            }
        }
    }
}

@Composable
fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(Spacing.sm).clip(RoundedCornerShape(4.dp)).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceMuted)
    }
}

@Composable
fun SummaryRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
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
fun DeleteOldestDialog(
    items: List<DownloadEntity>,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var count by remember { mutableIntStateOf(minOf(5, items.size)) }
    val oldest = remember(items) { items.sortedBy { it.addedAt } }
    val freedBytes = oldest.take(count).sumOf { it.sizeBytes }

    ConfirmDialog(
        title = "Delete oldest downloads",
        message = "Delete the $count oldest download${if (count != 1) "s" else ""}, freeing ${formatSize(freedBytes)}.",
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

