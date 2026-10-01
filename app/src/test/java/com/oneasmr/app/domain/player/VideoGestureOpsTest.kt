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

    // ------------------------------------------------------------------
    // volumeIndexFor / volumePercentFor (plan Todo 6)
    // ------------------------------------------------------------------

    @Test
    fun `volume index maps the percent bounds to the stream bounds`() {
        assertEquals(0, VideoGestureOps.volumeIndexFor(0f, 15))
        assertEquals(15, VideoGestureOps.volumeIndexFor(1f, 15))
        assertEquals(8, VideoGestureOps.volumeIndexFor(0.5f, 15))
    }

    @Test
    fun `volume index clamps out-of-range percents`() {
        assertEquals(0, VideoGestureOps.volumeIndexFor(-0.4f, 15))
        assertEquals(15, VideoGestureOps.volumeIndexFor(1.6f, 15))
    }

    @Test
    fun `volume index guards a zero or negative max`() {
        assertEquals(0, VideoGestureOps.volumeIndexFor(0.5f, 0))
        assertEquals(0, VideoGestureOps.volumeIndexFor(1f, -3))
    }

    @Test
    fun `volume percent maps the stream bounds to 0 and 1`() {
        assertEquals(0f, VideoGestureOps.volumePercentFor(0, 15), delta)
        assertEquals(1f, VideoGestureOps.volumePercentFor(15, 15), delta)
        assertEquals(0.6f, VideoGestureOps.volumePercentFor(9, 15), delta)
    }

    @Test
    fun `volume percent clamps out-of-range indexes and guards a zero max`() {
        assertEquals(0f, VideoGestureOps.volumePercentFor(-2, 15), delta)
        assertEquals(1f, VideoGestureOps.volumePercentFor(20, 15), delta)
        assertEquals(0f, VideoGestureOps.volumePercentFor(5, 0), delta)
    }

    @Test
    fun `volume index round-trips every stream step`() {
        for (max in listOf(1, 7, 15, 25)) {
            for (i in 0..max) {
                assertEquals(
                    i,
                    VideoGestureOps.volumeIndexFor(
                        VideoGestureOps.volumePercentFor(i, max),
                        max,
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // shouldAutoHide (plan Todo 5)
    // ------------------------------------------------------------------

    /** All-clear call with per-test overrides; mirrors the 7-arg signature. */
    private fun shouldAutoHide(
        isPlaying: Boolean = true,
        dragging: Boolean = false,
        gestureActive: Boolean = false,
        sheetOpen: Boolean = false,
        panelOpen: Boolean = false,
        locked: Boolean = false,
        hintVisible: Boolean = false,
    ): Boolean = VideoGestureOps.shouldAutoHide(
        isPlaying = isPlaying,
        dragging = dragging,
        gestureActive = gestureActive,
        sheetOpen = sheetOpen,
        panelOpen = panelOpen,
        locked = locked,
        hintVisible = hintVisible,
    )

    @Test
    fun `all clear while playing hides the controls`() {
        assertTrue(shouldAutoHide())
    }

    @Test
    fun `all clear while paused never hides the controls`() {
        assertFalse(shouldAutoHide(isPlaying = false))
    }

    @Test
    fun `seek drag in flight blocks the auto-hide`() {
        assertFalse(shouldAutoHide(dragging = true))
    }

    @Test
    fun `active brightness or volume gesture blocks the auto-hide`() {
        assertFalse(shouldAutoHide(gestureActive = true))
    }

    @Test
    fun `open speed sheet blocks the auto-hide`() {
        assertFalse(shouldAutoHide(sheetOpen = true))
    }

    @Test
    fun `open playlist panel blocks the auto-hide`() {
        assertFalse(shouldAutoHide(panelOpen = true))
    }

    @Test
    fun `locked page blocks the auto-hide`() {
        assertFalse(shouldAutoHide(locked = true))
    }

    @Test
    fun `visible lock hint blocks the auto-hide`() {
        assertFalse(shouldAutoHide(hintVisible = true))
    }

    @Test
    fun `every suppression flag blocks even with the others clear`() {
        val suppressors = listOf(
            shouldAutoHide(dragging = true),
            shouldAutoHide(gestureActive = true),
            shouldAutoHide(sheetOpen = true),
            shouldAutoHide(panelOpen = true),
            shouldAutoHide(locked = true),
            shouldAutoHide(hintVisible = true),
        )
        assertTrue(suppressors.none { it })
    }
}
