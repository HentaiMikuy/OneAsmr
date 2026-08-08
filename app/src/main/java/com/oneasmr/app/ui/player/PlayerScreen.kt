package com.oneasmr.app.ui.player

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.oneasmr.app.data.local.KeySpec
import com.oneasmr.app.data.local.Work
import com.oneasmr.app.data.local.WorkDao
import com.oneasmr.app.data.local.settings.QueueStore
import com.oneasmr.app.data.lyrics.LrcLoader
import com.oneasmr.app.data.lyrics.LrcMatcher
import com.oneasmr.app.data.repository.CoverStore
import com.oneasmr.app.data.repository.CoverType
import com.oneasmr.app.data.scanner.TrackNode
import com.oneasmr.app.data.scanner.TrackNodeType
import com.oneasmr.app.data.scanner.TrackTreeBuilder
import com.oneasmr.app.data.scanner.WorkPathResolver
import com.oneasmr.app.domain.lyrics.LrcLyrics
import com.oneasmr.app.domain.player.PlayQueue
import com.oneasmr.app.domain.player.PlaybackSpeed
import com.oneasmr.app.navigation.Routes
import com.oneasmr.app.player.PlayQueueBuilder
import com.oneasmr.app.player.PlaybackService
import com.oneasmr.app.player.ResumePositionResolver
import com.oneasmr.app.player.SessionConnection
import com.oneasmr.app.player.toMedia3
import com.oneasmr.app.player.toMediaItem
import com.oneasmr.app.player.toRepeatMode
import com.oneasmr.app.ui.common.CoverImage
import com.oneasmr.app.ui.work.DocumentFsFactory
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
 * Full player page state (plan Task 21).
 *
 * Task 18/19/20 fields plus the Task 21 surface: playback position /
 * buffered position / duration (progress bar), current timeline index and
 * the current item's rjCode (cover). [playbackFailed] is the failure-path
 * banner (work folder deleted mid-play -> the session clears itself; the UI
 * just reflects the session).
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
    val playbackState: Int = Player.STATE_IDLE,
    val positionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val currentIndex: Int = 0,
    val currentRjCode: String? = null,
    val playbackFailed: String? = null,
) {
    val ready: Boolean get() = !loading && error == null
    val isBuffering: Boolean get() = playbackState == Player.STATE_BUFFERING
    val progressFraction: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val bufferedFraction: Float
        get() = if (durationMs > 0L) (bufferedPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** Task 20 lyrics state (unchanged semantics from Task 20). */
data class LyricsUiState(
    val loading: Boolean = false,
    val lyrics: LrcLyrics? = null,
    val activeIndex: Int = -1,
)

/** Queue bottom-sheet state: a display mirror of the SESSION timeline. */
data class QueuePanelUiState(
    val open: Boolean = false,
    val items: List<QueuePanelOps.QueuePanelItem> = emptyList(),
    val currentIndex: Int = 0,
)

/**
 * Full player page (plan Task 21).
 *
 * Queue ownership (must-not "播放页不重建队列"): the session is the single
 * source of truth. Two entry modes:
 * - ATTACH-only: the session already plays the requested work (mini bar tap,
 *   cold-start restore, same-work re-entry) — the page only connects a
 *   controller and observes; NOTHING is rebuilt.
 * - LAUNCH: the session is empty or plays another work (detail-page tap) —
 *   the queue is built from the work's live track tree (Task 17/18 launch
 *   flow) and handed to the session; afterwards the session owns it.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val workDao: WorkDao,
    private val fsFactory: DocumentFsFactory,
    private val resumePositionResolver: ResumePositionResolver,
    private val lrcLoader: LrcLoader,
    private val queueStore: QueueStore,
    sessionConnection: SessionConnection,
) : ViewModel() {

    private val workId: String = checkNotNull(savedStateHandle[Routes.WORK_DETAIL_ARG])
    private val trackIndex: Int = checkNotNull(savedStateHandle[Routes.TRACK_INDEX_ARG])

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _lyricsUi = MutableStateFlow(LyricsUiState())
    val lyricsUi: StateFlow<LyricsUiState> = _lyricsUi.asStateFlow()

    private val _queuePanel = MutableStateFlow(QueuePanelUiState())
    val queuePanel: StateFlow<QueuePanelUiState> = _queuePanel.asStateFlow()

    private val _sleepTimerUi = MutableStateFlow(SleepTimerUiState())
    val sleepTimerUi: StateFlow<SleepTimerUiState> = _sleepTimerUi.asStateFlow()

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    /** Live track tree of the playing work (lyrics matching needs it). */
    private var treeRoot: TrackNode? = null
    private var audioByIndex: Map<Int, TrackNode> = emptyMap()
    private var workRow: Work? = null
    private var lyricsJob: Job? = null
    private var loadedForTrackIndex: Int? = null
    private var tickerJob: Job? = null

    private val sleepTimerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            _sleepTimerUi.value = SleepTimerUiState.fromExtras(intent)
        }
    }

    init {
        connectAndDispatch()
        // App-local sleep-timer state push from the service (Task 21 picker).
        ContextCompat.registerReceiver(
            context.applicationContext,
            sleepTimerReceiver,
            IntentFilter(PlaybackService.ACTION_SLEEP_TIMER_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Failure channel (work folder deleted mid-play): the shared session
        // connection's error event is the reliable source — the page's own
        // controller can miss onPlayerError when the service clears+stops
        // right after the error. Same event drives the mini-bar toast.
        viewModelScope.launch {
            sessionConnection.events.collect { event ->
                if (event is PlayerEvent.PlaybackFailed) {
                    _uiState.update { it.copy(playbackFailed = event.message) }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Connection + entry-mode dispatch
    // ------------------------------------------------------------------

    /**
     * Connects the controller and dispatches the entry mode. The controller's
     * own connection runs on the main looper — reading the future via
     * `future.get()` on the main thread would deadlock it, so the get() lives
     * INSIDE the completion listener (which runs after the future resolved).
     */
    private fun connectAndDispatch() {
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
            val parts = controller.currentMediaItem?.mediaId?.let(KeySpec::parseTrackKey)
            val sameWork = parts != null &&
                KeySpec.workId(parts.sourceScope, parts.rjCode) == workId
            if (controller.mediaItemCount > 0 && sameWork) {
                // ATTACH: the session already owns this work's queue (mini bar
                // tap / restored session / same-work re-entry). Never rebuild.
                attachOnly(controller, parts!!)
            } else {
                dispatchSettled(controller)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Cold-start race (deep-link straight into the player): the session's
     * [PlaybackService.restoreSession] populates the persisted queue
     * asynchronously, so a just-connected controller can look empty. The
     * persisted queue (QueueStore, Task 18) IS the session's own state —
     * when it belongs to the requested work we WAIT for the restore to land
     * and attach instead of rebuilding (plan must-not). Bounded; on timeout
     * the session is genuinely empty and the launch path takes over.
     */
    private fun dispatchSettled(controller: MediaController) {
        viewModelScope.launch {
            val persisted = withContext(Dispatchers.IO) { queueStore.queue.first() }
            if (persisted?.workId != workId) {
                launchFromTree(controller)
                return@launch
            }
            var waited = 0L
            while (waited < RESTORE_SETTLE_MS) {
                val p = controller.currentMediaItem?.mediaId?.let(KeySpec::parseTrackKey)
                if (controller.mediaItemCount > 0 && p != null &&
                    KeySpec.workId(p.sourceScope, p.rjCode) == workId
                ) {
                    attachOnly(controller, p)
                    return@launch
                }
                delay(RESTORE_POLL_MS)
                waited += RESTORE_POLL_MS
            }
            Log.w(TAG, "persisted session for $workId never landed; falling back to launch")
            launchFromTree(controller)
        }
    }

    private fun attachOnly(controller: MediaController, parts: KeySpec.TrackKeyParts) {
        Log.i(TAG, "attach-only: session already plays $workId (queue=${controller.mediaItemCount}, index=${controller.currentMediaItemIndex})")
        registerSessionListener(controller)
        refreshFromController()
        // Lyrics need the work tree; load it lazily (never blocks the UI).
        viewModelScope.launch {
            runCatching { loadWorkTree() }
                .onFailure { Log.w(TAG, "attach-only: tree unavailable (${it.message}); lyrics entry hidden") }
            loadLyricsFor(parts.trackIndex)
        }
        startTicker()
    }

    private fun launchFromTree(controller: MediaController) {
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) { buildLaunch() }
            }
            outcome
                .onSuccess { launch -> applyLaunch(controller, launch) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _uiState.update { it.copy(loading = false, error = e.message ?: "播放启动失败") }
                }
        }
    }

    private suspend fun buildLaunch(): PlaybackLaunch {
        val work = workDao.getById(workId)
            ?: throw IllegalStateException("作品不在库中: $workId")
        workRow = work
        val fs = fsFactory.create(work.rootFolderUri)
        return when (val resolved = WorkPathResolver(fs).resolve(work.relativeDir)) {
            is WorkPathResolver.Result.NotFound ->
                throw IllegalStateException("作品文件夹不可访问: ${resolved.message}")
            is WorkPathResolver.Result.Found -> {
                val tree = TrackTreeBuilder(fs).build(
                    resolved.path, resolved.displayName, resolved.documentUri,
                )
                treeRoot = tree.root
                audioByIndex = collectAudioNodes(tree.root)
                val queue = PlayQueueBuilder().build(
                    workId = work.id,
                    workTitle = work.title,
                    root = tree.root,
                    startTrackIndex = trackIndex,
                )
                if (!queue.isPlayable) {
                    throw IllegalStateException("该作品没有可播放的音频")
                }
                val startItem = queue.items[queue.startIndex]
                val startPositionMs = resumePositionResolver.resolve(
                    KeySpec.trackKey(startItem.sourceScope, startItem.rjCode, startItem.trackIndex),
                )
                PlaybackLaunch(queue, startPositionMs, work)
            }
        }
    }

    private fun applyLaunch(controller: MediaController, launch: PlaybackLaunch) {
        _uiState.update {
            it.copy(
                workTitle = launch.work.title,
                trackTitle = launch.queue.items[launch.queue.startIndex].trackTitle,
            )
        }
        controller.setMediaItems(
            launch.queue.items.map { it.toMediaItem() },
            launch.queue.startIndex,
            launch.startPositionMs,
        )
        controller.prepare()
        controller.play()
        registerSessionListener(controller)
        refreshFromController()
        loadLyricsFor(launch.queue.items[launch.queue.startIndex].trackIndex)
        startTicker()
    }

    private data class PlaybackLaunch(val queue: PlayQueue, val startPositionMs: Long, val work: Work)

    /** Loads the work row + live track tree (shared by launch + attach paths). */
    private suspend fun loadWorkTree() {
        val work = workRow ?: workDao.getById(workId) ?: return
        workRow = work
        val fs = fsFactory.create(work.rootFolderUri)
        val resolved = WorkPathResolver(fs).resolve(work.relativeDir)
        if (resolved is WorkPathResolver.Result.Found) {
            val tree = TrackTreeBuilder(fs).build(
                resolved.path, resolved.displayName, resolved.documentUri,
            )
            treeRoot = tree.root
            audioByIndex = collectAudioNodes(tree.root)
        }
    }

    private fun registerSessionListener(controller: MediaController) {
        controller.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _uiState.update { it.copy(playbackState = playbackState) }
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                mediaItem?.mediaMetadata?.title?.toString()?.let { title ->
                    _uiState.update { it.copy(trackTitle = title, playbackFailed = null) }
                }
                mediaItem?.mediaMetadata?.artist?.toString()?.let { artist ->
                    _uiState.update { it.copy(workTitle = artist) }
                }
                val trackKey = mediaItem?.mediaId?.let(KeySpec::parseTrackKey)
                _uiState.update { it.copy(currentRjCode = trackKey?.rjCode) }
                if (trackKey != null) loadLyricsFor(trackKey.trackIndex)
                refreshQueuePanelSilently()
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                _uiState.update { it.copy(queueSize = timeline.windowCount) }
                refreshQueuePanelSilently()
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

            override fun onPlayerError(error: PlaybackException) {
                // Session clears itself on fatal errors (PlaybackService); the
                // page just surfaces the failure banner — no ANR, no retry loop.
                _uiState.update { it.copy(playbackFailed = error.message ?: error.errorCodeName) }
            }
        })
    }

    private fun refreshFromController() {
        val c = controller ?: return
        val current = c.currentMediaItem
        val parts = current?.mediaId?.let(KeySpec::parseTrackKey)
        _uiState.update {
            it.copy(
                loading = false,
                workTitle = current?.mediaMetadata?.artist?.toString() ?: it.workTitle,
                trackTitle = current?.mediaMetadata?.title?.toString() ?: it.trackTitle,
                isPlaying = c.isPlaying,
                queueSize = c.mediaItemCount,
                repeatMode = c.repeatMode,
                shuffleEnabled = c.shuffleModeEnabled,
                speed = c.playbackParameters.speed,
                playbackState = c.playbackState,
                positionMs = c.currentPosition,
                bufferedPositionMs = c.bufferedPosition,
                durationMs = c.duration,
                currentIndex = c.currentMediaItemIndex,
                currentRjCode = parts?.rjCode,
            )
        }
    }

    /** 500ms position/buffer ticker — the media-session getters are
     *  main-thread affine; the ViewModel scope runs on Main.immediate. */
    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                val c = controller ?: break
                _uiState.update {
                    it.copy(
                        positionMs = c.currentPosition,
                        bufferedPositionMs = c.bufferedPosition,
                        durationMs = c.duration.coerceAtLeast(0L),
                    )
                }
                delay(POSITION_TICK_MS)
            }
        }
    }

    override fun onCleared() {
        lyricsJob?.cancel()
        tickerJob?.cancel()
        runCatching { context.applicationContext.unregisterReceiver(sleepTimerReceiver) }
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onCleared()
    }

    // ------------------------------------------------------------------
    // Task 20 synced lyrics
    // ------------------------------------------------------------------

    private fun collectAudioNodes(root: TrackNode): Map<Int, TrackNode> {
        val byIndex = mutableMapOf<Int, TrackNode>()
        fun walk(node: TrackNode) {
            if (node.isFolder) {
                node.children.forEach { walk(it) }
                return
            }
            if (node.type == TrackNodeType.AUDIO && node.trackIndex != null) {
                byIndex[node.trackIndex] = node
            }
        }
        walk(root)
        return byIndex
    }

    private fun loadLyricsFor(trackIndex: Int) {
        if (loadedForTrackIndex == trackIndex) return
        loadedForTrackIndex = trackIndex
        lyricsJob?.cancel()
        val root = treeRoot
        val audio = audioByIndex[trackIndex]
        if (root == null || audio == null) {
            _lyricsUi.value = LyricsUiState()
            return
        }
        val lrcNode = LrcMatcher.findLrc(root, audio.relativePath)
        if (lrcNode == null) {
            Log.i(TAG, "lyrics: no matching .lrc for track $trackIndex (${audio.relativePath})")
            _lyricsUi.value = LyricsUiState()
            return
        }
        _lyricsUi.value = LyricsUiState(loading = true)
        lyricsJob = viewModelScope.launch {
            val lyrics = withContext(Dispatchers.IO) { lrcLoader.load(lrcNode.documentUri) }
            if (lyrics == null) {
                Log.w(TAG, "lyrics: unavailable for track $trackIndex (${lrcNode.name})")
                _lyricsUi.value = LyricsUiState()
                return@launch
            }
            _lyricsUi.update { it.copy(loading = false, lyrics = lyrics) }
            while (isActive) {
                val positionMs = controller?.currentPosition ?: 0L
                val index = lyrics.indexAt(positionMs)
                if (index != _lyricsUi.value.activeIndex) {
                    _lyricsUi.update { it.copy(activeIndex = index) }
                }
                delay(LYRICS_TICK_MS)
            }
        }
    }

    fun seekToLyric(timestampMs: Long) {
        controller?.seekTo(timestampMs)
    }

    // ------------------------------------------------------------------
    // Transport + modes (session commands; Task 18 semantics)
    // ------------------------------------------------------------------

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = c.repeatMode.toRepeatMode().next().toMedia3()
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun setSpeed(speed: Float) {
        val c = controller ?: return
        c.setPlaybackSpeed(PlaybackSpeed.normalize(speed))
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun prevTrack() {
        val c = controller ?: return
        if (c.mediaItemCount > 0) c.seekToPreviousMediaItem()
    }

    fun nextTrack() {
        val c = controller ?: return
        if (c.mediaItemCount > 0) c.seekToNextMediaItem()
    }

    fun seekTo(positionMs: Long) {
        val c = controller ?: return
        c.seekTo(c.currentMediaItemIndex, positionMs.coerceAtLeast(0L))
    }

    // ------------------------------------------------------------------
    // Queue panel (Task 21): edits the SESSION queue via QueueOps semantics
    // ------------------------------------------------------------------

    fun openQueuePanel() {
        refreshQueuePanel()
        _queuePanel.update { it.copy(open = true) }
    }

    fun closeQueuePanel() {
        _queuePanel.update { it.copy(open = false) }
    }

    fun moveQueueItem(from: Int, to: Int) {
        val c = controller ?: return
        val move = QueuePanelOps.resolveMove(queueMirror(), from, to) ?: return
        c.moveMediaItem(move.first, move.second)
        refreshQueuePanel()
    }

    fun removeQueueItem(index: Int) {
        val c = controller ?: return
        val remove = QueuePanelOps.resolveRemove(queueMirror(), index) ?: return
        c.removeMediaItem(remove)
        refreshQueuePanel()
    }

    fun seekToQueueItem(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) c.seekTo(index, 0L)
    }

    private fun queueMirror(): PlayQueue =
        QueuePanelOps.mirror(currentTimelineEntries(), controller?.currentMediaItemIndex ?: 0)

    private fun currentTimelineEntries(): List<QueuePanelOps.Entry> {
        val c = controller ?: return emptyList()
        val entries = mutableListOf<QueuePanelOps.Entry>()
        val timeline = c.currentTimeline
        for (i in 0 until timeline.windowCount) {
            val window = androidx.media3.common.Timeline.Window()
            timeline.getWindow(i, window)
            val mediaItem = window.mediaItem ?: continue
            entries += QueuePanelOps.Entry(
                mediaId = mediaItem.mediaId,
                trackTitle = mediaItem.mediaMetadata.title?.toString().orEmpty(),
                workTitle = mediaItem.mediaMetadata.artist?.toString().orEmpty(),
            )
        }
        return entries
    }

    private fun refreshQueuePanel() {
        val c = controller ?: return
        val currentIndex = c.currentMediaItemIndex
        _queuePanel.value = _queuePanel.value.copy(
            items = QueuePanelOps.rows(currentTimelineEntries(), currentIndex),
            currentIndex = currentIndex,
        )
    }

    private fun refreshQueuePanelSilently() {
        if (_queuePanel.value.open) refreshQueuePanel()
    }

    // ------------------------------------------------------------------
    // Sleep timer (Task 19 presets; Task 21 picker UI)
    // ------------------------------------------------------------------

    fun startSleepTimerMs(ms: Long) {
        sendSleepTimerCommand(
            Bundle().apply { putLong(PlaybackService.EXTRA_SLEEP_TIMER_MS, ms) },
        )
    }

    fun startSleepTimerEndOfTrack() {
        sendSleepTimerCommand(
            Bundle().apply { putString(PlaybackService.EXTRA_SLEEP_TIMER_MODE, PlaybackService.MODE_END_OF_TRACK) },
        )
    }

    fun cancelSleepTimer() {
        sendSleepTimerCommand(
            Bundle().apply { putBoolean(PlaybackService.EXTRA_SLEEP_TIMER_CANCEL, true) },
        )
    }

    /** Pulls the current timer state (first open; then the broadcast ticks). */
    fun querySleepTimerState() {
        val c = controller ?: return
        val future = c.sendCustomCommand(
            SessionCommand(PlaybackService.ACTION_SLEEP_TIMER_STATE, Bundle.EMPTY),
            Bundle.EMPTY,
        )
        future.addListener({
            val result = try {
                future.get()
            } catch (e: Exception) {
                Log.w(TAG, "sleep timer state query failed: ${e.message}")
                null
            }
            result?.extras?.let { _sleepTimerUi.value = SleepTimerUiState.fromBundle(it) }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun sendSleepTimerCommand(args: Bundle) {
        val c = controller ?: return
        c.sendCustomCommand(
            SessionCommand(PlaybackService.ACTION_SLEEP_TIMER_SET, Bundle.EMPTY),
            args,
        )
    }

    companion object {
        private const val TAG = "PlayerViewModel"
        private const val POSITION_TICK_MS = 500L
        private const val LYRICS_TICK_MS = 100L
        private const val RESTORE_SETTLE_MS = 2_000L
        private const val RESTORE_POLL_MS = 100L
    }
}

/** Sleep-timer picker view state — driven by the service broadcast/query. */
data class SleepTimerUiState(
    val active: Boolean = false,
    val modeName: String = "COUNTDOWN",
    val remainingMs: Long = 0L,
    val display: String = "",
) {
    companion object {
        fun fromExtras(intent: Intent): SleepTimerUiState = SleepTimerUiState(
            active = intent.getBooleanExtra(PlaybackService.EXTRA_SLEEP_TIMER_ACTIVE, false),
            modeName = intent.getStringExtra(PlaybackService.EXTRA_SLEEP_TIMER_MODE) ?: "COUNTDOWN",
            remainingMs = intent.getLongExtra(PlaybackService.EXTRA_SLEEP_TIMER_REMAINING_MS, 0L),
            display = intent.getStringExtra(PlaybackService.EXTRA_SLEEP_TIMER_DISPLAY).orEmpty(),
        )

        fun fromBundle(bundle: Bundle): SleepTimerUiState = SleepTimerUiState(
            active = bundle.getBoolean(PlaybackService.EXTRA_SLEEP_TIMER_ACTIVE, false),
            modeName = bundle.getString(PlaybackService.EXTRA_SLEEP_TIMER_MODE) ?: "COUNTDOWN",
            remainingMs = bundle.getLong(PlaybackService.EXTRA_SLEEP_TIMER_REMAINING_MS, 0L),
            display = bundle.getString(PlaybackService.EXTRA_SLEEP_TIMER_DISPLAY).orEmpty(),
        )
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlayerCoverEntryPoint {
    fun coverStore(): CoverStore
}

@Composable
private fun rememberPlayerCoverStore(): CoverStore {
    val context = LocalContext.current
    return remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            PlayerCoverEntryPoint::class.java,
        ).coverStore()
    }
}

/**
 * Full player screen (plan Task 21): cover (rotating optional), track/work
 * titles, buffered progress bar + seek slider, prev/play/next, repeat /
 * shuffle / speed, sleep-timer picker (Task 19 presets), lyrics panel toggle
 * (Task 20 — entry hidden when the track has no .lrc) and the queue bottom
 * sheet (Task 18 reorder surface). Lock-screen styling is the MediaSession
 * notification (Task 17) — no custom work here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lyricsState by viewModel.lyricsUi.collectAsStateWithLifecycle()
    val queuePanel by viewModel.queuePanel.collectAsStateWithLifecycle()
    val sleepTimer by viewModel.sleepTimerUi.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coverStore = rememberPlayerCoverStore()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted or denied — playback proceeds either way */ }

    var showSpeedDialog by rememberSaveable { mutableStateOf(false) }
    var showSleepDialog by rememberSaveable { mutableStateOf(false) }
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var rotateCover by rememberSaveable { mutableStateOf(false) }
    var failureDismissed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(showSleepDialog) {
        if (showSleepDialog) viewModel.querySleepTimerState()
    }
    LaunchedEffect(state.playbackFailed) {
        failureDismissed = state.playbackFailed == null
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                state.workTitle.ifEmpty { "播放" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (state.playbackFailed != null && !failureDismissed) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics {
                        contentDescription = "playback failed ${state.playbackFailed}"
                    },
            ) {
                Row(
                    Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "播放失败：${state.playbackFailed} — 作品文件夹可能已被移动或删除，播放已停止。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { failureDismissed = true }) { Text("知道了") }
                }
            }
        }

        when {
            state.loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("正在启动播放…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            state.error != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        state.error!!,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            else -> {
                PlayerContent(
                    state = state,
                    sleepTimer = sleepTimer,
                    lyricsState = lyricsState,
                    showLyrics = showLyrics,
                    onToggleLyrics = { showLyrics = !showLyrics },
                    rotateCover = rotateCover,
                    onToggleRotate = { rotateCover = !rotateCover },
                    coverStore = coverStore,
                    onSeek = viewModel::seekTo,
                    onPrev = viewModel::prevTrack,
                    onTogglePlayPause = viewModel::togglePlayPause,
                    onNext = viewModel::nextTrack,
                    onCycleRepeat = viewModel::cycleRepeat,
                    onToggleShuffle = viewModel::toggleShuffle,
                    onSpeedClick = { showSpeedDialog = true },
                    onSleepClick = { showSleepDialog = true },
                    onQueueClick = viewModel::openQueuePanel,
                    onSeekToLyric = viewModel::seekToLyric,
                )
            }
        }
    }

    if (showSpeedDialog) {
        SpeedDialog(
            current = state.speed,
            onSelect = { viewModel.setSpeed(it); showSpeedDialog = false },
            onDismiss = { showSpeedDialog = false },
        )
    }

    if (showSleepDialog) {
        SleepTimerDialog(
            state = sleepTimer,
            onPreset = { viewModel.startSleepTimerMs(it); showSleepDialog = false },
            onEndOfTrack = { viewModel.startSleepTimerEndOfTrack(); showSleepDialog = false },
            onCancelTimer = { viewModel.cancelSleepTimer() },
            onDismiss = { showSleepDialog = false },
        )
    }

    if (queuePanel.open) {
        ModalBottomSheet(onDismissRequest = viewModel::closeQueuePanel) {
            QueuePanelSheet(
                queuePanel = queuePanel,
                onMove = viewModel::moveQueueItem,
                onRemove = viewModel::removeQueueItem,
                onSeek = viewModel::seekToQueueItem,
            )
        }
    }
}

@Composable
private fun PlayerContent(
    state: PlayerUiState,
    sleepTimer: SleepTimerUiState,
    lyricsState: LyricsUiState,
    showLyrics: Boolean,
    onToggleLyrics: () -> Unit,
    rotateCover: Boolean,
    onToggleRotate: () -> Unit,
    coverStore: CoverStore,
    onSeek: (Long) -> Unit,
    onPrev: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onSpeedClick: () -> Unit,
    onSleepClick: () -> Unit,
    onQueueClick: () -> Unit,
    onSeekToLyric: (Long) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PlayerCover(
            rjCode = state.currentRjCode,
            workTitle = state.workTitle,
            coverStore = coverStore,
            rotate = rotateCover,
            onToggleRotate = onToggleRotate,
        )

        Spacer(Modifier.height(16.dp))
        Text(
            state.trackTitle,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            state.workTitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(16.dp))

        // Buffered + played positions (Media3 bufferedPosition drives the fill).
        LinearProgressIndicator(
            progress = { state.bufferedFraction },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "buffered ${(state.bufferedFraction * 100).toInt()}%" },
        )
        var dragPosition by remember { mutableFloatStateOf(-1f) }
        Slider(
            value = if (dragPosition >= 0f) dragPosition else state.positionMs.toFloat(),
            onValueChange = { dragPosition = it },
            onValueChangeFinished = {
                if (dragPosition >= 0f) {
                    onSeek(dragPosition.toLong())
                    dragPosition = -1f
                }
            },
            valueRange = 0f..(state.durationMs.coerceAtLeast(1L).toFloat()),
            modifier = Modifier.semantics {
                contentDescription = "player position=${state.positionMs} duration=${state.durationMs}"
            },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(
                formatTime(state.positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (state.isBuffering) "缓冲中…" else formatTime(state.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IconButton(
                onClick = onPrev,
                enabled = state.queueSize > 0,
                modifier = Modifier.semantics { contentDescription = "prev track" },
            ) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = null, Modifier.size(36.dp))
            }
            IconButton(
                onClick = onTogglePlayPause,
                enabled = state.queueSize > 0,
                modifier = Modifier
                    .size(72.dp)
                    .semantics {
                        contentDescription = if (state.isPlaying) "player pause" else "player play"
                    },
            ) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                )
            }
            IconButton(
                onClick = onNext,
                enabled = state.queueSize > 0,
                modifier = Modifier.semantics { contentDescription = "next track" },
            ) {
                Icon(Icons.Filled.SkipNext, contentDescription = null, Modifier.size(36.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(
                onClick = onCycleRepeat,
                modifier = Modifier.semantics {
                    contentDescription = when (state.repeatMode) {
                        Player.REPEAT_MODE_ALL -> "repeat=ALL"
                        Player.REPEAT_MODE_ONE -> "repeat=ONE"
                        else -> "repeat=OFF"
                    }
                },
            ) {
                Icon(
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne
                    else Icons.Filled.Repeat,
                    contentDescription = null,
                    tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(
                onClick = onToggleShuffle,
                modifier = Modifier.semantics {
                    contentDescription = "shuffle=${if (state.shuffleEnabled) "on" else "off"}"
                },
            ) {
                Icon(
                    Icons.Filled.Shuffle,
                    contentDescription = null,
                    tint = if (state.shuffleEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            TextButton(
                onClick = onSpeedClick,
                modifier = Modifier.semantics { contentDescription = "speed=${state.speed}" },
            ) {
                Icon(Icons.Filled.Speed, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("${state.speed}x")
            }
            IconButton(
                onClick = onSleepClick,
                modifier = Modifier.semantics {
                    contentDescription = "sleep timer active=${sleepTimer.active} display=${sleepTimer.display}"
                },
            ) {
                Icon(Icons.Filled.Timer, contentDescription = null, Modifier.size(22.dp))
            }
            if (lyricsState.lyrics != null) {
                IconButton(
                    onClick = onToggleLyrics,
                    modifier = Modifier.semantics {
                        contentDescription = "lyrics panel=${if (showLyrics) "on" else "off"}"
                    },
                ) {
                    Icon(
                        Icons.Filled.Subtitles,
                        contentDescription = null,
                        tint = if (showLyrics) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            IconButton(
                onClick = onQueueClick,
                modifier = Modifier.semantics {
                    contentDescription = "queue panel items=${state.queueSize}"
                },
            ) {
                Icon(Icons.Filled.QueueMusic, contentDescription = null, Modifier.size(22.dp))
            }
        }

        if (showLyrics) {
            Spacer(Modifier.height(8.dp))
            when {
                lyricsState.loading -> Text(
                    "正在加载歌词…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                lyricsState.lyrics != null -> LyricsPanel(
                    lyrics = lyricsState.lyrics!!,
                    activeIndex = lyricsState.activeIndex,
                    onLineClick = onSeekToLyric,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PlayerCover(
    rjCode: String?,
    workTitle: String,
    coverStore: CoverStore,
    rotate: Boolean,
    onToggleRotate: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "coverSpin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(16_000, easing = LinearEasing)),
        label = "coverSpinAngle",
    )
    Box(contentAlignment = Alignment.Center) {
        if (rjCode.isNullOrBlank()) {
            // No current item (e.g. the session was cleared after a playback
            // error) — render the placeholder surface, never a blank-rjCode
            // CoverStore call (it requires a non-blank code).
            Box(
                Modifier
                    .padding(top = 16.dp)
                    .size(240.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        } else {
            CoverImage(
                coverStore = coverStore,
                rjCode = rjCode,
                type = CoverType.MAIN,
                rootFolderUri = null,
                relativeDir = null,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .size(240.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .graphicsLayer { rotationZ = if (rotate) rotation else 0f },
            )
        }
        IconButton(
            onClick = onToggleRotate,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .semantics { contentDescription = "cover rotation=${if (rotate) "on" else "off"}" },
        ) {
            Icon(
                Icons.Filled.RotateRight,
                contentDescription = null,
                tint = if (rotate) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun SpeedDialog(
    current: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("倍速") },
        text = {
            Column {
                PlaybackSpeed.SUPPORTED.forEach { speed ->
                    TextButton(
                        onClick = { onSelect(speed) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (speed == current) "●  ${speed}x（当前）" else "${speed}x",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun SleepTimerDialog(
    state: SleepTimerUiState,
    onPreset: (Long) -> Unit,
    onEndOfTrack: () -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("睡眠定时") },
        text = {
            Column {
                if (state.active) {
                    Text(
                        "定时中：${state.display}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = onCancelTimer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("取消定时", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                    }
                    HorizontalDivider()
                }
                SleepTimerPresets.MINUTES.forEach { minutes ->
                    TextButton(
                        onClick = { onPreset(SleepTimerPresets.minutesToMs(minutes)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            SleepTimerPresets.labelFor(minutes),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                        )
                    }
                }
                TextButton(
                    onClick = onEndOfTrack,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        SleepTimerPresets.END_OF_TRACK_LABEL,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun QueuePanelSheet(
    queuePanel: QueuePanelUiState,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onSeek: (Int) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .semantics {
                contentDescription = "queue panel items=${queuePanel.items.size} current=${queuePanel.currentIndex}"
            },
    ) {
        Text(
            "播放队列（${queuePanel.items.size} 首）",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        queuePanel.items.forEach { item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (item.isCurrent) "▶" else "${item.index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (item.isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.width(28.dp),
                )
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { onSeek(item.index) }
                        .semantics { contentDescription = "queue item ${item.index} current=${item.isCurrent}" },
                ) {
                    Text(
                        item.trackTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        item.workTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = { onMove(item.index, item.index - 1) },
                    enabled = item.index > 0,
                    modifier = Modifier.semantics { contentDescription = "move up ${item.index}" },
                ) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null)
                }
                IconButton(
                    onClick = { onMove(item.index, item.index + 1) },
                    enabled = item.index < queuePanel.items.lastIndex,
                    modifier = Modifier.semantics { contentDescription = "move down ${item.index}" },
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                }
                IconButton(
                    onClick = { onRemove(item.index) },
                    modifier = Modifier.semantics { contentDescription = "remove ${item.index}" },
                ) {
                    Icon(Icons.Filled.Clear, contentDescription = null)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** "m:ss" / "h:mm:ss" (sleep-timer formatting is already unit-tested). */
private fun formatTime(ms: Long): String = com.oneasmr.app.player.SleepTimerController.formatCountdown(ms)
