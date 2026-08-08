package com.oneasmr.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the resume-policy boundaries (plan Task 19): <3% starts over,
 * >95% starts over, everything in between resumes. QA scenarios 2% / 50% /
 * 96% plus the exact edge values (strict comparisons).
 */
class ResumePolicyTest {

    @Test
    fun `below 3 percent starts over`() {
        assertEquals(ResumePolicy.Decision.START_OVER, ResumePolicy.decideFromFraction(0.02))
        assertEquals(ResumePolicy.Decision.START_OVER, ResumePolicy.decideFromFraction(0.0))
        assertEquals(ResumePolicy.Decision.START_OVER, ResumePolicy.decideFromFraction(0.029999))
    }

    @Test
    fun `exactly 3 percent resumes`() {
        assertEquals(
            ResumePolicy.Decision.RESUME,
            ResumePolicy.decideFromFraction(ResumePolicy.NOT_STARTED_FRACTION),
        )
    }

    @Test
    fun `mid range resumes`() {
        assertEquals(ResumePolicy.Decision.RESUME, ResumePolicy.decideFromFraction(0.5))
        assertEquals(ResumePolicy.Decision.RESUME, ResumePolicy.decideFromFraction(0.10))
        assertEquals(ResumePolicy.Decision.RESUME, ResumePolicy.decideFromFraction(0.90))
    }

    @Test
    fun `exactly 95 percent resumes`() {
        assertEquals(
            ResumePolicy.Decision.RESUME,
            ResumePolicy.decideFromFraction(ResumePolicy.ALREADY_LISTENED_FRACTION),
        )
    }

    @Test
    fun `above 95 percent starts over`() {
        assertEquals(ResumePolicy.Decision.START_OVER, ResumePolicy.decideFromFraction(0.96))
        assertEquals(ResumePolicy.Decision.START_OVER, ResumePolicy.decideFromFraction(1.0))
        assertEquals(ResumePolicy.Decision.START_OVER, ResumePolicy.decideFromFraction(1.5))
    }

    @Test
    fun `negative fraction clamps to zero and starts over`() {
        assertEquals(ResumePolicy.Decision.START_OVER, ResumePolicy.decideFromFraction(-0.5))
    }

    @Test
    fun `unknown duration starts over`() {
        assertEquals(ResumePolicy.Decision.START_OVER, ResumePolicy.decide(positionMs = 5_000, durationMs = 0L))
        assertEquals(ResumePolicy.Decision.START_OVER, ResumePolicy.decide(positionMs = 5_000, durationMs = -1L))
    }

    @Test
    fun `time-based decision at the QA fractions`() {
        // 2% of a 600s track
        assertEquals(ResumePolicy.Decision.START_OVER, ResumePolicy.decide(positionMs = 12_000, durationMs = 600_000))
        // 50%
        assertEquals(ResumePolicy.Decision.RESUME, ResumePolicy.decide(positionMs = 300_000, durationMs = 600_000))
        // 96%
        assertEquals(ResumePolicy.Decision.START_OVER, ResumePolicy.decide(positionMs = 576_000, durationMs = 600_000))
    }
}
