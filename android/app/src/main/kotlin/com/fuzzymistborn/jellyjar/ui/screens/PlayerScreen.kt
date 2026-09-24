package com.fuzzymistborn.jellyjar.ui.screens

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import com.fuzzymistborn.jellyjar.data.repository.TimesUpReason
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Seek step for a double-tap on the left/right half of the video.
private const val SEEK_STEP_MS = 10_000L

// How early the "Up next" card appears when the episode has no credits marker: enough warning
// to cancel, without covering the picture for long.
private const val UP_NEXT_LEAD_MS = 30_000L

// Marks a subtitle track that was side-loaded from the server rather than muxed into the stream.
private const val EXTERNAL_SUBTITLE_ID_PREFIX = "jf-sub-"

// Countdown used only when the duration is unknown, so no real remaining time can be shown.
private const val AUTO_PLAY_FALLBACK_SECONDS = 10

// Kid Mode daily budget: warn once when this much screen time is left.
private const val LIMIT_WARNING_MS = 5 * 60_000L

// Stopping at or past this fraction of the runtime counts as "finished" for resume-position
// purposes, matching Jellyfin server's own default MaxResumePct (90%).
private const val PLAYED_THRESHOLD = 0.90

@Composable
fun PlayerScreen(
    localPath: String,
    jellyfinId: String? = null,
    mediaSourceId: String? = null,
    startPositionMs: Long = 0L,
    // True when this screen was opened by auto-play/Up Next rather than a tap on a title — it
    // extends the Kid Mode episode streak instead of starting a new one.
    autoAdvanced: Boolean = false,
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
            // Hand the screen back to the system brightness setting. The swipe gesture sets a
            // window-level override, and without this it survives for the rest of the app's
            // lifetime — dimming the library and everything else after one dark movie.
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(localPath))
            prepare()
            if (startPositionMs > 0L) seekTo(startPositionMs)
            // Started by the screen-time check below, once it's confirmed today's budget isn't
            // already spent — otherwise a kid would get a second of audio before the times-up card.
            playWhenReady = false
        }
    }

    // ── Kid Mode screen-time limits ───────────────────────────────────────────
    // All of these checks are no-ops (never block, never count) while Kid Mode is off.
    var timesUp by remember { mutableStateOf<TimesUpReason?>(null) }
    // Set once the screen-time check lets playback begin. Jellyfin reporting waits on it, so a
    // title blocked at the door never opens (or closes) a playback session on the server.
    var playbackAllowed by remember { mutableStateOf(false) }
    // The next episode a limit held back, so a grown-up override can still hand off to it.
    var blockedNext by remember { mutableStateOf<NextEpisodeTarget?>(null) }
    var limitWarning by remember { mutableStateOf<String?>(null) }
    // Saveable so a configuration change doesn't count this episode toward the streak twice.
    var streakRecorded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!streakRecorded) {
            viewModel.onPlayerStart(autoAdvanced)
            streakRecorded = true
        }
        if (viewModel.dailyLimitReached()) {
            timesUp = TimesUpReason.DAILY_LIMIT
        } else {
            playbackAllowed = true
            player.playWhenReady = true
        }
    }
    // Time actually spent playing (not paused or buffering) counts toward the daily budget. It's
    // flushed every 10s, and the remainder once more when the player is disposed.
    val unflushedWatchMs = remember { longArrayOf(0L) }
    LaunchedEffect(player) {
        var last = SystemClock.elapsedRealtime()
        var warned = false
        while (true) {
            delay(1_000)
            val now = SystemClock.elapsedRealtime()
            if (player.isPlaying) unflushedWatchMs[0] += now - last
            last = now
            if (unflushedWatchMs[0] >= 10_000L) {
                viewModel.addWatched(unflushedWatchMs[0])
                unflushedWatchMs[0] = 0L
                val remaining = viewModel.remainingScreenTimeMs()
                if (!warned && remaining != null && remaining in 1..LIMIT_WARNING_MS) {
                    warned = true
                    val minutes = (remaining + 59_999) / 60_000
                    limitWarning = if (minutes == 1L) "1 minute left today" else "$minutes minutes left today"
                }
            }
        }
    }
    LaunchedEffect(limitWarning) {
        if (limitWarning != null) {
            delay(5_000)
            limitWarning = null
        }
    }

    // Report playback start to Jellyfin
    LaunchedEffect(jellyfinId) {
        if (jellyfinId != null) {
            snapshotFlow { playbackAllowed }.first { it }
            viewModel.reportStart(jellyfinId, player.currentPosition, mediaSourceId)
        }
    }

    // Report progress every 10 seconds
    LaunchedEffect(jellyfinId) {
        if (jellyfinId != null) {
            snapshotFlow { playbackAllowed }.first { it }
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
            if (jellyfinId != null && playbackAllowed) {
                // Playback ran to the end, or stopped close enough to it: don't persist a resume
                // position sitting at (or near) the full duration, which would otherwise leave the
                // Resume button showing at ~90-100% instead of disappearing once the item is
                // watched. Mirrors Jellyfin server's own default MaxResumePct (90%) for
                // PlaybackStopped reports, which already marks the item played server-side at that
                // point — this just keeps the locally-cached download position in sync with it.
                val duration = player.duration
                val finished = player.playbackState == Player.STATE_ENDED ||
                    (duration > 0 && player.currentPosition >= duration * PLAYED_THRESHOLD)
                if (finished) {
                    viewModel.markFinished(jellyfinId, mediaSourceId)
                } else {
                    viewModel.savePosition(jellyfinId, player.currentPosition)
                    viewModel.reportStopped(jellyfinId, player.currentPosition, mediaSourceId)
                }
            }
            viewModel.addWatched(unflushedWatchMs[0])
            player.release()
        }
    }

    // Auto-play: when the current episode finishes, look up what comes next and offer it behind a
    // short countdown rather than cutting straight to it — an unattended tablet used to roll into
    // the next episode with no way to stop it.
    val coroutineScope = rememberCoroutineScope()
    var pendingNext by remember { mutableStateOf<NextEpisodeTarget?>(null) }
    var autoPlaySecondsLeft by remember { mutableIntStateOf(AUTO_PLAY_FALLBACK_SECONDS) }
    var playbackEnded by remember { mutableStateOf(false) }
    DisposableEffect(player, jellyfinId) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) playbackEnded = true
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

    // ── Touch gestures ────────────────────────────────────────────────────────
    // Off by default in state (not in the setting) so the very first frames can't respond to a
    // gesture before the stored preference has been read back.
    var gesturesEnabled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { gesturesEnabled = viewModel.gesturesEnabled() }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var gestureFeedback by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(gestureFeedback) {
        if (gestureFeedback != null) {
            delay(700)
            gestureFeedback = null
        }
    }

    // ── Skip intro/credits ────────────────────────────────────────────────────
    var skipSegments by remember { mutableStateOf<List<SkipSegment>>(emptyList()) }
    var positionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(jellyfinId) {
        if (jellyfinId != null) skipSegments = viewModel.loadSkipSegments(jellyfinId)
    }
    // Position ticker drives both the skip button window and the Up Next trigger; cheap at 2 Hz
    var durationMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition
            durationMs = player.duration
            delay(500)
        }
    }
    // Hide the button just before the segment ends so it can't seek past useful content
    val activeSegment = skipSegments.firstOrNull {
        // Hide the button in the last second before the segment ends, but never let that
        // clip a segment shorter than 1s down to an empty (always-false) window.
        positionMs >= it.startMs && positionMs < maxOf(it.startMs, it.endMs - 1_000)
    }

    // Resolving what plays next, and deciding whether to leave, both live in this one coroutine —
    // keyed only on the item. An earlier version keyed the effect on the "should offer next now"
    // condition and flipped that same condition inside the body, which cancelled the coroutine
    // mid-lookup: the card never appeared and the player just exited at the end of every episode.
    LaunchedEffect(jellyfinId) {
        if (jellyfinId == null) {
            // Nothing to look up (a local file opened without its Jellyfin id) — just don't sit on
            // a frozen last frame.
            snapshotFlow { playbackEnded }.first { it }
            onBack()
            return@LaunchedEffect
        }
        // Waits for whichever comes first: the outro (the credits marker when the server has one,
        // otherwise a fixed lead time), or the episode simply ending — short files, or a duration
        // the player never reported. Every value here is read *inside* the snapshotFlow lambda:
        // computing the condition outside it would capture one stale value and never re-evaluate.
        snapshotFlow {
            val creditsStartMs = skipSegments.firstOrNull { it.type == "Outro" }?.startMs
            playbackEnded ||
                (creditsStartMs != null && positionMs >= creditsStartMs) ||
                (durationMs > 0 && positionMs > 0 && durationMs - positionMs <= UP_NEXT_LEAD_MS)
        }.first { it }
        val target = viewModel.resolveNextEpisode(jellyfinId)
        // Kid Mode limits hold the resolved episode back rather than skipping the lookup, so a
        // grown-up override on the times-up card can still hand off to it. Only checked when
        // there *is* a next episode — "enough episodes in a row" makes no sense after a movie.
        val limitReason = when {
            target == null -> null
            viewModel.streakReached() -> TimesUpReason.EPISODE_STREAK
            viewModel.dailyLimitReached() -> TimesUpReason.DAILY_LIMIT
            else -> null
        }
        if (target != null && limitReason == null) {
            pendingNext = target
        } else {
            // Auto-play off, series finale, a movie, or a limit reached: act once playback is
            // actually over. The current title always gets to finish — cutting a show off
            // mid-scene is how a screen-time rule turns into an argument.
            snapshotFlow { playbackEnded }.first { it }
            val reason = limitReason
                ?: if (viewModel.dailyLimitReached()) TimesUpReason.DAILY_LIMIT else null
            if (reason != null) {
                blockedNext = target
                timesUp = reason
            } else {
                onBack()
            }
        }
    }

    // The countdown tracks the real time left in the episode, so a card raised at the start of a
    // 90-second credits roll doesn't claim the next episode is 10 seconds away.
    LaunchedEffect(pendingNext) {
        val target = pendingNext ?: return@LaunchedEffect
        var fallbackLeft = AUTO_PLAY_FALLBACK_SECONDS
        while (true) {
            if (player.playbackState == Player.STATE_ENDED) break
            val duration = player.duration
            if (duration > 0) {
                val remaining = duration - player.currentPosition
                if (remaining <= 0) break
                autoPlaySecondsLeft = ((remaining + 999) / 1000).toInt()
                delay(500)
            } else {
                autoPlaySecondsLeft = fallbackLeft
                if (fallbackLeft <= 0) break
                fallbackLeft--
                delay(1_000)
            }
        }
        onPlayNext(target)
    }

    // ── Trickplay scrub previews ──────────────────────────────────────────────
    var trickplaySpec by remember { mutableStateOf<TrickplaySpec?>(null) }
    val scrubPositionMs = remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(jellyfinId) {
        // Only makes sense for streamed playback; local files have no tiles to fetch
        if (jellyfinId != null && (localPath.startsWith("http://") || localPath.startsWith("https://"))) {
            trickplaySpec = viewModel.loadTrickplay(jellyfinId)
        }
    }

    // ── Codec diagnostics (Direct Play / Direct Stream / Transcoding) ────────────
    var playbackDiagnostics by remember { mutableStateOf<com.fuzzymistborn.jellyjar.data.repository.PlaybackDiagnostics?>(null) }
    val isStreamed = jellyfinId != null && (localPath.startsWith("http://") || localPath.startsWith("https://"))
    LaunchedEffect(jellyfinId) {
        // Downloaded files play straight off disk — no server negotiation happened, so there's
        // nothing to report.
        if (isStreamed) {
            playbackDiagnostics = viewModel.loadPlaybackDiagnostics(jellyfinId!!)
        }
    }

    // ── Server-side track list ────────────────────────────────────────────────
    // A transcoded stream arrives with one audio track and no embedded subtitles, so ExoPlayer's
    // own track list can't drive the picker. The negotiation reports everything the source has.
    var streamResolution by remember {
        mutableStateOf<com.fuzzymistborn.jellyjar.data.repository.StreamResolution?>(null)
    }
    var selectedAudioIndex by remember { mutableStateOf<Int?>(null) }
    var selectedSubtitleIndex by remember { mutableStateOf<Int?>(null) }
    var trackChanging by remember { mutableStateOf(false) }
    var subtitlesAttached by remember { mutableStateOf(false) }
    LaunchedEffect(jellyfinId) {
        if (isStreamed) {
            val resolution = viewModel.loadStreamResolution(jellyfinId!!)
            streamResolution = resolution
            selectedAudioIndex = resolution?.selectedAudioIndex
            selectedSubtitleIndex = resolution?.selectedSubtitleIndex
        }
    }
    // Text subtitles are delivered as separate files rather than muxed into the stream, so they
    // have to be attached to the MediaItem. Done once, in place, at the current position.
    LaunchedEffect(streamResolution) {
        val resolution = streamResolution ?: return@LaunchedEffect
        if (subtitlesAttached || resolution.externalSubtitles.isEmpty()) return@LaunchedEffect
        subtitlesAttached = true
        val resumeMs = player.currentPosition
        val wasPlaying = player.playWhenReady
        player.setMediaItem(buildMediaItem(resolution.url, resolution.externalSubtitles))
        player.prepare()
        if (resumeMs > 0) player.seekTo(resumeMs)
        player.playWhenReady = wasPlaying
    }

    // ── In-player streaming quality switcher ──────────────────────────────────
    var currentQuality by remember { mutableStateOf<com.fuzzymistborn.jellyjar.model.PlaybackQuality?>(null) }
    var qualityChanging by remember { mutableStateOf(false) }
    LaunchedEffect(jellyfinId) {
        if (isStreamed) currentQuality = viewModel.currentPlaybackQuality()
    }

    // ── Nerd stats (actual decoded format) ────────────────────────────────────
    // The negotiated PlaybackDiagnostics above only reflects what the server *offered*; the
    // format ExoPlayer actually ends up decoding is the ground truth for "is this really
    // transcoding," so read it straight off the player instead of trusting the negotiation.
    var videoFormat by remember { mutableStateOf<androidx.media3.common.Format?>(null) }
    var audioFormat by remember { mutableStateOf<androidx.media3.common.Format?>(null) }
    var nerdStatsVisible by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                videoFormat = player.videoFormat
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                videoFormat = player.videoFormat
                audioFormat = player.audioFormat
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
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
                    playerViewRef = this
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

        // Gesture surface. Deliberately only present while the controls are hidden: it sits above
        // the PlayerView and would otherwise swallow taps meant for the seek bar and buttons.
        // With controls showing, taps fall through to PlayerView, which hides them as before.
        if (gesturesEnabled && !controlsVisible) {
            val audioManager = remember {
                context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            }
            var dragIsBrightness by remember { mutableStateOf(false) }
            var dragValue by remember { mutableFloatStateOf(0f) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { playerViewRef?.showController() },
                            onDoubleTap = { offset ->
                                val forward = offset.x > size.width / 2f
                                val target = player.currentPosition +
                                    if (forward) SEEK_STEP_MS else -SEEK_STEP_MS
                                val duration = player.duration
                                player.seekTo(
                                    if (duration > 0) target.coerceIn(0L, duration)
                                    else target.coerceAtLeast(0L)
                                )
                                gestureFeedback = if (forward) "+10s" else "−10s"
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                dragIsBrightness = offset.x < size.width / 2f
                                dragValue = if (dragIsBrightness) {
                                    val current = activity?.window?.attributes?.screenBrightness ?: -1f
                                    // -1 means "follow the system setting"; there's no way to read
                                    // the effective value back, so start the drag from the middle.
                                    if (current < 0f) 0.5f else current
                                } else {
                                    val max = audioManager
                                        .getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                                        .coerceAtLeast(1)
                                    audioManager
                                        .getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                                        .toFloat() / max
                                }
                            },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                // Dragging up (negative dragAmount) increases; a full-height swipe
                                // covers the whole range.
                                dragValue = (dragValue - dragAmount / size.height).coerceIn(0f, 1f)
                                val percent = (dragValue * 100).toInt()
                                if (dragIsBrightness) {
                                    activity?.window?.let { window ->
                                        window.attributes = window.attributes.apply {
                                            screenBrightness = dragValue.coerceAtLeast(0.01f)
                                        }
                                    }
                                    gestureFeedback = "Brightness $percent%"
                                } else {
                                    val max = audioManager
                                        .getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                                    audioManager.setStreamVolume(
                                        android.media.AudioManager.STREAM_MUSIC,
                                        (dragValue * max).toInt(),
                                        0,
                                    )
                                    gestureFeedback = "Volume $percent%"
                                }
                            },
                        )
                    },
            )
        }

        gestureFeedback?.let { feedback ->
            androidx.compose.material3.Surface(
                color = ScrimStrong,
                shape = RoundedCornerShape(Radius.sm),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(
                    text = feedback,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                )
            }
        }

        pendingNext?.let { target ->
            UpNextCard(
                title = target.title,
                secondsLeft = autoPlaySecondsLeft,
                // Clearing this first cancels the countdown effect, so the handoff can't fire
                // twice if navigation takes a moment to tear this screen down.
                onPlayNow = {
                    pendingNext = null
                    onPlayNext(target)
                },
                onCancel = { pendingNext = null },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(Spacing.xl),
            )
        }

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

        // Tap the collapsed method label to expand the full nerd-stats breakdown
        // (resolution/bitrate/codec) for as long as it stays visible.
        if (controlsVisible) playbackDiagnostics?.let { diag ->
            val label = when (diag.method) {
                com.fuzzymistborn.jellyjar.data.repository.PlaybackMethod.DIRECT_PLAY -> "Direct Play"
                com.fuzzymistborn.jellyjar.data.repository.PlaybackMethod.DIRECT_STREAM -> "Direct Stream"
                com.fuzzymistborn.jellyjar.data.repository.PlaybackMethod.TRANSCODE -> "Transcoding"
            }
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(Spacing.sm)
                    .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small)
                    .clickable { nerdStatsVisible = !nerdStatsVisible }
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            ) {
                Text(
                    text = if (diag.reasons.isNotEmpty()) "$label · ${diag.reasons.joinToString(", ")}" else label,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
                if (nerdStatsVisible) {
                    val vf = videoFormat
                    val af = audioFormat
                    val statLines = buildList {
                        diag.container?.let { add("Container: $it") }
                        if (vf != null) {
                            add("Video: ${vf.sampleMimeType?.substringAfterLast('/') ?: "?"} ${vf.width}x${vf.height}")
                            if (vf.bitrate > 0) add("Video bitrate: ${vf.bitrate / 1000} kbps")
                            if (vf.frameRate > 0f) add("Frame rate: ${"%.2f".format(vf.frameRate)} fps")
                        }
                        if (af != null) {
                            add("Audio: ${af.sampleMimeType?.substringAfterLast('/') ?: "?"} ${af.channelCount}ch")
                            if (af.bitrate > 0) add("Audio bitrate: ${af.bitrate / 1000} kbps")
                        }
                        if (vf == null && af == null) add("Waiting for track info…")
                    }
                    statLines.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
            }
        }

        limitWarning?.let { warning ->
            androidx.compose.material3.Surface(
                color = ScrimStrong,
                shape = RoundedCornerShape(Radius.sm),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = Spacing.xl),
            ) {
                Text(
                    text = warning,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                )
            }
        }

        // Drawn last so it covers the video and swallows every tap meant for the controls.
        timesUp?.let { reason ->
            TimesUpOverlay(
                reason = reason,
                viewModel = viewModel,
                onDone = onBack,
                onGranted = {
                    timesUp = null
                    val next = blockedNext
                    when {
                        next != null -> {
                            blockedNext = null
                            onPlayNext(next)
                        }
                        // Granted after the title had already finished (e.g. a movie) — nothing
                        // queued to continue into, so leave for the kid to pick something.
                        playbackEnded -> onBack()
                        // Blocked before playback started: start it now.
                        else -> {
                            playbackAllowed = true
                            player.playWhenReady = true
                        }
                    }
                },
            )
        }
    }

    if (showTrackSheet) {
        // Reloads the stream after any server-side change (quality or track), keeping position
        // and play/pause state, and re-attaching whatever subtitles the new stream reports.
        fun applyResolution(
            resolution: com.fuzzymistborn.jellyjar.data.repository.StreamResolution,
        ) {
            val resumeMs = player.currentPosition
            val wasPlaying = player.playWhenReady
            player.setMediaItem(buildMediaItem(resolution.url, resolution.externalSubtitles))
            player.prepare()
            if (resumeMs > 0) player.seekTo(resumeMs)
            player.playWhenReady = wasPlaying
            streamResolution = resolution
            subtitlesAttached = true
            playbackDiagnostics = resolution.diagnostics
            selectedAudioIndex = resolution.selectedAudioIndex
            selectedSubtitleIndex = resolution.selectedSubtitleIndex
        }

        PlayerSettingsSheet(
            player = player,
            quality = if (isStreamed) currentQuality else null,
            showQuality = isStreamed,
            qualityChanging = qualityChanging,
            onSelectQuality = { quality ->
                if (quality == currentQuality || jellyfinId == null) return@PlayerSettingsSheet
                qualityChanging = true
                coroutineScope.launch {
                    val resolution = viewModel.changeStreamQuality(
                        jellyfinId, quality, selectedAudioIndex, selectedSubtitleIndex,
                    )
                    applyResolution(resolution)
                    currentQuality = quality
                    qualityChanging = false
                }
            },
            serverAudioTracks = if (isStreamed) streamResolution?.audioTracks.orEmpty() else emptyList(),
            serverSubtitleTracks = if (isStreamed) streamResolution?.subtitleTracks.orEmpty() else emptyList(),
            externalSubtitles = streamResolution?.externalSubtitles.orEmpty(),
            selectedAudioIndex = selectedAudioIndex,
            selectedSubtitleIndex = selectedSubtitleIndex,
            trackChanging = trackChanging,
            onSelectServerAudio = { index ->
                if (jellyfinId == null || index == selectedAudioIndex) return@PlayerSettingsSheet
                trackChanging = true
                coroutineScope.launch {
                    applyResolution(
                        viewModel.changeStreamTracks(jellyfinId, index, selectedSubtitleIndex)
                    )
                    trackChanging = false
                }
                showTrackSheet = false
            },
            onSelectServerSubtitle = { index ->
                if (jellyfinId == null) return@PlayerSettingsSheet
                trackChanging = true
                coroutineScope.launch {
                    applyResolution(
                        viewModel.changeStreamTracks(jellyfinId, selectedAudioIndex, index)
                    )
                    trackChanging = false
                }
                showTrackSheet = false
            },
            onDismiss = { showTrackSheet = false },
        )
    }
}

// Attaches side-loaded subtitle files to the stream so they show up as ordinary text tracks.
private fun buildMediaItem(
    url: String,
    subtitles: List<com.fuzzymistborn.jellyjar.data.repository.ExternalSubtitle>,
): MediaItem =
    MediaItem.Builder()
        .setUri(url)
        .setSubtitleConfigurations(
            subtitles.map { subtitle ->
                MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(subtitle.url))
                    .setId(EXTERNAL_SUBTITLE_ID_PREFIX + subtitle.index)
                    .setMimeType(subtitle.mimeType)
                    .setLanguage(subtitle.language)
                    .setLabel(subtitle.label)
                    .build()
            }
        )
        .build()

// Combines streaming-quality and audio/subtitle track selection into a single sheet, reached
// from the one gear icon in the bottom control bar — previously this was two separate gear
// icons (one custom, one from ExoPlayer's controls), which read as duplicated settings.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSettingsSheet(
    player: ExoPlayer,
    quality: com.fuzzymistborn.jellyjar.model.PlaybackQuality?,
    showQuality: Boolean,
    qualityChanging: Boolean,
    onSelectQuality: (com.fuzzymistborn.jellyjar.model.PlaybackQuality) -> Unit,
    serverAudioTracks: List<com.fuzzymistborn.jellyjar.data.repository.ServerTrack> = emptyList(),
    serverSubtitleTracks: List<com.fuzzymistborn.jellyjar.data.repository.ServerTrack> = emptyList(),
    externalSubtitles: List<com.fuzzymistborn.jellyjar.data.repository.ExternalSubtitle> = emptyList(),
    selectedAudioIndex: Int? = null,
    selectedSubtitleIndex: Int? = null,
    trackChanging: Boolean = false,
    onSelectServerAudio: (Int) -> Unit = {},
    onSelectServerSubtitle: (Int?) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val tracks = remember { player.currentTracks }
    val audioGroups = remember(tracks) { tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO } }
    val textGroups = remember(tracks) { tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT } }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surface) {
        Column(
            modifier = Modifier
                .padding(horizontal = Spacing.xl)
                .padding(bottom = 32.dp),
        ) {
            if (showQuality) {
                Text(
                    "Streaming Quality",
                    style = MaterialTheme.typography.titleMedium,
                    color = SectionHeading,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                com.fuzzymistborn.jellyjar.model.PlaybackQuality.entries.forEach { entry ->
                    TrackRow(
                        label = entry.label,
                        selected = entry == quality,
                        enabled = !qualityChanging,
                        onClick = { onSelectQuality(entry) },
                    )
                }
                Spacer(Modifier.height(Spacing.lg))
            }

            if (audioGroups.isEmpty() && textGroups.isEmpty() &&
                serverAudioTracks.size <= 1 && serverSubtitleTracks.isEmpty()
            ) {
                Text("No alternate tracks available",
                    style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted,
                    modifier = Modifier.padding(vertical = Spacing.lg))
            }

            // Prefer the server's list whenever it knows about more audio than ExoPlayer can see —
            // that's exactly the transcoding case, where only the chosen track is delivered and
            // switching has to go back through the server.
            val useServerAudio = serverAudioTracks.size > audioGroups.sumOf { it.length }
            if (useServerAudio) {
                Text("Audio", style = MaterialTheme.typography.titleMedium, color = SectionHeading,
                    modifier = Modifier.padding(bottom = 4.dp))
                serverAudioTracks.forEach { track ->
                    TrackRow(
                        label = track.label,
                        selected = track.index == selectedAudioIndex,
                        enabled = !trackChanging,
                        onClick = { onSelectServerAudio(track.index) },
                    )
                }
                Spacer(Modifier.height(Spacing.lg))
            }

            if (!useServerAudio && audioGroups.isNotEmpty()) {
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

            // Server subtitle list covers both kinds: text tracks side-loaded as separate files
            // (selectable instantly, no reload) and image tracks, which only exist once the server
            // burns them in — those need a re-negotiation.
            if (serverSubtitleTracks.isNotEmpty()) {
                Text("Subtitles", style = MaterialTheme.typography.titleMedium, color = SectionHeading,
                    modifier = Modifier.padding(bottom = 4.dp))
                val subtitlesDisabled = player.trackSelectionParameters.disabledTrackTypes
                    .contains(C.TRACK_TYPE_TEXT)
                val externalIndices = externalSubtitles.map { it.index }.toSet()

                fun isSideLoadedSelected(index: Int): Boolean =
                    !subtitlesDisabled && textGroups.any { group ->
                        (0 until group.length).any { trackIdx ->
                            group.isTrackSelected(trackIdx) &&
                                group.getTrackFormat(trackIdx).id == EXTERNAL_SUBTITLE_ID_PREFIX + index
                        }
                    }

                TrackRow(
                    label = "Off",
                    selected = subtitlesDisabled ||
                        (selectedSubtitleIndex == null && textGroups.isEmpty()),
                    enabled = !trackChanging,
                ) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                    // A burned-in subtitle is part of the video and can only be removed by asking
                    // the server for a stream without it.
                    if (selectedSubtitleIndex != null && selectedSubtitleIndex !in externalIndices) {
                        onSelectServerSubtitle(null)
                    } else {
                        onDismiss()
                    }
                }
                serverSubtitleTracks.forEach { track ->
                    val sideLoaded = track.index in externalIndices
                    TrackRow(
                        label = if (sideLoaded) track.label else "${track.label} (burned in)",
                        selected = if (sideLoaded) isSideLoadedSelected(track.index)
                        else selectedSubtitleIndex == track.index,
                        enabled = !trackChanging,
                    ) {
                        if (sideLoaded) {
                            val target = textGroups.firstNotNullOfOrNull { group ->
                                (0 until group.length).firstOrNull { trackIdx ->
                                    group.getTrackFormat(trackIdx).id ==
                                        EXTERNAL_SUBTITLE_ID_PREFIX + track.index
                                }?.let { group to it }
                            }
                            if (target != null) {
                                player.trackSelectionParameters =
                                    player.trackSelectionParameters.buildUpon()
                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                        .setOverrideForType(
                                            TrackSelectionOverride(
                                                target.first.mediaTrackGroup, target.second,
                                            )
                                        )
                                        .build()
                                onDismiss()
                            } else {
                                // Side-load hasn't finished attaching yet — fall back to the server.
                                onSelectServerSubtitle(track.index)
                            }
                        } else {
                            onSelectServerSubtitle(track.index)
                        }
                    }
                }
            } else if (textGroups.isNotEmpty()) {
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

// Shown when playback ends and a next episode was resolved. Auto-play still happens on its own,
// but never without a visible countdown and a way out.
@Composable
private fun UpNextCard(
    title: String,
    secondsLeft: Int,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Surface(
        color = Surface,
        shape = RoundedCornerShape(Radius.md),
        modifier = modifier.widthIn(max = 360.dp),
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Text(
                "Up next",
                style = MaterialTheme.typography.labelMedium,
                color = OnSurfaceMuted,
            )
            if (title.isNotBlank()) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = OnSurface,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                "Playing in ${secondsLeft}s",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.padding(top = Spacing.sm),
            ) {
                Button(
                    onClick = onPlayNow,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        contentColor = OnPrimary,
                    ),
                ) {
                    Text("Play now")
                }
                TextButton(onClick = onCancel) {
                    Text("Cancel", color = OnSurfaceMuted)
                }
            }
        }
    }
}

// Full-screen stop card for the Kid Mode screen-time limits. Written for the kid first ("Done" is
// the big obvious action); the grown-up override sits behind a small link and the admin PIN.
@Composable
private fun TimesUpOverlay(
    reason: TimesUpReason,
    viewModel: PlayerViewModel,
    onDone: () -> Unit,
    onGranted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var canOverride by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { canOverride = viewModel.canOverrideLimits() }
    var pinEntryOpen by remember { mutableStateOf(false) }
    var unlocked by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    val (title, message) = when (reason) {
        TimesUpReason.EPISODE_STREAK -> "Time for a break!" to "That's enough episodes in a row for now."
        TimesUpReason.DAILY_LIMIT -> "All done for today!" to "That's all the screen time for today. See you tomorrow!"
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            // Swallow taps so nothing reaches the player controls underneath.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            modifier = Modifier
                .widthIn(max = 420.dp)
                .padding(Spacing.xl),
        ) {
            Icon(
                Icons.Default.Bedtime,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(72.dp),
            )
            Text(title, style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Button(
                onClick = onDone,
                colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text("Done")
            }

            when {
                !canOverride -> Unit
                unlocked -> {
                    Text("Grown-up override", style = MaterialTheme.typography.labelMedium, color = OnSurfaceMuted)
                    val grants: List<Pair<String, suspend () -> Unit>> = when (reason) {
                        TimesUpReason.EPISODE_STREAK -> listOf(
                            "One more episode" to suspend { viewModel.grantEpisode() },
                            "No limit today" to suspend { viewModel.grantUnlimitedToday() },
                        )
                        TimesUpReason.DAILY_LIMIT -> listOf(
                            "+30 minutes" to suspend { viewModel.grantMinutes(30) },
                            "+1 hour" to suspend { viewModel.grantMinutes(60) },
                            "No limit today" to suspend { viewModel.grantUnlimitedToday() },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        grants.forEach { (label, grant) ->
                            OutlinedButton(onClick = {
                                scope.launch {
                                    grant()
                                    onGranted()
                                }
                            }) {
                                Text(label, color = Color.White)
                            }
                        }
                    }
                }
                pinEntryOpen -> {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = {
                            if (it.length <= 8) {
                                pin = it
                                pinError = false
                            }
                        },
                        label = { Text("Admin PIN") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                        ),
                        isError = pinError,
                        supportingText = if (pinError) { { Text("Incorrect PIN") } } else null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = themedTextFieldColors(),
                    )
                    TextButton(onClick = {
                        scope.launch {
                            if (viewModel.verifyPin(pin)) {
                                unlocked = true
                            } else {
                                pinError = true
                                pin = ""
                            }
                        }
                    }) {
                        Text("Unlock", color = Primary)
                    }
                }
                else -> TextButton(onClick = { pinEntryOpen = true }) {
                    Text("Grown-ups: more time", color = OnSurfaceMuted)
                }
            }
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
private fun TrackRow(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (!enabled) OnSurfaceMuted else if (selected) Primary else OnSurface)
        if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = Primary)
    }
    HorizontalDivider(color = OnSurfaceMuted.copy(alpha = 0.2f))
}

