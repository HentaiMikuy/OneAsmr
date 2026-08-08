package com.oneasmr.app.player

import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Final-wave finding F3 regression tests for [PlaybackService.ResumptionSnapshot]:
 * captureSession must treat an EMPTY timeline as "queue cleared" (swipe-dismiss /
 * video-exit), so Android 13+ onPlaybackResumption never restarts the stale queue.
 */
class PlaybackQueueSnapshotTest {

    private val items = listOf(
        MediaItem.Builder().setMediaId("local:RJ123456:0").build(),
        MediaItem.Builder().setMediaId("local:RJ123456:1").build(),
    )

    @Test
    fun `empty timeline clears the resumption snapshot`() {
        val snapshot = PlaybackService.ResumptionSnapshot.fromTimeline(emptyList(), startIndex = 3)
        assertEquals(emptyList<MediaItem>(), snapshot.items)
        assertEquals(0, snapshot.startIndex)
    }

    @Test
    fun `non-empty timeline is captured verbatim with its start index`() {
        val snapshot = PlaybackService.ResumptionSnapshot.fromTimeline(items, startIndex = 1)
        assertEquals(items, snapshot.items)
        assertEquals(1, snapshot.startIndex)
    }
}
