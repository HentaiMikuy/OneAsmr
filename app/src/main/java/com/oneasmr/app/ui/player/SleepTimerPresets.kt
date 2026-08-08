package com.oneasmr.app.ui.player

import com.oneasmr.app.player.SleepTimerMode

/**
 * Sleep-timer presets (plan Task 19 "15/30/45/60 分钟 + 播完当前曲"; the picker
 * UI ships with the Task 21 player screen).
 *
 * Pure JVM: the preset list, labels and the command-argument mapping are
 * unit-tested without any Android runtime. The picker sends durations through
 * the session custom command [com.oneasmr.app.player.PlaybackService.ACTION_SLEEP_TIMER_SET]
 * — the SERVICE's [com.oneasmr.app.player.SleepTimerController] (Task 19)
 * owns the countdown itself; this object is only the UI-side menu.
 */
object SleepTimerPresets {

    /** Countdown presets in minutes (plan Task 19). */
    val MINUTES: List<Int> = listOf(15, 30, 45, 60)

    /** Milliseconds of the preset at [index] (minutes * 60_000). */
    fun minutesToMs(minutes: Int): Long = minutes.toLong() * 60_000L

    /** Menu label for a countdown preset, e.g. "15 分钟". */
    fun labelFor(minutes: Int): String = "${minutes} 分钟"

    /** "播完当前曲" — the end-of-track mode (Task 19). */
    const val END_OF_TRACK_LABEL: String = "播完当前曲"

    /**
     * Whether [mode] is the end-of-track kind. Kept as a pure predicate so
     * the dialog can render the active mode without knowing the service.
     */
    fun isEndOfTrack(mode: SleepTimerMode): Boolean = mode == SleepTimerMode.END_OF_TRACK
}
