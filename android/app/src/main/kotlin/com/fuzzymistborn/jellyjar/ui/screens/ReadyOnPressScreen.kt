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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fuzzymistborn.jellyjar.api.JellyfinImageHelper
import com.fuzzymistborn.jellyjar.model.DownloadStatus
import com.fuzzymistborn.jellyjar.ui.theme.*
import com.fuzzymistborn.jellyjar.ui.viewmodel.DownloadSort
import com.fuzzymistborn.jellyjar.ui.viewmodel.ReadyOnPressRow
import com.fuzzymistborn.jellyjar.ui.viewmodel.ReadyFilter
import com.fuzzymistborn.jellyjar.ui.viewmodel.ReadyGroup
import com.fuzzymistborn.jellyjar.ui.viewmodel.browseReady
import com.fuzzymistborn.jellyjar.ui.viewmodel.ReadyOnPressViewModel

// A batch download waiting on the user's confirmation.
private data class PendingReadyDownload(val name: String, val rows: List<ReadyOnPressRow>)

@Composable
fun ReadyOnPressScreen(
    onBack: () -> Unit,
    viewModel: ReadyOnPressViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingDownload by remember { mutableStateOf<PendingReadyDownload?>(null) }

    pendingDownload?.let { pending ->
        BulkDownloadConfirmDialog(
            title = "Download ${pending.name}?",
            count = pending.rows.size,
            bytes = pending.rows.sumOf { it.entry.size_bytes },
            approximate = false,
            freeBytes = state.freeBytes,
            onConfirm = { viewModel.downloadAll(pending.rows) },
            onDismiss = { pendingDownload = null },
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(BackgroundGradient)) {
        ScreenHeader(
            title = "Ready on Press",
            onBack = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            trailingContent = {
                IconButton(onClick = { viewModel.refresh() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = OnSurface)
                }
            },
        )

        when {
            state.isLoading && state.rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error != null && state.rows.isEmpty() -> EmptyState(
                icon = Icons.Default.CloudOff,
                title = "Couldn't load Press",
                subtitle = state.error,
                actionLabel = "Retry",
                onAction = { viewModel.refresh() },
            )
            state.rows.isEmpty() -> EmptyState(
                icon = Icons.Default.Inbox,
                title = "Nothing ready yet",
                subtitle = "Titles that have already been encoded on Press show up here, and download without being encoded again.",
            )
            else -> {
                val groups = remember(state.rows, state.browse, state.downloadStatus) {
                    browseReady(state.rows, state.browse, state.downloadedIds)
                }
                val imageUrl: (String) -> String = { id ->
                    JellyfinImageHelper.primaryImageUrl(state.jellyfinUrl, id, maxWidth = 200)
                }
                LazyColumn(
                    contentPadding = PaddingValues(start = Spacing.xl, end = Spacing.xl, bottom = Spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    state.error?.let { message ->
                        item { Text(message, style = MaterialTheme.typography.bodySmall, color = Error) }
                    }
                    item(key = "controls") {
                        BrowseControls(
                            searchPlaceholder = "Search ready files",
                            query = state.browse.query,
                            onQuery = viewModel::setQuery,
                            showSearch = true,
                            filters = ReadyFilter.values().map { f ->
                                BrowseChip(f.label, state.browse.filter == f, { viewModel.setFilter(f) })
                            },
                            sorts = DownloadSort.values().map { sort ->
                                BrowseChip(sort.label, state.browse.sort == sort, { viewModel.setSort(sort) })
                            },
                        )
                    }
                    if (groups.isEmpty()) {
                        item(key = "empty") {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                            ) {
                                Text("Nothing matches", style = MaterialTheme.typography.titleMedium, color = OnSurface)
                                TextButton(onClick = viewModel::clearFilters) { Text("Clear filters") }
                            }
                        }
                    }
                    groups.forEach { group ->
                        when (group) {
                            is ReadyGroup.Single -> item(key = group.key) {
                                ReadyRow(
                                    title = group.row.item.displayTitle,
                                    detail = "${group.row.entry.preset} · ${formatSize(group.row.entry.size_bytes)}",
                                    thumbnailUrl = imageUrl(group.row.item.id),
                                    status = state.downloadStatus[group.row.item.id],
                                    onDownload = { viewModel.download(group.row) },
                                )
                            }
                            is ReadyGroup.Series -> {
                                // A search opens every matching series so the hits are visible.
                                val expanded = group.key in state.expanded || state.browse.query.isNotBlank()
                                val remaining = group.episodes.count { it.item.id !in state.downloadedIds }
                                item(key = group.key) {
                                    val n = group.episodes.size
                                    SeriesGroupHeader(
                                        title = group.name,
                                        subtitle = buildString {
                                            append("$n episode${if (n != 1) "s" else ""}")
                                            if (group.seasons.size > 1) append(" · ${group.seasons.size} seasons")
                                            append(" · ${formatSize(group.totalBytes)}")
                                            if (remaining == 0) append(" · all downloaded")
                                        },
                                        posterUrl = imageUrl(group.episodes.first().item.seriesId ?: group.episodes.first().item.id),
                                        expanded = expanded,
                                        onClick = { viewModel.toggleExpanded(group.key) },
                                        trailing = {
                                            if (remaining > 0) IconButton(onClick = {
                                                pendingDownload = PendingReadyDownload(group.name, group.episodes.filter { it.item.id !in state.downloadedIds })
                                            }) {
                                                Icon(Icons.Default.DownloadForOffline, contentDescription = "Download all $remaining", tint = OnSurface)
                                            }
                                        },
                                    )
                                }
                                if (expanded) group.seasons.forEach { season ->
                                    val left = season.episodes.count { it.item.id !in state.downloadedIds }
                                    item(key = "${group.key}:season:${season.number}") {
                                        val n = season.episodes.size
                                        SeasonSectionHeader(
                                            label = season.label,
                                            subtitle = "$n episode${if (n != 1) "s" else ""} · ${formatSize(season.totalBytes)}",
                                            trailing = {
                                                if (left > 0) {
                                                    Button(
                                                        onClick = {
                                                            pendingDownload = PendingReadyDownload(
                                                                "${group.name} ${season.label}",
                                                                season.episodes.filter { it.item.id !in state.downloadedIds },
                                                            )
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                                                        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
                                                    ) {
                                                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(IconSize.sm))
                                                        Spacer(Modifier.width(Spacing.xs))
                                                        Text(if (left == n) "Download season" else "Download remaining ($left)")
                                                    }
                                                } else {
                                                    Text("Downloaded", style = MaterialTheme.typography.labelMedium, color = OnSurfaceMuted)
                                                }
                                            },
                                        )
                                    }
                                    items(season.episodes, key = { it.entry.job_id }) { row ->
                                        Box(Modifier.padding(start = Spacing.xl)) {
                                            ReadyRow(
                                                title = row.item.indexNumber?.let { "E${it.toString().padStart(2, '0')} · ${row.item.name}" }
                                                    ?: row.item.name,
                                                detail = "${row.entry.preset} · ${formatSize(row.entry.size_bytes)}",
                                                thumbnailUrl = imageUrl(row.item.id),
                                                status = state.downloadStatus[row.item.id],
                                                onDownload = { viewModel.download(row) },
                                                compact = true,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadyRow(
    title: String,
    detail: String,
    thumbnailUrl: String,
    status: String?,
    onDownload: () -> Unit,
    // Episode rows inside a series: a landscape still instead of a poster.
    compact: Boolean = false,
) {
    Surface(color = SurfaceVariant, shape = RoundedCornerShape(Radius.md)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            PosterImage(
                imageUrl = thumbnailUrl,
                contentDescription = null,
                modifier = if (compact) Modifier.size(width = 80.dp, height = 45.dp) else Modifier.size(width = 48.dp, height = 72.dp),
                cornerRadius = Radius.sm,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = OnSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted,
                )
            }
            when (status) {
                DownloadStatus.COMPLETE.name -> Text("Downloaded", style = MaterialTheme.typography.labelMedium, color = OnSurfaceMuted)
                DownloadStatus.QUEUED.name,
                DownloadStatus.TRANSCODING.name,
                DownloadStatus.DOWNLOADING.name -> Text("In progress", style = MaterialTheme.typography.labelMedium, color = OnSurfaceMuted)
                else -> IconButton(onClick = onDownload) {
                    Icon(Icons.Default.Download, contentDescription = "Download", tint = OnSurface)
                }
            }
        }
    }
}
