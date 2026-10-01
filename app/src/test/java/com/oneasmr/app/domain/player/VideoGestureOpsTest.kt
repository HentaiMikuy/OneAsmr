package com.oneasmr.app.domain.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Video player gesture/skip/sibling ops (plan Task 1 of video-player-controls).
 * Pure JVM — float deltas, millisecond clamps and path strings only.
 */
class VideoGestureOpsTest {

    private val delta = 0.0001f

    // ------------------------------------------------------------------
    // dragDeltaToFraction
    // ------------------------------------------------------------------

    @Test
    fun `upward drag increases the fraction`() {
        // Negative deltaY = finger moved up on screen.
        assertEquals(0.25f, VideoGestureOps.dragDeltaToFraction(-250f, 1000f), delta)
        assertEquals(1.0f, VideoGestureOps.dragDeltaToFraction(-1000f, 1000f), delta)
    }

    @Test
    fun `downward drag decreases the fraction`() {
        assertEquals(-0.25f, VideoGestureOps.dragDeltaToFraction(250f, 1000f), delta)
        assertEquals(-1.0f, VideoGestureOps.dragDeltaToFraction(1000f, 1000f), delta)
    }

    @Test
    fun `full screen height maps to fraction 1 and no drag maps to 0`() {
        assertEquals(1.0f, VideoGestureOps.dragDeltaToFraction(-1080f, 1080f), delta)
        assertEquals(0.0f, VideoGestureOps.dragDeltaToFraction(0f, 1080f), delta)
    }

    @Test
    fun `zero or negative height yields zero instead of dividing by zero`() {
        assertEquals(0f, VideoGestureOps.dragDeltaToFraction(-100f, 0f), delta)
        assertEquals(0f, VideoGestureOps.dragDeltaToFraction(-100f, -50f), delta)
    }

    // ------------------------------------------------------------------
    // applyFraction
    // ------------------------------------------------------------------

    @Test
    fun `applyFraction adds the delta within the range`() {
        assertEquals(0.7f, VideoGestureOps.applyFraction(0.5f, 0.2f), delta)
        assertEquals(0.3f, VideoGestureOps.applyFraction(0.5f, -0.2f), delta)
    }

    @Test
    fun `applyFraction clamps at 1 and at 0`() {
        assertEquals(1f, VideoGestureOps.applyFraction(0.9f, 0.5f), delta)
        assertEquals(0f, VideoGestureOps.applyFraction(0.1f, -0.5f), delta)
    }

    // ------------------------------------------------------------------
    // skipTarget
    // ------------------------------------------------------------------

    @Test
    fun `skip target is exactly plus or minus 10 seconds in range`() {
        assertEquals(
            70_000L,
            VideoGestureOps.skipTarget(60_000L, VideoGestureOps.SKIP_STEP_MS, 120_000L),
        )
        assertEquals(
            50_000L,
            VideoGestureOps.skipTarget(60_000L, -VideoGestureOps.SKIP_STEP_MS, 120_000L),
        )
        assertEquals(10_000L, VideoGestureOps.SKIP_STEP_MS)
    }

    @Test
    fun `skip backward clamps at 0`() {
        assertEquals(0L, VideoGestureOps.skipTarget(5_000L, -10_000L, 120_000L))
        assertEquals(0L, VideoGestureOps.skipTarget(0L, -10_000L, 120_000L))
    }

    @Test
    fun `skip forward clamps at duration`() {
        assertEquals(120_000L, VideoGestureOps.skipTarget(115_000L, 10_000L, 120_000L))
        assertEquals(120_000L, VideoGestureOps.skipTarget(120_000L, 10_000L, 120_000L))
    }

    @Test
    fun `unknown or negative duration clamps everything to 0`() {
        assertEquals(0L, VideoGestureOps.skipTarget(60_000L, 10_000L, -1L))
        assertEquals(0L, VideoGestureOps.skipTarget(60_000L, -10_000L, -1L))
    }

    // ------------------------------------------------------------------
    // siblingFolderOf / isSibling
    // ------------------------------------------------------------------

    @Test
    fun `siblingFolderOf returns the parent directory`() {
        assertEquals("a/b", VideoGestureOps.siblingFolderOf("a/b/c.mp4"))
        assertEquals("a", VideoGestureOps.siblingFolderOf("a/c.mp4"))
        assertEquals("", VideoGestureOps.siblingFolderOf("a.mp4"))
        assertEquals("", VideoGestureOps.siblingFolderOf(""))
    }

    @Test
    fun `files in the same folder are siblings`() {
        assertTrue(VideoGestureOps.isSibling("a/b/c.mp4", "a/b/d.mp4"))
        assertTrue(VideoGestureOps.isSibling("a/b/d.mkv", "a/b/c.mp4"))
    }

    @Test
    fun `nested subfolder entries are not siblings`() {
        assertFalse(VideoGestureOps.isSibling("a/b/c.mp4", "a/d.mp4"))
        assertFalse(VideoGestureOps.isSibling("a/b/c/c.mp4", "a/b/d.mp4"))
        assertFalse(VideoGestureOps.isSibling("a.mp4", "a/b.mp4"))
    }

    @Test
    fun `two root-level files are siblings`() {
        assertTrue(VideoGestureOps.isSibling("a.mp4", "b.mkv"))
    }

    @Test
    fun `a path is its own sibling`() {
        assertTrue(VideoGestureOps.isSibling("a/b/c.mp4", "a/b/c.mp4"))
        assertTrue(VideoGestureOps.isSibling("a.mp4", "a.mp4"))
    }
}
