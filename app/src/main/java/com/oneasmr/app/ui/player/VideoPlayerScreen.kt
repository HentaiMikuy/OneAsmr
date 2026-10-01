package com.oneasmr.app.ui.player

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import com.oneasmr.app.R
import com.oneasmr.app.data.local.KeySpec
import com.oneasmr.app.data.local.SingleFileDao
import com.oneasmr.app.data.local.SingleFileKind
import com.oneasmr.app.data.local.WorkDao
import com.oneasmr.app.data.local.isCensored
import com.oneasmr.app.data.local.settings.SettingsStore
import com.oneasmr.app.data.scanner.TrackTreeBuilder
import com.oneasmr.app.data.scanner.WorkPathResolver
import com.oneasmr.app.domain.player.PlaybackSpeed
import com.oneasmr.app.domain.player.VideoGestureOps
import com.oneasmr.app.navigation.Routes
import com.oneasmr.app.player.PlaybackService
import com.oneasmr.app.player.continuesAfterPageExit
import com.oneasmr.app.player.coverArtUri
import com.oneasmr.app.player.ResumePositionResolver
import com.oneasmr.app.player.SessionConnection
import com.oneasmr.app.player.VideoEntryDecision
import com.oneasmr.app.player.VideoTrackFinder
import com.oneasmr.app.ui.common.singleThumbModel
import com.oneasmr.app.ui.work.DocumentFsFactory
import com.oneasmr.app.ui.player.SpeedPickerSheet
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Attached-video player page (plan Task 22).
 *
 * PLAYER-SHARING DECISION (documented per plan): the video plays through the
 * SAME MediaSession/ExoPlayer singleton as audio ([PlaybackService]) — the
 * plan-recommended option. Rationale:
 *
 * 1. The Task 17 background audio policy stays on the single managed player:
 *    audio focus, becoming-noisy, wake mode and the foreground service /
 *    notification are handled once, and video cannot fight audio for focus.
 * 2. Playback-position memory (Task 19) rides the existing
 *    [com.oneasmr.app.player.PlaybackProgressWriter] unchanged: the video
 *    item's mediaId IS its trackKey "{scope}:{rjCode}:{trackIndex}", so the
 *    5s cadence + pause-flush writes the video position under the same key
 *    and the same >95%/<3% [com.oneasmr.app.player.ResumePolicy] applies on
 *    reopen — no second write machinery.
 * 3. The swap is explicit and symmetric: [PlaybackService.ACTION_VIDEO_ENTER]
 *    snapshots the audio context (queue/index/position/speed/repeat/shuffle/
 *    playWhenReady) BEFORE the video item replaces the timeline, and
 *    [PlaybackService.ACTION_VIDEO_EXIT] restores it verbatim — so
 *    "返回音频队列时恢复音频上下文" is deterministic, not dependent on
 *    process survival.
 * 4. The video session timeline is MULTI-item (plan video-player-controls
 *    Todo 3): same-folder siblings (single-file route) or all VIDEO tracks
 *    of the work replace the audio queue in one `setMediaItems`, with
 *    repeat/shuffle NEUTRALIZED (REPEAT_MODE_OFF + shuffle off) inside the
 *    enter-snapshot listener and restored on exit — from the service's saved
 *    audio context (ACTION_VIDEO_EXIT) or from the ViewModel's own pre-entry
 *    capture (single-file leave-playing, which first collapses the timeline
 *    back to the current item). The service still skips QueueStore
 *    persistence in video mode, so even a force-stop mid-video cold-restores
 *    the AUDIO queue.
 *
 * Single-file entries (单档, the Videos tab) share the page but not the
 * attached-video exit contract: this page IS their player page, so leaving it
 * keeps them playing as ordinary session items ([exitSingleFile]) — mini
 * player pill, notification, lockscreen controls and resume memory included —
 * while attached videos are still removed on exit (see [exitVideo]).
 *
 * The MediaItem source is generic ([MediaItem.Builder.setUri]) — this page
 * is local-only today, playing content:// SAF uris from the local library.
 *
 * Must-NOT honored: no subtitle-track switching, no PiP, no casting; video is
 * never added to the shuffle/queue logic.
 *
 * Fullscreen: orientation change + immersive system bars (configChanges is
 * declared on MainActivity, so rotation recomposes without activity
 * recreation); portrait↔landscape via the fullscreen toggle. A lock guard
 * hides all controls and blocks the back gesture to prevent accidental exit.
 */
@Composable
fun VideoPlayerScreen(
    viewModel: VideoPlayerViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val window = remember(activity) { activity?.window }
    val view = LocalView.current

    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var locked by rememberSaveable { mutableStateOf(false) }
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var showSpeedDialog by rememberSaveable { mutableStateOf(false) }
    var lockHintTick by remember { mutableIntStateOf(0) }
    var lockHint by remember { mutableStateOf<String?>(null) }
    var dragPosition by remember { mutableFloatStateOf(-1f) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var lastInteractionTick by remember { mutableIntStateOf(0) }
    var showPlaylistPanel by rememberSaveable { mutableStateOf(false) }
    // Set by the gesture layer (Todo 6); suppresses auto-hide while dragging.
    var gestureActive by remember { mutableStateOf(false) }
    var gestureOverlay by remember { mutableStateOf<GestureFeedback?>(null) }
    var gestureOverlayTick by remember { mutableIntStateOf(0) }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    // Fullscreen: hide system bars (immersive) + landscape orientation.
    LaunchedEffect(fullscreen) {
        val w = window ?: return@LaunchedEffect
        val controller = WindowInsetsControllerCompat(w, view)
        if (fullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    // Keep the screen on while watching; restore orientation + bars on leave.
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            window?.let {
                WindowInsetsControllerCompat(it, view).show(WindowInsetsCompat.Type.systemBars())
                // The brightness gesture owns a window-level override only for
                // as long as the page lives (plan Todo 6c).
                applyBrightnessFraction(it, WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            }
        }
    }
    // Backgrounding (Home / power off) pauses the video; the audio policy
    // itself stays untouched (shared session).
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
                Lifecycle.Event.ON_START -> viewModel.onAppForegrounded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Lock guard: back while locked only hints, never exits.
    BackHandler(enabled = true) {
        if (locked) {
            lockHint = "已锁定，请先解锁"
            lockHintTick++
        } else {
            viewModel.exitVideo()
            onBack()
        }
    }
    LaunchedEffect(lockHintTick) {
        if (lockHint != null) {
            delay(HINT_MS)
            lockHint = null
        }
    }
    // Feedback linger is 1500ms (uiautomator dumps land ~1-2s after the
    // gesture); a drag keeps its overlay up and only drag end starts the timer.
    LaunchedEffect(gestureOverlayTick, gestureActive) {
        if (gestureOverlay != null && !gestureActive) {
            delay(GESTURE_FEEDBACK_MS)
            gestureOverlay = null
        }
    }
    // 3s auto-hide while playing; shouldAutoHide keeps the controls up while
    // paused, dragging, gesture-active, sheet/panel open, locked or hinting.
    LaunchedEffect(
        uiState.isPlaying,
        lastInteractionTick,
        dragPosition >= 0f,
        gestureActive,
        showSpeedDialog,
        showPlaylistPanel,
        locked,
        lockHint != null,
    ) {
        val hide = VideoGestureOps.shouldAutoHide(
            isPlaying = uiState.isPlaying,
            dragging = dragPosition >= 0f,
            gestureActive = gestureActive,
            sheetOpen = showSpeedDialog,
            panelOpen = showPlaylistPanel,
            locked = locked,
            hintVisible = lockHint != null,
        )
        if (hide) {
            delay(AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // 快照状态:控制器(可能晚于首帧)连接的那一刻,update 重跑并把
        // player 绑上 PlayerView —— 否则冷启动服务时 surface 永远接不上
        // (黑屏但有声)。
        val sessionPlayer by viewModel.playerFlow.collectAsStateWithLifecycle()
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.video_player_view, null) as PlayerView)
                    .also { playerView = it }
            },
            modifier = Modifier
                .fillMaxSize()
                // Tap layer (Todo 6): single tap toggles the controls (after the
                // double-tap timeout), double tap seeks ±10s by screen half.
                .pointerInput(locked) {
                    if (locked) return@pointerInput
                    detectTapGestures(
                        onTap = {
                            controlsVisible = !controlsVisible
                            if (controlsVisible) lastInteractionTick++
                        },
                        onDoubleTap = { offset ->
                            val forward = offset.x >= size.width / 2f
                            viewModel.skipBy(
                                if (forward) {
                                    VideoGestureOps.SKIP_STEP_MS
                                } else {
                                    -VideoGestureOps.SKIP_STEP_MS
                                },
                            )
                            gestureOverlay = GestureFeedback(
                                text = if (forward) "+10s" else "-10s",
                                description = "video seek feedback=" +
                                    if (forward) "+10s" else "-10s",
                            )
                            gestureOverlayTick++
                            lastInteractionTick++
                            controlsVisible = true
                        },
                    )
                }
                // Vertical drag layer (Todo 6): left half = window brightness,
                // right half = STREAM_MUSIC volume. A passed slop cancels the
                // tap detector above (no toggle from drags).
                .pointerInput(locked) {
                    if (locked) return@pointerInput
                    var brightnessGesture = false
                    var value = 0f
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            brightnessGesture = offset.x < size.width / 2f
                            value = if (brightnessGesture) {
                                seedBrightnessFraction(window, context.contentResolver)
                            } else {
                                seedVolumeFraction(audioManager)
                            }
                            gestureActive = true
                            controlsVisible = true
                            lastInteractionTick++
                            gestureOverlay = gestureFeedback(brightnessGesture, value)
                            gestureOverlayTick++
                        },
                        onVerticalDrag = { _, dragAmount ->
                            val delta = VideoGestureOps.dragDeltaToFraction(
                                dragAmount,
                                size.height.toFloat(),
                            )
                            value = VideoGestureOps.applyFraction(value, delta)
                            if (brightnessGesture) {
                                applyBrightnessFraction(window, value)
                            } else {
                                applyVolumeFraction(audioManager, value)
                            }
                            gestureOverlay = gestureFeedback(brightnessGesture, value)
                            lastInteractionTick++
                        },
                        onDragEnd = {
                            gestureActive = false
                            gestureOverlayTick++
                            lastInteractionTick++
                        },
                        onDragCancel = {
                            gestureActive = false
                            gestureOverlayTick++
                            lastInteractionTick++
                        },
                    )
                },
            update = { it.player = sessionPlayer },
        )
        DisposableEffect(Unit) {
            onDispose {
                playerView?.player = null
                playerView = null
            }
        }

        if (uiState.loading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
        }

        uiState.error?.let { message ->
            ErrorOverlay(message = message, onBack = { viewModel.exitVideo(); onBack() })
        }

        // Unsupported codec / broken source: "无法播放" (plan QA failure path).
        // The service restored the audio context already — back is the only
        // action, controls are hidden (playing again would play the audio).
        if (uiState.playbackFailed) {
            FailureCard(
                message = uiState.failedMessage,
                onBack = { viewModel.exitVideo(); onBack() },
            )
        }

        if (!locked && controlsVisible && uiState.error == null && !uiState.playbackFailed) {
            VideoControls(
                state = uiState,
                positionMs = viewModel.positionMs,
                durationMs = viewModel.durationMs,
                fullscreen = fullscreen,
                dragPosition = dragPosition,
                onDragPositionChange = { value ->
                    if (dragPosition < 0f) lastInteractionTick++
                    dragPosition = value
                    controlsVisible = true
                },
                onSeek = {
                    if (dragPosition >= 0f) {
                        viewModel.seekTo(dragPosition.toLong())
                        dragPosition = -1f
                        lastInteractionTick++
                        controlsVisible = true
                    }
                },
                onTogglePlay = viewModel::togglePlayPause,
                onSkipBackward = {
                    viewModel.skipBy(-VideoGestureOps.SKIP_STEP_MS)
                    lastInteractionTick++
                    controlsVisible = true
                },
                onSkipForward = {
                    viewModel.skipBy(VideoGestureOps.SKIP_STEP_MS)
                    lastInteractionTick++
                    controlsVisible = true
                },
                onSpeedClick = {
                    showSpeedDialog = true
                    lastInteractionTick++
                    controlsVisible = true
                },
                onPlaylistClick = {
                    showPlaylistPanel = true
                    lastInteractionTick++
                    controlsVisible = true
                },
                onToggleFullscreen = {
                    fullscreen = !fullscreen
                    lastInteractionTick++
                    controlsVisible = true
                },
                onLock = {
                    locked = true
                    lastInteractionTick++
                    controlsVisible = true
                },
                onBack = {
                    lastInteractionTick++
                    controlsVisible = true
                    viewModel.exitVideo()
                    onBack()
                },
            )
        }

        if (locked) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        locked = false
                        controlsVisible = true
                        lockHint = "已解锁"
                        lockHintTick++
                        lastInteractionTick++
                    },
            ) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = "视频已锁定",
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.Center).size(40.dp),
                )
            }
        }

        lockHint?.let { hint ->
            Surface(
                color = Color.Black.copy(alpha = 0.75f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            ) {
                Text(
                    hint,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        gestureOverlay?.let { overlay ->
            Surface(
                color = Color.Black.copy(alpha = 0.75f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp)
                    .semantics { contentDescription = overlay.description },
            ) {
                Text(
                    overlay.text,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }

    if (showSpeedDialog) {
        SpeedPickerSheet(
            current = uiState.speed,
            onSelect = {
                viewModel.setSpeed(it)
            },
            onDismiss = { showSpeedDialog = false },
        )
    }

    if (showPlaylistPanel) {
        VideoPlaylistSheet(
            entries = uiState.playlist,
            onSelect = viewModel::switchToPlaylistItem,
            onDismiss = { showPlaylistPanel = false },
        )
    }
}

@Composable
private fun BoxScope.ErrorOverlay(message: String, onBack: () -> Unit) {
    Column(
        Modifier
            .align(Alignment.Center)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("无法播放", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(
            message,
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("返回") }
    }
}

@Composable
private fun BoxScope.FailureCard(message: String, onBack: () -> Unit) {
    Surface(
        color = Color.Black.copy(alpha = 0.8f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.align(Alignment.Center).padding(24.dp),
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("无法播放", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(
                message,
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = onBack) { Text("返回", color = Color.White) }
        }
    }
}

@Composable
private fun VideoControls(
    state: VideoUiState,
    positionMs: StateFlow<Long>,
    durationMs: StateFlow<Long>,
    fullscreen: Boolean,
    dragPosition: Float,
    onDragPositionChange: (Float) -> Unit,
    onSeek: () -> Unit,
    onTogglePlay: () -> Unit,
    onSkipBackward: () -> Unit,
    onSkipForward: () -> Unit,
    onSpeedClick: () -> Unit,
    onPlaylistClick: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onLock: () -> Unit,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.5f))
                // scrim 先铺(垫进状态栏底下),内容再避让 —— 返回/锁定键
                // 不会顶进状态栏点不到;全屏沉浸时插边为 0,自动贴顶。
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.semantics { contentDescription = "video back" },
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    state.workTitle,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state.title,
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onLock,
                modifier = Modifier.semantics { contentDescription = "video lock" },
            ) {
                Icon(Icons.Filled.Lock, contentDescription = "锁定", tint = Color.White)
            }
        }
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            VideoSkipButton(
                semanticsDescription = "video seek backward",
                iconLabel = "快退 10 秒",
                icon = Icons.Filled.Replay10,
                onClick = onSkipBackward,
            )
            IconButton(
                onClick = onTogglePlay,
                modifier = Modifier
                    .size(64.dp)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                    .semantics {
                        contentDescription = if (state.isPlaying) "video pause" else "video play"
                    },
            ) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
            VideoSkipButton(
                semanticsDescription = "video seek forward",
                iconLabel = "快进 10 秒",
                icon = Icons.Filled.Forward10,
                onClick = onSkipForward,
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.5f))
                // 同顶栏:scrim 垫进手势条区域,滑杆/按钮避让。
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            VideoSeekRow(
                positionMs = positionMs,
                durationMs = durationMs,
                dragPosition = dragPosition,
                onDragPositionChange = onDragPositionChange,
                onSeek = onSeek,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(
                    onClick = onSpeedClick,
                    modifier = Modifier.semantics { contentDescription = "video speed=${state.speed}" },
                ) {
                    Text(
                        "${state.speed}x",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (state.playlist.size > 1) {
                    IconButton(
                        onClick = onPlaylistClick,
                        modifier = Modifier.semantics { contentDescription = "video playlist" },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "播放列表",
                            tint = Color.White,
                        )
                    }
                }
                IconButton(
                    onClick = onToggleFullscreen,
                    modifier = Modifier.semantics {
                        contentDescription = if (fullscreen) "video exit fullscreen" else "video fullscreen"
                    },
                ) {
                    Icon(
                        if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

/**
 * ±10s 快进/快退按钮:复用中心播放键的 scrim 样式(48dp/28dp 一档更小),
 * 属于控制层成员 —— 锁定或隐藏时随整层一起消失。
 */
@Composable
private fun VideoSkipButton(
    semanticsDescription: String,
    iconLabel: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .background(Color.Black.copy(alpha = 0.4f), CircleShape)
            .semantics { contentDescription = semanticsDescription },
    ) {
        Icon(
            icon,
            contentDescription = iconLabel,
            tint = Color.White,
            modifier = Modifier.size(28.dp),
        )
    }
}

/**
 * 进度条子树:独立收集 500ms 的位置/时长流,滑块与时间标签的刷新
 * 不重组整页控制条(与 PlayerScreen 的 SeekRow 同一模式)。
 */
@Composable
private fun VideoSeekRow(
    positionMs: StateFlow<Long>,
    durationMs: StateFlow<Long>,
    dragPosition: Float,
    onDragPositionChange: (Float) -> Unit,
    onSeek: () -> Unit,
) {
    val position by positionMs.collectAsStateWithLifecycle()
    val duration by durationMs.collectAsStateWithLifecycle()
    val shownPosition = if (dragPosition >= 0f) dragPosition else position.toFloat()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            formatTime(shownPosition.toLong()),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
        Slider(
            value = shownPosition.coerceIn(0f, maxOf(duration, 1L).toFloat()),
            onValueChange = onDragPositionChange,
            onValueChangeFinished = onSeek,
            valueRange = 0f..maxOf(duration, 1L).toFloat(),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .semantics {
                    contentDescription =
                        "video seekbar position=${shownPosition.toLong()} duration=$duration"
                },
        )
        Text(
            formatTime(duration),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0L)
    return "%02d:%02d".format(totalSec / 60, totalSec % 60)
}

/** Duration the lock-guard hint surface stays visible. */
private const val HINT_MS = 1600L

/** Idle window before the controls auto-hide while playing. */
private const val AUTO_HIDE_MS = 3000L

/** Linger of the gesture feedback surface after a double-tap / drag. */
private const val GESTURE_FEEDBACK_MS = 1500L

/** Centered feedback of the gesture layer; [description] is the dump semantics. */
private data class GestureFeedback(val text: String, val description: String)

private fun seedBrightnessFraction(window: Window?, resolver: ContentResolver): Float {
    val attr = window?.attributes?.screenBrightness
        ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    if (attr >= 0f) return attr.coerceIn(0f, 1f)
    val system = runCatching {
        Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrNull()
    return if (system == null) 0.5f else (system / 255f).coerceIn(0f, 1f)
}

/**
 * Window-level brightness: a 0f..1f value from the gesture, or
 * [WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE] to restore the system
 * default (dispose). No WRITE_SETTINGS — the override is per window.
 */
private fun applyBrightnessFraction(window: Window?, fraction: Float) {
    val w = window ?: return
    val lp = w.attributes
    lp.screenBrightness = fraction
    w.attributes = lp
}

private fun seedVolumeFraction(audioManager: AudioManager): Float {
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    return VideoGestureOps.volumePercentFor(
        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
        max,
    )
}

/** STREAM_MUSIC only (never player.volume — the sleep fade owns that), flag 0 = no system UI. */
private fun applyVolumeFraction(audioManager: AudioManager, fraction: Float) {
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    audioManager.setStreamVolume(
        AudioManager.STREAM_MUSIC,
        VideoGestureOps.volumeIndexFor(fraction, max),
        0,
    )
}

private fun gestureFeedback(brightness: Boolean, fraction: Float): GestureFeedback {
    val percent = (fraction * 100f).roundToInt().coerceIn(0, 100)
    return if (brightness) {
        GestureFeedback("亮度 $percent%", "video brightness value=$percent")
    } else {
        GestureFeedback("音量 $percent%", "video volume value=$percent")
    }
}

/**
 * Display row of the in-page playlist (plan video-player-controls Todo 3d):
 * same-folder siblings (single-file route) or all VIDEO tracks of the work.
 * Display-only — the keyed model (trackKey/kind/uri) stays a private
 * ViewModel field.
 */
data class VideoPlaylistEntry(
    val index: Int,
    val title: String,
    val subTitle: String,
    val isCurrent: Boolean,
)

/** View state of the video page (session-derived; single source = the session). */
data class VideoUiState(
    val loading: Boolean = true,
    /** Entry failure: video node missing / folder gone / service unreachable. */
    val error: String? = null,
    /** Mid-play failure (unsupported codec, broken source): the failure path. */
    val playbackFailed: Boolean = false,
    val failedMessage: String = "",
    val title: String = "",
    val workTitle: String = "",
    val isPlaying: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val speed: Float = 1f,
    /** Playlist rows (empty until the entry load resolves; <= 1 row hides the entry). */
    val playlist: List<VideoPlaylistEntry> = emptyList(),
)

/**
 * ViewModel of the attached-video page. Owns the session controller only —
 * never a player instance (plan must-not: the player lives in the service).
 * Playback runs through the SHARED session (see the file KDoc for the
 * documented decision); this class only orchestrates enter/exit around it.
 */
@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val workDao: WorkDao,
    private val singleFileDao: SingleFileDao,
    private val fsFactory: DocumentFsFactory,
    private val resumePositionResolver: ResumePositionResolver,
    private val settingsStore: SettingsStore,
    sessionConnection: SessionConnection,
) : ViewModel() {

    /**
     * 双入口:video_player/{workId}/{trackIndex}(作品附带视频)或
     * video_player_single/{fileId}(单档库文件,音/视频皆走本页 ——
     * ExoPlayer 对纯音频同样工作,PlayerView 显示黑场 + 控制条)。
     */
    private val singleFileId: Long? =
        savedStateHandle.get<String>(Routes.SINGLE_FILE_ARG)?.toLongOrNull()
    private val workId: String? = savedStateHandle[Routes.WORK_DETAIL_ARG]
    private val trackIndex: Int = savedStateHandle.get<Int>(Routes.TRACK_INDEX_ARG) ?: 1

    /** Normative trackKey of this video — the playback_state key (Task 19 rules). */
    private val videoKey: String = if (singleFileId != null) {
        KeySpec.singleFileTrackKey(singleFileId)
    } else {
        val parts = checkNotNull(KeySpec.parseWorkId(checkNotNull(workId)))
        KeySpec.trackKey(parts.sourceScope, parts.rjCode, trackIndex)
    }

    private val _uiState = MutableStateFlow(VideoUiState())
    val uiState: StateFlow<VideoUiState> = _uiState.asStateFlow()

    // 500ms ticker 的专用进度流:与 VideoUiState 解耦,只让进度条子树订阅,
    // 避免整页每 500ms 重组(PlayerScreen 同款模式)。
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()
    private val _bufferedPositionMs = MutableStateFlow(0L)
    val bufferedPositionMs: StateFlow<Long> = _bufferedPositionMs.asStateFlow()
    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var tickerJob: Job? = null
    private var enteredVideoMode = false
    private var exitRequested = false

    /**
     * Keyed playlist model (plan Todo 3a): the session timeline handed to the
     * controller at entry — same-folder siblings (single-file) or all VIDEO
     * tracks of the work. mediaId of each item IS its trackKey.
     */
    private var playlistModel: List<PlaylistItem> = emptyList()

    /** 当前条目 key(随 onMediaItemTransition 更新);初始为入口条目。 */
    private var currentVideoKey: String = videoKey

    /**
     * 进入视频前的 repeat/shuffle 自快照:单档「退出续播」路径由 VM 还原
     * (服务的快照已被 ACTION_VIDEO_LEAVE_PLAYING 丢弃)。
     */
    private var vmSavedRepeatMode: Int = Player.REPEAT_MODE_OFF
    private var vmSavedShuffle: Boolean = false

    /** ATTACH 页把已收拢的会话时间线重新展开过(c2)——退出时须再收拢+还原(h)。 */
    private var attachReexpanded = false

    /** 后台时视频轨被临时禁用(省电);回前台/退出时必须复位。 */
    private var videoTrackDisabledForBackground = false

    /**
     * 「视频后台续播」开关的进程内快照。单档页面退出时([exitVideo])必须同步
     * 决策是否继续播放,而设置只可能在页面关闭后修改 —— 打开页面时读一次
     * 就是最新值(设置页在别的路由,页面存活期间改不了)。
     */
    private var videoBackgroundPlaybackEnabled = true

    /**
     * The session player for the PlayerView binding(连接完成后才非 null)。
     * 必须是 StateFlow 而非普通属性:AndroidView 的 update lambda 只在其
     * 读取的快照状态变化时重跑 —— 普通属性会让「服务冷启动、控制器晚于
     * 首帧连接」的场景永远绑不上 surface(黑屏但有声,真机复现)。
     */
    private val _player = MutableStateFlow<Player?>(null)
    val playerFlow: StateFlow<Player?> = _player.asStateFlow()

    private val videoFailureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            _uiState.update {
                it.copy(
                    playbackFailed = true,
                    failedMessage = intent.getStringExtra(PlaybackService.EXTRA_VIDEO_FAILED_MESSAGE)
                        ?: "无法播放",
                )
            }
        }
    }

    init {
        connectAndStart()
        // 退出决策用的开关快照(见 videoBackgroundPlaybackEnabled)。
        viewModelScope.launch {
            videoBackgroundPlaybackEnabled = settingsStore.videoBackgroundPlayback.first()
        }
        // The service's error-restore can supersede the transient player
        // ERROR state before MediaController propagates it (device-verified
        // race), so the explicit app-local failure broadcast is the reliable
        // channel; the controller listener + shared events below are backup.
        ContextCompat.registerReceiver(
            context.applicationContext,
            videoFailureReceiver,
            IntentFilter(PlaybackService.ACTION_VIDEO_PLAYBACK_FAILED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        viewModelScope.launch {
            sessionConnection.events.collect { event ->
                if (event is PlayerEvent.PlaybackFailed) {
                    _uiState.update { it.copy(playbackFailed = true, failedMessage = event.message) }
                }
            }
        }
    }

    /**
     * Connects the controller and dispatches the entry mode. The controller's
     * own connection runs on the main looper — future.get() must live INSIDE
     * the completion listener (Task 21 ANR learning).
     */
    private fun connectAndStart() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val controller = try {
                future.get()
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, error = "无法连接播放服务: ${e.message}") }
                return@addListener
            }
            this.controller = controller
            _player.value = controller
            controller.addListener(controllerListener)
            // 上一个视频页可能在后台禁了视频轨后被杀/被替换,共享播放器
            // 会保留该参数 —— 进入时无条件复位,画面永不"神秘缺席"。
            setVideoTrackDisabled(false)
            // 单文件路由没有 workId/trackIndex,入口判定直接比对 mediaId。
            val decision = if (singleFileId != null) {
                if (controller.currentMediaItem?.mediaId == videoKey) {
                    VideoEntryDecision.ATTACH
                } else {
                    VideoEntryDecision.ENTER
                }
            } else {
                VideoTrackFinder.decideEntry(controller.currentMediaItem?.mediaId, checkNotNull(workId), trackIndex)
            }
            when (decision) {
                VideoEntryDecision.ATTACH -> attachExisting(controller)
                VideoEntryDecision.ENTER -> enterVideo(controller)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Re-entry on the same video (deep-link re-delivery): resume in place. */
    private fun attachExisting(controller: MediaController) {
        val item = controller.currentMediaItem
        _uiState.update {
            it.copy(
                loading = false,
                title = item?.mediaMetadata?.title?.toString().orEmpty(),
                workTitle = item?.mediaMetadata?.artist?.toString().orEmpty(),
                isPlaying = controller.isPlaying,
                playbackState = controller.playbackState,
                speed = controller.playbackParameters.speed,
            )
        }
        controller.play()
        startTicker()
        Log.i(TAG, "video attach: session already on $videoKey — resuming in place")
        // (c2) ATTACH 也建同一份播放列表模型(同一条 IO 管线);会话时间线
        // 与模型不一致时(上一页「退出续播」已收拢为单条)重新展开。永不发送
        // ACTION_VIDEO_ENTER —— PlaybackService 的单档持久化跳过覆盖本次展开。
        viewModelScope.launch {
            val model = withContext(Dispatchers.IO) { loadVideoWithPlaylist()?.playlist }
                ?: return@launch
            if (exitRequested) return@launch
            playlistModel = model
            val idx = model.indexOfFirst { it.trackKey == controller.currentMediaItem?.mediaId }
            if (idx >= 0) {
                currentVideoKey = model[idx].trackKey
                _uiState.update { it.copy(playlist = displayPlaylist(idx)) }
            }
            if (model.size <= 1 || controller.mediaItemCount == model.size) return@launch
            // 先判匹配:当前会话条目不在模型里说明时间线已另属他人,整个
            // 重展开块(含模式捕获/中和/setMediaItems)全部跳过。
            if (idx < 0) {
                Log.w(TAG, "video attach: re-expand skipped, current item not in playlist model")
                return@launch
            }
            vmSavedRepeatMode = controller.repeatMode
            vmSavedShuffle = controller.shuffleModeEnabled
            controller.repeatMode = Player.REPEAT_MODE_OFF
            controller.shuffleModeEnabled = false
            controller.setMediaItems(model.map(::buildMediaItem), idx, controller.currentPosition)
            controller.prepare()
            attachReexpanded = true
            Log.i(TAG, "video attach: session re-expanded to ${model.size} items at index $idx")
        }
    }

    /**
     * Video entry: resolve the video file + the full playlist + resume
     * position, snapshot the audio context on the service, then swap the
     * session timeline to the MULTI-item playlist (plan Todo 3a/b). The
     * snapshot command is awaited BEFORE setMediaItems — the restore point
     * must be the exact pre-video state.
     */
    private fun enterVideo(controller: MediaController) {
        // (b) FIRST: capture the pre-video modes. The single-file continue
        // exit (f) restores from THIS capture — the service's own snapshot is
        // dropped by ACTION_VIDEO_LEAVE_PLAYING.
        vmSavedRepeatMode = controller.repeatMode
        vmSavedShuffle = controller.shuffleModeEnabled
        viewModelScope.launch {
            // (a) playlist build + resume resolve live ENTIRELY in the IO
            // phase: the N× SAF listChildren walks are binder IPC and ANR on
            // the main thread (Task-21 ANR note above).
            val resolved = withContext(Dispatchers.IO) {
                loadVideoWithPlaylist()?.let { it to resumePositionResolver.resolve(videoKey) }
            }
            if (resolved == null) {
                _uiState.update { it.copy(loading = false, error = "未找到视频文件") }
                return@launch
            }
            // 页面在解析期间就被退出(慢速 SAF 解析 + 用户立刻返回):不要
            // 背着他启动播放。此检查只覆盖解析阶段 —— sendCustomCommand 到
            // 快照回调之间还有一次异步 binder 往返,回调里须再查一次(见下)。
            if (exitRequested) {
                Log.i(TAG, "video entry aborted: page already left")
                return@launch
            }
            val (loaded, startPositionMs) = resolved
            playlistModel = loaded.playlist
            val currentIndex = loaded.playlist.indexOfFirst { it.trackKey == videoKey }.coerceAtLeast(0)
            _uiState.update {
                it.copy(
                    loading = false,
                    title = loaded.entry.title,
                    workTitle = loaded.entry.subTitle,
                    speed = controller.playbackParameters.speed,
                    playlist = displayPlaylist(currentIndex),
                )
            }
            enteredVideoMode = true
            Log.i(TAG, "video resolved: key=$videoKey start=${startPositionMs}ms items=${loaded.playlist.size}")
            val snapshotFuture = controller.sendCustomCommand(
                SessionCommand(PlaybackService.ACTION_VIDEO_ENTER, Bundle.EMPTY),
                Bundle.EMPTY,
            )
            snapshotFuture.addListener({
                // 快照往返期间页面可能已退出(exitVideo 已让服务恢复音频
                // 上下文/收拢并还原模式)—— 此时再中和模式 + 换时间线会把
                // 视频播放列表压在已恢复的音频队列上且永远不会被还原。
                if (exitRequested) {
                    Log.i(TAG, "video enter: snapshot resolved after page exit — skipping timeline swap")
                    return@addListener
                }
                val result = runCatching { snapshotFuture.get() }.getOrNull()
                if (result?.resultCode != SessionResult.RESULT_SUCCESS) {
                    Log.w(TAG, "video enter: audio-context snapshot failed (code=${result?.resultCode})")
                }
                // (b) neutralize INSIDE the listener, after the RESULT_SUCCESS
                // check and immediately before setMediaItems — earlier would
                // poison the snapshot (PlaybackService.saveAudioContext reads
                // the modes; controller calls are delivered in order).
                controller.repeatMode = Player.REPEAT_MODE_OFF
                controller.shuffleModeEnabled = false
                controller.setMediaItems(
                    loaded.playlist.map(::buildMediaItem),
                    currentIndex,
                    startPositionMs,
                )
                controller.prepare()
                controller.play()
                startTicker()
                Log.i(
                    TAG,
                    "video started: ${loaded.entry.title} key=$videoKey pos=$startPositionMs " +
                        "(${loaded.playlist.size} items, modes neutralized)",
                )
            }, ContextCompat.getMainExecutor(context))
        }
    }

    private suspend fun loadVideoWithPlaylist(): ResolvedVideo? =
        if (singleFileId != null) loadSingleWithPlaylist(singleFileId) else loadWorkWithPlaylist()

    /**
     * 单档解析:库行 -> 沿 display-name 相对路径现场解析 document uri
     * (同 WorkPathResolver 的设计依据:SAF 文档 id 不入库,路径才是
     * 稳定身份),并列出同根同目录的全部兄弟条目(listByRoot + isSibling
     * 精确父目录匹配,子目录不算)。末段匹配文件而非目录。
     */
    private suspend fun loadSingleWithPlaylist(fileId: Long): ResolvedVideo? {
        val file = singleFileDao.getById(fileId) ?: return null
        return runCatching {
            val fs = fsFactory.create(file.rootFolderUri)
            val entryUri = resolveFileUri(fs, file.relativePath) ?: return@runCatching null
            val entry = PlaylistItem(
                trackKey = KeySpec.singleFileTrackKey(file.id),
                title = file.displayTitle,
                subTitle = file.channel ?: "单档库",
                documentUri = entryUri,
                // 单档缩略图(边车图/抽帧):thumbSource 经共享规则解析成
                // 通知栏封面 model(与单档列表/胶囊缩略图同一条)。
                artworkUri = file.thumbSource?.let(::singleThumbModel),
                isAudioOnly = file.kind == SingleFileKind.AUDIO,
            )
            val siblings = singleFileDao.listByRoot(file.rootFolderUri)
                .filter { VideoGestureOps.isSibling(it.relativePath, file.relativePath) }
                .mapNotNull { sibling ->
                    resolveFileUri(fs, sibling.relativePath)?.let { uri ->
                        PlaylistItem(
                            trackKey = KeySpec.singleFileTrackKey(sibling.id),
                            title = sibling.displayTitle,
                            subTitle = sibling.channel ?: "单档库",
                            documentUri = uri,
                            artworkUri = sibling.thumbSource?.let(::singleThumbModel),
                            isAudioOnly = sibling.kind == SingleFileKind.AUDIO,
                        )
                    }
                }
            // 入口条目必须在模型里(自身的 SAF 解析失败已在上游排除,这里
            // 只是防御):丢了就退化为单条,绝不交出不含入口的时间线。
            val model = if (siblings.any { it.trackKey == entry.trackKey }) siblings else listOf(entry)
            ResolvedVideo(entry = entry, playlist = model)
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "single file lookup failed: ${e.message}")
            null
        }
    }

    private fun resolveFileUri(fs: com.oneasmr.app.data.scanner.DocumentFs, relativePath: String): String? {
        val segments = relativePath.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return null
        var path = com.oneasmr.app.data.scanner.FsPath(emptyList())
        for ((index, name) in segments.withIndex()) {
            val entries = fs.listChildren(path)
            if (index == segments.lastIndex) {
                return entries.firstOrNull { it.name == name && !it.isDirectory }?.documentUri
            }
            val dir = entries.firstOrNull { it.name == name && it.isDirectory } ?: return null
            path += dir.documentId
        }
        return null
    }

    private suspend fun loadWorkWithPlaylist(): ResolvedVideo? {
        val work = workDao.getById(checkNotNull(workId)) ?: return null
        val parts = checkNotNull(KeySpec.parseWorkId(work.id))
        // 通知栏封面:作品附属视频沿用作品封面与和谐决策(安全模式下
        // 同样只显示默认占位,见 MediaItemMapper.coverArtUri)。
        val censoredArt = !settingsStore.nsfwEnabled.first() && work.ageRating.isCensored()
        val artwork = coverArtUri(parts.sourceScope, parts.rjCode, censoredArt)
        return runCatching {
            val fs = fsFactory.create(work.rootFolderUri)
            when (val resolved = WorkPathResolver(fs).resolve(work.relativeDir)) {
                is WorkPathResolver.Result.NotFound -> null
                is WorkPathResolver.Result.Found -> {
                    val tree = TrackTreeBuilder(fs).build(
                        resolved.path, resolved.displayName, resolved.documentUri,
                    )
                    // 作品路由的播放列表 = 该作品全部 VIDEO 轨(listVideos
                    // 与 find 同一棵前序遍历,媒体序号即 trackIndex)。
                    val model = VideoTrackFinder.listVideos(tree.root).map { node ->
                        PlaylistItem(
                            trackKey = KeySpec.trackKey(
                                parts.sourceScope, parts.rjCode, checkNotNull(node.trackIndex),
                            ),
                            title = node.name,
                            subTitle = work.title,
                            documentUri = node.documentUri,
                            artworkUri = artwork,
                            isAudioOnly = false,
                        )
                    }
                    val entry = model.firstOrNull { it.trackKey == videoKey }
                        ?: return@runCatching null
                    ResolvedVideo(entry = entry, playlist = model)
                }
            }
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "video node lookup failed: ${e.message}")
            null
        }
    }

    /** Keyed playlist model entry — trackKey/kind/uri stay private (Todo 3d). */
    private data class PlaylistItem(
        val trackKey: String,
        val title: String,
        val subTitle: String,
        val documentUri: String,
        val artworkUri: Uri?,
        val isAudioOnly: Boolean,
    )

    /** Entry item + the full playlist model resolved in the IO phase. */
    private data class ResolvedVideo(
        val entry: PlaylistItem,
        val playlist: List<PlaylistItem>,
    )

    /** MediaItem metadata mirrors the former single-item builder (:801-811). */
    private fun buildMediaItem(item: PlaylistItem): MediaItem =
        MediaItem.Builder()
            .setMediaId(item.trackKey)
            .setUri(item.documentUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.subTitle)
                    .apply { item.artworkUri?.let(::setArtworkUri) }
                    .build(),
            )
            .build()

    /** Display rows for [VideoUiState.playlist]; [currentIndex] < 0 flags nothing. */
    private fun displayPlaylist(currentIndex: Int): List<VideoPlaylistEntry> =
        playlistModel.mapIndexed { index, item ->
            VideoPlaylistEntry(
                index = index,
                title = item.title,
                subTitle = item.subTitle,
                isCurrent = index == currentIndex,
            )
        }

    /** 当前条目的音频-only 类别,从播放列表模型按 currentVideoKey 现取(不冻结)。 */
    private fun currentIsAudioOnly(): Boolean =
        playlistModel.firstOrNull { it.trackKey == currentVideoKey }?.isAudioOnly ?: false

    private val controllerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _uiState.update { it.copy(playbackState = playbackState) }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            _uiState.update { it.copy(speed = playbackParameters.speed) }
        }

        /**
         * (c) 条目切换:标题/副标题/当前行随 CURRENT 条目走,音频-only 类别
         * 也从模型现取(见 [currentIsAudioOnly])——不再是入口冻结值。会话里
         * 与本页模型无关的切换(进入前的音频队列、退出后的上下文恢复)忽略。
         */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val key = mediaItem?.mediaId ?: return
            val modelIndex = playlistModel.indexOfFirst { it.trackKey == key }
            if (modelIndex < 0) return
            currentVideoKey = key
            _uiState.update {
                it.copy(
                    title = mediaItem.mediaMetadata.title?.toString().orEmpty(),
                    workTitle = mediaItem.mediaMetadata.artist?.toString().orEmpty(),
                    playlist = displayPlaylist(modelIndex),
                )
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "video player error: ${error.errorCodeName}: ${error.message}")
            _uiState.update {
                it.copy(playbackFailed = true, failedMessage = error.errorCodeName ?: "无法播放")
            }
        }
    }

    /** 500ms position/buffer ticker (media-controller getters are main-thread affine). */
    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                val c = controller ?: break
                _positionMs.value = c.currentPosition.coerceAtLeast(0L)
                _bufferedPositionMs.value = c.bufferedPosition.coerceAtLeast(0L)
                _durationMs.value = c.duration.coerceAtLeast(0L)
                delay(POSITION_TICK_MS)
            }
        }
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.playWhenReady) c.pause() else c.play()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
        Log.i(TAG, "video seek -> ${positionMs.coerceAtLeast(0L)}ms")
    }

    /**
     * ±10s 单步跳转(快进/快退按钮;Todo 6 的双击手势共用):目标位置由
     * 纯函数 [VideoGestureOps.skipTarget] 钳制在 [0, duration] 内,再走
     * [seekTo](其自身也会 coerceAtLeast(0))。
     */
    fun skipBy(deltaMs: Long) {
        val c = controller ?: return
        val target = VideoGestureOps.skipTarget(c.currentPosition, deltaMs, c.duration)
        Log.i(TAG, "video skip ${if (deltaMs >= 0) "+" else ""}${deltaMs}ms -> ${target}ms")
        seekTo(target)
    }

    fun setSpeed(speed: Float) {
        val c = controller ?: return
        c.setPlaybackSpeed(PlaybackSpeed.normalize(speed))
        Log.i(TAG, "video speed -> ${PlaybackSpeed.normalize(speed)}x")
    }

    /**
     * (e) 播放列表切集:纯会话内 seekTo(index, 各自记忆的续播位置) ——
     * 不导航、不重发 ACTION_VIDEO_ENTER。ALWAYS_ASK 仍走自动续播占位
     * (无询问 UI,与 ResumePositionResolver 的既有约定一致)。
     */
    fun switchToPlaylistItem(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        val key = playlistModel.getOrNull(index)?.trackKey ?: return
        viewModelScope.launch {
            val resolvedMs = resumePositionResolver.resolve(key)
            c.seekTo(index, resolvedMs)
            c.play()
            Log.i(TAG, "playlist switch -> index $index key=$key pos=${resolvedMs}ms")
        }
    }

    /**
     * Home / 关屏(ON_STOP):
     * - 纯音频单文件:无条件后台续播(与作品音轨同权)。
     * - 视频 + 「视频后台续播」开(默认):不暂停,只临时禁用视频轨 ——
     *   仅解码音频,省电接近纯音频;回前台恢复画面。播放本就走前台
     *   服务,通知栏/锁屏控制自然可用。
     * - 视频 + 开关关:保持旧行为,后台即暂停(never play unseen)。
     */
    fun onAppBackgrounded() {
        val c = controller ?: return
        if (!c.playWhenReady) return
        // (c) 判定基于 CURRENT 条目(切集后已更新),不是入口冻结的 videoKey。
        if (c.currentMediaItem?.mediaId != currentVideoKey) return
        if (currentIsAudioOnly()) return
        viewModelScope.launch {
            if (settingsStore.videoBackgroundPlayback.first()) {
                setVideoTrackDisabled(true)
                Log.i(TAG, "video backgrounded: keep playing, video track disabled (battery)")
            } else {
                Log.i(TAG, "video backgrounded: pausing (service flushes the position)")
                controller?.pause()
            }
        }
    }

    /** 回前台(ON_START):恢复被后台禁用的视频轨。 */
    fun onAppForegrounded() {
        if (videoTrackDisabledForBackground) {
            setVideoTrackDisabled(false)
            Log.i(TAG, "video foregrounded: video track re-enabled")
        }
    }

    /** 视频轨临时开关(后台省电);共享播放器上属全局参数,必须成对复位。 */
    private fun setVideoTrackDisabled(disabled: Boolean) {
        val c = controller ?: return
        c.trackSelectionParameters = c.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, disabled)
            .build()
        videoTrackDisabledForBackground = disabled
    }

    /**
     * Page exit (back / page removed). Two different contracts:
     *
     * - Attached video (作品附属视频): pause first — the service flushes the
     *   video position (Task 19 rules) — then ask the service to restore the
     *   saved audio context. Unchanged Task 22 behaviour.
     * - Single file (单档, the Videos tab): this page IS the file's player
     *   page, so leaving it does not stop playback — the item stays in the
     *   session and keeps playing, with the video track disabled while nobody
     *   is watching the picture (battery; the same treatment as backgrounding),
     *   and the mini player pill / notification / lockscreen controls / resume
     *   memory all work on it like any other item. [continuesAfterPageExit]
     *   decides whether it continues: audio-only singles always do, video
     *   singles follow the persisted 「视频后台续播」switch (off => pause and
     *   roll the audio context back).
     *
     * Idempotent via [exitRequested]; the safety-net [onCleared] path lands
     * here too. When the playback FAILED the service has already restored the
     * audio context, so only the page pops (sending an exit command then would
     * pause+clear the RESTORED queue).
     */
    fun exitVideo() {
        if (exitRequested) return
        exitRequested = true
        val c = controller ?: return
        if (singleFileId != null) {
            exitSingleFile(c)
            return
        }
        // 离开视频页前复位视频轨参数:共享播放器把它带回音频队列虽无
        // 感知,但属状态泄漏,下个视频会莫名黑屏。
        if (videoTrackDisabledForBackground) setVideoTrackDisabled(false)
        if (!enteredVideoMode) return
        if (_uiState.value.playbackFailed) {
            Log.i(TAG, "video exit after failure: audio context restored by service, popping only")
            return
        }
        runCatching { if (c.playWhenReady) c.pause() }
        sendVideoCommand(c, PlaybackService.ACTION_VIDEO_EXIT, "video exit: paused + restore command sent")
    }

    /**
     * 单档页面退出。ATTACH 进入的页面(胶囊点回)不改变播放状态 —— 会话本就
     * 没被这类页面打断过,退出只把画面让出去,不需要任何服务命令;ENTER 过的
     * 页面才需要收尾:继续播放(先把会话收拢为当前条目、还原进入前的循环/随机,
     * 再禁用视频轨 + 让服务结束视频模式)或暂停回滚(旧行为,「视频后台续播」
     * 关时)。重展开过时间线的 ATTACH 页(attachReexpanded)同样先收拢+还原,
     * 但不发任何服务命令(h)。
     */
    private fun exitSingleFile(c: MediaController) {
        if (_uiState.value.playbackFailed) {
            // (g) 服务在「无存档音频上下文」时走通用清理路径,不会还原模式
            // —— 本页中和过(ENTER 或重展开的 ATTACH)就由 VM 自快照补还;
            // 从未中和的纯 ATTACH 页不动(vmSaved* 只是默认值)。
            if (enteredVideoMode || attachReexpanded) {
                runCatching {
                    c.repeatMode = vmSavedRepeatMode
                    c.shuffleModeEnabled = vmSavedShuffle
                }
            }
            Log.i(TAG, "single exit after failure: service already restored, popping only")
            return
        }
        // (h) 必须在 !enteredVideoMode 分支之前:重展开的 ATTACH 页从不置
        // enteredVideoMode,但该页中和过模式、展开过时间线,退出同样要收尾。
        if (attachReexpanded) {
            collapseToCurrentAndRestoreModes(c)
            setVideoTrackDisabled(true)
            Log.i(TAG, "single exit (attach re-expanded): collapsed + modes restored, no service command")
            return
        }
        if (!enteredVideoMode) {
            setVideoTrackDisabled(true)
            return
        }
        if (continuesAfterPageExit(currentIsAudioOnly(), videoBackgroundPlaybackEnabled)) {
            // 画面无人看:与后台同款省电,只解码音频;当前条目留在会话里继续播。
            setVideoTrackDisabled(true)
            collapseToCurrentAndRestoreModes(c)
            sendVideoCommand(
                c,
                PlaybackService.ACTION_VIDEO_LEAVE_PLAYING,
                "single exit: playback continues (collapsed to current item, modes restored)",
            )
            return
        }
        runCatching { if (c.playWhenReady) c.pause() }
        sendVideoCommand(c, PlaybackService.ACTION_VIDEO_EXIT, "single exit: paused + audio context restored")
    }

    /**
     * (f)/(h) 退出收尾,顺序即契约:先把会话时间线收拢为当前条目(迷你
     * 播放器只带这一条走),再从 VM 自快照还原 repeat/shuffle —— 先收拢
     * 后还原,避免在收缩中的时间线上触发 fair-deck 整组重洗。
     *
     * 已接受边界(计划 3f):收拢的 setMediaItems 会触发
     * onMediaItemTransition(PLAYLIST_CHANGED),进而带动服务的
     * checkEndOfTrackTimer —— 「播完当前曲」睡眠定时在退出续播时会提前
     * 到期。仅记录,不改服务。
     */
    private fun collapseToCurrentAndRestoreModes(c: MediaController) {
        runCatching {
            val current = c.currentMediaItem
            if (current != null && c.mediaItemCount > 1) {
                val position = c.currentPosition
                c.setMediaItems(listOf(current), 0, position)
                c.prepare()
                if (c.playWhenReady) c.play()
                Log.i(
                    TAG,
                    "session collapsed to current item (pos=${position}ms; " +
                        "end-of-track sleep timer may expire — accepted edge)",
                )
            }
            c.repeatMode = vmSavedRepeatMode
            c.shuffleModeEnabled = vmSavedShuffle
        }.onFailure { e ->
            Log.w(TAG, "single exit collapse/restore failed: ${e.message}")
        }
    }

    /** Fire-and-log a video-mode session command (main-thread executor callback). */
    private fun sendVideoCommand(c: MediaController, action: String, logMessage: String) {
        val future = c.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), Bundle.EMPTY)
        future.addListener({
            val result = runCatching { future.get() }.getOrNull()
            if (result?.resultCode != SessionResult.RESULT_SUCCESS) {
                Log.w(TAG, "command $action failed (code=${result?.resultCode}); audio queue survives in QueueStore")
            }
        }, ContextCompat.getMainExecutor(context))
        Log.i(TAG, logMessage)
    }

    override fun onCleared() {
        exitVideo()
        tickerJob?.cancel()
        runCatching { context.applicationContext.unregisterReceiver(videoFailureReceiver) }
        runCatching { controllerFuture?.let { MediaController.releaseFuture(it) } }
        controllerFuture = null
        _player.value = null
        controller?.removeListener(controllerListener)
        controller = null
        super.onCleared()
    }

    private companion object {
        const val TAG = "VideoPlayer"
        const val POSITION_TICK_MS = 500L
    }
}
