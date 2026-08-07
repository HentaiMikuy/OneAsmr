package com.oneasmr.app.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Sleep-timer countdown STATE MACHINE (plan Task 17 plumbing; the real timer
 * behavior lands in Task 19: 15/30/45/60 min + "播完当前曲", 1s fade-out stop,
 * cancel-on-manual-stop, debug hook).
 *
 * What Task 17 owns (and this class implements):
 * - The countdown model + ticking display: the media notification shows
 *   "睡眠定时 HH:MM:SS" while active (the service re-renders the button label
 *   on every state emission).
 * - A clean [onExpired] seam: Task 19 replaces the no-op with the fade-out
 *   stop. The handler runs on the caller's scope.
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
    /** Default stub duration (15 min). Task 19 adds the picker (15/30/45/60). */
    private val defaultDurationMs: Long = 15 * 60 * 1000L

    /** Countdown view state; [remainingMs] ticks down while [active]. */
    data class State(
        val active: Boolean = false,
        val remainingMs: Long = 0L,
        val totalMs: Long = 0L,
    ) {
        /** Notification label, e.g. "睡眠 14:59" / "睡眠 0:05". */
        val display: String get() = formatCountdown(remainingMs)
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

    /** Starts a countdown of [durationMs]; replaces any running one. */
    fun start(durationMs: Long) {
        cancel()
        deadlineMs = clock() + durationMs
        _state.value = State(active = true, remainingMs = durationMs, totalMs = durationMs)
        tickJob = scope.launch {
            while (true) {
                delay(1000L)
                val remaining = deadlineMs - clock()
                if (remaining <= 0L) {
                    _state.value = State()
                    onExpired()
                    return@launch
                }
                _state.value = State(active = true, remainingMs = remaining, totalMs = durationMs)
            }
        }
    }

    /** Cancels the countdown; the notification button returns to idle label. */
    fun cancel() {
        tickJob?.cancel()
        tickJob = null
        _state.value = State()
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
