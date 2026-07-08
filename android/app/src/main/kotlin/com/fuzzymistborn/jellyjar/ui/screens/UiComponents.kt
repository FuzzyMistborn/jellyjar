package com.fuzzymistborn.jellyjar.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.fuzzymistborn.jellyjar.ui.theme.*

@Composable
fun PresetPickerDialog(
    onPresetSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose Quality", style = MaterialTheme.typography.titleLarge, color = OnSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = modifier,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = OnSurfaceMuted,
                modifier = Modifier.size(64.dp),
            )
            Text(title, style = MaterialTheme.typography.headlineMedium, color = OnSurfaceMuted)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted)
            }
        }
    }
}
