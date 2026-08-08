package com.oneasmr.app.ui.player

import com.oneasmr.app.player.SleepTimerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the Task 19 sleep-timer presets surfaced by the Task 21 picker:
 * the 15/30/45/60 minute menu, its ms mapping and the end-of-track mode.
 * Pure JVM — no Android runtime.
 */
class SleepTimerPresetsTest {

    @Test
    fun `countdown presets are the plan's 15 30 45 60`() {
        assertEquals(listOf(15, 30, 45, 60), SleepTimerPresets.MINUTES)
    }

    @Test
    fun `minutes map to exact milliseconds`() {
        assertEquals(15L * 60_000L, SleepTimerPresets.minutesToMs(15))
        assertEquals(60L * 60_000L, SleepTimerPresets.minutesToMs(60))
    }

    @Test
    fun `labels render the plan wording`() {
        assertEquals("15 分钟", SleepTimerPresets.labelFor(15))
        assertEquals("播完当前曲", SleepTimerPresets.END_OF_TRACK_LABEL)
    }

    @Test
    fun `end-of-track mode is recognized`() {
        assertTrue(SleepTimerPresets.isEndOfTrack(SleepTimerMode.END_OF_TRACK))
        assertFalse(SleepTimerPresets.isEndOfTrack(SleepTimerMode.COUNTDOWN))
    }
}
