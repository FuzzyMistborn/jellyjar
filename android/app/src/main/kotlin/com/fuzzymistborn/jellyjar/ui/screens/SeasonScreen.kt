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

    // "Next up" — see nextUpEpisode(). Its downloaded thumbnail (a local file) is the art of last
    // resort for a season that exists only from downloads, where the season and series ids are
    // both synthetic and nothing can be fetched from the server.
    val nextEpisode = remember(visibleEpisodes, state.episodeDownloads) {
        nextUpEpisode(visibleEpisodes, state.episodeDownloads)
    }
    val localThumbnail = remember(visibleEpisodes, state.episodeDownloads, nextEpisode) {
        nextEpisode?.let { state.episodeDownloads[it.id]?.thumbnailUri }
            ?: visibleEpisodes.firstNotNullOfOrNull { state.episodeDownloads[it.id]?.thumbnailUri }
    }

    // Same URL as the hero poster, so the portrait background is a cache hit.
    val seasonPosterUrl = viewModel.posterItemId(seasonId, seriesId)?.let { viewModel.posterUrl(it) }
        ?: localThumbnail

    // Accent from the art actually behind the content: the season poster in portrait, the
    // series backdrop in landscape (the same URL the series Detail screen uses, so the colours
    // match there). Portrait falls back to the backdrop when the poster is missing or fails.
    val backdropUrl = viewModel.backdropUrl().ifBlank { null } ?: localThumbnail
    val portrait = isPortraitWindow()
    val accent = rememberDynamicAccentColor(
        imageUrl = if (portrait) seasonPosterUrl ?: backdropUrl else backdropUrl,
        fallbackImageUrl = if (portrait && seasonPosterUrl != null) backdropUrl else null,
    )

    Box(modifier = Modifier.fillMaxSize().background(BackgroundGradient)) {

        // Portrait windows show the season poster (series poster for a synthetic offline season)
        // as the background instead of a zoomed-in crop of the 16:9 series backdrop.
        DimmedBackdrop(
            imageUrl = backdropUrl,
            portraitImageUrl = seasonPosterUrl,
        )

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
                        // Season poster; series poster for a synthetic offline season; a
                        // downloaded episode thumbnail when the series is synthetic too.
                        PosterImage(
                            imageUrl = seasonPosterUrl ?: "",
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
                                style = MaterialTheme.typography.displaySmall.withHeroShadow(),
                                color = OnSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (visibleEpisodes.isNotEmpty()) {
                                Spacer(Modifier.height(Spacing.sm))
                                val watched = visibleEpisodes.count { isEpisodePlayed(it, state.episodeDownloads) }
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
                                // Same resolution as EpisodeRow: resume the offline position from
                                // the download, else the server's stream position; otherwise play
                                // from the start, offline first.
                                val isComplete = download?.status == DownloadStatus.COMPLETE.name
                                val streamFrom: (Long) -> Unit = { startMs ->
                                    coroutineScope.launch {
                                        onStreamClick(viewModel.streamUrl(ep.id), ep.id, startMs)
                                    }
                                }
                                val resumeAction: (() -> Unit)? = when {
                                    isComplete && download != null && offlinePos > 0L ->
                                        { { onPlayClick(download.localPath, download.jellyfinId, offlinePos) } }
                                    state.canStream && streamPos > 0L -> { { streamFrom(streamPos) } }
                                    else -> null
                                }
                                val playFromStartAction: (() -> Unit)? = when {
                                    isComplete && download != null ->
                                        { { onPlayClick(download.localPath, download.jellyfinId, 0L) } }
                                    state.canStream -> { { streamFrom(0L) } }
                                    else -> null
                                }
                                val isResume = resumeAction != null
                                val playAction = resumeAction ?: playFromStartAction
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
            } else {
                // Offline hides everything that isn't downloaded, which can leave nothing at all.
                item(key = "empty") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = Spacing.xl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Text(
                            text = when {
                                !state.isOnline -> "No downloaded episodes in this season"
                                state.error != null -> state.error!!
                                else -> "No episodes"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = OnSurface,
                        )
                        if (!state.isOnline) {
                            Text(
                                text = "Reconnect to browse and stream the rest of the season.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = OnSurfaceMuted,
                            )
                        }
                    }
                }
            }
        }

        DownloadErrorSnackbar(state.downloadError) { viewModel.clearDownloadError() }
    }
}

// Watched either on the server or offline: offline playback marks the download played right
// away (PlayerViewModel.markFinished), while the server's flag only updates after a sync and a
// reload.
private fun isEpisodePlayed(
    episode: com.fuzzymistborn.jellyjar.model.JellyfinItem,
    downloads: Map<String, com.fuzzymistborn.jellyjar.data.local.DownloadEntity>,
): Boolean = episode.userData?.played == true || downloads[episode.id]?.played == true

// Which episode the Season hero's Play/Resume button offers:
// 1. the furthest-along episode that's partly watched (offline or streamed), so a half-watched
//    E05 wins over a skipped E02;
// 2. else the first unwatched episode after the last watched one;
// 3. else the first unwatched episode (anything skipped earlier);
// 4. else the first episode (whole season watched — rewatch).
// Scoped to this season; cross-season "next up" is the server's Shows/NextUp, not done here.
private fun nextUpEpisode(
    episodes: List<com.fuzzymistborn.jellyjar.model.JellyfinItem>,
    downloads: Map<String, com.fuzzymistborn.jellyjar.data.local.DownloadEntity>,
): com.fuzzymistborn.jellyjar.model.JellyfinItem? {
    fun played(ep: com.fuzzymistborn.jellyjar.model.JellyfinItem) = isEpisodePlayed(ep, downloads)
    fun inProgress(ep: com.fuzzymistborn.jellyjar.model.JellyfinItem): Boolean {
        val offlinePos = downloads[ep.id]?.playbackPositionMs ?: 0L
        val streamTicks = ep.userData?.playbackPositionTicks ?: 0L
        return !played(ep) && (offlinePos > 0L || streamTicks > 0L)
    }
    episodes.lastOrNull(::inProgress)?.let { return it }
    val lastPlayed = episodes.indexOfLast(::played)
    episodes.drop(lastPlayed + 1).firstOrNull { !played(it) }?.let { return it }
    return episodes.firstOrNull { !played(it) } ?: episodes.firstOrNull()
}
