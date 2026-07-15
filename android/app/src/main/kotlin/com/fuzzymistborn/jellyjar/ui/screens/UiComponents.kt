package com.fuzzymistborn.jellyjar.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.fuzzymistborn.jellyjar.ui.theme.*

// Dark-theme colors for FilterChip's selected state — Material3 defaults to a muted
// secondary-container tint, so every chip in the app needs this to pick up the Primary accent.
@Composable
fun themedChipColors(): SelectableChipColors = FilterChipDefaults.filterChipColors(
    selectedContainerColor = Primary,
    selectedLabelColor = OnPrimary,
)

// Dark-theme colors for OutlinedTextField — Material3's defaults assume a light-leaning
// palette, so every text field in the app needs this override to read correctly.
@Composable
fun themedTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Primary,
    unfocusedBorderColor = SurfaceVariant,
    focusedContainerColor = SurfaceVariant,
    unfocusedContainerColor = SurfaceVariant,
    focusedTextColor = OnSurface,
    unfocusedTextColor = OnSurface,
    cursorColor = Primary,
)

// Shared "back arrow + title" screen header — the outer layout modifier (padding,
// statusBarsPadding, etc.) is left to the caller since it varies with each screen's
// surrounding layout; this only standardizes the row's internal content.
@Composable
fun ScreenHeader(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null,
    titleContent: @Composable RowScope.() -> Unit,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = OnSurface)
        }
        Spacer(Modifier.width(Spacing.sm))
        titleContent()
        trailingContent?.invoke()
    }
}

@Composable
fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    ScreenHeader(onBack = onBack, modifier = modifier, trailingContent = trailingContent) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = OnSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun BoxScope.DownloadErrorSnackbar(error: String?, onDismissed: () -> Unit) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        if (error != null) {
            snackbarHostState.showSnackbar(error)
            onDismissed()
        }
    }
    SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
}

// Themed AlertDialog base — Material3's AlertDialog defaults to a light-leaning surface, so
// every confirmation dialog in the app needs this override to read correctly on the dark theme.
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Remove",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = OnSurface) },
        text = {
            if (content != null) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted)
                    content()
                }
            } else {
                Text(message, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted)
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirmLabel, color = Error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        containerColor = SurfaceVariant,
    )
}

@Composable
fun DeleteConfirmDialog(
    title: String = "Remove download?",
    message: String = "This deletes the downloaded file from this device. You can download it again later.",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) = ConfirmDialog(
    title = title,
    message = message,
    confirmLabel = "Remove",
    onConfirm = onConfirm,
    onDismiss = onDismiss,
)

@Composable
fun PresetPickerDialog(
    onPresetSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose Quality", style = MaterialTheme.typography.titleLarge, color = OnSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                listOf("1080p" to "Full HD · ~4GB/hr", "720p" to "HD · ~2GB/hr").forEach { (preset, desc) ->
                    OutlinedButton(
                        onClick = { onPresetSelected(preset) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(preset, style = MaterialTheme.typography.titleMedium)
                            Text(desc, style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        containerColor = SurfaceVariant,
    )
}

@Composable
fun FullScreenLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Primary)
    }
}

@Composable
fun FullScreenError(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            modifier = modifier,
        ) {
            Text(message, style = MaterialTheme.typography.bodyLarge, color = OnSurfaceMuted)
            if (onRetry != null) {
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                ) {
                    Text("Retry")
                }
            }
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            modifier = modifier,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = OnSurfaceMuted,
                modifier = Modifier.size(IconSize.xxl),
            )
            Text(title, style = MaterialTheme.typography.headlineMedium, color = OnSurfaceMuted)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted)
            }
        }
    }
}
