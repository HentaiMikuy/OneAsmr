package com.oneasmr.app.ui.player

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.oneasmr.app.data.local.WorkDao
import com.oneasmr.app.data.scanner.TrackTreeBuilder
import com.oneasmr.app.data.scanner.WorkPathResolver
import com.oneasmr.app.domain.player.PlayQueue
import com.oneasmr.app.domain.player.PlaybackSpeed
import com.oneasmr.app.navigation.Routes
import com.oneasmr.app.player.PlayQueueBuilder
import com.oneasmr.app.player.PlaybackService
import com.oneasmr.app.player.toMedia3
import com.oneasmr.app.player.toMediaItem
import com.oneasmr.app.player.toRepeatMode
import com.oneasmr.app.ui.work.DocumentFsFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Minimal player state for Task 17: the screen is a placeholder until the
 * full player UI lands in Task 21, but playback START and the notification
 * permission request live here (they are Task 17's wiring, driven from the
 * detail-page audio-node tap -> player route).
 *
 * Task 18 extends this placeholder with a compact queue-control cluster
 * (repeat / shuffle / speed / next / reorder / clear) — the QA surface for
 * the queue-mode-speed work. Task 21 replaces the whole screen; the session
 * state stays owned by PlaybackService either way.
 */
data class PlayerUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val workTitle: String = "",
    val trackTitle: String = "",
    val isPlaying: Boolean = false,
    val queueSize: Int = 0,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffleEnabled: Boolean = false,
    val speed: Float = 1f,
) {
    val ready: Boolean get() = !loading && error == null
}

/**
 * Task 17 playback launcher (plan Task 17 wiring; the full player screen is
 * Task 21):
 *
 * 1. Loads the work + builds its live track tree (Task 14 pattern).
 * 2. Builds the [PlayQueue] (Task 17 model) and starts playback through the
 *    [PlaybackService] MediaSession via a [MediaController] — the player
 *    itself NEVER lives in this ViewModel or any Activity (plan must-not).
 * 3. Reflects playback state so the placeholder screen can show it.
 *
 * The controller is released in onCleared; playback continues in the service
 * (that is the whole point of the background session).
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val workDao: WorkDao,
    private val fsFactory: DocumentFsFactory,
) : ViewModel() {

    private val workId: String = checkNotNull(savedStateHandle[Routes.WORK_DETAIL_ARG])
    private val trackIndex: Int = checkNotNull(savedStateHandle[Routes.TRACK_INDEX_ARG])

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var controller: MediaController? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    init {
        viewModelScope.launch { startPlayback() }
    }

    private suspend fun startPlayback() {
        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                val work = workDao.getById(workId)
                    ?: throw IllegalStateException("作品不在库中: $workId")
                val fs = fsFactory.create(work.rootFolderUri)
                when (val resolved = WorkPathResolver(fs).resolve(work.relativeDir)) {
                    is WorkPathResolver.Result.NotFound ->
                        throw IllegalStateException("作品文件夹不可访问: ${resolved.message}")
                    is WorkPathResolver.Result.Found -> {
                        val tree = TrackTreeBuilder(fs).build(
                            resolved.path, resolved.displayName, resolved.documentUri,
                        )
                        val queue = PlayQueueBuilder().build(
                            workId = work.id,
                            workTitle = work.title,
                            root = tree.root,
                            startTrackIndex = trackIndex,
                        )
                        if (!queue.isPlayable) {
                            throw IllegalStateException("该作品没有可播放的音频")
                        }
                        _uiState.update {
                            it.copy(
                                workTitle = work.title,
                                trackTitle = queue.items[queue.startIndex].trackTitle,
                            )
                        }
                        queue
                    }
                }
            }
        }
        outcome
            .onSuccess { queue -> connectAndPlay(queue) }
            .onFailure { e ->
                if (e is CancellationException) throw e
                _uiState.update { it.copy(loading = false, error = e.message ?: "播放启动失败") }
            }
    }

    private fun connectAndPlay(queue: PlayQueue) {
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
            controller.setMediaItems(queue.items.map { it.toMediaItem() }, queue.startIndex, 0L)
            controller.prepare()
            controller.play()
            controller.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _uiState.update { it.copy(isPlaying = isPlaying) }
                }

                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    val title = mediaItem?.mediaMetadata?.title?.toString() ?: return
                    _uiState.update { it.copy(trackTitle = title) }
                }

                override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                    _uiState.update { it.copy(queueSize = timeline.windowCount) }
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    _uiState.update { it.copy(repeatMode = repeatMode) }
                }

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    _uiState.update { it.copy(shuffleEnabled = shuffleModeEnabled) }
                }

                override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                    _uiState.update { it.copy(speed = playbackParameters.speed) }
                }
            })
            _uiState.update {
                it.copy(
                    loading = false,
                    queueSize = controller.mediaItemCount,
                    repeatMode = controller.repeatMode,
                    shuffleEnabled = controller.shuffleModeEnabled,
                    speed = controller.playbackParameters.speed,
                )
            }
        }, ContextCompat.getMainExecutor(context))
    }

    override fun onCleared() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onCleared()
    }

    // ------------------------------------------------------------------
    // Task 18 queue controls — drive the SESSION (single source of truth);
    // PlaybackService persists every change and never rebuilds the player.
    // ------------------------------------------------------------------

    /** Cycles repeat OFF -> ALL -> ONE -> OFF. */
    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = c.repeatMode.toRepeatMode().next().toMedia3()
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun speedUp() {
        val c = controller ?: return
        c.setPlaybackSpeed(PlaybackSpeed.stepUp(c.playbackParameters.speed))
    }

    fun speedDown() {
        val c = controller ?: return
        c.setPlaybackSpeed(PlaybackSpeed.stepDown(c.playbackParameters.speed))
    }

    /** Guarded next: an empty queue must be a no-op, never a crash. */
    fun nextTrack() {
        val c = controller ?: return
        if (c.mediaItemCount > 0) c.seekToNextMediaItem()
    }

    /** Reorder evidence: swap the current item one position forward. */
    fun swapWithNext() {
        val c = controller ?: return
        val i = c.currentMediaItemIndex
        if (i >= 0 && i + 1 < c.mediaItemCount) c.moveMediaItem(i, i + 1)
    }

    fun clearQueue() {
        val c = controller ?: return
        c.setMediaItems(emptyList())
    }
}

/**
 * Task 17 placeholder player screen (full UI in Task 21): requests the
 * POST_NOTIFICATIONS runtime permission (Android 13+) and shows the playback
 * status. Playback works even when the permission is denied — only the
 * notification is affected; the denial path must not crash (QA failure path).
 */
@Composable
fun PlayerScreen(viewModel: PlayerViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted or denied — playback proceeds either way */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when {
            state.loading -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("正在启动播放…", style = MaterialTheme.typography.bodyMedium)
            }
            state.error != null -> {
                Text(
                    state.error!!,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            else -> {
                Text(
                    state.workTitle,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    state.trackTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.isPlaying) "▶ 播放中" else "⏸ 已暂停",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(16.dp))
                QueueControls(viewModel, state)
                Spacer(Modifier.height(16.dp))
                Text(
                    "播放器界面将在后续任务中完成 — 按 Home 键可从通知栏控制播放",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Task 18 queue-mode control cluster (temporary QA surface; Task 21 ships the
 * real player UI). All state lives in the session — this cluster only sends
 * standard Player commands through the [MediaController].
 */
@Composable
private fun QueueControls(viewModel: PlayerViewModel, state: PlayerUiState) {
    val repeatLabel = when (state.repeatMode) {
        Player.REPEAT_MODE_ALL -> "循环:列表"
        Player.REPEAT_MODE_ONE -> "循环:单曲"
        else -> "循环:关闭"
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "队列 ${state.queueSize} 首 · 随机 ${if (state.shuffleEnabled) "开" else "关"} · 倍速 ${state.speed}x",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::cycleRepeat) { Text(repeatLabel) }
            Button(onClick = viewModel::toggleShuffle) {
                Text(if (state.shuffleEnabled) "随机 开" else "随机 关")
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::speedDown) { Text("倍速 −") }
            Button(onClick = viewModel::speedUp) { Text("倍速 +") }
            Button(onClick = viewModel::nextTrack, enabled = state.queueSize > 0) { Text("下一首") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::swapWithNext, enabled = state.queueSize > 1) { Text("交换下一首") }
            Button(onClick = viewModel::clearQueue) { Text("清空队列") }
        }
    }
}
