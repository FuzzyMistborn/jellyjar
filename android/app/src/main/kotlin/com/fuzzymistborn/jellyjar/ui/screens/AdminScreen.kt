package com.fuzzymistborn.jellyjar.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fuzzymistborn.jellyjar.BuildConfig
import com.fuzzymistborn.jellyjar.ui.theme.*
import com.fuzzymistborn.jellyjar.ui.viewmodel.AdminViewModel

// ─── PIN Gate ─────────────────────────────────────────────────────────────────

@Composable
fun PinGateScreen(
    isPinEnabled: Boolean,
    settingsLoaded: Boolean = false,
    onUnlocked: () -> Unit,
    onSkip: () -> Unit,
    viewModel: AdminViewModel = hiltViewModel(),
) {
    LaunchedEffect(isPinEnabled, settingsLoaded) {
        if (settingsLoaded && !isPinEnabled) onSkip()
    }

    if (!settingsLoaded || !isPinEnabled) return

    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize().background(BackgroundGradient),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = Primary, modifier = Modifier.size(IconSize.xl))
            Text("Admin Access", style = MaterialTheme.typography.headlineMedium, color = OnSurface)

            OutlinedTextField(
                value = pin,
                onValueChange = {
                    if (it.length <= 8) {
                        pin = it
                        error = false
                    }
                },
                label = { Text("PIN") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                isError = error,
                supportingText = if (error) { { Text("Incorrect PIN") } } else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = themedTextFieldColors(),
            )

            Button(
                onClick = {
                    viewModel.verifyPin(pin) { ok ->
                        if (ok) onUnlocked()
                        else { error = true; pin = "" }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
            ) {
                Text("Unlock")
            }
        }
    }
}

// ─── Admin screen ─────────────────────────────────────────────────────────────

@Composable
fun AdminScreen(
    onBack: () -> Unit,
    viewModel: AdminViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.checkJellyfinConnection() }

    // No explicit Save button — everything persists as it changes; the Press URL commits on
    // focus loss / test / leaving the screen, so catch system back as well as the header arrow.
    val leaveScreen = {
        viewModel.commitShimUrl()
        viewModel.commitJellyfinUrl()
        onBack()
    }
    androidx.activity.compose.BackHandler(onBack = leaveScreen)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundGradient)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        // Header
        ScreenHeader(
            title = "Settings",
            onBack = leaveScreen,
        )
        Text(
            text = "Jellyfin connection, downloads, and playback preferences",
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceMuted,
            modifier = Modifier.padding(start = 56.dp),
        )

        // ── Jellyfin Server ───────────────────────────────────────────────────
        SettingsCard(
            title = "Jellyfin Server",
            icon = Icons.Default.Dns,
            statusBadge = {
                when {
                    state.isCheckingConnection -> StatusChip(label = "Checking…", color = OnSurfaceMuted)
                    state.isAuthenticated -> StatusChip(label = "Connected", color = Success)
                    state.hasCredentials -> StatusChip(label = "Unreachable", color = Warning)
                    else -> StatusChip(label = "Not connected", color = OnSurfaceMuted)
                }
            },
        ) {
            SettingsTextField(
                label = "Server URL",
                placeholder = "192.168.1.x:8096",
                value = state.jellyfinUrl,
                onValueChange = viewModel::setJellyfinUrl,
                onFocusLost = viewModel::commitJellyfinUrl,
            )
            OutlinedButton(
                onClick = viewModel::discoverJellyfinServers,
                enabled = !state.isDiscovering,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isDiscovering) {
                    CircularProgressIndicator(modifier = Modifier.size(IconSize.sm), strokeWidth = 2.dp)
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Searching…")
                } else {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(IconSize.sm))
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Discover")
                }
            }
            state.discoverError?.let { err ->
                Text(err, style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
            }
            if (state.discoveredServers.size > 1) {
                Surface(
                    color = Background,
                    shape = RoundedCornerShape(Radius.sm),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(Spacing.sm)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Select a server",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceMuted,
                                modifier = Modifier.padding(start = Spacing.sm),
                            )
                            IconButton(onClick = viewModel::dismissDiscoveredServers) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(IconSize.sm))
                            }
                        }
                        state.discoveredServers.forEach { server ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.selectDiscoveredServer(server) }
                                    .padding(Spacing.sm),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Dns, contentDescription = null,
                                    tint = Primary, modifier = Modifier.size(IconSize.sm))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(server.name, style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                                    Text(server.address, style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                                }
                            }
                        }
                    }
                }
            }
            SettingsTextField(
                label = "Username",
                value = state.username,
                onValueChange = viewModel::setUsername,
            )
            SettingsTextField(
                label = "Password",
                value = state.password,
                onValueChange = viewModel::setPassword,
                isPassword = true,
            )
            Button(
                onClick = viewModel::authenticate,
                enabled = !state.isAuthenticating,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
            ) {
                if (state.isAuthenticating) {
                    CircularProgressIndicator(modifier = Modifier.size(IconSize.sm), color = OnPrimary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Connecting…")
                } else {
                    Icon(
                        if (state.isAuthenticated) Icons.Default.CheckCircle else Icons.Default.Link,
                        contentDescription = null,
                        modifier = Modifier.size(IconSize.md),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(if (state.isAuthenticated) "Re-authenticate" else "Connect")
                }
            }
            OutlinedButton(
                onClick = { if (state.isQuickConnecting) viewModel.cancelQuickConnect() else viewModel.startQuickConnect() },
                enabled = !state.isAuthenticating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isQuickConnecting) {
                    CircularProgressIndicator(modifier = Modifier.size(IconSize.sm), strokeWidth = 2.dp)
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Cancel Quick Connect")
                } else {
                    Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(IconSize.md))
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Login with Quick Connect")
                }
            }
            AnimatedVisibility(visible = state.quickConnectCode != null) {
                state.quickConnectCode?.let { code ->
                    Surface(
                        color = Primary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(Radius.sm),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.md),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "Enter this code on your Jellyfin server or another signed-in device",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceMuted,
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            Text(
                                code,
                                style = MaterialTheme.typography.headlineMedium,
                                color = Primary,
                            )
                        }
                    }
                }
            }
            AnimatedVisibility(visible = state.quickConnectError != null) {
                state.quickConnectError?.let { err ->
                    Surface(
                        color = Error.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(Radius.sm),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(Spacing.md),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null,
                                tint = Error, modifier = Modifier.size(IconSize.sm))
                            Text(err, style = MaterialTheme.typography.bodySmall, color = Error)
                        }
                    }
                }
            }
            AnimatedVisibility(visible = state.authError != null) {
                state.authError?.let { err ->
                    Surface(
                        color = Error.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(Radius.sm),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(Spacing.md),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null,
                                tint = Error, modifier = Modifier.size(IconSize.sm))
                            Text(err, style = MaterialTheme.typography.bodySmall, color = Error)
                        }
                    }
                }
            }
        }

        // ── Press ─────────────────────────────────────────────────────────────
        SettingsCard(
            title = "Press",
            icon = Icons.Default.Movie,
            statusBadge = {
                when (state.shimOk) {
                    true -> StatusChip(
                        label = state.shimVersion?.let { "Reachable · v$it" } ?: "Reachable",
                        color = Success,
                    )
                    false -> StatusChip(label = "Unreachable", color = Error)
                    null -> {}
                }
            },
        ) {
            SettingsTextField(
                label = "Press URL",
                placeholder = "192.168.1.x:8090",
                value = state.shimUrl,
                onValueChange = viewModel::setShimUrl,
                onFocusLost = viewModel::commitShimUrl,
            )
            OutlinedButton(
                onClick = viewModel::testShim,
                enabled = !state.isTestingShim,
                modifier = Modifier.fillMaxWidth(),
                colors = when (state.shimOk) {
                    true -> ButtonDefaults.outlinedButtonColors(contentColor = Success)
                    false -> ButtonDefaults.outlinedButtonColors(contentColor = Error)
                    null -> ButtonDefaults.outlinedButtonColors()
                },
            ) {
                if (state.isTestingShim) {
                    CircularProgressIndicator(modifier = Modifier.size(IconSize.sm), strokeWidth = 2.dp)
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Testing…")
                } else {
                    Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(IconSize.sm))
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Test Connection")
                }
            }
        }

        // ── Downloads ─────────────────────────────────────────────────────────
        SettingsCard(title = "Downloads", icon = Icons.Default.Download) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val folderPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
            ) { uri ->
                if (uri != null) {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    viewModel.setDownloadPath(uri.toString())
                }
            }

            // Folder row
            Surface(
                color = Background,
                shape = RoundedCornerShape(Radius.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(Spacing.md),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null,
                            tint = Primary, modifier = Modifier.size(IconSize.md))
                        Column {
                            Text("Download Folder", style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                            if (state.downloadPath.isNotBlank()) {
                                val displayPath = remember(state.downloadPath) {
                                    runCatching {
                                        android.net.Uri.parse(state.downloadPath).lastPathSegment
                                            ?.replace("primary:", "/storage/emulated/0/")
                                            ?: state.downloadPath
                                    }.getOrDefault(state.downloadPath)
                                }
                                Text(displayPath, style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                            } else {
                                Text("Not set", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                            }
                        }
                    }
                    TextButton(onClick = { folderPickerLauncher.launch(null) }) {
                        Text(if (state.downloadPath.isBlank()) "Choose" else "Change")
                    }
                }
            }

            state.storageInfo?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
            }

            SettingsToggleRow(
                title = "Wi-Fi Only",
                subtitle = "Pause downloads on mobile data",
                checked = state.wifiOnly,
                onCheckedChange = { viewModel.setWifiOnly(it) },
            )
            SettingsToggleRow(
                title = "Force Offline Mode",
                subtitle = "Browse only what's downloaded, even when online",
                checked = state.forceOfflineMode,
                onCheckedChange = { viewModel.setForceOfflineMode(it) },
            )

            // Simultaneous downloads (queue concurrency)
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = Spacing.sm)) {
                    Text("Simultaneous Downloads", style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(
                        "Items transcoding at once",
                        style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted,
                    )
                }
                SingleChoiceSegmentedButtonRow {
                    listOf(1, 2).forEachIndexed { index, value ->
                        SegmentedButton(
                            selected = state.maxConcurrentDownloads == value,
                            onClick = { viewModel.setMaxConcurrentDownloads(value) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                        ) {
                            Text("$value")
                        }
                    }
                }
            }
        }

        // ── Home Screen ───────────────────────────────────────────────────────
        SettingsCard(title = "Home Screen", icon = Icons.Default.Home, dense = true) {
            SettingsToggleRow(
                title = "Continue Watching",
                checked = state.showContinueWatching,
                onCheckedChange = { viewModel.setShowContinueWatching(it) },
            )
            SettingsToggleRow(
                title = "Recently Added",
                checked = state.showRecentlyAdded,
                onCheckedChange = { viewModel.setShowRecentlyAdded(it) },
            )
            SettingsToggleRow(
                title = "My List",
                checked = state.showMyList,
                onCheckedChange = { viewModel.setShowMyList(it) },
            )
            SettingsToggleRow(
                title = "Genre Filter",
                subtitle = "Genre chips when browsing a library",
                checked = state.genreFilterEnabled,
                onCheckedChange = { viewModel.setGenreFilterEnabled(it) },
            )
        }

        // ── Playback ──────────────────────────────────────────────────────────
        SettingsCard(title = "Playback", icon = Icons.Default.PlayCircle, dense = true) {
            SettingsToggleRow(
                title = "Auto-play Next Episode",
                checked = state.autoPlayNextEpisode,
                onCheckedChange = { viewModel.setAutoPlayNextEpisode(it) },
            )
            SettingsToggleRow(
                title = "Playback Gestures",
                subtitle = "Double-tap to seek, swipe for brightness and volume",
                checked = state.playbackGesturesEnabled,
                onCheckedChange = { viewModel.setPlaybackGesturesEnabled(it) },
            )
            SettingsToggleRow(
                title = "Skip Intro / Credits",
                checked = state.introSkipEnabled,
                onCheckedChange = { viewModel.setIntroSkipEnabled(it) },
            )
            SettingsToggleRow(
                title = "Scrubbing Previews",
                subtitle = "Thumbnails when seeking (streaming only)",
                checked = state.trickplayEnabled,
                onCheckedChange = { viewModel.setTrickplayEnabled(it) },
            )
            SettingsToggleRow(
                title = "Playback Stats",
                subtitle = "Direct Play / Transcoding overlay",
                checked = state.playbackStatsEnabled,
                onCheckedChange = { viewModel.setPlaybackStatsEnabled(it) },
            )
            SettingsToggleRow(
                title = "Stream over Cellular",
                subtitle = "Browsing always works",
                checked = state.streamOverCellular,
                onCheckedChange = { viewModel.setStreamOverCellular(it) },
            )
            HorizontalDivider(color = Background, modifier = Modifier.padding(vertical = Spacing.xs))
            Text(
                "Streaming Quality",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurface,
            )
            Text(
                "Caps the streamed bitrate and resolution; Jellyfin transcodes down when the source exceeds it. Downloads always use the preset chosen at queue time.",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.xs),
            ) {
                com.fuzzymistborn.jellyjar.model.PlaybackQuality.entries.forEach { quality ->
                    FilterChip(
                        selected = state.playbackQuality == quality,
                        onClick = { viewModel.setPlaybackQuality(quality) },
                        label = { Text(quality.label, style = MaterialTheme.typography.labelMedium) },
                        colors = themedChipColors(),
                    )
                }
            }
        }

        // ── Kid Mode ──────────────────────────────────────────────────────────
        SettingsCard(
            title = "Kid Mode",
            icon = Icons.Default.ChildCare,
            statusBadge = {
                // Non-blocking nudge: without a PIN, PinGateScreen lets anyone straight through
                // to Settings, so Kid Mode can be switched off by whoever finds the long-press.
                if (state.kidModeEnabled && !state.isPinEnabled) {
                    StatusChip(label = "Set a PIN", color = Warning)
                }
            },
        ) {
            SettingsToggleRow(
                title = "Kid Mode",
                subtitle = "Strip the app down for a kid's tablet",
                checked = state.kidModeEnabled,
                onCheckedChange = { viewModel.setKidMode(it) },
            )
            Text(
                "Hides the settings gear, search, and every download and storage control, and turns " +
                    "off player gestures, the stats overlay and scrub previews. To get back here, " +
                    "press and hold the \"JellyJar\" title on the home screen.",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
            )
            Text(
                "Kid Mode does not filter what's in the library. Restrict library access and set a " +
                    "maximum parental rating on the Jellyfin user this app signs in as — Jellyfin " +
                    "enforces that on the server, so it holds no matter what the app does.",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
            )
            if (state.kidModeEnabled) {
                Text(
                    "Turning Kid Mode off restores the hidden controls but leaves the other settings " +
                        "where it put them — adjust them individually if you want them back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted,
                )
            }
        }

        // ── Admin PIN ─────────────────────────────────────────────────────────
        SettingsCard(
            title = "Admin PIN",
            icon = Icons.Default.Lock,
            statusBadge = {
                StatusChip(
                    label = if (state.isPinEnabled) "Enabled" else "Disabled",
                    color = if (state.isPinEnabled) Primary else OnSurfaceMuted,
                )
            },
        ) {
            var newPin by remember { mutableStateOf("") }
            var pinVisible by remember { mutableStateOf(false) }

            OutlinedTextField(
                value = newPin,
                onValueChange = { if (it.length <= 8) newPin = it },
                label = { Text("New PIN") },
                placeholder = { Text("Leave blank to disable", color = OnSurfaceMuted) },
                visualTransformation = if (pinVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                trailingIcon = {
                    IconButton(onClick = { pinVisible = !pinVisible }) {
                        Icon(
                            if (pinVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (pinVisible) "Hide PIN" else "Show PIN",
                            tint = OnSurfaceMuted,
                        )
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = themedTextFieldColors(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Button(
                    onClick = { viewModel.savePin(newPin); newPin = "" },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(IconSize.sm))
                    Spacer(Modifier.width(6.dp))
                    Text("Save PIN")
                }
                if (state.isPinEnabled) {
                    OutlinedButton(
                        onClick = viewModel::clearPin,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Error),
                    ) {
                        Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(IconSize.sm))
                        Spacer(Modifier.width(6.dp))
                        Text("Clear")
                    }
                }
            }
        }

        // ── Active Jobs ───────────────────────────────────────────────────────
        AnimatedVisibility(visible = state.activeJobs.isNotEmpty()) {
            SettingsCard(title = "Active Jobs", icon = Icons.Default.Sync) {
                state.activeJobs.forEach { job ->
                    Surface(
                        color = Background,
                        shape = RoundedCornerShape(Radius.sm),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(Spacing.md)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        job.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = OnSurface,
                                        maxLines = 1,
                                    )
                                    Text(
                                        "${job.status.lowercase().replaceFirstChar { it.uppercase() }} · ${job.preset}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OnSurfaceMuted,
                                    )
                                }
                                Text(
                                    "${job.progress.toInt()}%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Primary,
                                    modifier = Modifier.padding(start = Spacing.md),
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            val animatedProgress by animateFloatAsState(
                                targetValue = (job.progress / 100f).coerceIn(0f, 1f), label = "jobProgress",
                            )
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier.fillMaxWidth(),
                                color = Primary,
                                trackColor = SurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.sm))

        Text(
            "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceMuted,
            modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

// ─── Shared composables ────────────────────────────────────────────────────────

/**
 * Card for server configuration and storage — roomy padding, a divider under the header.
 *
 * [dense] tightens the padding and drops the header divider; use it for cards that are just a
 * list of [SettingsToggleRow]s, where the switches already delineate the rows and the extra
 * vertical space reads as empty.
 */
@Composable
private fun SettingsCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    statusBadge: @Composable RowScope.() -> Unit = {},
    dense: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = SurfaceVariant,
        shape = RoundedCornerShape(Radius.lg),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = Spacing.lg,
                vertical = if (dense) Spacing.md else Spacing.lg,
            ),
            verticalArrangement = Arrangement.spacedBy(if (dense) Spacing.xs else Spacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (icon != null) {
                        Surface(color = Primary.copy(alpha = 0.15f), shape = RoundedCornerShape(Radius.sm)) {
                            Icon(
                                icon,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.padding(6.dp).size(IconSize.sm),
                            )
                        }
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium, color = SectionHeading)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { statusBadge() }
            }
            if (!dense) HorizontalDivider(color = Background)
            content()
        }
    }
}

@Composable
private fun StatusChip(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(Radius.pill),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * Compact toggle row: the whole row is the tap target, and [subtitle] is optional so a toggle
 * whose title already says everything ("Continue Watching" under a "Home Screen" card) takes one
 * line instead of two.
 */
@Composable
private fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .clickable { onCheckedChange(!checked) }
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = Spacing.sm)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = OnSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = OnPrimary, checkedTrackColor = Primary),
        )
    }
}

@Composable
private fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    isPassword: Boolean = false,
    onFocusLost: (() -> Unit)? = null,
) {
    var passwordVisible by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = if (placeholder.isNotBlank()) { { Text(placeholder, color = OnSurfaceMuted) } } else null,
        visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation()
                               else VisualTransformation.None,
        keyboardOptions = if (isPassword) KeyboardOptions(keyboardType = KeyboardType.Password)
                          else KeyboardOptions.Default,
        trailingIcon = if (isPassword) {
            {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        tint = OnSurfaceMuted,
                    )
                }
            }
        } else null,
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                if (focusState.isFocused) hadFocus = true
                else if (hadFocus) onFocusLost?.invoke()
            },
        colors = themedTextFieldColors(),
    )
}
