package com.fuzzymistborn.jellyjar.ui.screens

import android.app.Activity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import com.fuzzymistborn.jellyjar.R
import com.fuzzymistborn.jellyjar.model.SkipSegment
import com.fuzzymistborn.jellyjar.ui.theme.OnPrimary
import com.fuzzymistborn.jellyjar.ui.theme.OnSurface
import com.fuzzymistborn.jellyjar.ui.theme.OnSurfaceMuted
import com.fuzzymistborn.jellyjar.ui.theme.Primary
import com.fuzzymistborn.jellyjar.ui.theme.Radius
import com.fuzzymistborn.jellyjar.ui.theme.ScrimStrong
import com.fuzzymistborn.jellyjar.ui.theme.SectionHeading
import com.fuzzymistborn.jellyjar.ui.theme.Spacing
import com.fuzzymistborn.jellyjar.ui.theme.Surface
import com.fuzzymistborn.jellyjar.ui.viewmodel.NextEpisodeTarget
import com.fuzzymistborn.jellyjar.ui.viewmodel.PlayerViewModel
import com.fuzzymistborn.jellyjar.ui.viewmodel.TrickplaySpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PlayerScreen(
    localPath: String,
    jellyfinId: String? = null,
    mediaSourceId: String? = null,
    startPositionMs: Long = 0L,
    onBack: () -> Unit,
    onPlayNext: (NextEpisodeTarget) -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // Enter full-screen immersive mode. The app runs edge-to-edge for its whole lifetime
    // (enableEdgeToEdge() in MainActivity.onCreate, decorFitsSystemWindows=false) and every
    // screen's header manually clears the status bar via statusBarsPadding(). This is a single-
    // Activity app, so don't flip decorFitsSystemWindows back to true on dispose — that would
    // revert the *entire* window out of edge-to-edge mode, racing against every other screen's
    // own inset handling and intermittently leaving a header rendered under the status bar/
    // notification-pulldown area after backing out of a video. Only toggle system bar visibility.
    DisposableEffect(Unit) {
        val window = activity?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            // WindowInsetsControllerCompat.show() doesn't always trigger an immediate
            // relayout, so the next screen's statusBarsPadding() can read a stale (zero)
            // inset for one frame and render its header under the status bar. Forcing a
            // fresh insets pass here closes that race.
            window.decorView.requestApplyInsets()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(localPath))
            prepare()
            if (startPositionMs > 0L) seekTo(startPositionMs)
            playWhenReady = true
        }
    }

    // Report playback start to Jellyfin
    LaunchedEffect(jellyfinId) {
        if (jellyfinId != null) {
            viewModel.reportStart(jellyfinId, player.currentPosition, mediaSourceId)
        }
    }

    // Report progress every 10 seconds
    LaunchedEffect(jellyfinId) {
        if (jellyfinId != null) {
            while (true) {
                delay(10_000)
                viewModel.reportProgress(
                    jellyfinId,
                    player.currentPosition,
                    isPaused = !player.isPlaying,
                    mediaSourceId = mediaSourceId,
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (jellyfinId != null) {
                viewModel.savePosition(jellyfinId, player.currentPosition)
                viewModel.reportStopped(jellyfinId, player.currentPosition, mediaSourceId)
            }
            player.release()
        }
    }

    // Auto-play: when the current episode finishes, look up what comes next and hand off to it.
    val coroutineScope = rememberCoroutineScope()
    var autoPlayTriggered by remember { mutableStateOf(false) }
    DisposableEffect(player, jellyfinId) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && jellyfinId != null && !autoPlayTriggered) {
                    autoPlayTriggered = true
                    coroutineScope.launch {
                        viewModel.resolveNextEpisode(jellyfinId)?.let { onPlayNext(it) }
                    }
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    BackHandler {
        player.pause()
        onBack()
    }

    var controlsVisible by remember { mutableStateOf(false) }
    var showTrackSheet by remember { mutableStateOf(false) }

    // ── Skip intro/credits ────────────────────────────────────────────────────
    var skipSegments by remember { mutableStateOf<List<SkipSegment>>(emptyList()) }
    var positionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(jellyfinId) {
        if (jellyfinId != null) skipSegments = viewModel.loadSkipSegments(jellyfinId)
    }
    // Position ticker drives the skip button window; cheap enough at 2 Hz
    LaunchedEffect(skipSegments) {
        if (skipSegments.isNotEmpty()) {
            while (true) {
                positionMs = player.currentPosition
                delay(500)
            }
        }
    }
    // Hide the button just before the segment ends so it can't seek past useful content
    val activeSegment = skipSegments.firstOrNull {
        // Hide the button in the last second before the segment ends, but never let that
        // clip a segment shorter than 1s down to an empty (always-false) window.
        positionMs >= it.startMs && positionMs < maxOf(it.startMs, it.endMs - 1_000)
    }

    // ── Trickplay scrub previews ──────────────────────────────────────────────
    var trickplaySpec by remember { mutableStateOf<TrickplaySpec?>(null) }
    val scrubPositionMs = remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(jellyfinId) {
        // Only makes sense for streamed playback; local files have no tiles to fetch
        if (jellyfinId != null && !localPath.startsWith("/")) {
            trickplaySpec = viewModel.loadTrickplay(jellyfinId)
        }
    }

    // Distance in px from the root's bottom edge up to the top of the control bar (the row
    // containing the seek bar). Measured off the real view instead of a hardcoded dp guess —
    // a fixed offset landed in the middle of the screen on tablets, where the video area is a
    // much smaller fraction of a wide/short landscape frame than on a phone.
    var rootHeightPx by remember { mutableStateOf(0) }
    var controlBarTopPx by remember { mutableStateOf<Int?>(null) }
    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onGloballyPositioned { rootHeightPx = it.size.height },
    ) {
        AndroidView(
            factory = {
                (android.view.LayoutInflater.from(context)
                    .inflate(R.layout.player_view, null) as PlayerView).apply {
                    this.player = player
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    controllerAutoShow = true
                    controllerHideOnTouch = true
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                        controlsVisible = visibility == android.view.View.VISIBLE
                    })
                    findViewById<android.widget.ImageButton>(R.id.exo_track_select)
                        ?.setOnClickListener { showTrackSheet = true }
                    // Media3 swaps exo_progress_placeholder for a DefaultTimeBar at inflation
                    findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)
                        ?.addListener(object : TimeBar.OnScrubListener {
                            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                                scrubPositionMs.value = position
                            }
                            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                                scrubPositionMs.value = position
                            }
                            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                                scrubPositionMs.value = null
                            }
                        })
                    findViewById<android.view.View>(androidx.media3.ui.R.id.exo_bottom_bar)
                        ?.addOnLayoutChangeListener { _, _, top, _, _, _, _, _, _ ->
                            controlBarTopPx = top
                        }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Trickplay preview while scrubbing
        val spec = trickplaySpec
        val scrubPos = scrubPositionMs.value
        if (spec != null && scrubPos != null) {
            val barTop = controlBarTopPx
            val bottomPadding = if (barTop != null && rootHeightPx > 0) {
                with(density) { ((rootHeightPx - barTop).toFloat().coerceAtLeast(0f)).toDp() + Spacing.md }
            } else {
                120.dp
            }
            TrickplayPreview(
                spec = spec,
                positionMs = scrubPos,
                viewModel = viewModel,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomPadding),
            )
        }

        // Skip intro / credits button
        if (activeSegment != null) {
            Button(
                onClick = { player.seekTo(activeSegment.endMs) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = OnPrimary,
                ),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 32.dp, bottom = 96.dp),
            ) {
                Text(activeSegment.label)
            }
        }

        if (controlsVisible) {
            IconButton(
                onClick = {
                    player.pause()
                    onBack()
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(Spacing.sm),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
        }
    }

    if (showTrackSheet) {
        TrackSelectionSheet(player = player, onDismiss = { showTrackSheet = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackSelectionSheet(player: ExoPlayer, onDismiss: () -> Unit) {
    val tracks = remember { player.currentTracks }
    val audioGroups = remember(tracks) { tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO } }
    val textGroups = remember(tracks) { tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT } }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surface) {
        Column(
            modifier = Modifier
                .padding(horizontal = Spacing.xl)
                .padding(bottom = 32.dp),
        ) {
            if (audioGroups.isEmpty() && textGroups.isEmpty()) {
                Text("No alternate tracks available",
                    style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted,
                    modifier = Modifier.padding(vertical = Spacing.lg))
            }

            if (audioGroups.isNotEmpty()) {
                Text("Audio", style = MaterialTheme.typography.titleMedium, color = SectionHeading,
                    modifier = Modifier.padding(bottom = 4.dp))
                audioGroups.forEachIndexed { groupIdx, group ->
                    for (trackIdx in 0 until group.length) {
                        val format = group.getTrackFormat(trackIdx)
                        val isSelected = group.isTrackSelected(trackIdx)
                        val label = buildString {
                            append(format.language?.uppercase() ?: "Track ${groupIdx + 1}")
                            format.label?.let { append(" — $it") }
                        }
                        TrackRow(label = label, selected = isSelected) {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIdx))
                                .build()
                            onDismiss()
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.lg))
            }

            if (textGroups.isNotEmpty()) {
                Text("Subtitles", style = MaterialTheme.typography.titleMedium, color = SectionHeading,
                    modifier = Modifier.padding(bottom = 4.dp))
                val subtitlesDisabled = player.trackSelectionParameters.disabledTrackTypes
                    .contains(C.TRACK_TYPE_TEXT)
                TrackRow(label = "Off", selected = subtitlesDisabled) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                    onDismiss()
                }
                textGroups.forEachIndexed { groupIdx, group ->
                    for (trackIdx in 0 until group.length) {
                        val format = group.getTrackFormat(trackIdx)
                        val isSelected = !subtitlesDisabled && group.isTrackSelected(trackIdx)
                        val label = buildString {
                            append(format.language?.uppercase() ?: "Subtitle ${groupIdx + 1}")
                            format.label?.let { append(" — $it") }
                        }
                        TrackRow(label = label, selected = isSelected) {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIdx))
                                .build()
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

// Shows the thumbnail nearest to `positionMs` while the user drags the seek bar. Trickplay
// tiles are sprite sheets (tileWidth × tileHeight thumbnails per JPEG); we fetch the tile
// containing the target thumbnail and crop it out.
@Composable
private fun TrickplayPreview(
    spec: TrickplaySpec,
    positionMs: Long,
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
) {
    val info = spec.info
    val thumbsPerTile = info.tileWidth * info.tileHeight
    val interval = info.interval.coerceAtLeast(1)
    val thumbIndex = (positionMs / interval).toInt()
        .coerceIn(0, (info.thumbnailCount - 1).coerceAtLeast(0))
    val tileIndex = thumbIndex / thumbsPerTile

    var tile by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(spec, tileIndex) {
        tile = viewModel.loadTrickplayTile(spec, tileIndex)
    }

    val thumb = remember(tile, thumbIndex) {
        val bitmap = tile ?: return@remember null
        val indexInTile = thumbIndex % thumbsPerTile
        val x = (indexInTile % info.tileWidth) * info.width
        val y = (indexInTile / info.tileWidth) * info.height
        runCatching {
            android.graphics.Bitmap.createBitmap(
                bitmap, x, y,
                info.width.coerceAtMost(bitmap.width - x),
                info.height.coerceAtMost(bitmap.height - y),
            )
        }.getOrNull()
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (thumb != null) {
            Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .width(200.dp)
                    .clip(RoundedCornerShape(Radius.sm)),
            )
            Spacer(Modifier.height(6.dp))
        }
        androidx.compose.material3.Surface(
            color = ScrimStrong,
            shape = RoundedCornerShape(Radius.sm),
        ) {
            Text(
                text = formatPlayerTime(positionMs),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

private fun formatPlayerTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (selected) Primary else OnSurface)
        if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = Primary)
    }
    HorizontalDivider(color = OnSurfaceMuted.copy(alpha = 0.2f))
}

