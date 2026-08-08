package com.oneasmr.app.domain.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** Playback speed stepping (plan Task 18): 0.5x-2.0x in 0.1 steps. */
class PlaybackSpeedTest {

    @Test
    fun `supported list spans 0_5 to 2_0 in 0_1 steps`() {
        assertEquals(16, PlaybackSpeed.SUPPORTED.size)
        assertEquals(0.5f, PlaybackSpeed.SUPPORTED.first())
        assertEquals(2.0f, PlaybackSpeed.SUPPORTED.last())
        assertEquals(listOf(0.5f, 0.6f, 0.7f), PlaybackSpeed.SUPPORTED.take(3))
    }

    @Test
    fun `step up walks 0_5 to 2_0 without float drift`() {
        var speed = PlaybackSpeed.MIN
        val walked = mutableListOf(speed)
        repeat(15) { speed = PlaybackSpeed.stepUp(speed); walked += speed }
        assertEquals(PlaybackSpeed.SUPPORTED, walked)
        assertEquals(PlaybackSpeed.MAX, speed)
    }

    @Test
    fun `step down walks 2_0 back to 0_5`() {
        var speed = PlaybackSpeed.MAX
        val walked = mutableListOf(speed)
        repeat(15) { speed = PlaybackSpeed.stepDown(speed); walked += speed }
        assertEquals(PlaybackSpeed.SUPPORTED.reversed(), walked)
        assertEquals(PlaybackSpeed.MIN, speed)
    }

    @Test
    fun `steps clamp at the bounds`() {
        assertEquals(PlaybackSpeed.MAX, PlaybackSpeed.stepUp(PlaybackSpeed.MAX))
        assertEquals(PlaybackSpeed.MIN, PlaybackSpeed.stepDown(PlaybackSpeed.MIN))
    }

    @Test
    fun `normalize snaps out-of-grid values to the nearest supported step`() {
        assertEquals(1.0f, PlaybackSpeed.normalize(1.04f))
        assertEquals(1.1f, PlaybackSpeed.normalize(1.06f))
        assertEquals(0.5f, PlaybackSpeed.normalize(0.1f))
        assertEquals(2.0f, PlaybackSpeed.normalize(9f))
    }
}
