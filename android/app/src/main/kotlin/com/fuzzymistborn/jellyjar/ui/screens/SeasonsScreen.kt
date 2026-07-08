package com.fuzzymistborn.jellyjar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.fuzzymistborn.jellyjar.model.JellyfinItem
import com.fuzzymistborn.jellyjar.ui.theme.*
import com.fuzzymistborn.jellyjar.ui.viewmodel.SeasonsViewModel

@Composable
fun SeasonsScreen(
    seriesId: String,
    onEpisodeClick: (streamUrl: String) -> Unit,
    onBack: () -> Unit,
    viewModel: SeasonsViewModel = hiltViewModel(),
) {
    LaunchedEffect(seriesId) { viewModel.load(seriesId) }

    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().background(Background)) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    if (state.selectedSeasonIndex != null) {
                        viewModel.clearSeasonSelection()
                    } else {
                        onBack()
                    }
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = OnSurface)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when {
                        state.selectedSeasonIndex != null ->
                            "${state.seriesName ?: ""} · ${state.seasons.getOrNull(state.selectedSeasonIndex!!)?.name ?: ""}"
                        else -> state.seriesName ?: "Seasons"
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    color = OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Primary)
                }
            } else if (state.selectedSeasonIndex == null) {
                // Season poster grid
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(state.seasons, key = { _, s -> s.id }) { index, season ->
                        SeasonCard(
                            season = season,
                            posterUrl = viewModel.posterUrl(season.id),
                            onClick = { viewModel.selectSeason(index) },
                        )
                    }
                }
            } else {
                // Episode grid for selected season
                if (state.episodes.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No episodes found", color = OnSurfaceMuted)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 220.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.episodes, key = { it.id }) { episode ->
                            EpisodeCard(
                                episode = episode,
                                thumbnailUrl = viewModel.thumbnailUrl(episode.id),
                                onClick = { onEpisodeClick(viewModel.streamUrl(episode.id)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeasonCard(
    season: JellyfinItem,
    posterUrl: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceVariant),
        ) {
            AsyncImage(
                model = posterUrl,
                contentDescription = season.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = season.name,
            style = MaterialTheme.typography.titleMedium,
            color = OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        season.year?.let {
            Text(
                text = it.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
            )
        }
    }
}

@Composable
private fun EpisodeCard(
    episode: JellyfinItem,
    thumbnailUrl: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceVariant),
        ) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = episode.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Play overlay
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = Color(0x88000000),
                    shape = RoundedCornerShape(50),
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = OnSurface,
                        modifier = Modifier.padding(8.dp).size(28.dp),
                    )
                }
            }
            // Episode number badge
            episode.indexNumber?.let { epNum ->
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                    color = Color(0xCC000000),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        text = "E${epNum.toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurface,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = episode.name,
            style = MaterialTheme.typography.titleMedium,
            color = OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        episode.runtimeMinutes?.let {
            Text(
                text = "${it}m",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
            )
        }
    }
}
