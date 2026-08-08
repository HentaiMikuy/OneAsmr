package com.oneasmr.app.domain.player

/**
 * Playback repeat mode (plan Task 18) — pure domain enum, no media3 imports.
 *
 * The media3 int mapping lives in the player layer ([RepeatModeMapper] in
 * `com.oneasmr.app.player`) so this package stays a plain JVM module.
 * Cycle order for the UI toggle: OFF -> ALL -> ONE -> OFF.
 */
enum class RepeatMode {
    /** No repeat: playback stops at the end of the queue. */
    OFF,

    /** List repeat: the whole queue loops. */
    ALL,

    /** Single repeat: the current item loops. */
    ONE,
    ;

    /** Next mode in the UI toggle cycle (OFF -> ALL -> ONE -> OFF). */
    fun next(): RepeatMode = when (this) {
        OFF -> ALL
        ALL -> ONE
        ONE -> OFF
    }

    companion object {
        /** Read the persisted value; unknown/missing values fall back to [OFF]. */
        fun fromStored(value: Int?): RepeatMode = when (value) {
            1 -> ALL
            2 -> ONE
            else -> OFF
        }

        /** Stable storage ordinal — do NOT reuse media3's ints (different layout). */
        fun toStored(mode: RepeatMode): Int = when (mode) {
            OFF -> 0
            ALL -> 1
            ONE -> 2
        }
    }
}
