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
import com.fuzzymistborn.jellyjar.ui.viewmodel.ReadyOnPressRow
import com.fuzzymistborn.jellyjar.ui.viewmodel.ReadyOnPressViewModel

@Composable
fun ReadyOnPressScreen(
    onBack: () -> Unit,
    viewModel: ReadyOnPressViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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
            else -> LazyColumn(
                contentPadding = PaddingValues(start = Spacing.xl, end = Spacing.xl, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.fillMaxSize(),
            ) {
                state.error?.let { message ->
                    item { Text(message, style = MaterialTheme.typography.bodySmall, color = Error) }
                }
                items(state.rows, key = { it.entry.job_id }) { row ->
                    ReadyRow(
                        row = row,
                        thumbnailUrl = JellyfinImageHelper.primaryImageUrl(state.jellyfinUrl, row.item.id, maxWidth = 200),
                        status = state.downloadStatus[row.item.id],
                        onDownload = { viewModel.download(row) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadyRow(
    row: ReadyOnPressRow,
    thumbnailUrl: String,
    status: String?,
    onDownload: () -> Unit,
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
                modifier = Modifier.size(width = 48.dp, height = 72.dp),
                cornerRadius = Radius.sm,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    row.item.displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = OnSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${row.entry.preset} · ${readyFileSize(row.entry.size_bytes)}",
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

private fun readyFileSize(bytes: Long): String =
    if (bytes >= 1_073_741_824L) "%.1f GB".format(bytes / 1_073_741_824.0)
    else "%.0f MB".format(bytes / 1_048_576.0)
