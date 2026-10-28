package com.oneasmr.app.domain.player

/**
 * Video-only end-of-item policy. Unlike the audio queue's three repeat modes,
 * video can also pause at the end of the current item without advancing.
 *
 * Stored values are stable and independent of media3's repeat-mode ints.
 */
enum class VideoRepeatMode(val baseMode: RepeatMode) {
    OFF(RepeatMode.OFF),
    ALL(RepeatMode.ALL),
    ONE(RepeatMode.ONE),
    PAUSE_AT_END(RepeatMode.OFF),
    ;

    val pauseAtEnd: Boolean
        get() = this == PAUSE_AT_END

    fun next(): VideoRepeatMode = when (this) {
        OFF -> ALL
        ALL -> ONE
        ONE -> PAUSE_AT_END
        PAUSE_AT_END -> OFF
    }

    companion object {
        fun fromStored(value: Int?): VideoRepeatMode = when (value) {
            1 -> ALL
            2 -> ONE
            3 -> PAUSE_AT_END
            else -> OFF
        }

        fun toStored(mode: VideoRepeatMode): Int = when (mode) {
            OFF -> 0
            ALL -> 1
            ONE -> 2
            PAUSE_AT_END -> 3
        }
    }
}
