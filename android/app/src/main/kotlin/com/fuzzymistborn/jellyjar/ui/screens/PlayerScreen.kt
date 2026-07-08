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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.fuzzymistborn.jellyjar.R
import com.fuzzymistborn.jellyjar.ui.theme.OnSurface
import com.fuzzymistborn.jellyjar.ui.theme.OnSurfaceMuted
import com.fuzzymistborn.jellyjar.ui.theme.Primary
import com.fuzzymistborn.jellyjar.ui.theme.Surface
import com.fuzzymistborn.jellyjar.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(
    localPath: String,
    jellyfinId: String? = null,
    mediaSourceId: String? = null,
    startPositionMs: Long = 0L,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // Enter full-screen immersive mode
    DisposableEffect(Unit) {
        val window = activity?.window ?: return@DisposableEffect onDispose {}
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            WindowCompat.setDecorFitsSystemWindows(window, true)
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

    BackHandler {
        player.pause()
        onBack()
    }

    var controlsVisible by remember { mutableStateOf(false) }
    var showTrackSheet by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
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
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (controlsVisible) {
            IconButton(
                onClick = {
                    player.pause()
                    onBack()
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
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
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            if (audioGroups.isEmpty() && textGroups.isEmpty()) {
                Text("No alternate tracks available",
                    style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted,
                    modifier = Modifier.padding(vertical = 16.dp))
            }

            if (audioGroups.isNotEmpty()) {
                Text("Audio", style = MaterialTheme.typography.titleMedium, color = Primary,
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
                Spacer(Modifier.height(16.dp))
            }

            if (textGroups.isNotEmpty()) {
                Text("Subtitles", style = MaterialTheme.typography.titleMedium, color = Primary,
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

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (selected) Primary else OnSurface)
        if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = Primary)
    }
    HorizontalDivider(color = Color(0x22FFFFFF))
}

