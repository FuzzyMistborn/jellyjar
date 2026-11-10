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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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

    // The series' colour, from the same backdrop URL the series Detail screen uses — usually a
    // cache hit, so the Season screen matches the series it was opened from.
    val accent = rememberDynamicAccentColor(viewModel.backdropUrl().ifBlank { null })

    // "Next up": the first unwatched episode, else the first episode.
    val nextEpisode = remember(visibleEpisodes) {
        visibleEpisodes.firstOrNull { it.userData?.played != true } ?: visibleEpisodes.firstOrNull()
    }

    Box(modifier = Modifier.fillMaxSize().background(BackgroundGradient)) {

        DimmedBackdrop(viewModel.backdropUrl())

        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        ) {
            item(key = "header") {
                ScreenHeader(
                    onBack = onBack,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(horizontal = Spacing.sm, vertical = 4.dp),
                ) {}
            }

            // Hero: season poster beside series name, big season name, episode/watched counts
            // and a Play/Resume button for the next unwatched episode.
            item(key = "hero") {
                BoxWithConstraints {
                    val isWide = maxWidth >= 600.dp
                    Row(
                        modifier = Modifier
                            .padding(horizontal = if (isWide) 32.dp else Spacing.lg)
                            .padding(top = Spacing.lg, bottom = Spacing.xl),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        // A synthetic offline season has no fetchable art — use the series'
                        // poster, the same fallback the Detail screen's seasons row uses.
                        PosterImage(
                            imageUrl = viewModel.posterUrl(if (seasonId.startsWith("offline-season-")) seriesId else seasonId),
                            contentDescription = state.seasonName,
                            modifier = Modifier.width(if (isWide) 160.dp else 110.dp),
                        )
                        Spacer(Modifier.width(if (isWide) Spacing.xl else Spacing.lg))
                        Column(modifier = Modifier.weight(1f)) {
                            state.seriesName?.let {
                                Text(it, style = MaterialTheme.typography.labelLarge, color = OnSurfaceMuted)
                                Spacer(Modifier.height(4.dp))
                            }
                            Text(
                                text = state.seasonName ?: "",
                                style = MaterialTheme.typography.displaySmall.copy(
                                    shadow = androidx.compose.ui.graphics.Shadow(
                                        color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f),
                                        offset = androidx.compose.ui.geometry.Offset(0f, 2f),
                                        blurRadius = 6f,
                                    ),
                                ),
                                color = OnSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (visibleEpisodes.isNotEmpty()) {
                                Spacer(Modifier.height(Spacing.sm))
                                val watched = visibleEpisodes.count { it.userData?.played == true }
                                Text(
                                    text = buildString {
                                        append("${visibleEpisodes.size} episode")
                                        if (visibleEpisodes.size != 1) append("s")
                                        if (watched > 0) append(" · $watched watched")
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = OnSurface.copy(alpha = 0.85f),
                                )
                            }
                            nextEpisode?.let { ep ->
                                val download = state.episodeDownloads[ep.id]
                                val offlinePos = download?.playbackPositionMs ?: 0L
                                val streamPos = ep.userData?.playbackPositionTicks?.div(10_000L) ?: 0L
                                // Same offline-first resolution as EpisodeRow: a finished download
                                // plays locally, otherwise stream when streaming is allowed.
                                val playsOffline = download?.status == DownloadStatus.COMPLETE.name
                                val isResume = if (playsOffline) offlinePos > 0L else streamPos > 0L
                                val playAction: (() -> Unit)? = when {
                                    playsOffline && download != null ->
                                        { { onPlayClick(download.localPath, download.jellyfinId, offlinePos) } }
                                    state.canStream ->
                                        {
                                            {
                                                coroutineScope.launch {
                                                    onStreamClick(viewModel.streamUrl(ep.id), ep.id, streamPos)
                                                }
                                            }
                                        }
                                    else -> null
                                }
                                if (playAction != null) {
                                    val code = ep.indexNumber?.let { e ->
                                        val season = ep.parentIndexNumber?.let { "S${it.toString().padStart(2, '0')}" } ?: ""
                                        " ${season}E${e.toString().padStart(2, '0')}"
                                    } ?: ""
                                    Spacer(Modifier.height(Spacing.md))
                                    PrimaryActionButton(
                                        text = (if (isResume) "Resume" else "Play") + code,
                                        icon = Icons.Default.PlayArrow,
                                        onClick = playAction,
                                        accentColor = accent,
                                    )
                                }
                            }
                        }
                    }
                }
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
                        // Episode count lives in the hero; this row is just the controls.
                        Spacer(Modifier.weight(1f))
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
                                tint = if (state.episodeViewGrid) accent else OnSurface.copy(alpha = 0.4f),
                            )
                        }
                        IconButton(onClick = { if (state.episodeViewGrid) viewModel.toggleEpisodeView() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ViewList,
                                contentDescription = "List view",
                                tint = if (!state.episodeViewGrid) accent else OnSurface.copy(alpha = 0.4f),
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
                                    kidMode = state.kidModeEnabled,
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
                            kidMode = state.kidModeEnabled,
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
                            accentColor = accent,
                        )
                    }
                }

                item(key = "bottom_spacer") { Spacer(Modifier.height(40.dp)) }
            } else if (state.isLoading) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = accent)
                    }
                }
            }
        }

        DownloadErrorSnackbar(state.downloadError) { viewModel.clearDownloadError() }
    }
}
