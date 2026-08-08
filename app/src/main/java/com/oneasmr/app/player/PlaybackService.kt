package com.oneasmr.app.player

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
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
import com.google.common.util.concurrent.SettableFuture
import com.oneasmr.app.MainActivity
import com.oneasmr.app.R
import com.oneasmr.app.data.local.KeySpec
import com.oneasmr.app.data.local.PlaybackStateDao
import com.oneasmr.app.data.local.ProgressState
import com.oneasmr.app.data.local.Review
import com.oneasmr.app.data.local.ReviewDao
import com.oneasmr.app.data.local.settings.PersistedQueue
import com.oneasmr.app.data.local.settings.QueueStore
import com.oneasmr.app.domain.player.FairDeckShuffle
import com.oneasmr.app.domain.player.PlayQueueItem
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
 * - Task 18 (queue/repeat/shuffle/speed): the service is the SINGLE source
 *   of truth for the session state. Every queue/mode change is persisted to
 *   [QueueStore] (DataStore) via [Player.Listener] callbacks; on cold start
 *   [restoreSession] rebuilds the queue structure + repeat/shuffle/speed.
 *   POSITION restore is Task 19's playback_state table — this task restores
 *   with start position 0 (see [restoreSession] seam comment). Speed changes
 *   go through `player.setPlaybackSpeed` ONLY — the player/session are never
 *   rebuilt (plan must-not); identity logging under "PlaybackService" is the
 *   QA evidence channel (same player/session hash across speed steps).
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

    @Inject
    lateinit var queueStore: QueueStore

    @Inject
    lateinit var playbackStateDao: PlaybackStateDao

    @Inject
    lateinit var resumePositionResolver: ResumePositionResolver

    private lateinit var notificationProvider: DefaultMediaNotificationProvider
    private var mediaSession: MediaSession? = null
    private lateinit var serviceScope: CoroutineScope
    private lateinit var sleepTimer: SleepTimerController

    /** Task 19: debounced per-trackKey position writer (5s cadence + pause flush). */
    private lateinit var progressWriter: PlaybackProgressWriter

    /** Debug-only sleep-timer hook (see [registerDebugSleepTimerHook]); null on release. */
    private var sleepTimerDebugReceiver: BroadcastReceiver? = null

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
        progressWriter = PlaybackProgressWriter(
            dao = playbackStateDao,
            scope = serviceScope,
            positionProvider = ::positionSample,
        ).apply { start() }
        registerDebugSleepTimerHook()
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

        Log.i(TAG, "onCreate: player@${System.identityHashCode(player)} session@${System.identityHashCode(mediaSession)}")
        player.addListener(queueSnapshotListener)
        // Re-render the notification on every sleep-timer tick (countdown label)
        // AND broadcast the state so the player screen's picker stays live.
        serviceScope.launch {
            sleepTimer.state.collect {
                updateButtonLabels()
                broadcastSleepTimerState()
            }
        }
        restoreSession()
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
        progressWriter.stop()
        unregisterDebugSleepTimerHook()
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

    /**
     * Task 19 seam: real fade-out stop lands there; inert (logged) today.
     */
    private fun onSleepTimerExpired() {
        Log.i(TAG, "sleep timer expired — fading out ~1s then pausing and releasing")
        serviceScope.launch {
            val initial = player.volume
            for (step in FADE_STEPS downTo 1) {
                player.volume = initial * step / FADE_STEPS
                Log.i(TAG, "sleep fade: volume=${player.volume} (step $step/$FADE_STEPS)")
                delay(FADE_STEP_MS)
            }
            player.volume = 0f
            // Final position write BEFORE stopping (last-write-wins must hold).
            progressWriter.flush()
            player.pause()
            Log.i(TAG, "sleep timer: paused after fade, stopping foreground service (released)")
            stopSelf()
        }
    }

    /**
     * "播完当前曲" trigger: the player side detects the current track end
     * (media-item transition with a real advance, or STATE_ENDED at queue
     * end) and expires the timer through the same seam as a countdown.
     */
    private fun checkEndOfTrackTimer() {
        if (!sleepTimer.state.value.active) return
        if (sleepTimer.state.value.mode != SleepTimerMode.END_OF_TRACK) return
        Log.i(TAG, "sleep timer END_OF_TRACK: current track finished — expiring")
        sleepTimer.expireNow()
    }

    /** Snapshot for the position writer: mediaId IS the normative trackKey. */
    private fun positionSample(): PlaybackProgressWriter.Sample {
        val key = player.currentMediaItem?.mediaId
        val duration = player.duration
        return if (duration > 0L) {
            PlaybackProgressWriter.Sample(key, player.currentPosition.coerceIn(0L, duration), duration)
        } else {
            PlaybackProgressWriter.Sample(key, 0L, 0L)
        }
    }

    /**
     * Serializes the sleep-timer state (same extras for the ACTION_SLEEP_TIMER_STATE
     * query result AND the app-local broadcast), so the player screen's picker
     * can render "睡眠 14:59" live without polling.
     */
    private fun sleepTimerStateBundle(): Bundle {
        val s = sleepTimer.state.value
        return Bundle().apply {
            putBoolean(EXTRA_SLEEP_TIMER_ACTIVE, s.active)
            putString(EXTRA_SLEEP_TIMER_MODE, s.mode.name)
            putLong(EXTRA_SLEEP_TIMER_REMAINING_MS, s.remainingMs)
            putString(EXTRA_SLEEP_TIMER_DISPLAY, if (s.active) s.display else "")
        }
    }

    /** App-local state push (explicit package: same app only). */
    private fun broadcastSleepTimerState() {
        sendBroadcast(
            Intent(ACTION_SLEEP_TIMER_STATE)
                .setPackage(packageName)
                .putExtras(sleepTimerStateBundle()),
        )
    }

    /**
     * Debug/test hook (plan Task 19 mandates: "可通过测试参数设置任意秒数"):
     * a dynamic broadcast receiver `oneasmr.debug.sleep_timer` with an extra
     * `sleep_timer_seconds` starts the countdown for ANY duration (e.g. 30s)
     * so QA never waits 15 minutes. Registered ONLY on debuggable builds;
     * the receiver's only power is starting/cancelling a sleep timer, so the
     * exported registration is acceptable (documented).
     */
    private fun registerDebugSleepTimerHook() {
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val seconds = intent.getLongExtra(EXTRA_SLEEP_TIMER_SECONDS, -1L)
                if (seconds > 0L) {
                    sleepTimer.start(seconds * 1000L)
                    Log.i(TAG, "debug hook: sleep timer started for ${seconds}s")
                } else if (seconds == 0L) {
                    sleepTimer.cancel()
                    Log.i(TAG, "debug hook: sleep timer cancelled")
                }
                updateButtonLabels()
            }
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(ACTION_DEBUG_SLEEP_TIMER),
            ContextCompat.RECEIVER_EXPORTED,
        )
        sleepTimerDebugReceiver = receiver
    }

    private fun unregisterDebugSleepTimerHook() {
        sleepTimerDebugReceiver?.let { runCatching { unregisterReceiver(it) } }
        sleepTimerDebugReceiver = null
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
    // Queue snapshot + session persistence (Task 18)
    // ---------------------------------------------------------------------

    private val queueSnapshotListener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            captureSession()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            captureSession()
            // The progress button label shows the CURRENT work's six-state
            // progress — refresh it whenever the item changes (a fresh
            // service start has no item yet, so labels start idle).
            updateButtonLabels()
            // "播完当前曲": a real advance (not a repeat-one replay) means the
            // previous track finished. Repeat-one replays are excluded — the
            // track "never ends" while looping.
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                checkEndOfTrackTimer()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                // Queue end (repeat off): flush the final position AND expire
                // an END_OF_TRACK timer — the current track just finished.
                serviceScope.launch { progressWriter.flush() }
                checkEndOfTrackTimer()
            }
        }

        /**
         * Failure path (plan QA: work folder deleted mid-play -> toast + stop,
         * no ANR): a fatal source error stops playback. We clear the session
         * queue (persists the cleared queue via [captureSession], removes the
         * notification) and stop the service; the UI side (SessionConnection
         * controller listener) shows the toast from the same error event.
         * The clear is posted on the service scope — never blocking the main
         * thread inside a listener callback.
         */
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "player error: ${error.errorCodeName}: ${error.message}")
            lastQueue = emptyList()
            serviceScope.launch {
                if (player.mediaItemCount > 0) {
                    player.clearMediaItems()
                    progressWriter.flush()
                }
                sleepTimer.cancel()
                updateButtonLabels()
                Log.i(TAG, "playback error handled: queue cleared, service stopping")
                stopSelf()
            }
        }

        /**
         * Task 19 must-not: the sleep timer must never outlive manual
         * control. ANY pause/stop (notification, media button, audio-focus
         * loss, noisy) flushes the position and cancels the countdown — a
         * late auto-stop is impossible. The fade-out's own pause is exempt:
         * by then the timer already reset itself (see [SleepTimerController.expire]).
         */
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) {
                serviceScope.launch { progressWriter.flush() }
                sleepTimer.cancel()
                updateButtonLabels()
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            serviceScope.launch { queueStore.setRepeatMode(repeatMode.toRepeatMode()) }
            Log.i(TAG, "repeat mode -> ${repeatMode.toRepeatMode()}")
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            // Fair-deck first cycle: hand the player OUR seeded permutation
            // (no-repeat-until-exhausted; FairDeckShuffleTest locks the
            // property). When the order is exhausted mid-playback ExoPlayer
            // generates a fresh permutation from the seed — still fair.
            if (shuffleModeEnabled) {
                applyFairShuffleOrder()
                // Enabled at queue end: restart the walk from the deck's first
                // item so the next play runs the WHOLE order instead of the
                // remainder (repeat OFF stops at order end either way).
                if (player.playbackState == Player.STATE_ENDED) {
                    player.seekToDefaultPosition()
                    Log.i(TAG, "shuffle enabled at queue end: deck restarted")
                }
            }
            serviceScope.launch { queueStore.setShuffleEnabled(shuffleModeEnabled) }
            Log.i(TAG, "shuffle -> $shuffleModeEnabled")
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            val speed = playbackParameters.speed
            val pitch = playbackParameters.pitch
            serviceScope.launch { queueStore.setSpeed(speed) }
            // Identity evidence: same player/session hash on every step proves
            // the speed change never rebuilds the player (plan must-not);
            // pitch=1.0 proves the Sonic pitch-preserving path stayed on.
            Log.i(
                TAG,
                "speed -> ${speed}x pitch=$pitch player@${System.identityHashCode(player)} " +
                    "session@${System.identityHashCode(mediaSession)} (no rebuild)",
            )
        }
    }

    /** Snapshot of the CURRENT timeline: fills [lastQueue] (Android 13+ resumption) and persists the session. */
    private fun captureSession() {
        val session = mediaSession ?: return
        val timeline = session.player.currentTimeline
        val mediaItems = mutableListOf<MediaItem>()
        val playQueueItems = mutableListOf<PlayQueueItem>()
        for (i in 0 until timeline.windowCount) {
            val window = Timeline.Window()
            timeline.getWindow(i, window)
            val mediaItem = window.mediaItem ?: continue
            mediaItems += mediaItem
            // mediaId IS the normative trackKey "{scope}:{rjCode}:{index}".
            val parts = KeySpec.parseTrackKey(mediaItem.mediaId)
            if (parts != null) {
                playQueueItems += PlayQueueItem(
                    sourceScope = parts.sourceScope,
                    rjCode = parts.rjCode,
                    trackIndex = parts.trackIndex,
                    trackTitle = mediaItem.mediaMetadata.title?.toString() ?: "",
                    workTitle = mediaItem.mediaMetadata.artist?.toString() ?: "",
                    uri = mediaItem.localConfiguration?.uri?.toString() ?: "",
                )
            }
        }
        if (mediaItems.isNotEmpty()) {
            lastQueue = mediaItems
            lastStartIndex = session.player.currentMediaItemIndex
            Log.i(TAG, "queue snapshot: ${mediaItems.size} items, start index $lastStartIndex")
        }
        val currentIndex = session.player.currentMediaItemIndex
        serviceScope.launch {
            if (playQueueItems.isEmpty()) {
                queueStore.saveQueue(null)
            } else {
                queueStore.saveQueue(
                    PersistedQueue(
                        workId = KeySpec.workId(playQueueItems[0].sourceScope, playQueueItems[0].rjCode),
                        items = playQueueItems,
                        currentIndex = currentIndex.coerceAtLeast(0),
                    ),
                )
            }
        }
    }

    /**
     * Cold-start restore (plan Task 18): rebuild the queue STRUCTURE and the
     * repeat/shuffle/speed modes from [QueueStore]. Task 19 owns the playback
     * POSITION — its playback_state read plugs in here as the
     * `startPositionMs` argument of the [androidx.media3.session.MediaSession.MediaItemsWithStartPosition]
     * style set (today: position 0). Playback is NOT auto-started; the user
     * or a media-button play resumes (Android 13+ [onPlaybackResumption]).
     */
    private fun restoreSession() {
        serviceScope.launch {
            val persisted = queueStore.queue.first()
            // Modes are independent of the queue: restore them even when the
            // last queue was cleared (the player singleton starts fresh here).
            player.repeatMode = queueStore.repeatMode.first().toMedia3()
            // Enabling shuffle fires onShuffleModeEnabledChanged, whose
            // listener applies the fair-deck order automatically.
            if (queueStore.shuffleEnabled.first()) player.shuffleModeEnabled = true
            player.setPlaybackSpeed(queueStore.speed.first())
            if (persisted == null) {
                Log.i(TAG, "session restore: no persisted queue (modes only)")
                return@launch
            }
            if (player.mediaItemCount > 0) {
                Log.i(TAG, "session restore skipped (queue already on player)")
                return@launch
            }
            if (!persisted.isRestorable) {
                Log.w(
                    TAG,
                    "persisted session not restorable (${persisted.items.size} items, index ${persisted.currentIndex})",
                )
                return@launch
            }
            // Task 19: per-trackKey position memory — replace the Task 18
            // placeholder 0L with the playback_state read for the restored
            // item (resume policy applied: <3% / >95% start over).
            val startIndex = persisted.currentIndex
            val startKey = persisted.items.getOrNull(startIndex)
                ?.let { KeySpec.trackKey(it.sourceScope, it.rjCode, it.trackIndex) }
            val startPositionMs = resumePositionResolver.resolve(startKey)
            player.setMediaItems(persisted.items.map { it.toMediaItem() }, startIndex, startPositionMs)
            Log.i(
                TAG,
                "session restored: ${persisted.items.size} items at index $startIndex " +
                    "repeat=${player.repeatMode.toRepeatMode()} shuffle=${player.shuffleModeEnabled} " +
                    "speed=${player.playbackParameters.speed} " +
                    "position=$startPositionMs ms (key=$startKey, not started)",
            )
        }
    }

    /**
     * Hands the player a seeded fair-deck permutation as the shuffle order
     * (see [FairDeckShuffle]); skipped on an empty timeline.
     */
    private fun applyFairShuffleOrder() {
        val count = player.mediaItemCount
        if (count == 0) return
        val seed = FairDeckShuffle.newSeed()
        player.setShuffleOrder(
            ShuffleOrder.DefaultShuffleOrder(FairDeckShuffle.permutation(count, Random(seed)), seed),
        )
        Log.i(TAG, "fair shuffle order applied for $count items (seed=$seed)")
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
                .add(SessionCommand(ACTION_SLEEP_TIMER_SET, Bundle.EMPTY))
                .add(SessionCommand(ACTION_SLEEP_TIMER_STATE, Bundle.EMPTY))
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
                ACTION_SLEEP_TIMER_SET -> {
                    handleSleepTimerSet(args)
                    updateButtonLabels()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_SLEEP_TIMER_STATE -> {
                    return Futures.immediateFuture(
                        SessionResult(SessionResult.RESULT_SUCCESS, sleepTimerStateBundle()),
                    )
                }
                ACTION_TOGGLE_PROGRESS -> {
                    toggleProgress()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        /**
         * Task 19 presets from the Task 21 player screen: a countdown duration
         * (ms), the end-of-track mode, or a cancel — exactly the same
         * controller [SleepTimerController] the notification toggle drives.
         */
        private fun handleSleepTimerSet(args: Bundle) {
            when {
                args.getBoolean(EXTRA_SLEEP_TIMER_CANCEL, false) -> {
                    sleepTimer.cancel()
                    Log.i(TAG, "sleep timer set: cancelled")
                }
                args.getString(EXTRA_SLEEP_TIMER_MODE) == MODE_END_OF_TRACK -> {
                    sleepTimer.startAtTrackEnd()
                    Log.i(TAG, "sleep timer set: end-of-track")
                }
                else -> {
                    val ms = args.getLong(EXTRA_SLEEP_TIMER_MS, 0L)
                    if (ms > 0L) {
                        sleepTimer.start(ms)
                        Log.i(TAG, "sleep timer set: ${ms}ms")
                    } else {
                        Log.w(TAG, "sleep timer set: no duration/mode/cancel — ignored")
                    }
                }
            }
        }

        /**
         * Android 13+ playback resumption: a headset/media-button play request
         * with an empty playlist calls here — return the last queue so
         * playback restarts from where the session left off. Task 19: the
         * start position comes from playback_state via [ResumePositionResolver]
         * (resolved async on the service scope).
         */
        override fun onPlaybackResumption(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (lastQueue.isEmpty()) {
                return Futures.immediateFailedFuture(IllegalStateException("no queue stored for resumption"))
            }
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                val key = lastQueue[lastStartIndex].mediaId
                val startPositionMs = resumePositionResolver.resolve(key)
                Log.i(
                    TAG,
                    "playback resumption: ${lastQueue.size} items from index $lastStartIndex " +
                        "at ${startPositionMs}ms (key=$key)",
                )
                future.set(MediaSession.MediaItemsWithStartPosition(lastQueue, lastStartIndex, startPositionMs))
            }
            return future
        }
    }

    companion object {
        private const val TAG = "PlaybackService"

        const val NOTIFICATION_CHANNEL_ID = "playback"

        /** Custom command actions (custom layout buttons in the notification). */
        const val ACTION_SLEEP_TIMER = "oneasmr.command.sleep_timer"
        const val ACTION_TOGGLE_PROGRESS = "oneasmr.command.toggle_progress"

        /** Task 21: sleep-timer preset picker command (player screen -> service). */
        const val ACTION_SLEEP_TIMER_SET = "oneasmr.command.sleep_timer_set"
        const val EXTRA_SLEEP_TIMER_MS = "sleep_timer_ms"
        const val EXTRA_SLEEP_TIMER_MODE = "sleep_timer_mode"
        const val EXTRA_SLEEP_TIMER_CANCEL = "sleep_timer_cancel"
        const val MODE_END_OF_TRACK = "end_of_track"

        /** Task 21: sleep-timer state (query result + app-local broadcast). */
        const val ACTION_SLEEP_TIMER_STATE = "oneasmr.app.sleep_timer_state"
        const val EXTRA_SLEEP_TIMER_ACTIVE = "active"
        const val EXTRA_SLEEP_TIMER_REMAINING_MS = "remaining_ms"
        const val EXTRA_SLEEP_TIMER_DISPLAY = "display"

        /** Debug sleep-timer hook (plan-mandated; debuggable builds only). */
        const val ACTION_DEBUG_SLEEP_TIMER = "oneasmr.debug.sleep_timer"
        const val EXTRA_SLEEP_TIMER_SECONDS = "sleep_timer_seconds"

        /** ~1s fade-out: 10 steps x 100ms (plan Task 19 "1s 渐弱"). */
        private const val FADE_STEPS = 10
        private const val FADE_STEP_MS = 100L
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
