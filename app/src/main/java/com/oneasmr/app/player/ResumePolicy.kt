package com.oneasmr.app.player

/**
 * Playback-resume policy (plan Task 19): decide, from a remembered position,
 * whether the next play of a track starts over or resumes.
 *
 * Boundaries (plan): a track that was played more than 95% counts as listened
 * (start over next time); less than 3% counts as not really started (start
 * over); anything in between resumes from the remembered position. The
 * boundary comparisons are STRICT: exactly 95% resumes, exactly 3% resumes —
 * only strictly-above 95% and strictly-below 3% start over.
 *
 * Pure JVM — no Android types, unit-tested at the exact boundaries
 * (2% / 50% / 96% + the edge values).
 */
object ResumePolicy {

    /** Played more than this fraction => treat as fully listened, start over. */
    const val ALREADY_LISTENED_FRACTION = 0.95

    /** Played less than this fraction => treat as not started, start over. */
    const val NOT_STARTED_FRACTION = 0.03

    enum class Decision { START_OVER, RESUME }

    /**
     * Decide from raw times. Unknown duration (<= 0) has no meaningful
     * fraction — start over. Negative/clamped positions are treated as 0.
     */
    fun decide(positionMs: Long, durationMs: Long): Decision {
        if (durationMs <= 0L) return Decision.START_OVER
        val fraction = positionMs.coerceAtLeast(0L).toDouble() / durationMs
        return decideFromFraction(fraction)
    }

    /** Decide from a 0..1+ played fraction (clamped at 0 from below). */
    fun decideFromFraction(fraction: Double): Decision {
        val f = fraction.coerceAtLeast(0.0)
        return if (f < NOT_STARTED_FRACTION || f > ALREADY_LISTENED_FRACTION) {
            Decision.START_OVER
        } else {
            Decision.RESUME
        }
    }
}
