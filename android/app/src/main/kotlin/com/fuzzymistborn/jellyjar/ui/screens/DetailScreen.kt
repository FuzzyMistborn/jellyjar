package com.fuzzymistborn.jellyjar.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import com.fuzzymistborn.jellyjar.model.DownloadStatus
import com.fuzzymistborn.jellyjar.model.JellyfinItem
import com.fuzzymistborn.jellyjar.model.MediaSource
import com.fuzzymistborn.jellyjar.ui.theme.*
import com.fuzzymistborn.jellyjar.ui.viewmodel.DetailState
import com.fuzzymistborn.jellyjar.ui.viewmodel.DetailViewModel

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun DetailScreen(
    itemId: String,
    onPlayClick: (localPath: String, jellyfinId: String, startMs: Long) -> Unit,
    onStreamClick: (streamUrl: String, jellyfinId: String, startMs: Long) -> Unit,
    onEpisodeClick: (itemId: String) -> Unit = {},
    onSeasonClick: (seasonId: String, seriesId: String) -> Unit = { _, _ -> },
    onBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope? = null,
    animatedVisibilityScope: androidx.compose.animation.AnimatedContentScope? = null,
) {
    LaunchedEffect(itemId) { viewModel.loadItem(itemId) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, itemId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshStreamPosition(itemId)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    var showPresetDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize().background(BackgroundGradient)) {

        // Backdrop — episodes fall back to series backdrop, then episode primary
        state.item?.let { item ->
            val backdropId = if (item.type == "Episode") item.seriesId ?: item.id else item.id
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(viewModel.backdropUrl(backdropId))
                    .crossfade(300)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                alignment = Alignment.TopCenter,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.65f)
                    .clip(RectangleShape),
            )
        }

        // Gradient fade to background — same scrimStop/backdropHeight ratio as SeasonScreen's
        // hero (0.25/0.45), scaled to this screen's taller 0.65f backdrop so both screens shade
        // by the same proportion instead of Detail reading lighter/darker than Season.
        Box(modifier = Modifier.fillMaxSize().background(heroBackdropScrim(scrimStop = 0.36f, solidStop = 0.65f)))

        val item = state.item

        // Per-title accent extracted from the backdrop art (falls back to the theme's default
        // blue while loading or if extraction fails) so primary actions/chips read as "this
        // title's color" instead of always the same fixed blue.
        val accentColor = rememberDynamicAccentColor(
            imageUrl = item?.let { viewModel.backdropUrl(if (it.type == "Episode") it.seriesId ?: it.id else it.id) },
        )

        val actions = DetailActions(
            onPlayClick = onPlayClick,
            onStreamClick = onStreamClick,
            onSeasonClick = onSeasonClick,
            onShowPresetDialog = { showPresetDialog = true },
            onShowDeleteConfirm = { showDeleteConfirm = true },
        )

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Tablet landscape and up gets a real two-pane layout instead of the phone's single
            // stacked column — a fixed poster/title/actions pane alongside independently
            // scrolling overview + seasons content.
            val isTwoPane = maxWidth >= 840.dp

            if (item != null && isTwoPane) {
                DetailTwoPaneContent(
                    item = item,
                    state = state,
                    viewModel = viewModel,
                    accentColor = accentColor,
                    onBack = onBack,
                    actions = actions,
                    coroutineScope = coroutineScope,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                )
            } else {
                DetailStackedContent(
                    item = item,
                    state = state,
                    viewModel = viewModel,
                    accentColor = accentColor,
                    onBack = onBack,
                    actions = actions,
                    coroutineScope = coroutineScope,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                )
            }
        }

        // Loading/error overlays when item hasn't loaded yet
        if (state.isLoading && state.item == null) {
            FullScreenLoading()
        } else if (state.error != null && state.item == null && !state.isLoading) {
            FullScreenError(
                message = state.error!!,
                onRetry = { viewModel.loadItem(itemId) },
            )
        }

        DownloadErrorSnackbar(state.downloadError) { viewModel.clearDownloadError() }
    } // end Box

    // Preset selection dialog
    if (showPresetDialog) {
        PresetPickerDialog(
            onPresetSelected = { preset ->
                showPresetDialog = false
                viewModel.queueDownload(preset)
            },
            onDismiss = { showPresetDialog = false },
        )
    }

    if (showDeleteConfirm) {
        DeleteConfirmDialog(
            onConfirm = { viewModel.deleteDownload(itemId) },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

// Bundles the navigation callbacks needed by both the stacked and two-pane content layouts so
// DetailScreen doesn't have to pass six separate lambdas to each.
private data class DetailActions(
    val onPlayClick: (localPath: String, jellyfinId: String, startMs: Long) -> Unit,
    val onStreamClick: (streamUrl: String, jellyfinId: String, startMs: Long) -> Unit,
    val onSeasonClick: (seasonId: String, seriesId: String) -> Unit,
    val onShowPresetDialog: () -> Unit,
    val onShowDeleteConfirm: () -> Unit,
)

// Key must match the one MediaCard applies to the same item's poster in the library grid
// (LibraryScreen.kt) so the tapped poster morphs into this one instead of a hard cut. A no-op
// when either scope is null (e.g. this screen reached via a nav path outside the shared
// SharedTransitionLayout, or previews).
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
private fun detailPosterModifier(
    base: Modifier,
    itemId: String,
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope?,
    animatedVisibilityScope: androidx.compose.animation.AnimatedContentScope?,
): Modifier {
    if (sharedTransitionScope == null || animatedVisibilityScope == null) return base
    return with(sharedTransitionScope) {
        base.sharedElement(
            rememberSharedContentState(key = "poster-$itemId"),
            animatedVisibilityScope = animatedVisibilityScope,
        )
    }
}

// Phone / narrow-tablet layout: everything in one scrolling column, poster beside the title.
// This is the pre-two-pane layout, unchanged in behavior.
@Composable
private fun DetailStackedContent(
    item: JellyfinItem?,
    state: DetailState,
    viewModel: DetailViewModel,
    accentColor: Color,
    onBack: () -> Unit,
    actions: DetailActions,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope? = null,
    animatedVisibilityScope: androidx.compose.animation.AnimatedContentScope? = null,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
    ) {
        item(key = "back") {
            ScreenHeader(
                onBack = onBack,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(horizontal = Spacing.sm, vertical = 4.dp),
            ) {}
        }

        item(key = "hero_spacer") { Spacer(Modifier.height(100.dp)) }

        if (item != null) {
            item(key = "metadata") {
                BoxWithConstraints(modifier = Modifier.padding(horizontal = 32.dp)) {
                    val isWide = maxWidth >= 600.dp
                    val posterWidth = if (isWide) 220.dp else 130.dp

                    Row {
                        PosterImage(
                            imageUrl = state.download?.thumbnailUri ?: viewModel.posterUrl(item.id),
                            contentDescription = item.name,
                            modifier = detailPosterModifier(
                                Modifier.width(posterWidth), item.id, sharedTransitionScope, animatedVisibilityScope,
                            ),
                        )
                        Spacer(Modifier.width(if (isWide) Spacing.xl else Spacing.lg))

                        Column(modifier = Modifier.weight(1f)) {
                            TitleAndMetaRow(item, accentColor)
                            Spacer(Modifier.height(Spacing.lg))
                            OverviewBlock(item, isWide = isWide, accentColor = accentColor)
                            if (!item.mediaSources.isNullOrEmpty()) {
                                Spacer(Modifier.height(Spacing.md))
                                TechSpecRow(item.mediaSources, accentColor)
                            }
                            Spacer(Modifier.height(28.dp))
                            if (item.type != "Series") {
                                ActionButtonsSection(item, state, viewModel, accentColor, actions, coroutineScope)
                            }
                        }
                    }
                }
            }

            if (item.type == "Series" && state.seasons.isNotEmpty()) {
                item(key = "seasons_header") {
                    SeasonsSection(item, state, viewModel, actions)
                }
                item(key = "bottom_spacer") { Spacer(Modifier.height(0.dp)) }
            } else {
                item(key = "bottom_spacer") { Spacer(Modifier.height(40.dp)) }
            }
        }
    }
}

// Tablet-landscape layout (>=840dp): a fixed left pane (poster, title, metadata, tech specs,
// actions) beside a right pane that scrolls its own overview + seasons content independently —
// the actions stay visible without following the user down through a long overview/season list.
@Composable
private fun DetailTwoPaneContent(
    item: JellyfinItem,
    state: DetailState,
    viewModel: DetailViewModel,
    accentColor: Color,
    onBack: () -> Unit,
    actions: DetailActions,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope? = null,
    animatedVisibilityScope: androidx.compose.animation.AnimatedContentScope? = null,
) {
    Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
        ScreenHeader(
            onBack = onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = Spacing.sm, vertical = 4.dp),
        ) {}

        Spacer(Modifier.height(40.dp))

        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .width(300.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 32.dp, end = Spacing.lg),
            ) {
                PosterImage(
                    imageUrl = state.download?.thumbnailUri ?: viewModel.posterUrl(item.id),
                    contentDescription = item.name,
                    modifier = detailPosterModifier(
                        Modifier.fillMaxWidth().widthIn(max = 170.dp), item.id, sharedTransitionScope, animatedVisibilityScope,
                    ),
                )
                Spacer(Modifier.height(Spacing.sm))
                TitleAndMetaRow(item, accentColor, compact = true)
                if (!item.mediaSources.isNullOrEmpty()) {
                    Spacer(Modifier.height(Spacing.xs))
                    TechSpecRow(item.mediaSources, accentColor)
                }
                Spacer(Modifier.height(Spacing.md))
                if (item.type != "Series") {
                    ActionButtonsSection(item, state, viewModel, accentColor, actions, coroutineScope, compact = true)
                }
                Spacer(Modifier.height(Spacing.lg))
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(end = 32.dp, bottom = 40.dp),
            ) {
                if (item.overview != null) {
                    item(key = "overview") {
                        Box(modifier = Modifier.padding(start = Spacing.lg, top = Spacing.xs)) {
                            OverviewBlock(item, isWide = true, accentColor = accentColor)
                        }
                    }
                }
                if (item.type == "Series" && state.seasons.isNotEmpty()) {
                    item(key = "seasons_header") {
                        SeasonsSection(item, state, viewModel, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun TitleAndMetaRow(item: JellyfinItem, accentColor: Color, compact: Boolean = false) {
    // Episodes: "Series · SxxExx · Episode Name" is too long for the displayMedium heading style
    // to wrap gracefully in a narrow column (pushes buttons below the fold on tablet). Split it
    // into a small eyebrow line (series + episode code) and just the episode name as the heading.
    if (item.type == "Episode" && item.seriesName != null) {
        Text(
            text = "${item.seriesName} · S${item.parentIndexNumber?.toString()?.padStart(2, '0')}E${item.indexNumber?.toString()?.padStart(2, '0')}",
            style = MaterialTheme.typography.labelLarge,
            color = OnSurfaceMuted,
        )
        Spacer(Modifier.height(4.dp))
    }
    // A long movie title wraps to multiple lines at displayMedium in the narrower two-pane
    // sidebar, pushing the action buttons below the fold — drop to a smaller heading there.
    Text(
        text = if (item.type == "Episode") item.name else item.displayTitle,
        style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displayMedium,
        color = OnSurface,
    )
    Spacer(Modifier.height(Spacing.sm))
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item.year?.let {
            Text(it.toString(), style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted)
        }
        item.runtimeMinutes?.let {
            Text("${it}m", style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted)
        }
        item.communityRating?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Star, contentDescription = null,
                    tint = accentColor, modifier = Modifier.size(IconSize.sm),
                )
                Spacer(Modifier.width(4.dp))
                Text("${"%.1f".format(it)}", style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted)
            }
        }
    }
}

// Portrait/phone widths get a clamped, tap-to-expand overview so a long description doesn't
// push the Seasons row far down the scroll; wide/tablet layouts have room to just show it all.
@Composable
private fun OverviewBlock(item: JellyfinItem, isWide: Boolean, accentColor: Color) {
    val overview = item.overview ?: return
    var overviewExpanded by remember(item.id) { mutableStateOf(false) }
    var overviewOverflows by remember(item.id) { mutableStateOf(false) }
    Text(
        text = overview,
        style = MaterialTheme.typography.bodyLarge,
        color = OnSurface.copy(alpha = 0.85f),
        maxLines = if (isWide || overviewExpanded) Int.MAX_VALUE else 4,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { result -> overviewOverflows = result.hasVisualOverflow },
        modifier = Modifier
            .widthIn(max = 600.dp)
            .let { mod ->
                if (isWide) mod else mod.clickable { overviewExpanded = !overviewExpanded }
            },
    )
    if (!isWide && (overviewOverflows || overviewExpanded)) {
        Text(
            text = if (overviewExpanded) "Read less" else "Read more",
            style = MaterialTheme.typography.labelLarge,
            color = accentColor,
            modifier = Modifier
                .clickable { overviewExpanded = !overviewExpanded }
                .padding(top = 2.dp),
        )
    }
}

@Composable
private fun ActionButtonsSection(
    item: JellyfinItem,
    state: DetailState,
    viewModel: DetailViewModel,
    accentColor: Color,
    actions: DetailActions,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    compact: Boolean = false,
) {
    val dl = state.download
    val offlinePositionMs = dl?.playbackPositionMs ?: 0L
    val streamPositionMs = state.streamPositionMs
    val hasPosition = offlinePositionMs > 0L || streamPositionMs > 0L
    val resumeAction: (() -> Unit)? = when {
        dl?.status == DownloadStatus.COMPLETE.name && offlinePositionMs > 0L ->
            { { actions.onPlayClick(dl.localPath, dl.jellyfinId, offlinePositionMs) } }
        state.canStream && streamPositionMs > 0L ->
            {
                {
                    coroutineScope.launch {
                        actions.onStreamClick(viewModel.streamUrl(item.id), item.id, streamPositionMs)
                    }
                }
            }
        else -> null
    }
    val playFromStartAction: (() -> Unit)? = when {
        dl?.status == DownloadStatus.COMPLETE.name ->
            { { actions.onPlayClick(dl.localPath, dl.jellyfinId, 0L) } }
        state.canStream ->
            {
                {
                    coroutineScope.launch {
                        actions.onStreamClick(viewModel.streamUrl(item.id), item.id, 0L)
                    }
                }
            }
        else -> null
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasPosition && resumeAction != null) {
            PrimaryActionButton("Resume", Icons.Default.PlayArrow, resumeAction, accentColor = accentColor)
        }
        if (playFromStartAction != null) {
            val isSecondary = hasPosition && resumeAction != null
            if (isSecondary) {
                SecondaryActionButton(
                    icon = Icons.Default.PlayArrow,
                    onClick = playFromStartAction,
                    text = "Play",
                    compact = compact,
                )
            } else {
                PrimaryActionButton("Play", Icons.Default.PlayArrow, playFromStartAction, accentColor = accentColor)
            }
        }
    }

    Spacer(Modifier.height(Spacing.sm))

    // Separate row for the secondary/status actions — on narrow screens these previously
    // overflowed off-screen when combined with the play row above, clipping the watched/download
    // buttons and squeezing the favorite icon's touch target into its neighbor.
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { viewModel.toggleFavorite() }) {
            Icon(
                if (state.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (state.isFavorite) "Remove from My List" else "Add to My List",
                tint = if (state.isFavorite) Error else OnSurfaceMuted,
            )
        }
        IconButton(onClick = { viewModel.togglePlayed() }) {
            Icon(
                if (state.isPlayed) Icons.Default.CheckCircle else Icons.Default.CheckCircleOutline,
                contentDescription = if (state.isPlayed) "Mark Unplayed" else "Mark Played",
                tint = if (state.isPlayed) accentColor else OnSurfaceMuted,
            )
        }
        when {
            dl == null && state.isOnline -> SecondaryActionButton(
                icon = Icons.Default.Download,
                onClick = actions.onShowPresetDialog,
                text = "Download",
                compact = compact,
            )
            dl != null && dl.status in listOf(
                DownloadStatus.QUEUED.name,
                DownloadStatus.TRANSCODING.name,
                DownloadStatus.DOWNLOADING.name,
            ) ->
                DownloadProgressButton(dl.status, dl.progress, accentColor)
            dl?.status == DownloadStatus.COMPLETE.name ->
                SecondaryActionButton(
                    icon = Icons.Default.Delete,
                    onClick = actions.onShowDeleteConfirm,
                    text = "Remove",
                    contentColor = Error,
                    compact = compact,
                )
            dl?.status == DownloadStatus.FAILED.name -> {
                if (state.isOnline) {
                    SecondaryActionButton(
                        icon = Icons.Default.Refresh,
                        onClick = { viewModel.retryDownload(item.id) },
                        text = "Retry",
                        compact = compact,
                    )
                }
                SecondaryActionButton(
                    icon = Icons.Default.Delete,
                    onClick = actions.onShowDeleteConfirm,
                    contentColor = Error,
                    compact = compact,
                )
            }
            else -> {}
        }
    }
}

@Composable
private fun SeasonsSection(
    item: JellyfinItem,
    state: DetailState,
    viewModel: DetailViewModel,
    actions: DetailActions,
) {
    Column {
        Spacer(Modifier.height(40.dp))
        Text(
            text = "Seasons",
            style = MaterialTheme.typography.titleMedium,
            color = SectionHeading,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        Spacer(Modifier.height(Spacing.md))
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            itemsIndexed(state.seasons) { index, season ->
                Column(modifier = Modifier.width(110.dp)) {
                    PosterImage(
                        // A synthetic offline season (see DetailViewModel.loadOfflineSeasons) has no
                        // real Jellyfin id, so its own poster art can't be fetched — fall back to the
                        // series' art (real when a Series cache row exists; itself synthetic, and thus
                        // still unfetchable, when the whole series is offline-synthetic — best effort).
                        imageUrl = if (season.id.startsWith("offline-season-")) viewModel.posterUrl(item.id) else viewModel.posterUrl(season.id),
                        contentDescription = season.name,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { actions.onSeasonClick(season.id, item.id) },
                    ) {
                        SeasonDownloadBadge(
                            queuingProgress = state.seasonDownloadProgress[season.id],
                            episodeIds = state.seasonEpisodeIds[season.id],
                            episodeDownloads = state.episodeDownloads,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = season.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

// Resolution/HDR/audio-format chips derived from the first media source's video and audio
// streams — only meaningful for movies/episodes, which carry their own MediaSources.
@Composable
private fun TechSpecRow(mediaSources: List<MediaSource>, accentColor: Color) {
    val streams = mediaSources.firstOrNull()?.mediaStreams ?: return
    val video = streams.firstOrNull { it.type == "Video" }
    val audio = streams.firstOrNull { it.type == "Audio" }

    val resolution = video?.height?.let {
        when {
            it >= 2000 -> "4K"
            it >= 1000 -> "1080p"
            it >= 700 -> "720p"
            else -> "${it}p"
        }
    }
    val isHdr = video?.videoRange?.contains("HDR", ignoreCase = true) == true
    val audioLabel = audio?.channelLayout ?: audio?.channels?.let {
        when (it) {
            8 -> "7.1"
            6 -> "5.1"
            2 -> "Stereo"
            1 -> "Mono"
            else -> "${it}ch"
        }
    }

    val chips = listOfNotNull(resolution, "HDR".takeIf { isHdr }, audioLabel)
    if (chips.isEmpty()) return

    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        chips.forEach { label ->
            Surface(color = accentColor.copy(alpha = 0.16f), shape = RoundedCornerShape(Radius.sm)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor,
                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun DownloadProgressButton(status: String, progress: Float, accentColor: Color = Primary) {
    val animatedProgress by animateFloatAsState(targetValue = progress / 100f, label = "downloadProgress")
    Surface(
        color = SurfaceVariant,
        shape = RoundedCornerShape(Radius.sm),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.size(IconSize.md),
                strokeWidth = 2.dp,
                color = accentColor,
            )
            Text(
                text = when (status) {
                    "TRANSCODING" -> "Transcoding ${"%.0f".format(progress)}%"
                    "DOWNLOADING" -> "Downloading ${"%.0f".format(progress)}%"
                    else -> "Working..."
                },
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SeasonDownloadBadge(
    queuingProgress: Float?,
    episodeIds: List<String>?,
    episodeDownloads: Map<String, com.fuzzymistborn.jellyjar.data.local.DownloadEntity>,
    modifier: Modifier = Modifier,
) {
    if (queuingProgress != null) {
        Surface(
            color = ScrimStrong,
            shape = RoundedCornerShape(bottomStart = Radius.sm, bottomEnd = Radius.sm),
            modifier = modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(IconSize.xs), strokeWidth = 2.dp, color = Primary)
                Text("Queuing…", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
        return
    }

    val ids = episodeIds
    if (ids.isNullOrEmpty()) return

    val downloadedCount = ids.count { episodeDownloads[it]?.status == DownloadStatus.COMPLETE.name }
    val activeCount = ids.count {
        episodeDownloads[it]?.status == DownloadStatus.TRANSCODING.name ||
            episodeDownloads[it]?.status == DownloadStatus.DOWNLOADING.name
    }
    if (downloadedCount == 0 && activeCount == 0) return

    val allDownloaded = downloadedCount == ids.size
    val label = when {
        allDownloaded -> "Downloaded"
        activeCount > 0 -> "$downloadedCount/${ids.size} · downloading"
        else -> "$downloadedCount/${ids.size} downloaded"
    }
    Surface(
        color = ScrimStrong,
        shape = RoundedCornerShape(bottomStart = Radius.sm, bottomEnd = Radius.sm),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (allDownloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                contentDescription = null,
                tint = if (allDownloaded) Success else OnSurfaceMuted,
                modifier = Modifier.size(IconSize.xs),
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun EpisodeRow(
    episode: JellyfinItem,
    thumbnailUrl: String,
    download: com.fuzzymistborn.jellyjar.data.local.DownloadEntity?,
    isOnline: Boolean,
    canStream: Boolean = isOnline,
    onClick: () -> Unit,
    onStreamClick: ((startMs: Long) -> Unit)? = null,
    onDownloadClick: (preset: String) -> Unit,
    onDeleteClick: () -> Unit,
    onPlayOfflineClick: (path: String, jellyfinId: String, startMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPresetDialog by remember { mutableStateOf(false) }

    Surface(
        color = ScrimSoft,
        shape = RoundedCornerShape(Radius.md),
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Thumbnail
            PosterImage(
                imageUrl = thumbnailUrl,
                contentDescription = episode.name,
                modifier = Modifier.width(120.dp),
                aspectRatio = 16f / 9f,
            ) {
                episode.indexNumber?.let { epNum ->
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(3.dp),
                        color = ScrimStrong,
                        shape = RoundedCornerShape(Radius.sm),
                    ) {
                        Text(
                            "E${epNum.toString().padStart(2, '0')}",
                            style = MaterialTheme.typography.labelSmall,
                            color = OnSurface,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
                if (episode.userData?.played == true) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopEnd).padding(3.dp),
                        color = ScrimStrong,
                        shape = RoundedCornerShape(Radius.pill),
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Watched",
                            tint = Primary,
                            modifier = Modifier.padding(2.dp).size(IconSize.sm),
                        )
                    }
                }
                // Watch progress bar
                val watchFraction = watchProgressFraction(episode, download)
                if (watchFraction > 0.01f) {
                    LinearProgressIndicator(
                        progress = { watchFraction },
                        modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),
                        color = Primary,
                        trackColor = Color.Transparent,
                    )
                }
            }

            // Metadata + actions
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = episode.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = OnSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    episode.runtimeMinutes?.let {
                        Text("${it}m", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                    }
                    episode.communityRating?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Star, contentDescription = null,
                                tint = Accent, modifier = Modifier.size(IconSize.xs))
                            Spacer(Modifier.width(2.dp))
                            Text("${"%.1f".format(it)}", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                        }
                    }
                }
                episode.overview?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))

                // Play + download controls
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val offlinePos = download?.playbackPositionMs ?: 0L
                    val streamPos = episode.userData?.playbackPositionTicks?.div(10_000L) ?: 0L
                    val hasPos = offlinePos > 0L || streamPos > 0L
                    if (hasPos) {
                        val resumeAction: (() -> Unit)? = when {
                            download?.status == DownloadStatus.COMPLETE.name && offlinePos > 0L ->
                                { { onPlayOfflineClick(download.localPath, download.jellyfinId, offlinePos) } }
                            canStream && onStreamClick != null && streamPos > 0L ->
                                { { onStreamClick(streamPos) } }
                            else -> null
                        }
                        if (resumeAction != null) {
                            CompactActionButton(icon = Icons.Default.PlayArrow, onClick = resumeAction, text = "Resume")
                        }
                    }
                    val playFromStartAction: (() -> Unit)? = when {
                        download?.status == DownloadStatus.COMPLETE.name ->
                            { { onPlayOfflineClick(download.localPath, download.jellyfinId, 0L) } }
                        canStream && onStreamClick != null -> { { onStreamClick(0L) } }
                        else -> null
                    }
                    if (playFromStartAction != null) {
                        CompactActionButton(icon = Icons.Default.PlayArrow, onClick = playFromStartAction, text = "Play")
                    }
                    when {
                        download == null && isOnline -> CompactActionButton(
                            icon = Icons.Default.Download,
                            onClick = { showPresetDialog = true },
                            text = "Download",
                        )
                        download != null && download.status in listOf(
                            DownloadStatus.QUEUED.name,
                            DownloadStatus.TRANSCODING.name,
                            DownloadStatus.DOWNLOADING.name,
                        ) -> {
                            val animatedProgress by animateFloatAsState(
                                targetValue = download.progress / 100f, label = "episodeProgress",
                            )
                            CircularProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier.size(IconSize.sm),
                                strokeWidth = 2.dp,
                                color = Primary,
                            )
                            Text(
                                if (download.status == DownloadStatus.QUEUED.name) "Queued"
                                else "${"%.0f".format(download.progress)}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = OnSurface,
                            )
                        }
                        download?.status == DownloadStatus.COMPLETE.name ->
                            CompactActionButton(icon = Icons.Default.Delete, onClick = onDeleteClick, contentColor = Error)
                        download?.status == DownloadStatus.FAILED.name -> {
                            if (isOnline) {
                                CompactActionButton(
                                    icon = Icons.Default.Refresh,
                                    onClick = { onDownloadClick("retry") },
                                    text = "Retry",
                                )
                            }
                            CompactActionButton(icon = Icons.Default.Delete, onClick = onDeleteClick, contentColor = Error)
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    if (showPresetDialog) {
        PresetPickerDialog(
            onPresetSelected = { preset ->
                showPresetDialog = false
                onDownloadClick(preset)
            },
            onDismiss = { showPresetDialog = false },
        )
    }
}

@Composable
internal fun EpisodeThumb(
    episode: JellyfinItem,
    thumbnailUrl: String,
    download: com.fuzzymistborn.jellyjar.data.local.DownloadEntity?,
    isOnline: Boolean,
    canStream: Boolean = isOnline,
    onClick: () -> Unit,
    onStreamClick: ((startMs: Long) -> Unit)? = null,
    onDownloadClick: (preset: String) -> Unit,
    onDeleteClick: () -> Unit,
    onPlayOfflineClick: (path: String, jellyfinId: String, startMs: Long) -> Unit,
) {
    var showPresetDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.width(200.dp)) {
        PosterImage(
            imageUrl = thumbnailUrl,
            contentDescription = episode.name,
            modifier = Modifier.fillMaxWidth(),
            aspectRatio = 16f / 9f,
            onClick = onClick,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(
                    color = ScrimSoft,
                    shape = RoundedCornerShape(Radius.pill),
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = OnSurface,
                        modifier = Modifier.padding(6.dp).size(IconSize.md),
                    )
                }
            }
            episode.indexNumber?.let { epNum ->
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                    color = ScrimStrong,
                    shape = RoundedCornerShape(Radius.sm),
                ) {
                    Text(
                        "E${epNum.toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurface,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
            if (episode.userData?.played == true) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    color = ScrimStrong,
                    shape = RoundedCornerShape(Radius.pill),
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Watched",
                        tint = Primary,
                        modifier = Modifier.padding(2.dp).size(IconSize.sm),
                    )
                }
            }
            // Watch progress bar
            val watchFraction = watchProgressFraction(episode, download)
            if (watchFraction > 0.01f) {
                LinearProgressIndicator(
                    progress = { watchFraction },
                    modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),
                    color = Primary,
                    trackColor = Color.Transparent,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = episode.name,
            style = MaterialTheme.typography.titleMedium,
            color = OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        episode.runtimeMinutes?.let {
            Text("${it}m", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
        }
        Spacer(Modifier.height(6.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val offlinePos = download?.playbackPositionMs ?: 0L
            val streamPos = episode.userData?.playbackPositionTicks?.div(10_000L) ?: 0L
            val hasPos = offlinePos > 0L || streamPos > 0L
            if (hasPos) {
                val resumeAction: (() -> Unit)? = when {
                    download?.status == DownloadStatus.COMPLETE.name && offlinePos > 0L ->
                        { { onPlayOfflineClick(download.localPath, download.jellyfinId, offlinePos) } }
                    canStream && onStreamClick != null && streamPos > 0L ->
                        { { onStreamClick(streamPos) } }
                    else -> null
                }
                if (resumeAction != null) {
                    CompactActionButton(
                        icon = Icons.Default.PlayArrow,
                        onClick = resumeAction,
                        modifier = Modifier.weight(1f),
                        text = "Resume",
                    )
                }
            }
            val playFromStartAction: (() -> Unit)? = when {
                download?.status == DownloadStatus.COMPLETE.name ->
                    { { onPlayOfflineClick(download.localPath, download.jellyfinId, 0L) } }
                canStream && onStreamClick != null -> { { onStreamClick(0L) } }
                else -> null
            }
            if (playFromStartAction != null) {
                CompactActionButton(
                    icon = Icons.Default.PlayArrow,
                    onClick = playFromStartAction,
                    modifier = Modifier.weight(1f),
                    text = "Play",
                )
            }
            when {
                download == null && isOnline -> CompactActionButton(
                    icon = Icons.Default.Download,
                    onClick = { showPresetDialog = true },
                    modifier = Modifier.weight(1f),
                    text = "Download",
                )
                download != null && download.status in listOf(
                    DownloadStatus.QUEUED.name,
                    DownloadStatus.TRANSCODING.name,
                    DownloadStatus.DOWNLOADING.name,
                ) -> {
                    val animatedProgress by animateFloatAsState(
                        targetValue = download.progress / 100f, label = "episodeThumbProgress",
                    )
                    CircularProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.size(IconSize.sm),
                        strokeWidth = 2.dp,
                        color = Primary,
                    )
                    Text(
                        if (download.status == DownloadStatus.QUEUED.name) "Queued"
                        else "${"%.0f".format(download.progress)}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurface,
                    )
                }
                download?.status == DownloadStatus.COMPLETE.name ->
                    CompactActionButton(icon = Icons.Default.Delete, onClick = onDeleteClick, contentColor = Error)
                download?.status == DownloadStatus.FAILED.name -> {
                    if (isOnline) {
                        CompactActionButton(icon = Icons.Default.Refresh, onClick = { onDownloadClick("retry") })
                    }
                    CompactActionButton(icon = Icons.Default.Delete, onClick = onDeleteClick, contentColor = Error)
                }
                else -> {}
            }
        }
    }

    if (showPresetDialog) {
        PresetPickerDialog(
            onPresetSelected = { preset ->
                showPresetDialog = false
                onDownloadClick(preset)
            },
            onDismiss = { showPresetDialog = false },
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

private fun watchProgressFraction(
    episode: com.fuzzymistborn.jellyjar.model.JellyfinItem,
    download: com.fuzzymistborn.jellyjar.data.local.DownloadEntity?,
): Float {
    val runtimeMs = (episode.runtimeMinutes ?: download?.runtimeMinutes)?.let { it * 60_000L } ?: return 0f
    val positionMs = download?.playbackPositionMs?.takeIf { it > 0L }
        ?: episode.userData?.playbackPositionTicks?.div(10_000L)?.takeIf { it > 0L }
        ?: return 0f
    return (positionMs.toFloat() / runtimeMs).coerceIn(0f, 0.99f)
}
