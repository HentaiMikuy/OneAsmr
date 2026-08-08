package com.oneasmr.app.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Sleep-timer kind: a fixed countdown, or "stop when the current track ends". */
enum class SleepTimerMode { COUNTDOWN, END_OF_TRACK }

/**
 * Sleep-timer countdown STATE MACHINE (plan Task 17 plumbing + Task 19
 * behavior).
 *
 * Task 19 owns:
 * - The picker durations 15/30/45/60 min — [start] accepts ANY [durationMs]
 *   (the notification toggle uses the 15-min default; the picker UI ships
 *   with the Task 21 player screen). Debug hook: callers may pass seconds-
 *   scale durations (e.g. 30_000) so QA never waits 15 minutes.
 * - "播完当前曲" ([startAtTrackEnd]): [State.mode] == END_OF_TRACK; the
 *   PLAYER side (PlaybackService listener) detects the track end and calls
 *   [expireNow] — the controller cannot see the player, so it only models
 *   the mode and exposes the same expiry seam.
 * - [onExpired]: invoked on the caller's scope at expiry/end-of-track; the
 *   service fills it with the ~1s volume-fade stop.
 *
 * Determinism: [clock] (monotonic source) and [scope] are injected so unit
 * tests drive the countdown with a virtual scheduler — no real-time waits
 * (repo flake convention).
 */
class SleepTimerController(
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Task 19 seam — invoked on the controller scope when the timer expires. */
    private val onExpired: () -> Unit = {},
) {
    /** Default stub duration (15 min); the 15/30/45/60 picker is Task 21 UI. */
    private val defaultDurationMs: Long = 15 * 60 * 1000L

    /** Countdown view state; [remainingMs] ticks down while active. */
    data class State(
        val active: Boolean = false,
        val mode: SleepTimerMode = SleepTimerMode.COUNTDOWN,
        val remainingMs: Long = 0L,
        val totalMs: Long = 0L,
    ) {
        /** Notification label, e.g. "睡眠 14:59" / "睡眠 0:05" / "睡眠 播完当前曲". */
        val display: String get() = if (mode == SleepTimerMode.END_OF_TRACK) "播完当前曲" else formatCountdown(remainingMs)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var tickJob: Job? = null
    private var deadlineMs: Long = 0L

    /**
     * Starts the countdown (first press) or cancels it (second press).
     * @return the resulting state, so callers can re-render immediately.
     */
    fun toggle(): State {
        if (_state.value.active) {
            cancel()
        } else {
            start(defaultDurationMs)
        }
        return _state.value
    }

    /** Starts a countdown of [durationMs] (any value — debug hook included); replaces any running one. */
    fun start(durationMs: Long) {
        cancel()
        deadlineMs = clock() + durationMs
        _state.value = State(active = true, remainingMs = durationMs, totalMs = durationMs)
        tickJob = scope.launch {
            while (true) {
                delay(1000L)
                val remaining = deadlineMs - clock()
                if (remaining <= 0L) {
                    expire()
                    return@launch
                }
                _state.value = State(active = true, remainingMs = remaining, totalMs = durationMs)
            }
        }
    }

    /**
     * Starts "播完当前曲": no countdown ticks; the PLAYER side calls
     * [expireNow] when the current track actually ends (media-item
     * transition / STATE_ENDED — the controller is player-agnostic).
     */
    fun startAtTrackEnd() {
        cancel()
        _state.value = State(active = true, mode = SleepTimerMode.END_OF_TRACK)
    }

    /** Cancels the countdown; the notification button returns to idle label. */
    fun cancel() {
        tickJob?.cancel()
        tickJob = null
        _state.value = State()
    }

    /**
     * Fires the expiry seam NOW (end-of-track mode, or an external trigger):
     * resets the state and calls [onExpired] — the same path a countdown
     * expiry takes.
     */
    fun expireNow() {
        if (!_state.value.active) return
        expire()
    }

    private fun expire() {
        tickJob?.cancel()
        tickJob = null
        _state.value = State()
        onExpired()
    }

    companion object {
        /** "mm:ss" or "h:mm:ss" when >= 1h (notification space is tight). */
        fun formatCountdown(ms: Long): String {
            val totalSeconds = (ms.coerceAtLeast(0L) + 999L) / 1000L
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0L) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%d:%02d".format(minutes, seconds)
            }
        }
    }
}
