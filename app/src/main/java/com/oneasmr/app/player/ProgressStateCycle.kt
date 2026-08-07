package com.oneasmr.app.player

import com.oneasmr.app.data.local.ProgressState

/**
 * Six-state listening progress cycle driven from the media notification
 * (plan Task 17: "标记进度（在六态间切换）"; kikoeru t_review progress semantics).
 *
 * The cycle order matches [ProgressState] declaration order:
 * none -> marked -> listening -> listened -> replay -> postponed -> none.
 *
 * Pure JVM — unit-tested.
 */
object ProgressStateCycle {

    /** All six states in cycle order (also the canonical display order). */
    val ORDER: List<ProgressState> = ProgressState.entries.toList()

    /**
     * The next state after [current]. Toggling from any state always moves to
     * a different one — the notification button is a true six-way toggle.
     */
    fun next(current: ProgressState): ProgressState {
        val index = ORDER.indexOf(current)
        return ORDER[(index + 1) % ORDER.size]
    }
}
