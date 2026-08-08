package com.oneasmr.app.player

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.oneasmr.app.ui.player.PlayerEvent
import com.oneasmr.app.ui.player.PlayerSnapshot
import com.oneasmr.app.ui.player.PlayerSnapshotMapper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide connection to the [PlaybackService] media session — the mini
 * player bar's (plan Task 21) single source of truth.
 *
 * Visibility and every field of [snapshot] are derived from the MediaSession
 * connection, NOT from app-local state: the controller binds the session
 * (creating the service if needed — MediaController auto-reconnects when the
 * service restarts), and [Player.Listener] callbacks re-derive the snapshot
 * through the pure [PlayerSnapshotMapper]. The queue itself is NEVER touched
 * here — it lives on the session (Task 18 persists it); this class only
 * observes and issues player commands (pause/play/stop).
 *
 * Threading: MediaController is main-thread-affine (getters included, media3
 * 1.10.1) — all reads happen on the main executor inside listener callbacks;
 * mutations are issued from the UI thread. No blocking work anywhere (plan
 * must-not: never block the main thread).
 */
@Singleton
class SessionConnection @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _snapshot = MutableStateFlow(PlayerSnapshot())
    val snapshot: StateFlow<PlayerSnapshot> = _snapshot.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    // Declared BEFORE init: connect() (called from init) captures them.
    private val connectionListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            Log.i(TAG, "session disconnected")
            controller.removeListener(controllerListener)
            if (this@SessionConnection.controller === controller) {
                this@SessionConnection.controller = null
            }
            _snapshot.value = PlayerSnapshot()
        }
    }

    private val controllerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = publish()
        override fun onPlaybackStateChanged(playbackState: Int) = publish()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = publish()
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = publish()
        override fun onRepeatModeChanged(repeatMode: Int) = publish()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = publish()
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = publish()

        /**
         * Failure path (plan QA: work folder deleted mid-play -> toast + stop,
         * no ANR). The event drives the UI toast; the SERVICE mirrors the same
         * error (PlaybackService queueSnapshotListener) by clearing the queue
         * and stopping, so both sides agree without any app-local state.
         */
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "playback error: ${error.errorCodeName}: ${error.message}")
            publish()
            _events.tryEmit(PlayerEvent.PlaybackFailed(error.message ?: error.errorCodeName))
        }
    }

    init {
        connect()
    }

    /** (Re)connects the controller; MediaController reconnects automatically
     *  when the service (re)starts, and onDisconnected re-derives the state. */
    private fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setListener(connectionListener)
            .buildAsync()
        controllerFuture = future
        future.addListener({
            val controller = try {
                future.get()
            } catch (e: Exception) {
                // Service bind failed (e.g. process just killed). The
                // controller auto-reconnects; keep the snapshot disconnected.
                Log.w(TAG, "controller connect failed: ${e.message}")
                _snapshot.value = PlayerSnapshot()
                return@addListener
            }
            this.controller = controller
            controller.addListener(controllerListener)
            publish()
        }, ContextCompat.getMainExecutor(context))
    }

    /** Re-derives [snapshot] from the live session (main thread only). */
    private fun publish() {
        val c = controller ?: return
        val current = c.currentMediaItem
        _snapshot.value = PlayerSnapshotMapper.map(
            connected = true,
            mediaItemCount = c.mediaItemCount,
            currentIndex = c.currentMediaItemIndex,
            currentMediaId = current?.mediaId,
            trackTitle = current?.mediaMetadata?.title?.toString().orEmpty(),
            workTitle = current?.mediaMetadata?.artist?.toString().orEmpty(),
            isPlaying = c.isPlaying,
            playbackState = c.playbackState,
            positionMs = c.currentPosition,
            bufferedPositionMs = c.bufferedPosition,
            durationMs = c.duration,
            repeatMode = c.repeatMode.toRepeatMode(),
            shuffleEnabled = c.shuffleModeEnabled,
            speed = c.playbackParameters.speed,
        )
    }

    /** Play/pause toggle on the session (mini bar control). */
    fun togglePlayPause() {
        val c = controller ?: return
        if (c.playWhenReady) c.pause() else c.play()
    }

    /**
     * Mini-bar swipe-to-dismiss -> stop playback (plan Task 21 "可滑动关闭停止").
     * Pause then clear the session queue: the service's captureSession
     * persists the cleared queue (Task 18) and the notification disappears —
     * the session itself becomes idle, so [snapshot.hasSession] flips false
     * and the mini bar leaves composition. The queue is cleared THROUGH the
     * session, never from a local copy.
     */
    fun stopPlayback() {
        val c = controller ?: return
        Log.i(TAG, "mini bar dismiss: stopping playback (pause + clear session queue)")
        c.pause()
        c.setMediaItems(emptyList())
    }

    fun release() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller?.removeListener(controllerListener)
        controller = null
        scope.cancel()
    }

    companion object {
        private const val TAG = "SessionConnection"
    }
}
