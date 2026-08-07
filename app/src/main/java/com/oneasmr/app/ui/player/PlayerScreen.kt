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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.oneasmr.app.navigation.Routes
import com.oneasmr.app.player.PlayQueueBuilder
import com.oneasmr.app.player.PlaybackService
import com.oneasmr.app.player.toMediaItem
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
 */
data class PlayerUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val workTitle: String = "",
    val trackTitle: String = "",
    val isPlaying: Boolean = false,
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
            })
            _uiState.update { it.copy(loading = false) }
        }, ContextCompat.getMainExecutor(context))
    }

    override fun onCleared() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onCleared()
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
