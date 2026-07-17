package com.fuzzymistborn.jellyjar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.fuzzymistborn.jellyjar.model.DownloadStatus
import com.fuzzymistborn.jellyjar.ui.theme.*
import com.fuzzymistborn.jellyjar.ui.viewmodel.SeasonViewModel
import kotlinx.coroutines.launch

@Composable
fun SeasonScreen(
    seasonId: String,
    seriesId: String,
    onEpisodeClick: (itemId: String) -> Unit,
    onPlayClick: (localPath: String, jellyfinId: String, startMs: Long) -> Unit,
    onStreamClick: (streamUrl: String, jellyfinId: String, startMs: Long) -> Unit,
    onBack: () -> Unit,
    viewModel: SeasonViewModel = hiltViewModel(),
) {
    LaunchedEffect(seasonId) { viewModel.load(seasonId, seriesId) }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    val visibleEpisodes = remember(state.episodes, state.episodeDownloads, state.isOnline) {
        if (state.isOnline) state.episodes
        else state.episodes.filter { ep ->
            state.episodeDownloads[ep.id]?.status == DownloadStatus.COMPLETE.name
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(BackgroundGradient)) {

        // Series backdrop
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(viewModel.backdropUrl())
                .crossfade(300)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            alignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.45f)
                .clip(RectangleShape),
        )

        // Gradient fade
        Box(
            modifier = Modifier.fillMaxSize()
                .background(heroBackdropScrim(scrimStop = 0.25f, solidStop = 0.45f)),
        )

        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        ) {
            // Header
            item(key = "header") {
                ScreenHeader(
                    onBack = onBack,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(horizontal = Spacing.sm, vertical = 4.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        state.seriesName?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium, color = OnSurfaceMuted)
                        }
                        Text(
                            text = state.seasonName ?: "",
                            style = MaterialTheme.typography.headlineSmall,
                            color = OnSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(120.dp))
            }

            // Episode count + view toggle + bulk download
            if (visibleEpisodes.isNotEmpty()) {
                item(key = "episodes_header") {
                    var showBulkMenu by remember { mutableStateOf(false) }
                    var showPresetsFor by remember { mutableStateOf<String?>(null) } // "remaining" | "next5" | "next10"

                    Row(
                        modifier = Modifier.padding(horizontal = 32.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${visibleEpisodes.size} Episodes",
                            style = MaterialTheme.typography.titleMedium,
                            color = Primary,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.isOnline) {
                            Box {
                                IconButton(onClick = { showBulkMenu = true }) {
                                    Icon(Icons.Default.DownloadForOffline, contentDescription = "Bulk download",
                                        tint = OnSurfaceMuted)
                                }
                                DropdownMenu(expanded = showBulkMenu, onDismissRequest = { showBulkMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Download Remaining") },
                                        onClick = { showBulkMenu = false; showPresetsFor = "remaining" },
                                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Download Next 5") },
                                        onClick = { showBulkMenu = false; showPresetsFor = "next5" },
                                        leadingIcon = { Icon(Icons.Default.Filter5, contentDescription = null) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Download Next 10") },
                                        onClick = { showBulkMenu = false; showPresetsFor = "next10" },
                                        leadingIcon = { Icon(Icons.Default.Filter, contentDescription = null) },
                                    )
                                }
                            }
                            if (showPresetsFor != null) {
                                PresetPickerDialog(
                                    onPresetSelected = { preset ->
                                        when (showPresetsFor) {
                                            "remaining" -> viewModel.downloadRemaining(preset)
                                            "next5" -> viewModel.downloadNextN(5, preset)
                                            "next10" -> viewModel.downloadNextN(10, preset)
                                        }
                                        showPresetsFor = null
                                    },
                                    onDismiss = { showPresetsFor = null },
                                )
                            }
                        }
                        IconButton(onClick = { if (!state.episodeViewGrid) viewModel.toggleEpisodeView() }) {
                            Icon(
                                Icons.Default.GridView,
                                contentDescription = "Grid view",
                                tint = if (state.episodeViewGrid) Primary else OnSurface.copy(alpha = 0.4f),
                            )
                        }
                        IconButton(onClick = { if (state.episodeViewGrid) viewModel.toggleEpisodeView() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ViewList,
                                contentDescription = "List view",
                                tint = if (!state.episodeViewGrid) Primary else OnSurface.copy(alpha = 0.4f),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                if (state.episodeViewGrid) {
                    item(key = "episodes_grid") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 32.dp),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            items(visibleEpisodes, key = { it.id }) { episode ->
                                val epDownload = state.episodeDownloads[episode.id]
                                EpisodeThumb(
                                    episode = episode,
                                    thumbnailUrl = epDownload?.thumbnailUri
                                        ?: viewModel.thumbnailUrl(episode.id),
                                    download = epDownload,
                                    isOnline = state.isOnline,
                                    canStream = state.canStream,
                                    onClick = { onEpisodeClick(episode.id) },
                                    onStreamClick = { startMs ->
                                        coroutineScope.launch {
                                            onStreamClick(viewModel.streamUrl(episode.id), episode.id, startMs)
                                        }
                                    },
                                    onDownloadClick = { preset ->
                                        if (preset == "retry") viewModel.retryEpisodeDownload(episode.id)
                                        else viewModel.queueEpisodeDownload(episode, preset)
                                    },
                                    onDeleteClick = { viewModel.deleteDownload(episode.id) },
                                    onPlayOfflineClick = onPlayClick,
                                )
                            }
                        }
                    }
                } else {
                    items(visibleEpisodes, key = { it.id }) { episode ->
                        val epDownload = state.episodeDownloads[episode.id]
                        EpisodeRow(
                            episode = episode,
                            thumbnailUrl = epDownload?.thumbnailUri
                                ?: viewModel.thumbnailUrl(episode.id),
                            download = epDownload,
                            isOnline = state.isOnline,
                            canStream = state.canStream,
                            onClick = { onEpisodeClick(episode.id) },
                            onStreamClick = { startMs ->
                                coroutineScope.launch {
                                    onStreamClick(viewModel.streamUrl(episode.id), episode.id, startMs)
                                }
                            },
                            onDownloadClick = { preset ->
                                if (preset == "retry") viewModel.retryEpisodeDownload(episode.id)
                                else viewModel.queueEpisodeDownload(episode, preset)
                            },
                            onDeleteClick = { viewModel.deleteDownload(episode.id) },
                            onPlayOfflineClick = onPlayClick,
                            modifier = Modifier.padding(horizontal = 32.dp).padding(bottom = 10.dp),
                        )
                    }
                }

                item(key = "bottom_spacer") { Spacer(Modifier.height(40.dp)) }
            } else if (state.isLoading) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Primary)
                    }
                }
            }
        }

        DownloadErrorSnackbar(state.downloadError) { viewModel.clearDownloadError() }
    }
}
