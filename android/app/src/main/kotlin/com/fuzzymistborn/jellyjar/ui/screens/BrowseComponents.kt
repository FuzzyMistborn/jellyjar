package com.fuzzymistborn.jellyjar.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fuzzymistborn.jellyjar.data.repository.FolderInfo
import com.fuzzymistborn.jellyjar.ui.theme.*

// Pieces shared by the Downloads and Ready on Press lists, so the two read as one design.

fun formatSize(bytes: Long): String =
    if (bytes >= 1_073_741_824L) "%.1f GB".format(bytes / 1_073_741_824.0)
    else "%.0f MB".format(bytes / 1_048_576.0)

data class BrowseChip(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
    val icon: ImageVector? = null,
)

// Search box, then one row of filter chips, then the sort chips.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowseControls(
    searchPlaceholder: String,
    query: String,
    onQuery: (String) -> Unit,
    showSearch: Boolean,
    filters: List<BrowseChip>,
    sorts: List<BrowseChip>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(bottom = Spacing.sm)) {
        if (showSearch) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text(searchPlaceholder) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (query.isEmpty()) null else {
                    { IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, contentDescription = "Clear search") } }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.Center) {
            filters.forEach { BrowseChipView(it) }
            Text(
                "Sort",
                style = MaterialTheme.typography.labelMedium,
                color = OnSurfaceMuted,
                modifier = Modifier.padding(start = Spacing.md, end = Spacing.xs).align(Alignment.CenterVertically),
            )
            sorts.forEach { BrowseChipView(it) }
        }
    }
}

@Composable
private fun BrowseChipView(chip: BrowseChip) {
    FilterChip(
        selected = chip.selected,
        onClick = chip.onClick,
        label = { Text(chip.label, style = MaterialTheme.typography.labelMedium) },
        leadingIcon = chip.icon?.let { icon -> { Icon(icon, contentDescription = null, modifier = Modifier.size(IconSize.sm)) } },
        colors = themedChipColors(),
    )
}

fun folderChip(folder: FolderInfo, selected: Boolean, onClick: () -> Unit) =
    BrowseChip(folder.shortLabel, selected, onClick, icon = Icons.Default.Folder)

// A series folded into one row; tap to open it. Same poster-led look in both lists.
@Composable
fun SeriesGroupHeader(
    title: String,
    subtitle: String,
    posterUrl: String,
    expanded: Boolean,
    onClick: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Surface(color = SurfaceVariant, shape = RoundedCornerShape(Radius.md), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            PosterImage(
                imageUrl = posterUrl,
                contentDescription = null,
                modifier = Modifier.size(width = 48.dp, height = 72.dp),
                cornerRadius = Radius.sm,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = OnSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
            }
            trailing()
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = OnSurfaceMuted,
            )
        }
    }
}

// A season inside an open series: label + counts on the left, an action on the right.
@Composable
fun SeasonSectionHeader(
    label: String,
    subtitle: String,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.xl, top = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = Primary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
        }
        trailing()
    }
}

// "Move to…" / "Delete watched" / "Delete" for a group of downloads (a series or one season).
@Composable
fun GroupActionsMenu(
    moveTargets: List<FolderInfo>,
    hasWatched: Boolean,
    moving: Boolean,
    onMove: (String) -> Unit,
    onDeleteWatched: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            if (moving) CircularProgressIndicator(modifier = Modifier.size(IconSize.md), strokeWidth = 2.dp)
            else Icon(Icons.Default.MoreVert, contentDescription = "Actions", tint = OnSurfaceMuted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (!moving) moveTargets.forEach { target ->
                DropdownMenuItem(
                    text = { Text("Move to ${target.shortLabel}") },
                    onClick = { open = false; onMove(target.uri) },
                    leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
                )
            }
            if (hasWatched) DropdownMenuItem(
                text = { Text("Delete watched") },
                onClick = { open = false; onDeleteWatched() },
                leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text("Delete all", color = Error) },
                onClick = { open = false; onDelete() },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Error) },
            )
        }
    }
}
