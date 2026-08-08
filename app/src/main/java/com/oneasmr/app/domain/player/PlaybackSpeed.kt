package com.oneasmr.app.domain.player

import kotlin.math.roundToInt

/**
 * Playback speed stepping (plan Task 18): 0.5x-2.0x in 0.1 steps.
 *
 * Pure float math with explicit rounding at every step so repeated
 * increments never accumulate binary drift (0.7f + 0.1f != 0.8f exactly).
 * Applied via `ExoPlayer.setPlaybackSpeed(speed)` — the player instance is
 * NEVER rebuilt on a speed change (plan must-not); pitch stays at 1.0 via
 * ExoPlayer's default Sonic algorithm.
 */
object PlaybackSpeed {
    const val MIN = 0.5f
    const val MAX = 2.0f
    const val STEP = 0.1f

    /** All supported speeds: 0.5, 0.6, ..., 2.0 (16 values). */
    val SUPPORTED: List<Float> = (5..20).map { it / 10f }

    /** Normalizes any speed to the nearest supported 0.1 step (clamped). */
    fun normalize(speed: Float): Float =
        (speed * 10).roundToInt().coerceIn((MIN * 10).toInt(), (MAX * 10).toInt()) / 10f

    /** Next higher supported speed; stays at [MAX] when already there. */
    fun stepUp(speed: Float): Float {
        val next = (speed * 10).roundToInt() + 1
        return next.coerceAtMost((MAX * 10).toInt()) / 10f
    }

    /** Next lower supported speed; stays at [MIN] when already there. */
    fun stepDown(speed: Float): Float {
        val next = (speed * 10).roundToInt() - 1
        return next.coerceAtLeast((MIN * 10).toInt()) / 10f
    }
}
