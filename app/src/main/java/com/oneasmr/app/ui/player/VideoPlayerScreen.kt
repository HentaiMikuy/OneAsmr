package com.oneasmr.app.ui.player

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import com.oneasmr.app.navigation.Routes
import com.oneasmr.app.player.PlaybackService
import com.oneasmr.app.player.coverArtUri
import com.oneasmr.app.player.ResumePositionResolver
import com.oneasmr.app.player.SessionConnection
import com.oneasmr.app.player.VideoEntryDecision
import com.oneasmr.app.player.VideoTrackFinder
import com.oneasmr.app.ui.work.DocumentFsFactory
import com.oneasmr.app.ui.player.SpeedPickerSheet
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
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
 * 4. The video is a single-item replacement timeline that is removed on exit;
 *    it never enters the audio queue, so it can never hit repeat/shuffle logic
 *    (plan must-not). The service skips QueueStore persistence in video mode,
 *    so even a force-stop mid-video cold-restores the AUDIO queue.
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
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (!locked) controlsVisible = !controlsVisible
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
                onDragPositionChange = { dragPosition = it },
                onSeek = {
                    if (dragPosition >= 0f) {
                        viewModel.seekTo(dragPosition.toLong())
                        dragPosition = -1f
                    }
                },
                onTogglePlay = viewModel::togglePlayPause,
                onSpeedClick = { showSpeedDialog = true },
                onToggleFullscreen = { fullscreen = !fullscreen },
                onLock = { locked = true },
                onBack = { viewModel.exitVideo(); onBack() },
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
                        lockHint = "已解锁"
                        lockHintTick++
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
    onSpeedClick: () -> Unit,
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
        IconButton(
            onClick = onTogglePlay,
            modifier = Modifier
                .align(Alignment.Center)
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

    /** 当前条目是纯音频单文件(m4a/mp3 等):后台续播无条件生效。 */
    private var isAudioOnlySingle = false

    /** 后台时视频轨被临时禁用(省电);回前台/退出时必须复位。 */
    private var videoTrackDisabledForBackground = false

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
    }

    /**
     * Video entry: resolve the video file + resume position, snapshot the
     * audio context on the service, then swap the session timeline to the
     * single video item. The snapshot command is awaited BEFORE setMediaItems
     * — the restore point must be the exact pre-video state.
     */
    private fun enterVideo(controller: MediaController) {
        viewModelScope.launch {
            val video = withContext(Dispatchers.IO) { loadVideoNode() }
            if (video == null) {
                _uiState.update { it.copy(loading = false, error = "未找到视频文件") }
                return@launch
            }
            _uiState.update {
                it.copy(
                    loading = false,
                    title = video.name,
                    workTitle = video.workTitle,
                    speed = controller.playbackParameters.speed,
                )
            }
            enteredVideoMode = true
            val startPositionMs = resumePositionResolver.resolve(videoKey)
            Log.i(TAG, "video resolved: key=$videoKey start=${startPositionMs}ms")
            val snapshotFuture = controller.sendCustomCommand(
                SessionCommand(PlaybackService.ACTION_VIDEO_ENTER, Bundle.EMPTY),
                Bundle.EMPTY,
            )
            snapshotFuture.addListener({
                val result = runCatching { snapshotFuture.get() }.getOrNull()
                if (result?.resultCode != SessionResult.RESULT_SUCCESS) {
                    Log.w(TAG, "video enter: audio-context snapshot failed (code=${result?.resultCode})")
                }
                // Generic source: content:// SAF uri from the local library
                // (the app is local-only; no remote/streaming sources).
                val item = MediaItem.Builder()
                    .setMediaId(videoKey)
                    .setUri(video.documentUri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(video.name)
                            .setArtist(video.workTitle)
                            .apply { video.artworkUri?.let(::setArtworkUri) }
                            .build(),
                    )
                    .build()
                controller.setMediaItems(listOf(item), 0, startPositionMs)
                controller.prepare()
                controller.play()
                startTicker()
                Log.i(TAG, "video started: ${video.name} key=$videoKey pos=$startPositionMs")
            }, ContextCompat.getMainExecutor(context))
        }
    }

    private suspend fun loadVideoNode(): VideoFile? =
        if (singleFileId != null) loadSingleFile(singleFileId) else loadWorkVideoNode()

    /**
     * 单档解析:库行 -> 沿 display-name 相对路径现场解析 document uri
     * (同 WorkPathResolver 的设计依据:SAF 文档 id 不入库,路径才是
     * 稳定身份)。末段匹配文件而非目录。
     */
    private suspend fun loadSingleFile(fileId: Long): VideoFile? {
        val file = singleFileDao.getById(fileId) ?: return null
        isAudioOnlySingle = file.kind == SingleFileKind.AUDIO
        return runCatching {
            val fs = fsFactory.create(file.rootFolderUri)
            resolveFileUri(fs, file.relativePath)?.let { uri ->
                VideoFile(
                    name = file.displayTitle,
                    documentUri = uri,
                    workTitle = file.channel ?: "单档库",
                    // 单档缩略图(边车图/抽帧):thumbSource 原样作 artworkUri,
                    // 本地绝对路径补 file://(通用解码分支)。
                    artworkUri = file.thumbSource?.let { path ->
                        if (path.startsWith("content:")) Uri.parse(path) else Uri.fromFile(File(path))
                    },
                )
            }
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

    private suspend fun loadWorkVideoNode(): VideoFile? {
        val work = workDao.getById(checkNotNull(workId)) ?: return null
        // 通知栏封面:作品附属视频沿用作品封面与和谐决策(安全模式下
        // 同样只显示默认占位,见 MediaItemMapper.coverArtUri)。
        val censoredArt = !settingsStore.nsfwEnabled.first() && work.ageRating.isCensored()
        val artwork = KeySpec.parseWorkId(work.id)?.let {
            coverArtUri(it.sourceScope, it.rjCode, censoredArt)
        }
        return runCatching {
            val fs = fsFactory.create(work.rootFolderUri)
            when (val resolved = WorkPathResolver(fs).resolve(work.relativeDir)) {
                is WorkPathResolver.Result.NotFound -> null
                is WorkPathResolver.Result.Found -> {
                    val tree = TrackTreeBuilder(fs).build(
                        resolved.path, resolved.displayName, resolved.documentUri,
                    )
                    VideoTrackFinder.find(tree.root, trackIndex)?.let { node ->
                        VideoFile(
                            name = node.name,
                            documentUri = node.documentUri,
                            workTitle = work.title,
                            artworkUri = artwork,
                        )
                    }
                }
            }
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "video node lookup failed: ${e.message}")
            null
        }
    }

    private data class VideoFile(
        val name: String,
        val documentUri: String,
        val workTitle: String,
        val artworkUri: Uri? = null,
    )

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

    fun setSpeed(speed: Float) {
        val c = controller ?: return
        c.setPlaybackSpeed(PlaybackSpeed.normalize(speed))
        Log.i(TAG, "video speed -> ${PlaybackSpeed.normalize(speed)}x")
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
        if (c.currentMediaItem?.mediaId != videoKey) return
        if (isAudioOnlySingle) return
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
     * Video exit (back / page removed): pause first — the service flushes the
     * video position (Task 19 rules) — then ask the service to restore the
     * saved audio context. Idempotent via [exitRequested]; the safety-net
     * [onCleared] path lands here too. When the video FAILED the service has
     * already restored the audio context, so only the page pops (sending
     * VIDEO_EXIT then would pause+clear the RESTORED queue).
     */
    fun exitVideo() {
        if (exitRequested) return
        exitRequested = true
        val c = controller ?: return
        // 离开视频页前复位视频轨参数:共享播放器把它带回音频队列虽无
        // 感知,但属状态泄漏,下个视频会莫名黑屏。
        if (videoTrackDisabledForBackground) setVideoTrackDisabled(false)
        if (!enteredVideoMode) return
        if (_uiState.value.playbackFailed) {
            Log.i(TAG, "video exit after failure: audio context restored by service, popping only")
            return
        }
        runCatching { if (c.playWhenReady) c.pause() }
        val future = c.sendCustomCommand(
            SessionCommand(PlaybackService.ACTION_VIDEO_EXIT, Bundle.EMPTY),
            Bundle.EMPTY,
        )
        future.addListener({
            val result = runCatching { future.get() }.getOrNull()
            if (result?.resultCode != SessionResult.RESULT_SUCCESS) {
                Log.w(
                    TAG,
                    "video exit: restore command failed (code=${result?.resultCode}); " +
                        "audio queue survives in QueueStore",
                )
            }
        }, ContextCompat.getMainExecutor(context))
        Log.i(TAG, "video exit: paused + restore command sent")
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
