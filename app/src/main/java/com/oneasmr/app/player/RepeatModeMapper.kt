package com.oneasmr.app.player

import androidx.media3.common.Player
import com.oneasmr.app.domain.player.RepeatMode

/**
 * Maps the domain [RepeatMode] to/from media3's repeat-mode ints
 * (plan Task 18). media3's layout (OFF=0, ONE=1, ALL=2) deliberately differs
 * from the persisted storage ordinal in [RepeatMode.toStored] — the store
 * ordinal is stable app-internal state, this mapping is media3's contract.
 */
fun RepeatMode.toMedia3(): Int = when (this) {
    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
    RepeatMode.ALL -> Player.REPEAT_MODE_ALL
    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
}

fun Int.toRepeatMode(): RepeatMode = when (this) {
    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
    else -> RepeatMode.OFF
}
