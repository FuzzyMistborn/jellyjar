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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fuzzymistborn.jellyjar.api.JellyfinImageHelper
import com.fuzzymistborn.jellyjar.data.local.DownloadEntity
import com.fuzzymistborn.jellyjar.data.local.playbackUri
import com.fuzzymistborn.jellyjar.ui.theme.*
import com.fuzzymistborn.jellyjar.ui.viewmodel.DownloadEntry
import com.fuzzymistborn.jellyjar.ui.viewmodel.DownloadFilter
import com.fuzzymistborn.jellyjar.ui.viewmodel.DownloadSort
import com.fuzzymistborn.jellyjar.ui.viewmodel.QueueGroup
import com.fuzzymistborn.jellyjar.ui.viewmodel.groupQueue
import com.fuzzymistborn.jellyjar.ui.viewmodel.DownloadsViewModel
import com.fuzzymistborn.jellyjar.ui.viewmodel.browseDownloads

// A batch delete waiting on the user's confirmation (a series, a season, or the filtered list).
private data class PendingBulkDelete(val title: String, val ids: List<String>, val bytes: Long, val note: String? = null)

@Composable
fun DownloadsScreen(
    onPlayClick: (localPath: String, jellyfinId: String) -> Unit,
    onReadyClick: () -> Unit,
    onBack: () -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Kid Mode keeps this screen as a way to *watch* what's already downloaded and nothing else:
    // the in-progress, queue and failed sections are management surfaces a kid can't act on once
    // their buttons are gone, so they're dropped wholesale rather than rendered inert.
    val kidMode = state.kidModeEnabled
    val hasContent = if (kidMode) {
        state.completed.isNotEmpty()
    } else {
        state.active.isNotEmpty() || state.queued.isNotEmpty() ||
            state.completed.isNotEmpty() || state.failed.isNotEmpty()
    }
    val entries = remember(state.completed, state.browse, state.folderRootById) {
        browseDownloads(state.completed, state.browse, state.folderRootById)
    }
    // Every download the filtered list currently shows — what "Delete these" acts on.
    val visible = remember(entries) {
        entries.flatMap { e -> if (e is DownloadEntry.Series) e.episodes else listOf((e as DownloadEntry.Single).download) }
    }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var pendingBulk by remember { mutableStateOf<PendingBulkDelete?>(null) }
    var showDeleteOldest by remember { mutableStateOf(false) }
    var storageExpanded by rememberSaveable { mutableStateOf(false) }

    val posterFor: (String?, DownloadEntity) -> String = { seriesId, fallback ->
        seriesId?.let { JellyfinImageHelper.primaryImageUrl(state.jellyfinUrl, it, maxWidth = 200) }
            ?: fallback.thumbnailUri ?: viewModel.thumbnailUrl(fallback.jellyfinId)
    }
    val completedCard: @Composable (DownloadEntity, Boolean) -> Unit = { entity, compact ->
        CompletedDownloadCard(
            entity = entity,
            thumbnailUrl = entity.thumbnailUri ?: viewModel.thumbnailUrl(entity.jellyfinId),
            onPlay = { onPlayClick(entity.playbackUri(), entity.jellyfinId) },
            onDelete = if (kidMode) null else {
                { pendingDeleteId = entity.jellyfinId }
            },
            folderLabel = state.folderLabelById[entity.jellyfinId]
                ?.takeIf { state.folders.size > 1 },
            unavailable = entity.jellyfinId in state.unavailableIds,
            moving = entity.jellyfinId in state.movingIds,
            // Targets other than where it already is; none in Kid Mode.
            moveTargets = if (kidMode) emptyList() else state.folders.filter {
                it.available && it.uri != state.folderRootById[entity.jellyfinId]
            },
            onMove = { folder -> viewModel.moveDownloads(listOf(entity.jellyfinId), folder) },
            compact = compact,
        )
    }
    // The ⋮ menu of a series or one season.
    val groupMenu: @Composable (String, List<DownloadEntity>) -> Unit = { name, items ->
        val ids = items.map { it.jellyfinId }
        GroupActionsMenu(
            moveTargets = state.folders.filter { f ->
                f.available && items.any { it.jellyfinId !in state.unavailableIds && state.folderRootById[it.jellyfinId] != f.uri }
            },
            hasWatched = items.any { it.isWatched },
            moving = ids.any { it in state.movingIds },
            onMove = { folder -> viewModel.moveDownloads(ids, folder) },
            onDeleteWatched = {
                val watched = items.filter { it.isWatched }
                pendingBulk = PendingBulkDelete("Delete watched from $name?", watched.map { it.jellyfinId }, watched.sumOf { it.sizeBytes })
            },
            onDelete = {
                pendingBulk = PendingBulkDelete("Delete $name?", ids, items.sumOf { it.sizeBytes })
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(BackgroundGradient)) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(
                title = "Downloads",
                onBack = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                trailingContent = if (kidMode) null else {
                    {
                        IconButton(onClick = onReadyClick) {
                            Icon(Icons.Default.Inbox, contentDescription = "Ready on Press", tint = OnSurface)
                        }
                    }
                },
            )

            if (!kidMode && !notificationsPermissionGranted()) {
                NotificationsDisabledBanner(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.xl, vertical = Spacing.sm),
                )
            }

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
                    if (!kidMode && state.completed.isNotEmpty()) {
                        item(key = "storage_panel") {
                            StoragePanel(
                                completed = state.completed,
                                freeBytes = state.deviceFreeBytes,
                                deviceTotalBytes = state.deviceTotalBytes,
                                folders = state.folderStorage,
                                expanded = storageExpanded,
                                onToggle = { storageExpanded = !storageExpanded },
                                onDeleteWatched = {
                                    val watched = state.completed.filter { it.isWatched }
                                    pendingBulk = PendingBulkDelete(
                                        "Delete watched downloads?", watched.map { it.jellyfinId }, watched.sumOf { it.sizeBytes },
                                    )
                                },
                                onDeleteOldest = { showDeleteOldest = true },
                            )
                        }
                    }

                    if (state.active.isNotEmpty() && !kidMode) {
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
                        groupQueue(state.active).forEach { group ->
                            when (group) {
                                is QueueGroup.Single -> item(key = "active:${group.key}") {
                                    ActiveDownloadCard(
                                        entity = group.download,
                                        thumbnailUrl = group.download.thumbnailUri ?: viewModel.thumbnailUrl(group.download.jellyfinId),
                                        etaMinutes = state.etaByJellyfinId[group.download.jellyfinId],
                                        onCancel = { viewModel.cancelDownload(group.download.jellyfinId) },
                                    )
                                }
                                is QueueGroup.Series -> {
                                    val key = "active:${group.key}"
                                    val open = key in state.expandedSeries
                                    item(key = key) {
                                        // progress is already 0–100
                                        val avg = (group.items.sumOf { it.progress.toDouble() } / group.items.size).toInt()
                                        SeriesGroupHeader(
                                            title = group.name,
                                            subtitle = "${group.items.size} in progress · $avg%",
                                            posterUrl = posterFor(group.items.firstNotNullOfOrNull { it.seriesId }, group.items.first()),
                                            expanded = open,
                                            onClick = { viewModel.toggleSeries(key) },
                                            trailing = {
                                                IconButton(onClick = { viewModel.deleteDownloads(group.items.map { it.jellyfinId }) }) {
                                                    Icon(Icons.Default.Close, contentDescription = "Cancel ${group.name}", tint = Error)
                                                }
                                            },
                                        )
                                    }
                                    if (open) items(group.items, key = { "active:${it.jellyfinId}" }) { entity ->
                                        Box(Modifier.padding(start = Spacing.xl)) {
                                            ActiveDownloadCard(
                                                entity = entity,
                                                thumbnailUrl = entity.thumbnailUri ?: viewModel.thumbnailUrl(entity.jellyfinId),
                                                etaMinutes = state.etaByJellyfinId[entity.jellyfinId],
                                                onCancel = { viewModel.cancelDownload(entity.jellyfinId) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (!kidMode && (state.queued.isNotEmpty() || (state.queuePaused && state.active.isEmpty()))) {
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
                        } else {
                            // Only encodes hold a queue slot: an item already copying to the device
                            // doesn't (see DownloadQueueManager), so counting all of `active` said
                            // "2 items transcoding" while one of them was downloading.
                            val transcoding = state.active.count { it.status == "TRANSCODING" }
                            if (transcoding > 0) item {
                                Text(
                                    "Waiting for Press — $transcoding " +
                                        "item${if (transcoding != 1) "s" else ""} transcoding",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurfaceMuted,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                        }
                        val queueCard: @Composable (DownloadEntity) -> Unit = { entity ->
                            val index = state.queued.indexOfFirst { it.jellyfinId == entity.jellyfinId }
                            QueuedDownloadCard(
                                entity = entity,
                                thumbnailUrl = entity.thumbnailUri ?: viewModel.thumbnailUrl(entity.jellyfinId),
                                position = index + 1,
                                isFirst = index == 0,
                                isLast = index == state.queued.lastIndex,
                                onPrioritize = { viewModel.prioritize(entity.jellyfinId) },
                                onMoveUp = { viewModel.moveUp(entity.jellyfinId) },
                                onMoveDown = { viewModel.moveDown(entity.jellyfinId) },
                                onCancel = { viewModel.cancelDownload(entity.jellyfinId) },
                            )
                        }
                        groupQueue(state.queued).forEach { group ->
                            when (group) {
                                is QueueGroup.Single -> item(key = "queued:${group.key}") { queueCard(group.download) }
                                is QueueGroup.Series -> {
                                    val key = "queue:${group.key}"
                                    val open = key in state.expandedSeries
                                    item(key = key) {
                                        SeriesGroupHeader(
                                            title = group.name,
                                            subtitle = "${group.items.size} queued",
                                            posterUrl = posterFor(group.items.firstNotNullOfOrNull { it.seriesId }, group.items.first()),
                                            expanded = open,
                                            onClick = { viewModel.toggleSeries(key) },
                                            trailing = {
                                                IconButton(onClick = { viewModel.deleteDownloads(group.items.map { it.jellyfinId }) }) {
                                                    Icon(Icons.Default.Close, contentDescription = "Cancel ${group.name}", tint = Error)
                                                }
                                            },
                                        )
                                    }
                                    if (open) items(group.items, key = { "queued:${it.jellyfinId}" }) { entity ->
                                        Box(Modifier.padding(start = Spacing.xl)) { queueCard(entity) }
                                    }
                                }
                            }
                        }
                    }
                    if (state.completed.isNotEmpty()) {
                        item {
                            SectionHeader("Available Offline") {
                                if (!kidMode && visible.isNotEmpty()) {
                                    TextButton(onClick = {
                                        pendingBulk = PendingBulkDelete(
                                            title = if (state.browse.isFiltering) "Delete these downloads?" else "Delete all downloads?",
                                            ids = visible.map { it.jellyfinId },
                                            bytes = visible.sumOf { it.sizeBytes },
                                            note = "Media on the server is not affected.",
                                        )
                                    }) {
                                        Text(
                                            if (state.browse.isFiltering) "Delete these ${visible.size}" else "Delete All",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Error,
                                        )
                                    }
                                }
                            }
                        }
                        item(key = "browse_controls") {
                            BrowseControls(
                                searchPlaceholder = "Search downloads",
                                query = state.browse.query,
                                onQuery = viewModel::setQuery,
                                // Not in Kid Mode: the search box is part of the management surface.
                                showSearch = !kidMode,
                                filters = DownloadFilter.values().map { f ->
                                    BrowseChip(f.label, state.browse.filter == f, { viewModel.setFilter(f) })
                                } +
                                    // Folder chips only when there's more than one place to look; tapping
                                    // the active one again goes back to every folder.
                                    state.folders.takeIf { it.size > 1 }.orEmpty().map { f ->
                                        folderChip(f, state.browse.folder == f.uri) {
                                            viewModel.setFolderFilter(if (state.browse.folder == f.uri) null else f.uri)
                                        }
                                    },
                                sorts = DownloadSort.values().map { sort ->
                                    BrowseChip(sort.label, state.browse.sort == sort, { viewModel.setSort(sort) })
                                },
                            )
                        }
                        if (entries.isEmpty()) {
                            item(key = "browse_empty") {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                                ) {
                                    Text("No downloads match", style = MaterialTheme.typography.titleMedium, color = OnSurface)
                                    TextButton(onClick = viewModel::clearFilters) { Text("Clear filters") }
                                }
                            }
                        }
                        entries.forEach { entry ->
                            when (entry) {
                                is DownloadEntry.Single -> item(key = entry.key) { completedCard(entry.download, false) }
                                is DownloadEntry.Series -> {
                                    // A search opens every matching series so the hits are visible.
                                    val expanded = entry.key in state.expandedSeries || state.browse.query.isNotBlank()
                                    item(key = entry.key) {
                                        val n = entry.episodes.size
                                        val watched = entry.episodes.count { it.isWatched }
                                        SeriesGroupHeader(
                                            title = entry.name,
                                            subtitle = buildString {
                                                append("$n episode${if (n != 1) "s" else ""}")
                                                if (entry.seasons.size > 1) append(" · ${entry.seasons.size} seasons")
                                                if (entry.totalBytes > 0) append(" · ${formatSize(entry.totalBytes)}")
                                                if (watched > 0) append(" · $watched watched")
                                            },
                                            posterUrl = posterFor(entry.seriesId, entry.episodes.first()),
                                            expanded = expanded,
                                            onClick = { viewModel.toggleSeries(entry.key) },
                                            trailing = { if (!kidMode) groupMenu(entry.name, entry.episodes) },
                                        )
                                    }
                                    if (expanded) entry.seasons.forEach { season ->
                                        item(key = "${entry.key}:season:${season.number}") {
                                            val n = season.episodes.size
                                            SeasonSectionHeader(
                                                label = season.label,
                                                subtitle = "$n episode${if (n != 1) "s" else ""} · ${formatSize(season.totalBytes)}" +
                                                    if (season.watchedCount > 0) " · ${season.watchedCount} watched" else "",
                                                trailing = { if (!kidMode) groupMenu("${entry.name} ${season.label}", season.episodes) },
                                            )
                                        }
                                        items(season.episodes, key = { it.jellyfinId }) { ep ->
                                            Box(Modifier.padding(start = Spacing.xl)) { completedCard(ep, true) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (state.failed.isNotEmpty() && !kidMode) {
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
                                thumbnailUrl = entity.thumbnailUri
                                    ?: viewModel.thumbnailUrl(entity.jellyfinId),
                                onRemove = { viewModel.removeDownload(entity.jellyfinId) },
                                onRetry = { viewModel.retryDownload(entity.jellyfinId) },
                            )
                        }
                    }
                }
            }
        }

        DownloadErrorSnackbar(state.downloadError) { viewModel.clearDownloadError() }

        pendingDeleteId?.let { id ->
            DeleteConfirmDialog(
                onConfirm = { viewModel.removeDownload(id) },
                onDismiss = { pendingDeleteId = null },
            )
        }

        pendingBulk?.let { bulk ->
            val n = bulk.ids.size
            ConfirmDialog(
                title = bulk.title,
                message = "$n download${if (n != 1) "s" else ""} (${formatSize(bulk.bytes)}) will be removed from this device." +
                    (bulk.note?.let { " $it" } ?: " You can download ${if (n != 1) "them" else "it"} again later."),
                confirmLabel = "Delete",
                onConfirm = { viewModel.deleteDownloads(bulk.ids) },
                onDismiss = { pendingBulk = null },
            )
        }

        if (showDeleteOldest) {
            DeleteOldestDialog(
                items = state.completed,
                onConfirm = { count -> viewModel.deleteOldest(count) },
                onDismiss = { showDeleteOldest = false },
            )
        }
    }
}

@Composable
private fun notificationsPermissionGranted(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    // POST_NOTIFICATIONS only exists from API 33 — below that, notifications are always allowed.
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return true
    return androidx.core.content.ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.POST_NOTIFICATIONS
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

@Composable
private fun NotificationsDisabledBanner(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Surface(
        color = Warning.copy(alpha = 0.15f),
        shape = RoundedCornerShape(Radius.md),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Icon(Icons.Default.NotificationsOff, contentDescription = null,
                tint = Warning, modifier = Modifier.size(IconSize.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Notifications are off",
                    style = MaterialTheme.typography.bodySmall,
                    color = Warning,
                )
                Text(
                    "You won't be notified when downloads finish or fail.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted,
                )
            }
            TextButton(onClick = {
                val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                context.startActivity(intent)
            }) {
                Text("Settings", style = MaterialTheme.typography.labelSmall, color = Warning)
            }
        }
    }
}

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
private fun ActiveDownloadCard(
    entity: DownloadEntity,
    thumbnailUrl: String,
    etaMinutes: Int?,
    onCancel: () -> Unit,
) {
    Surface(color = SurfaceVariant, shape = RoundedCornerShape(Radius.md)) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.Top,
            ) {
                DownloadRowPoster(thumbnailUrl)
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
                val finishing = entity.status == "TRANSCODING" && entity.progress >= 99.5f
                val progressLabel = if (finishing) "Finishing…"
                    else phasePrefix?.let { "$it ${entity.progress.toInt()}%" } ?: "${entity.progress.toInt()}%"
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
    thumbnailUrl: String,
    position: Int,
    isFirst: Boolean,
    isLast: Boolean,
    onPrioritize: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(color = SurfaceVariant, shape = RoundedCornerShape(Radius.md)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        ) {
            // Title gets the full card width — the reorder controls used to sit alongside it
            // in one row, crushing the title down to a sliver on portrait-width screens.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "$position",
                    style = MaterialTheme.typography.titleMedium,
                    color = OnSurfaceMuted,
                    modifier = Modifier.widthIn(min = Spacing.xl),
                )
                DownloadRowPoster(thumbnailUrl)
                Text(
                    entity.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = OnSurface,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel", tint = OnSurfaceMuted)
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Waiting · ${entity.preset}",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPrioritize, enabled = !isFirst, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.KeyboardDoubleArrowUp, contentDescription = "Move to top",
                            tint = if (isFirst) OnSurfaceMuted.copy(alpha = 0.3f) else Primary,
                        )
                    }
                    IconButton(onClick = onMoveUp, enabled = !isFirst, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.KeyboardArrowUp, contentDescription = "Move up",
                            tint = if (isFirst) OnSurfaceMuted.copy(alpha = 0.3f) else OnSurfaceMuted,
                        )
                    }
                    IconButton(onClick = onMoveDown, enabled = !isLast, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.KeyboardArrowDown, contentDescription = "Move down",
                            tint = if (isLast) OnSurfaceMuted.copy(alpha = 0.3f) else OnSurfaceMuted,
                        )
                    }
                }
            }
        }
    }
}

// Compact poster for the in-progress/queued/failed rows. Smaller than the completed row's
// 80x120 — those rows are informational and shouldn't out-weigh finished, playable downloads.
@Composable
private fun DownloadRowPoster(thumbnailUrl: String) {
    PosterImage(
        imageUrl = thumbnailUrl,
        contentDescription = null,
        modifier = Modifier.size(width = 48.dp, height = 72.dp),
        cornerRadius = Radius.sm,
    )
}

@Composable
private fun CompletedDownloadCard(
    entity: DownloadEntity,
    thumbnailUrl: String,
    onPlay: () -> Unit,
    // Null in Kid Mode — the row stays playable but loses its delete affordance.
    onDelete: (() -> Unit)?,
    // Shown only when more than one download folder is configured.
    folderLabel: String?,
    // Its folder is out right now (SD card ejected): not playable, can't be moved.
    unavailable: Boolean,
    moving: Boolean,
    moveTargets: List<com.fuzzymistborn.jellyjar.data.repository.FolderInfo>,
    onMove: (String) -> Unit,
    // Episodes inside a series: a landscape still and "E01 · Title" instead of a poster and the
    // long "Series · S01E01 · Title".
    compact: Boolean = false,
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
                modifier = if (compact) Modifier.size(width = 112.dp, height = 63.dp) else Modifier.size(width = 80.dp, height = 120.dp),
                aspectRatio = if (compact) 16f / 9f else 2f / 3f,
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
                    if (compact) entity.indexNumber?.let { "E${it.toString().padStart(2, '0')} · ${entity.episodeName()}" } ?: entity.episodeName()
                    else entity.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = OnSurface,
                    maxLines = 2,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (!compact) entity.year?.let {
                        Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                    }
                    entity.runtimeMinutes?.let {
                        Text("${it}m", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                    }
                    Text(entity.preset, style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                    if (entity.sizeBytes > 0) {
                        Text(formatSize(entity.sizeBytes), style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                    }
                }
                folderLabel?.let {
                    Text(
                        if (unavailable) "$it · not available" else it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (unavailable) Error else OnSurfaceMuted,
                    )
                }
                Spacer(Modifier.height(Spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onPlay,
                        enabled = !unavailable && !moving,
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(IconSize.sm),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (unavailable) "Insert SD card" else "Play Offline",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    if (moving) {
                        CircularProgressIndicator(modifier = Modifier.size(IconSize.md), strokeWidth = 2.dp)
                        Text("Moving…", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                    } else if (!unavailable && moveTargets.isNotEmpty()) {
                        var menuOpen by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(
                                onClick = { menuOpen = true },
                                contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
                            ) {
                                Icon(Icons.Default.DriveFileMove, contentDescription = "Move to…", modifier = Modifier.size(IconSize.sm))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                moveTargets.forEach { target ->
                                    DropdownMenuItem(
                                        text = { Text("Move to ${target.label}") },
                                        onClick = { menuOpen = false; onMove(target.uri) },
                                    )
                                }
                            }
                        }
                    }
                    if (onDelete != null) {
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
}

@Composable
private fun FailedDownloadCard(
    entity: DownloadEntity,
    thumbnailUrl: String,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
) {
    Surface(color = SurfaceVariant, shape = RoundedCornerShape(Radius.md)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            DownloadRowPoster(thumbnailUrl)
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

// "Series · S01E01 · Title" → "Title"; a movie's title is already bare.
private fun DownloadEntity.episodeName(): String = title.substringAfterLast(" · ")
