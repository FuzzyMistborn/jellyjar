package com.fuzzymistborn.jellyjar.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import com.fuzzymistborn.jellyjar.data.local.playbackUri
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

        // Backdrop — episodes use the series' art. Full-screen and evenly dimmed at every
        // width; content scrolls over it. Portrait windows use the poster instead (see
        // DimmedBackdrop).
        // The poster uses the library grid's cache key, so it's usually already cached — offline
        // included.
        val artItemId = state.item?.let { if (it.type == "Episode") it.seriesId ?: it.id else it.id }
        val backdropUrl = artItemId?.let { viewModel.backdropUrl(it) }
        val posterUrl = artItemId?.let { viewModel.posterUrl(it) }
        val posterCacheKey = artItemId?.let { "poster_$it" }
        DimmedBackdrop(
            imageUrl = backdropUrl,
            portraitImageUrl = posterUrl,
            portraitCacheKey = posterCacheKey,
        )

        val item = state.item

        // Per-title accent extracted from the art actually behind the content (poster in
        // portrait, backdrop in landscape; the other one if that fails) so primary actions/chips
        // read as "this title's color" and match the background. Falls back to the theme blue.
        val portrait = isPortraitWindow()
        val accentColor = rememberDynamicAccentColor(
            imageUrl = if (portrait) posterUrl else backdropUrl,
            cacheKey = if (portrait) posterCacheKey else null,
            fallbackImageUrl = if (portrait) backdropUrl else null,
        )

        val actions = DetailActions(
            onPlayClick = onPlayClick,
            onStreamClick = onStreamClick,
            onSeasonClick = onSeasonClick,
            onShowPresetDialog = { showPresetDialog = true },
            onShowDeleteConfirm = { showDeleteConfirm = true },
        )

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Tablet landscape and up gets a hero (large artwork + title + actions) above a
            // full-width info panel, instead of the phone's single stacked column.
            val isLandscapeLayout = maxWidth >= 840.dp

            if (item != null && isLandscapeLayout) {
                DetailLandscapeContent(
                    item = item,
                    state = state,
                    viewModel = viewModel,
                    accentColor = accentColor,
                    onBack = onBack,
                    actions = actions,
                    coroutineScope = coroutineScope,
                    heroHeight = (maxHeight * 0.42f).coerceIn(220.dp, 340.dp),
                    contentWidth = maxWidth - 80.dp,
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

        item(key = "hero_spacer") { Spacer(Modifier.height(Spacing.lg)) }

        if (item != null) {
            item(key = "metadata") {
                BoxWithConstraints(modifier = Modifier.padding(horizontal = 32.dp)) {
                    val isWide = maxWidth >= 600.dp
                    val posterWidth = if (isWide) 150.dp else 130.dp
                    val isEpisode = item.type == "Episode"
                    // A 16:9 episode thumbnail at double poster width can eat almost the whole
                    // row on a narrow phone, leaving the title column too thin to word-wrap —
                    // stack the thumbnail above the text instead once it would exceed roughly
                    // half the available width.
                    val stackEpisodeThumbnail = isEpisode && (posterWidth * 2) > maxWidth / 2

                    val poster = @Composable {
                        PosterImage(
                            imageUrl = state.download?.thumbnailUri ?: viewModel.posterUrl(item.id),
                            contentDescription = item.name,
                            aspectRatio = if (isEpisode) 16f / 9f else 2f / 3f,
                            modifier = detailPosterModifier(
                                (if (isEpisode) Modifier.width(posterWidth * 2) else Modifier.width(posterWidth))
                                    .let { if (stackEpisodeThumbnail) Modifier.fillMaxWidth() else it },
                                item.id, sharedTransitionScope, animatedVisibilityScope,
                            ),
                        )
                    }
                    // Hero text: title + actions only. Metadata, tech specs and the overview live
                    // in the tinted info panel below, as in the landscape layout.
                    val textColumn = @Composable {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (item.type == "Series" && isWide) {
                                // Wide series heroes carry their own meta row and overview next to
                                // the poster, rather than a separate panel below the seasons.
                                TitleBlock(item, maxTitleLines = 2)
                                Spacer(Modifier.height(Spacing.sm))
                                MetaRow(item, accentColor, large = true)
                                Spacer(Modifier.height(Spacing.md))
                                SeriesOverview(item, accentColor, maxLines = 4)
                                Spacer(Modifier.height(Spacing.lg))
                                SeriesPlayButton(state, viewModel, accentColor, actions, coroutineScope)
                            } else {
                                TitleBlock(item)
                                Spacer(Modifier.height(Spacing.md))
                                if (item.type != "Series") {
                                    ActionButtonsSection(item, state, viewModel, accentColor, actions, coroutineScope)
                                } else {
                                    SeriesPlayButton(state, viewModel, accentColor, actions, coroutineScope)
                                }
                            }
                        }
                    }

                    if (stackEpisodeThumbnail) {
                        Column {
                            poster()
                            Spacer(Modifier.height(Spacing.lg))
                            textColumn()
                        }
                    } else {
                        Row {
                            poster()
                            Spacer(Modifier.width(Spacing.lg))
                            Box(modifier = Modifier.weight(1f)) { textColumn() }
                        }
                    }
                }
            }

            // Seasons are a series' main navigation, so they sit right under the hero instead of
            // below a tall info panel, where they were clipped at the bottom of the screen.
            if (item.type == "Series" && state.seasons.isNotEmpty()) {
                item(key = "seasons_header") {
                    SeasonsSection(item, state, viewModel, actions)
                }
            }

            item(key = "info") {
                BoxWithConstraints {
                    val isWide = maxWidth >= 600.dp
                    if (item.type == "Series" && isWide) return@BoxWithConstraints
                    InfoPanel(
                        item = item,
                        accentColor = accentColor,
                        showTechSpecs = !state.kidModeEnabled,
                        clampOverview = !isWide,
                        modifier = Modifier.padding(
                            horizontal = if (isWide) 32.dp else Spacing.lg,
                            vertical = Spacing.xl,
                        ),
                    )
                }
            }

            item(key = "bottom_spacer") { Spacer(Modifier.height(40.dp)) }
        }
    }
}

// Tablet-landscape layout (>=840dp): one scrolling column over the full-screen backdrop —
// a hero row (large artwork beside a big title, with the actions anchored to the artwork's
// bottom edge), then a full-width translucent info panel (date/runtime/rating/age rating, tech
// specs, overview), then seasons for a series. Replaced a 300dp sidebar + separate scrolling
// pane that squeezed everything into the top-left and left the lower half of the screen empty.
@Composable
private fun DetailLandscapeContent(
    item: JellyfinItem,
    state: DetailState,
    viewModel: DetailViewModel,
    accentColor: Color,
    onBack: () -> Unit,
    actions: DetailActions,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    heroHeight: androidx.compose.ui.unit.Dp,
    contentWidth: androidx.compose.ui.unit.Dp,
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

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = Spacing.sm, bottom = 40.dp),
        ) {
            item(key = "hero") {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 40.dp)
                        .fillMaxWidth(),
                ) {
                    // Artwork targets the hero height, but never more than half the content width
                    // — a 16:9 episode still at full height would otherwise squeeze the title
                    // column on narrower landscape tablets.
                    val aspect = if (item.type == "Episode") 16f / 9f else 2f / 3f
                    val artworkWidth = minOf(heroHeight * aspect, contentWidth * 0.5f)
                    val artworkHeight = artworkWidth / aspect
                    PosterImage(
                        imageUrl = state.download?.thumbnailUri ?: viewModel.posterUrl(item.id),
                        contentDescription = item.name,
                        aspectRatio = aspect,
                        modifier = detailPosterModifier(
                            Modifier.width(artworkWidth),
                            item.id, sharedTransitionScope, animatedVisibilityScope,
                        ),
                    )
                    Spacer(Modifier.width(32.dp))
                    // At least as tall as the artwork, with the title at the top and the actions
                    // pinned to the artwork's bottom edge — but free to grow taller when the title
                    // and wrapped buttons need more room (a fixed height clipped the buttons on
                    // narrower landscape tablets).
                    Column(
                        modifier = Modifier.weight(1f).heightIn(min = artworkHeight),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // Each half in its own Column so SpaceBetween separates title from
                        // actions rather than spreading TitleBlock's eyebrow/title apart.
                        Column {
                            TitleBlock(item, maxTitleLines = 2)
                            if (item.type == "Series") {
                                Spacer(Modifier.height(Spacing.sm))
                                MetaRow(item, accentColor, large = true)
                                Spacer(Modifier.height(Spacing.md))
                                SeriesOverview(item, accentColor, maxLines = 5)
                                // Straight under the overview rather than pinned to the artwork's
                                // bottom edge: a short description otherwise leaves a gap.
                                Spacer(Modifier.height(Spacing.lg))
                                SeriesPlayButton(state, viewModel, accentColor, actions, coroutineScope)
                            }
                        }
                        if (item.type != "Series") {
                            Column(modifier = Modifier.padding(top = Spacing.md)) {
                                ActionButtonsSection(
                                    item, state, viewModel, accentColor, actions, coroutineScope,
                                    flowRow = true,
                                )
                            }
                        }
                    }
                }
            }

            if (item.type == "Series" && state.seasons.isNotEmpty()) {
                item(key = "seasons_header") {
                    SeasonsSection(item, state, viewModel, actions)
                }
            }

            if (item.type != "Series") {
                item(key = "info") {
                    InfoPanel(
                        item = item,
                        accentColor = accentColor,
                        showTechSpecs = !state.kidModeEnabled,
                        modifier = Modifier.padding(horizontal = 40.dp, vertical = Spacing.xl),
                    )
                }
            }
        }
    }
}

// Translucent, per-title-tinted card carrying the metadata row, tech specs and the full
// overview. Black base for contrast over any backdrop, accent wash on top so it picks up the
// title's colour.
@Composable
private fun InfoPanel(
    item: JellyfinItem,
    accentColor: Color,
    showTechSpecs: Boolean,
    modifier: Modifier = Modifier,
    // Phone widths clamp the overview to 4 lines with a Read more toggle so a long description
    // doesn't push the Seasons row far down; wider layouts show it all.
    clampOverview: Boolean = false,
) {
    val hasTechSpecs = showTechSpecs && !item.mediaSources.isNullOrEmpty()
    val hasMeta = item.premiereDate != null || item.year != null || item.runtimeMinutes != null ||
        item.communityRating != null || item.officialRating != null
    if (!hasMeta && item.overview == null && !hasTechSpecs) return

    var overviewExpanded by remember(item.id) { mutableStateOf(false) }
    var overviewOverflows by remember(item.id) { mutableStateOf(false) }
    val clamped = clampOverview && !overviewExpanded

    Column(
        modifier = modifier
            .fillMaxWidth()
            .tintedPanel(accentColor)
            .padding(
                horizontal = if (clampOverview) Spacing.lg else Spacing.xl,
                vertical = Spacing.lg,
            ),
    ) {
        if (hasMeta) {
            MetaRow(item, accentColor, large = true)
        }
        if (hasTechSpecs) {
            Spacer(Modifier.height(Spacing.md))
            TechSpecRow(item.mediaSources!!, accentColor)
        }
        item.overview?.let { overview ->
            if (hasMeta || hasTechSpecs) Spacer(Modifier.height(Spacing.md))
            Text(
                text = overview,
                style = MaterialTheme.typography.bodyLarge,
                color = OnSurface.copy(alpha = 0.9f),
                maxLines = if (clamped) 4 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { result -> if (clamped) overviewOverflows = result.hasVisualOverflow },
                modifier = if (clampOverview) Modifier.clickable { overviewExpanded = !overviewExpanded } else Modifier,
            )
            if (clampOverview && (overviewOverflows || overviewExpanded)) {
                Text(
                    text = if (overviewExpanded) "Read less" else "Read more",
                    style = MaterialTheme.typography.labelLarge,
                    color = accentColor,
                    modifier = Modifier
                        .clickable { overviewExpanded = !overviewExpanded }
                        .padding(top = Spacing.xs),
                )
            }
        }
    }
}

// A series' overview inside the hero: clamped to a few lines, tap (or Read more) to expand.
// Sits straight on the backdrop, so it carries the same text shadow as the title.
@Composable
private fun SeriesOverview(item: JellyfinItem, accentColor: Color, maxLines: Int) {
    val overview = item.overview ?: return
    var expanded by remember(item.id) { mutableStateOf(false) }
    var overflows by remember(item.id) { mutableStateOf(false) }
    Text(
        text = overview,
        style = MaterialTheme.typography.bodyLarge.withHeroShadow(),
        color = OnSurface.copy(alpha = 0.92f),
        maxLines = if (expanded) Int.MAX_VALUE else maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { result -> if (!expanded) overflows = result.hasVisualOverflow },
        // A readable line length, and it keeps the text off the brightest part of a wide backdrop.
        modifier = Modifier.widthIn(max = 640.dp).clickable(enabled = overflows || expanded) { expanded = !expanded },
    )
    if (overflows || expanded) {
        Text(
            text = if (expanded) "Read less" else "Read more",
            style = MaterialTheme.typography.labelLarge,
            color = accentColor,
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(top = Spacing.xs),
        )
    }
}

// Episodes: "Series · SxxExx · Episode Name" is too long for the displayMedium heading style
// to wrap gracefully — split it into a small eyebrow line (series + episode code) and just the
// episode name as the heading.
@Composable
private fun TitleBlock(item: JellyfinItem, maxTitleLines: Int = Int.MAX_VALUE) {
    if (item.type == "Episode" && item.seriesName != null) {
        Text(
            text = "${item.seriesName} · S${item.parentIndexNumber?.toString()?.padStart(2, '0')}E${item.indexNumber?.toString()?.padStart(2, '0')}",
            style = MaterialTheme.typography.labelLarge,
            color = OnSurfaceMuted,
        )
        Spacer(Modifier.height(4.dp))
    }
    // Subtle text shadow as a legibility backstop where the title sits over backdrop art.
    Text(
        text = if (item.type == "Episode") item.name else item.displayTitle,
        style = MaterialTheme.typography.displayMedium.withHeroShadow(),
        color = OnSurface,
        maxLines = maxTitleLines,
        overflow = TextOverflow.Ellipsis,
    )
}

private val premiereDateFormat: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG)

// Year/air date · runtime · ★ rating · age-rating badge. Episodes show their full air date when
// the server has one ("March 17, 2020") — a bare year says little within a series.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetaRow(item: JellyfinItem, accentColor: Color, large: Boolean = false) {
    val textStyle = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
    val textColor = if (large) OnSurface.copy(alpha = 0.85f) else OnSurfaceMuted
    val dateLabel = item.premiereDate
        ?.takeIf { item.type == "Episode" }
        ?.let { runCatching { java.time.LocalDate.parse(it.take(10)).format(premiereDateFormat) }.getOrNull() }
        ?: item.year?.toString()

    // FlowRow so a long air date + runtime + rating + badge wraps on a phone instead of clipping.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        dateLabel?.let { Text(it, style = textStyle, color = textColor) }
        item.runtimeMinutes?.let { Text("${it}m", style = textStyle, color = textColor) }
        item.communityRating?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Star, contentDescription = null,
                    tint = accentColor, modifier = Modifier.size(IconSize.sm),
                )
                Spacer(Modifier.width(4.dp))
                Text("%.1f".format(it), style = textStyle, color = textColor)
            }
        }
        item.officialRating?.takeIf { it.isNotBlank() }?.let { rating ->
            Text(
                text = rating,
                style = MaterialTheme.typography.labelMedium,
                color = textColor,
                modifier = Modifier
                    .border(1.dp, textColor.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionButtonsSection(
    item: JellyfinItem,
    state: DetailState,
    viewModel: DetailViewModel,
    accentColor: Color,
    actions: DetailActions,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    // Landscape hero: everything in one FlowRow that wraps onto another line when it runs out
    // of width (Resume + Play + Favorite + Played + Download can need ~600dp), instead of a
    // single Row that clipped the trailing button off-screen.
    flowRow: Boolean = false,
) {
    val dl = state.download
    val offlinePositionMs = dl?.playbackPositionMs ?: 0L
    // The local position (saved when the player closes), else the server's — set by playback on
    // another device, or here when the app was killed before the player could save it. The
    // episode rows already fell back like this; movies only read the local one, so a title in
    // Continue Watching could open to a plain Play button.
    val streamPositionMs = state.streamPositionMs.takeIf { it > 0L }
        ?: item.userData?.playbackPositionTicks?.div(10_000L)
        ?: 0L
    val hasPosition = offlinePositionMs > 0L || streamPositionMs > 0L
    val resumeAction: (() -> Unit)? = when {
        dl?.status == DownloadStatus.COMPLETE.name && offlinePositionMs > 0L ->
            { { actions.onPlayClick(dl.playbackUri(), dl.jellyfinId, offlinePositionMs) } }
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
            { { actions.onPlayClick(dl.playbackUri(), dl.jellyfinId, 0L) } }
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

    val playRow: @Composable RowScope.() -> Unit = {
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
                )
            } else {
                PrimaryActionButton("Play", Icons.Default.PlayArrow, playFromStartAction, accentColor = accentColor)
            }
        }
    }

    if (!flowRow) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            content = playRow,
        )
        Spacer(Modifier.height(Spacing.sm))
    }

    // The landscape hero (flowRow=true) puts the play button(s) and the status controls in one
    // wrapping FlowRow; stacked layouts keep the two-row split.
    val controls: @Composable RowScope.() -> Unit = {
        if (flowRow) playRow()
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
        // Kid Mode hides the whole download control — queueing, progress, remove and retry alike.
        // Play/Resume and the Favorite/Played icons above stay: they're harmless and useful.
        when {
            state.kidModeEnabled -> {}
            dl == null && state.isOnline -> SecondaryActionButton(
                icon = Icons.Default.Download,
                onClick = actions.onShowPresetDialog,
                text = "Download",
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
                )
            dl?.status == DownloadStatus.FAILED.name -> {
                if (state.isOnline) {
                    SecondaryActionButton(
                        icon = Icons.Default.Refresh,
                        onClick = { viewModel.retryDownload(item.id) },
                        text = "Retry",
                    )
                }
                SecondaryActionButton(
                    icon = Icons.Default.Delete,
                    onClick = actions.onShowDeleteConfirm,
                    contentColor = Error,
                )
            }
            else -> {}
        }
    }
    if (flowRow) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) { controls() }
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            content = controls,
        )
    }
}

// The series page's Play/Resume: the server's "Next Up" episode, played from the downloaded copy
// when there is one. Absent while offline with nothing downloaded, or before Next Up has loaded.
@Composable
private fun SeriesPlayButton(
    state: DetailState,
    viewModel: DetailViewModel,
    accentColor: Color,
    actions: DetailActions,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
) {
    val ep = state.nextUp ?: return
    val dl = state.episodeDownloads[ep.id]
    val complete = dl?.status == DownloadStatus.COMPLETE.name
    val offlinePos = dl?.playbackPositionMs ?: 0L
    val streamPos = ep.userData?.playbackPositionTicks?.div(10_000L) ?: 0L
    val startMs = if (complete && offlinePos > 0L) offlinePos else streamPos
    val action: () -> Unit = when {
        complete && dl != null -> { { actions.onPlayClick(dl.playbackUri(), dl.jellyfinId, startMs) } }
        state.canStream -> {
            {
                coroutineScope.launch {
                    actions.onStreamClick(viewModel.streamUrl(ep.id), ep.id, startMs)
                }
            }
        }
        else -> return
    }
    val code = ep.indexNumber?.let { e ->
        val season = ep.parentIndexNumber?.let { "S${it.toString().padStart(2, '0')}" } ?: ""
        " ${season}E${e.toString().padStart(2, '0')}"
    } ?: ""
    PrimaryActionButton(
        text = (if (startMs > 0L) "Resume" else "Play") + code,
        icon = Icons.Default.PlayArrow,
        onClick = action,
        accentColor = accentColor,
    )
}

@Composable
private fun SeasonsSection(
    item: JellyfinItem,
    state: DetailState,
    viewModel: DetailViewModel,
    actions: DetailActions,
) {
    Column {
        Spacer(Modifier.height(Spacing.xl))
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
                Column(modifier = Modifier.width(PosterSize.standard)) {
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
                        val unwatched = season.userData?.unplayedItemCount ?: 0
                        if (unwatched > 0) {
                            UnwatchedCountBadge(
                                count = unwatched,
                                modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.sm),
                            )
                        }
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

    // Width counts too: a widescreen encode is cropped vertically, so 1280×688 is 720p and
    // 1920×800 is 1080p — height alone labelled them "688p" and "720p".
    val resolution = video?.let { v ->
        val w = v.width ?: 0
        val h = v.height ?: 0
        when {
            w >= 3200 || h >= 2000 -> "4K"
            w >= 1800 || h >= 1000 -> "1080p"
            w >= 1200 || h >= 700 -> "720p"
            h > 0 -> "SD"
            else -> null
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
                    // Press holds a finished encode at ~100% while it hashes the file.
                    "TRANSCODING" -> if (progress >= 99.5f) "Finishing…" else "Transcoding ${"%.0f".format(progress)}%"
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
    // Kid Mode hides this row's download/progress/remove/retry controls, leaving Play and Resume.
    kidMode: Boolean = false,
    onClick: () -> Unit,
    onStreamClick: ((startMs: Long) -> Unit)? = null,
    onDownloadClick: (preset: String) -> Unit,
    onDeleteClick: () -> Unit,
    onPlayOfflineClick: (path: String, jellyfinId: String, startMs: Long) -> Unit,
    modifier: Modifier = Modifier,
    // Tints the row's panel, progress bar and rating star (the series' colour on the Season screen).
    accentColor: Color = Primary,
) {
    var showPresetDialog by remember { mutableStateOf(false) }

    // On wide screens the controls sit beside the text instead of under it, which takes the row
    // from thumbnail + buttons tall down to roughly the thumbnail's height — short episodes (1–2
    // minute shorts) otherwise fit only a couple of rows on screen.
    val sideControls = LocalConfiguration.current.screenWidthDp >= 700
    val controls: @Composable () -> Unit = {
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
                                { { onPlayOfflineClick(download.playbackUri(), download.jellyfinId, offlinePos) } }
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
                            { { onPlayOfflineClick(download.playbackUri(), download.jellyfinId, 0L) } }
                        canStream && onStreamClick != null -> { { onStreamClick(0L) } }
                        else -> null
                    }
                    if (playFromStartAction != null) {
                        CompactActionButton(icon = Icons.Default.PlayArrow, onClick = playFromStartAction, text = "Play")
                    }
                    when {
                        kidMode -> {}
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
                                color = accentColor,
                            )
                            Text(
                                if (download.status == DownloadStatus.QUEUED.name) "Queued"
                                else if (download.status == DownloadStatus.TRANSCODING.name && download.progress >= 99.5f) "Finishing…"
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

    val panelShape = RoundedCornerShape(Radius.md)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(panelShape)
            .tintedPanel(accentColor, panelShape)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
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
                            tint = Success,
                            modifier = Modifier.padding(2.dp).size(IconSize.sm),
                        )
                    }
                }
                // Watch progress bar
                val watchFraction = watchProgressFraction(episode, download)
                if (watchFraction > 0.01f) {
                    LinearProgressIndicator(
                        progress = { watchFraction },
                        modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomCenter),
                        color = accentColor,
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
                                tint = accentColor, modifier = Modifier.size(IconSize.xs))
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
                if (!sideControls) {
                    Spacer(Modifier.height(4.dp))
                    controls()
                }
            }
            if (sideControls) controls()
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
    // Kid Mode hides this row's download/progress/remove/retry controls, leaving Play and Resume.
    kidMode: Boolean = false,
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
                        tint = Success,
                        modifier = Modifier.padding(2.dp).size(IconSize.sm),
                    )
                }
            }
            // Watch progress bar
            val watchFraction = watchProgressFraction(episode, download)
            if (watchFraction > 0.01f) {
                LinearProgressIndicator(
                    progress = { watchFraction },
                    modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomCenter),
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
                        { { onPlayOfflineClick(download.playbackUri(), download.jellyfinId, offlinePos) } }
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
                    { { onPlayOfflineClick(download.playbackUri(), download.jellyfinId, 0L) } }
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
                kidMode -> {}
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
