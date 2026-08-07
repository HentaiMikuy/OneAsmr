package com.oneasmr.app.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.oneasmr.app.MainActivity
import com.oneasmr.app.R
import com.oneasmr.app.data.local.KeySpec
import com.oneasmr.app.data.local.ProgressState
import com.oneasmr.app.data.local.Review
import com.oneasmr.app.data.local.ReviewDao
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Playback core (plan Task 17): the app's single [MediaSessionService].
 *
 * - Owns the Hilt-injected ExoPlayer SINGLETON (never inside an Activity —
 *   plan must-not). The player survives service restarts in-process, which is
 *   what enables media-button playback resumption after `stopService`.
 * - Media notification: Media3 default provider + the app's custom action
 *   buttons — 睡眠定时 (sleep-timer countdown display; Task 19 fills the real
 *   timer via [SleepTimerController.onExpired]) and 标记进度 (six-state
 *   toggle through [ProgressStateCycle]). NO "收藏" action — favorites are
 *   explicitly out of scope (plan must-not).
 * - Audio focus / noisy / wake mode are configured on the player in
 *   [PlayerModule]; Bluetooth media buttons reach the session through the
 *   manifest MediaButtonReceiver (API 26-32) and the platform session
 *   (API 33+).
 * - Playback resumption (Android 13+): [SessionCallback.onPlaybackResumption]
 *   returns the last queue so a headset/media-button play request with an
 *   empty playlist restarts it. (Cross-process queue persistence is Task 18;
 *   this service keeps an in-process snapshot.)
 * - Foreground service: Media3's [MediaNotificationManager] handles
 *   startForeground for media sessions automatically (see
 *   MediaSessionService.onUpdateNotification default impl); the manifest
 *   declares FOREGROUND_SERVICE_MEDIA_PLAYBACK + foregroundServiceType.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    lateinit var player: ExoPlayer

    @Inject
    lateinit var reviewDao: ReviewDao

    private lateinit var notificationProvider: DefaultMediaNotificationProvider
    private var mediaSession: MediaSession? = null
    private lateinit var serviceScope: CoroutineScope
    private lateinit var sleepTimer: SleepTimerController

    /** Last queue snapshot for Android 13+ playback resumption. */
    private var lastQueue: List<MediaItem> = emptyList()
    private var lastStartIndex: Int = 0

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        sleepTimer = SleepTimerController(
            scope = serviceScope,
            onExpired = ::onSleepTimerExpired,
        )
        notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(NOTIFICATION_CHANNEL_ID)
            .setChannelName(R.string.playback_channel_name)
            .build()
        setMediaNotificationProvider(notificationProvider)

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setMediaButtonPreferences(mediaButtonPreferences(null))
            .build()

        player.addListener(queueSnapshotListener)
        // Re-render the notification on every sleep-timer tick (countdown label).
        serviceScope.launch {
            sleepTimer.state.collect { updateButtonLabels() }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = player
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        player.removeListener(queueSnapshotListener)
        sleepTimer.cancel()
        serviceScope.cancel()
        mediaSession?.run { release() }
        mediaSession = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Notification custom actions
    // ---------------------------------------------------------------------

    private fun mediaButtonPreferences(progress: ProgressState?): List<CommandButton> = listOf(
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setSessionCommand(SessionCommand(ACTION_SLEEP_TIMER, Bundle.EMPTY))
            .setDisplayName(sleepTimerButtonLabel())
            .setCustomIconResId(R.drawable.ic_sleep_timer)
            .build(),
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setSessionCommand(SessionCommand(ACTION_TOGGLE_PROGRESS, Bundle.EMPTY))
            .setDisplayName(progressButtonLabel(progress))
            .setCustomIconResId(R.drawable.ic_progress)
            .build(),
    )

    /** Countdown display while active, idle label otherwise. */
    private fun sleepTimerButtonLabel(): String =
        if (sleepTimer.state.value.active) "睡眠 ${sleepTimer.state.value.display}" else getString(R.string.player_action_sleep_timer)

    /** "标记进度：收听中" for the current work; plain label when idle. */
    private fun progressButtonLabel(progress: ProgressState?): String = progress?.let {
        getString(R.string.player_action_progress_fmt, progressLabel(it))
    } ?: getString(R.string.player_action_progress)

    /**
     * Rebuilds both custom buttons with current labels and re-renders.
     * The progress label reads the review row for the CURRENT work (from the
     * current media item's trackKey) — a DAO read on the service scope.
     */
    private fun updateButtonLabels() {
        val session = mediaSession ?: return
        val trackKey = session.player.currentMediaItem?.mediaId
        val parts = trackKey?.let { KeySpec.parseTrackKey(it) }
        val workId = parts?.let { KeySpec.workId(it.sourceScope, it.rjCode) }
        if (workId == null) {
            applyButtonLabels(null)
            return
        }
        serviceScope.launch {
            applyButtonLabels(reviewDao.getByWorkId(workId)?.progress)
        }
    }

    private fun applyButtonLabels(progress: ProgressState?) {
        val session = mediaSession ?: return
        session.setMediaButtonPreferences(mediaButtonPreferences(progress))
        triggerNotificationUpdate()
    }

    /** Task 19 seam: real fade-out stop lands there; inert (logged) today. */
    private fun onSleepTimerExpired() {
        Log.i(TAG, "sleep timer expired — Task 19 will fade out and stop playback")
        updateButtonLabels()
    }

    /**
     * Six-state progress toggle for the CURRENT work (from the current
     * media item's trackKey, via [KeySpec]). Preserves rating/review text;
     * writes through [ReviewDao] so the detail page sees it live.
     */
    private fun toggleProgress() {
        val session = mediaSession ?: return
        val trackKey = session.player.currentMediaItem?.mediaId
        val parts = trackKey?.let { KeySpec.parseTrackKey(it) }
        if (parts == null) {
            Log.w(TAG, "progress toggle: no current trackKey (mediaId=$trackKey)")
            return
        }
        val workId = KeySpec.workId(parts.sourceScope, parts.rjCode)
        serviceScope.launch {
            val existing = reviewDao.getByWorkId(workId)
            val next = ProgressStateCycle.next(existing?.progress ?: ProgressState.none)
            reviewDao.upsert(
                Review(
                    workId = workId,
                    rating = existing?.rating,
                    reviewText = existing?.reviewText,
                    progress = next,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            Log.i(TAG, "progress toggled: $workId -> $next")
            updateButtonLabels()
        }
    }

    // ---------------------------------------------------------------------
    // Queue snapshot (playback resumption source)
    // ---------------------------------------------------------------------

    private val queueSnapshotListener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = snapshotQueue()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            snapshotQueue()
            // The progress button label shows the CURRENT work's six-state
            // progress — refresh it whenever the item changes (a fresh
            // service start has no item yet, so labels start idle).
            updateButtonLabels()
        }
    }

    private fun snapshotQueue() {
        val session = mediaSession ?: return
        // Player.getMediaItems() was removed in media3 1.10 — read the timeline.
        val timeline = session.player.currentTimeline
        val items = mutableListOf<MediaItem>()
        for (i in 0 until timeline.windowCount) {
            timeline.getWindow(i, Timeline.Window()).mediaItem?.let(items::add)
        }
        if (items.isNotEmpty()) {
            lastQueue = items
            lastStartIndex = session.player.currentMediaItemIndex
            Log.i(TAG, "queue snapshot: ${items.size} items, start index $lastStartIndex")
        }
    }

    // ---------------------------------------------------------------------
    // Session callback
    // ---------------------------------------------------------------------

    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ConnectionResult {
            val sessionCommands = SessionCommands.Builder()
                .add(SessionCommand(ACTION_SLEEP_TIMER, Bundle.EMPTY))
                .add(SessionCommand(ACTION_TOGGLE_PROGRESS, Bundle.EMPTY))
                .build()
            return ConnectionResult.accept(
                sessionCommands,
                Player.Commands.Builder().addAllCommands().build(),
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_SLEEP_TIMER -> {
                    val state = sleepTimer.toggle()
                    Log.i(TAG, "sleep timer toggled: active=${state.active}")
                    updateButtonLabels()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_TOGGLE_PROGRESS -> {
                    toggleProgress()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        /**
         * Android 13+ playback resumption: a headset/media-button play request
         * with an empty playlist calls here — return the last queue so
         * playback restarts from where the session left off. Position memory
         * (Task 19) will refine [MediaItemsWithStartPosition.startPositionMs].
         */
        override fun onPlaybackResumption(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            return if (lastQueue.isEmpty()) {
                Futures.immediateFailedFuture(IllegalStateException("no queue stored for resumption"))
            } else {
                Log.i(TAG, "playback resumption: ${lastQueue.size} items from index $lastStartIndex")
                Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(lastQueue, lastStartIndex, 0L),
                )
            }
        }
    }

    companion object {
        private const val TAG = "PlaybackService"

        const val NOTIFICATION_CHANNEL_ID = "playback"

        /** Custom command actions (custom layout buttons in the notification). */
        const val ACTION_SLEEP_TIMER = "oneasmr.command.sleep_timer"
        const val ACTION_TOGGLE_PROGRESS = "oneasmr.command.toggle_progress"
    }
}

/** Human label of one progress state for the notification button. */
private fun progressLabel(state: ProgressState): String = when (state) {
    ProgressState.none -> "无"
    ProgressState.marked -> "已标记"
    ProgressState.listening -> "收听中"
    ProgressState.listened -> "已听完"
    ProgressState.replay -> "回听中"
    ProgressState.postponed -> "搁置"
}
