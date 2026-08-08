package com.oneasmr.app.ui.player

import com.oneasmr.app.data.local.KeySpec
import com.oneasmr.app.domain.player.RepeatMode

/**
 * Pure view-state of the playback session (plan Task 21 mini player bar and
 * shared player UI).
 *
 * EVERY field is derived from the MediaSession connection — there is no
 * app-local duplication of the queue or the playback state (plan must-not:
 * single source of truth = the session, Task 18). The mapper ([PlayerSnapshotMapper])
 * is a pure function so the derivation rules are JVM unit-tested without any
 * Android runtime (repo flake convention: no real-time waits).
 */
data class PlayerSnapshot(
    /** True when a MediaController is connected to the [PlaybackService] session. */
    val connected: Boolean = false,
    /** Media items on the session (the queue — session-owned, never rebuilt here). */
    val mediaItemCount: Int = 0,
    /** Index of the current item within the session timeline. */
    val currentIndex: Int = 0,
    /** "{sourceScope}:{rjCode}" key of the CURRENT item (null when idle). */
    val workId: String? = null,
    /** Bare rjCode of the current item — cover lookup key (Task 10). */
    val rjCode: String? = null,
    /** 1-based trackIndex of the current item (routes into the player page). */
    val trackIndex: Int? = null,
    /** Track display name (metadata title of the current item). */
    val trackTitle: String = "",
    /** Work display name (metadata artist line of the current item). */
    val workTitle: String = "",
    val isPlaying: Boolean = false,
    /** media3 Player.STATE_* int of the current item (mirror constants below). */
    val playbackState: Int = STATE_IDLE,
    val positionMs: Long = 0L,
    /** Media3 buffered position of the current item (progress-bar buffer fill). */
    val bufferedPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
    val speed: Float = 1f,
) {
    /**
     * Mini bar visibility rule: a session with content is active. The queue
     * lives on the session (Task 18) — a restored/cold-start session counts
     * exactly like a freshly started one, so force-stop + relaunch keeps the
     * mini bar honest (repeated_interruptions probe).
     */
    val hasSession: Boolean get() = connected && mediaItemCount > 0 && workId != null

    /** True while the player is buffering/loading the current item. */
    val isBuffering: Boolean get() = playbackState == STATE_BUFFERING

    /** Played fraction of the current item, 0..1 (0 when duration unknown). */
    val progressFraction: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** Buffered fraction of the current item, 0..1 (0 when duration unknown). */
    val bufferedFraction: Float
        get() = if (durationMs > 0L) (bufferedPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    companion object {
        /** media3 Player.STATE_* mirrors (kept inlined so the model stays pure JVM). */
        const val STATE_IDLE = 1
        const val STATE_BUFFERING = 2
        const val STATE_READY = 3
        const val STATE_ENDED = 4
    }
}

/**
 * One-shot playback events surfaced to UI (toast etc.), NOT part of the
 * snapshot — an error is a moment, not a state.
 */
sealed interface PlayerEvent {
    /** The session hit a fatal playback error (e.g. the work folder vanished). */
    data class PlaybackFailed(val message: String) : PlayerEvent
}

/**
 * Pure derivation of [PlayerSnapshot] from raw session fields — the ONLY
 * place mini-bar/player state is computed, so the rules are unit-testable:
 * - current item identity comes from the mediaId (= the normative trackKey,
 *   Task 4 key spec) via [KeySpec] — never from a UI-side queue copy.
 * - buffered position is clamped to [0, duration] (media3 can report values
 *   slightly past the duration on some sources).
 * - repeat mode is the domain [RepeatMode], mapped by the caller via
 *   [com.oneasmr.app.player.toRepeatMode] (already unit-tested).
 */
object PlayerSnapshotMapper {

    fun map(
        connected: Boolean,
        mediaItemCount: Int,
        currentIndex: Int,
        currentMediaId: String?,
        trackTitle: String,
        workTitle: String,
        isPlaying: Boolean,
        playbackState: Int,
        positionMs: Long,
        bufferedPositionMs: Long,
        durationMs: Long,
        repeatMode: RepeatMode,
        shuffleEnabled: Boolean,
        speed: Float,
    ): PlayerSnapshot {
        val parts = currentMediaId?.let(KeySpec::parseTrackKey)
        val duration = durationMs.coerceAtLeast(0L)
        return PlayerSnapshot(
            connected = connected,
            mediaItemCount = mediaItemCount.coerceAtLeast(0),
            currentIndex = currentIndex.coerceAtLeast(0),
            workId = parts?.let { KeySpec.workId(it.sourceScope, it.rjCode) },
            rjCode = parts?.rjCode,
            trackIndex = parts?.trackIndex,
            trackTitle = trackTitle,
            workTitle = workTitle,
            isPlaying = isPlaying,
            playbackState = playbackState,
            positionMs = positionMs.coerceAtLeast(0L),
            bufferedPositionMs = if (duration > 0L) bufferedPositionMs.coerceIn(0L, duration) else bufferedPositionMs,
            durationMs = duration,
            repeatMode = repeatMode,
            shuffleEnabled = shuffleEnabled,
            speed = speed,
        )
    }
}
