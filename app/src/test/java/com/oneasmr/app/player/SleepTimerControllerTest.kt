package com.oneasmr.app.player

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sleep-timer countdown state machine (plan Task 17 plumbing; Task 19 fills
 * the real expiry behavior through the onExpired seam).
 *
 * Fully virtual: the controller's clock reads the TestCoroutineScheduler's
 * virtual time, so every tick is scheduler-driven — no real-time waits
 * (repo flake convention).
 */
class SleepTimerControllerTest {

    private fun TestScope.controller(
        durationMs: Long = 60_000L,
        onExpired: () -> Unit = {},
    ): SleepTimerController {
        val controller = SleepTimerController(
            scope = this,
            clock = { testScheduler.currentTime },
            onExpired = onExpired,
        )
        controller.start(durationMs)
        return controller
    }

    @Test
    fun `toggle starts a countdown`() = runTest {
        val controller = SleepTimerController(scope = this, clock = { testScheduler.currentTime })
        val state = controller.toggle()
        assertTrue(state.active)
        assertEquals(15 * 60_000L, state.totalMs)
    }

    @Test
    fun `countdown ticks down one second per scheduler second`() = runTest {
        val controller = controller()
        advanceTimeBy(1_000)
        runCurrent() // tasks scheduled exactly at the target time need runCurrent
        assertEquals(59_000L, controller.state.value.remainingMs)
        assertEquals("0:59", controller.state.value.display)
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(55_000L, controller.state.value.remainingMs)
        assertEquals("0:55", controller.state.value.display)
    }

    @Test
    fun `expiry resets the state and fires the Task 19 seam`() = runTest {
        var expired = false
        val controller = controller(durationMs = 3_000, onExpired = { expired = true })
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(controller.state.value.active)
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(controller.state.value.active)
        assertEquals(0L, controller.state.value.remainingMs)
        assertTrue(expired)
        // No further ticks after expiry
        advanceTimeBy(5_000)
        assertEquals(0L, controller.state.value.remainingMs)
    }

    @Test
    fun `second toggle cancels the countdown`() = runTest {
        val controller = controller()
        assertTrue(controller.state.value.active)
        val after = controller.toggle()
        assertFalse(after.active)
        advanceTimeBy(10_000)
        assertFalse(controller.state.value.active)
    }

    @Test
    fun `start replaces a running countdown`() = runTest {
        val controller = controller(durationMs = 60_000)
        controller.start(30_000)
        assertEquals(30_000L, controller.state.value.totalMs)
        advanceTimeBy(30_000)
        runCurrent()
        assertFalse(controller.state.value.active)
    }

    @Test
    fun `formatCountdown renders mm-ss and h-mm-ss`() {
        assertEquals("0:00", SleepTimerController.formatCountdown(0L))
        assertEquals("0:01", SleepTimerController.formatCountdown(999L))
        assertEquals("14:59", SleepTimerController.formatCountdown(14 * 60_000L + 59_000L))
        assertEquals("1:00:00", SleepTimerController.formatCountdown(3_600_000L))
        // negative clamps to zero
        assertEquals("0:00", SleepTimerController.formatCountdown(-5L))
    }

    // ------------------------------------------------------------------
    // Task 19: END_OF_TRACK mode ("播完当前曲")
    // ------------------------------------------------------------------

    @Test
    fun `startAtTrackEnd activates end-of-track mode with a static label`() = runTest {
        val controller = SleepTimerController(scope = this, clock = { testScheduler.currentTime })
        controller.startAtTrackEnd()
        assertTrue(controller.state.value.active)
        assertEquals(SleepTimerMode.END_OF_TRACK, controller.state.value.mode)
        assertEquals("播完当前曲", controller.state.value.display)
        // No countdown ticks in this mode.
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(SleepTimerMode.END_OF_TRACK, controller.state.value.mode)
        assertTrue(controller.state.value.active)
    }

    @Test
    fun `startAtTrackEnd replaces a running countdown`() = runTest {
        val controller = SleepTimerController(scope = this, clock = { testScheduler.currentTime })
        controller.start(60_000L)
        controller.startAtTrackEnd()
        assertEquals(SleepTimerMode.END_OF_TRACK, controller.state.value.mode)
        advanceTimeBy(60_000)
        runCurrent()
        // The old countdown job is gone: no expiry after the switch.
        assertTrue(controller.state.value.active)
    }

    @Test
    fun `expireNow fires the seam and resets state - the player-driven end-of-track stop`() = runTest {
        var expired = false
        val controller = SleepTimerController(scope = this, clock = { testScheduler.currentTime }, onExpired = { expired = true })
        controller.startAtTrackEnd()
        controller.expireNow()
        assertFalse(controller.state.value.active)
        assertTrue(expired)
    }

    @Test
    fun `expireNow on an inactive timer is a no-op`() = runTest {
        var expired = false
        val controller = SleepTimerController(scope = this, clock = { testScheduler.currentTime }, onExpired = { expired = true })
        controller.expireNow()
        assertFalse(expired)
    }

    @Test
    fun `countdown expiry still fires the seam exactly once`() = runTest {
        var expired = 0
        val controller = SleepTimerController(scope = this, clock = { testScheduler.currentTime }, onExpired = { expired += 1 })
        controller.start(3_000L)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(1, expired)
        // No late double-expiry.
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, expired)
    }
}
