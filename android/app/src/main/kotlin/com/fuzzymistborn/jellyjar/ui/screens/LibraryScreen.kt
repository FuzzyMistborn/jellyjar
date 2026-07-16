package com.fuzzymistborn.jellyjar.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.fuzzymistborn.jellyjar.model.DownloadStatus
import com.fuzzymistborn.jellyjar.model.JellyfinItem
import com.fuzzymistborn.jellyjar.model.JellyfinLibrary
import com.fuzzymistborn.jellyjar.model.SortOrder
import com.fuzzymistborn.jellyjar.ui.theme.*
import com.fuzzymistborn.jellyjar.ui.viewmodel.LibraryViewModel

@Composable
fun LibraryScreen(
    onItemClick: (JellyfinItem) -> Unit,
    onPlayOffline: (String) -> Unit,
    onAdminClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    BackHandler(enabled = state.globalSearchActive) {
        viewModel.closeGlobalSearch()
    }
    BackHandler(enabled = state.selectedLibrary != null || state.showingDownloads) {
        viewModel.goHome()
    }

    val isHome = state.selectedLibrary == null && !state.showingDownloads

    Box(modifier = Modifier.fillMaxSize().background(BackgroundGradient)) {

        if (state.selectedLibrary != null) {
            state.featuredItem?.let { featured ->
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(viewModel.backdropUrl(featured.id))
                        .memoryCacheKey("backdrop_${featured.id}")
                        .diskCacheKey("backdrop_${featured.id}")
                        .crossfade(300)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(28.dp),
                )
                Box(modifier = Modifier.fillMaxSize().background(featuredBackdropScrim()))
                Box(modifier = Modifier.fillMaxSize().background(vignetteScrim()))
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {

            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.selectedLibrary != null || state.showingDownloads) {
                        IconButton(onClick = { viewModel.goHome() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Home", tint = OnSurface)
                        }
                        Spacer(Modifier.width(Spacing.sm))
                    }
                    Text(
                        text = when {
                            state.showingDownloads -> "Downloads"
                            state.selectedLibrary != null -> state.selectedLibrary!!
                            else -> "JellyJar"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        color = OnSurface,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    if (!state.isOnline) {
                        Surface(color = Warning.copy(alpha = 0.15f), shape = RoundedCornerShape(Radius.pill)) {
                            Row(
                                modifier = Modifier.padding(horizontal = Spacing.md, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.WifiOff, contentDescription = null,
                                    tint = Warning, modifier = Modifier.size(IconSize.sm))
                                Text("Offline", style = MaterialTheme.typography.labelSmall,
                                    color = Warning)
                            }
                        }
                    }
                    if (isHome) {
                        IconButton(onClick = { viewModel.openGlobalSearch() }) {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = OnSurfaceMuted)
                        }
                    }
                    IconButton(onClick = onAdminClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = OnSurfaceMuted)
                    }
                }
            }

            // Global search — shown on home screen
            if (isHome && state.globalSearchActive) {
                OutlinedTextField(
                    value = state.globalSearchQuery,
                    onValueChange = viewModel::setGlobalSearchQuery,
                    placeholder = { Text("Search movies, shows, episodes…", color = OnSurfaceMuted) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = OnSurfaceMuted) },
                    trailingIcon = {
                        IconButton(onClick = { viewModel.closeGlobalSearch() }) {
                            Icon(Icons.Default.Close, contentDescription = "Close search", tint = OnSurfaceMuted)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.xl)
                        .padding(bottom = Spacing.sm),
                    colors = themedTextFieldColors(),
                    shape = RoundedCornerShape(Radius.md),
                )
                when {
                    state.isGlobalSearching -> SkeletonGrid()
                    state.globalSearchQuery.isBlank() -> Unit
                    state.globalSearchResults.isEmpty() -> EmptyState(
                        icon = Icons.Default.SearchOff,
                        title = "No results for \"${state.globalSearchQuery}\"",
                    )
                    else -> MediaGrid(
                        items = state.globalSearchResults,
                        isRefreshing = false,
                        downloadStatuses = state.downloadStatuses,
                        downloadProgress = state.downloadProgress,
                        onItemClick = onItemClick,
                        onItemFocus = viewModel::setFeatured,
                        onRefresh = {},
                        viewModel = viewModel,
                    )
                }
                return@Column
            }

            // Search bar — shown when inside a library
            if (state.selectedLibrary != null) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::setSearchQuery,
                    placeholder = { Text("Search ${state.selectedLibrary}…", color = OnSurfaceMuted) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = OnSurfaceMuted) },
                    trailingIcon = {
                        if (state.searchQuery.isNotBlank()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = OnSurfaceMuted)
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.xl)
                        .padding(bottom = Spacing.sm),
                    colors = themedTextFieldColors(),
                    shape = RoundedCornerShape(Radius.md),
                )

                // Sort chips
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Spacing.xl),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.padding(bottom = Spacing.sm),
                ) {
                    val options = listOf(
                        SortOrder.DEFAULT to "Default",
                        SortOrder.ALPHABETICAL to "A–Z",
                        SortOrder.YEAR_DESC to "Newest",
                        SortOrder.YEAR_ASC to "Oldest",
                        SortOrder.RATING_DESC to "Top Rated",
                        SortOrder.UNWATCHED_FIRST to "Unwatched",
                    )
                    items(options) { (order, label) ->
                        FilterChip(
                            selected = state.sortOrder == order,
                            onClick = { viewModel.setSortOrder(order) },
                            label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                            colors = themedChipColors(),
                        )
                    }
                }

                // Genre filter chips
                if (state.genres.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Spacing.xl),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        modifier = Modifier.padding(bottom = Spacing.sm),
                    ) {
                        item {
                            FilterChip(
                                selected = state.selectedGenre == null,
                                onClick = { viewModel.setGenre(null) },
                                label = { Text("All Genres", style = MaterialTheme.typography.labelSmall) },
                                colors = themedChipColors(),
                            )
                        }
                        items(state.genres) { genre ->
                            FilterChip(
                                selected = state.selectedGenre == genre,
                                onClick = {
                                    viewModel.setGenre(if (state.selectedGenre == genre) null else genre)
                                },
                                label = { Text(genre, style = MaterialTheme.typography.labelSmall) },
                                colors = themedChipColors(),
                            )
                        }
                    }
                }
            }

            // Content
            when {
                !state.showingDownloads && state.selectedLibrary == null -> {
                    HomeScreen(
                        libraries = state.libraries,
                        isOnline = state.isOnline,
                        jellyfinAvailable = state.jellyfinAvailable,
                        resumeItems = state.resumeItems,
                        recentlyAdded = state.recentlyAdded,
                        favoriteIds = state.favoriteIds,
                        favoriteItems = state.favoriteItems,
                        downloadStatuses = state.downloadStatuses,
                        showContinueWatching = state.showContinueWatching,
                        showRecentlyAdded = state.showRecentlyAdded,
                        showMyList = state.showMyList,
                        onLibraryClick = { lib -> viewModel.selectLibrary(lib.name) },
                        onDownloadsClick = onDownloadsClick,
                        onResumeItemClick = onItemClick,
                        onItemClick = onItemClick,
                        viewModel = viewModel,
                    )
                }
                state.showingDownloads -> {
                    if (state.items.isEmpty() && !state.isLoading) {
                        EmptyState(
                            icon = Icons.Default.DownloadDone,
                            title = "No downloads yet",
                            subtitle = "Download movies and shows to watch offline.",
                            actionLabel = "Browse Library",
                            onAction = { viewModel.goHome() },
                        )
                    } else {
                        MediaGrid(
                            items = state.displayItems,
                            isRefreshing = false,
                            downloadStatuses = state.downloadStatuses,
                            downloadProgress = state.downloadProgress,
                            onItemClick = { item ->
                                val localPath = item.mediaSources?.firstOrNull()?.path
                                if (localPath != null) onPlayOffline(localPath) else onItemClick(item)
                            },
                            onItemFocus = viewModel::setFeatured,
                            onRefresh = {},
                            viewModel = viewModel,
                        )
                    }
                }
                state.isLoading -> SkeletonGrid()
                state.displayItems.isEmpty() -> {
                    EmptyState(
                        icon = if (!state.isOnline || !state.jellyfinAvailable) Icons.Default.WifiOff
                               else Icons.Default.VideoLibrary,
                        title = when {
                            state.searchQuery.isNotBlank() -> "No results for \"${state.searchQuery}\""
                            !state.isOnline || !state.jellyfinAvailable -> "No downloaded content"
                            else -> "No content found"
                        },
                        subtitle = if (!state.isOnline || !state.jellyfinAvailable) {
                            "Only downloaded items are available while offline."
                        } else null,
                    )
                }
                else -> {
                    MediaGrid(
                        items = state.displayItems,
                        isRefreshing = state.isRefreshing,
                        downloadStatuses = state.downloadStatuses,
                        downloadProgress = state.downloadProgress,
                        onItemClick = onItemClick,
                        onItemFocus = viewModel::setFeatured,
                        onRefresh = viewModel::refresh,
                        viewModel = viewModel,
                    )
                }
            }
        }
    }
}

// ─── Home Screen ──────────────────────────────────────────────────────────────

@Composable
private fun HomeScreen(
    libraries: List<JellyfinLibrary>,
    isOnline: Boolean,
    jellyfinAvailable: Boolean,
    resumeItems: List<JellyfinItem>,
    recentlyAdded: List<JellyfinItem>,
    favoriteIds: Set<String>,
    favoriteItems: List<JellyfinItem>,
    downloadStatuses: Map<String, String>,
    showContinueWatching: Boolean,
    showRecentlyAdded: Boolean,
    showMyList: Boolean,
    onLibraryClick: (JellyfinLibrary) -> Unit,
    onDownloadsClick: () -> Unit,
    onResumeItemClick: (JellyfinItem) -> Unit,
    onItemClick: (JellyfinItem) -> Unit,
    viewModel: LibraryViewModel,
) {
    val jellyfinUrl = viewModel.state.collectAsStateWithLifecycle().value.jellyfinUrl

    val gridState = rememberLazyGridState()
    val hasTopRows = (showContinueWatching && resumeItems.isNotEmpty()) ||
                     (showRecentlyAdded && recentlyAdded.isNotEmpty()) ||
                     (showMyList && favoriteItems.isNotEmpty())
    LaunchedEffect(hasTopRows) {
        if (hasTopRows) gridState.animateScrollToItem(0)
    }

    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 280.dp),
        contentPadding = PaddingValues(
            start = Spacing.xl,
            end = Spacing.xl,
            top = Spacing.sm,
            bottom = Spacing.sm + navBarPadding.calculateBottomPadding(),
        ),
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (isOnline && !jellyfinAvailable) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Surface(
                    color = Warning.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(Radius.md),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.CloudOff, contentDescription = null,
                            tint = Warning, modifier = Modifier.size(IconSize.sm))
                        Text("Jellyfin server unreachable — retrying…",
                            style = MaterialTheme.typography.bodySmall, color = Warning)
                    }
                }
            }
        }

        // Continue Watching row
        if (showContinueWatching && resumeItems.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                HomeSectionRow(title = "Continue Watching") {
                    items(resumeItems, key = { it.id }) { item ->
                        ResumeCard(
                            item = item,
                            imageUrl = viewModel.posterUrl(item.id),
                            onClick = { onResumeItemClick(item) },
                        )
                    }
                }
            }
        }

        // Recently Added row
        if (showRecentlyAdded && recentlyAdded.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                HomeSectionRow(title = "Recently Added") {
                    items(recentlyAdded, key = { it.id }) { item ->
                        PosterCard(
                            item = item,
                            imageUrl = viewModel.posterUrl(item.id),
                            onClick = { onItemClick(item) },
                        )
                    }
                }
            }
        }

        // My List row
        if (showMyList && favoriteItems.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                HomeSectionRow(title = "My List") {
                    items(favoriteItems, key = { it.id }) { item ->
                        PosterCard(
                            item = item,
                            imageUrl = viewModel.posterUrl(item.id),
                            onClick = { onItemClick(item) },
                        )
                    }
                }
            }
        }

        if (libraries.isNotEmpty()) {
            items(libraries, key = { it.id }) { lib ->
                LibraryTile(library = lib, jellyfinUrl = jellyfinUrl, onClick = { onLibraryClick(lib) })
            }
        }
        item {
            DownloadsTile(onClick = onDownloadsClick)
        }
    }
}

@Composable
private fun ResumeCard(
    item: JellyfinItem,
    imageUrl: String,
    onClick: () -> Unit,
) {
    val progressFraction = item.userData?.playbackPositionTicks?.let { ticks ->
        val runtimeTicks = item.runTimeTicks ?: return@let null
        if (runtimeTicks > 0) (ticks.toFloat() / runtimeTicks).coerceIn(0f, 0.99f) else null
    }
    Column(modifier = Modifier.width(130.dp)) {
        PosterImage(
            imageUrl = imageUrl,
            contentDescription = item.name,
            modifier = Modifier.fillMaxWidth(),
            cacheKey = "poster_${item.id}",
            onClick = onClick,
        ) {
            if (progressFraction != null) {
                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),
                    color = Primary,
                    trackColor = Color.Transparent,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = item.displayTitle,
            style = MaterialTheme.typography.labelMedium,
            color = OnSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HomeSectionRow(
    title: String,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = SectionHeading,
            modifier = Modifier.padding(bottom = Spacing.md),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            content = content,
        )
    }
}

@Composable
private fun PosterCard(
    item: JellyfinItem?,
    imageUrl: String,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.width(110.dp)) {
        PosterImage(
            imageUrl = imageUrl,
            contentDescription = item?.name,
            modifier = Modifier.fillMaxWidth(),
            cacheKey = "poster_${item?.id ?: imageUrl}",
            onClick = onClick,
        )
        if (item != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = item.displayTitle,
                style = MaterialTheme.typography.labelMedium,
                color = OnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LibraryTile(library: JellyfinLibrary, jellyfinUrl: String, onClick: () -> Unit) {
    val libraryId = library.id
    var usePrimary by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(Radius.md))
            .background(SurfaceVariant)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(
                    if (usePrimary)
                        "$jellyfinUrl/Items/$libraryId/Images/Primary?fillWidth=600&quality=85"
                    else
                        "$jellyfinUrl/Items/$libraryId/Images/Backdrop/0?fillWidth=600&quality=85"
                )
                .memoryCacheKey("lib_${libraryId}_${if (usePrimary) "primary" else "backdrop"}")
                .diskCacheKey("lib_${libraryId}_${if (usePrimary) "primary" else "backdrop"}")
                .crossfade(300)
                .build(),
            contentDescription = library.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            onError = { if (!usePrimary) usePrimary = true },
        )
        Box(modifier = Modifier.fillMaxSize().background(tileScrim()))
        Text(
            text = library.name ?: "",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp),
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).padding(20.dp),
        )
    }
}

@Composable
private fun DownloadsTile(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(Radius.md))
            .background(SurfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Icon(Icons.Default.DownloadDone, contentDescription = null,
                tint = Primary, modifier = Modifier.size(IconSize.xl))
            Text("Downloads",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = OnSurface)
            Text("Offline content", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
        }
    }
}

// ─── Media grid ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaGrid(
    items: List<JellyfinItem>,
    isRefreshing: Boolean,
    downloadStatuses: Map<String, String>,
    downloadProgress: Map<String, Float> = emptyMap(),
    onItemClick: (JellyfinItem) -> Unit,
    onItemFocus: (JellyfinItem) -> Unit,
    onRefresh: () -> Unit,
    viewModel: LibraryViewModel,
) {
    val gridState = rememberLazyGridState()
    val state by viewModel.state.collectAsStateWithLifecycle()

    val shouldLoadMore = remember {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = gridState.layoutInfo.totalItemsCount
            lastVisible >= total - 8
        }
    }
    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value) viewModel.loadMore()
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 140.dp),
            contentPadding = PaddingValues(
                start = Spacing.xl,
                end = Spacing.xl,
                top = Spacing.sm,
                bottom = Spacing.sm + navBarPadding.calculateBottomPadding(),
            ),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { it.id }) { item ->
                MediaCard(
                    item = item,
                    imageUrl = viewModel.posterUrl(item.id),
                    downloadStatus = downloadStatuses[item.id],
                    downloadProgress = downloadProgress[item.id],
                    onClick = { onItemClick(item) },
                    onFocus = { onItemFocus(item) },
                )
            }
            if (state.isLoadingMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(Spacing.lg), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Primary, modifier = Modifier.size(IconSize.lg))
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaCard(
    item: JellyfinItem,
    imageUrl: String,
    downloadStatus: String?,
    downloadProgress: Float? = null,
    onClick: () -> Unit,
    onFocus: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .clickable { onFocus(); onClick() }
    ) {
        PosterImage(
            imageUrl = imageUrl,
            contentDescription = item.name,
            modifier = Modifier.fillMaxWidth(),
            cacheKey = "poster_${item.id}",
        ) {
            item.communityRating?.let { rating ->
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    color = ScrimStrong,
                    shape = RoundedCornerShape(Radius.sm),
                ) {
                    Text(
                        text = "★ ${"%.1f".format(rating)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Accent,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }
            // Watched indicator
            if (item.userData?.played == true) {
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                    color = ScrimStrong,
                    shape = RoundedCornerShape(Radius.pill),
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Watched",
                        tint = Primary,
                        modifier = Modifier.padding(3.dp).size(IconSize.sm),
                    )
                }
            }
            // Download status badge — icon-only once complete/failed, otherwise a
            // percentage so the grid can be scanned without opening each item.
            if (downloadStatus != null) {
                val isActive = downloadStatus != DownloadStatus.COMPLETE.name && downloadStatus != DownloadStatus.FAILED.name
                val (badgeIcon, badgeColor) = when (downloadStatus) {
                    DownloadStatus.COMPLETE.name -> Icons.Default.DownloadDone to Success
                    DownloadStatus.FAILED.name -> Icons.Default.Warning to Error
                    else -> Icons.Default.Downloading to Primary
                }
                Surface(
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                    color = ScrimStrong,
                    shape = RoundedCornerShape(Radius.pill),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(badgeIcon, contentDescription = null, tint = badgeColor, modifier = Modifier.size(IconSize.sm))
                        if (isActive && downloadProgress != null && downloadProgress > 0f) {
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "${downloadProgress.toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = badgeColor,
                                modifier = Modifier.padding(end = 2.dp),
                            )
                        }
                    }
                }
                // Thin progress bar across the bottom of the poster for at-a-glance scanning.
                if (isActive && downloadProgress != null && downloadProgress > 0f) {
                    LinearProgressIndicator(
                        progress = { downloadProgress / 100f },
                        modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),
                        color = Primary,
                        trackColor = Color.Transparent,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(text = item.name, style = MaterialTheme.typography.titleMedium,
            color = OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        item.year?.let {
            Text(text = it.toString(), style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
        }
    }
}

// ─── Skeleton loading placeholders ─────────────────────────────────────────────

@Composable
private fun rememberShimmerAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shimmerAlpha",
    )
    return alpha
}

@Composable
private fun SkeletonCard(modifier: Modifier = Modifier) {
    val alpha = rememberShimmerAlpha()
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(Radius.sm))
                .background(SurfaceVariant.copy(alpha = alpha)),
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth(0.8f)
                .height(Spacing.md)
                .clip(RoundedCornerShape(Radius.sm))
                .background(SurfaceVariant.copy(alpha = alpha)),
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth(0.4f)
                .height(10.dp)
                .clip(RoundedCornerShape(Radius.sm))
                .background(SurfaceVariant.copy(alpha = alpha)),
        )
    }
}

@Composable
private fun SkeletonGrid() {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        contentPadding = PaddingValues(horizontal = Spacing.xl, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = false,
    ) {
        items(18) { SkeletonCard() }
    }
}
