package com.oneasmr.app.player

import com.oneasmr.app.data.scanner.TrackNode
import com.oneasmr.app.data.scanner.TrackNodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Attached-video helpers (plan Task 22): video-node lookup in the work track
 * tree and the enter-vs-attach entry decision. Pure JVM — synthetic trees
 * only; the tree building itself is covered by TrackTreeBuilderTest.
 */
class VideoTrackFinderTest {

    private fun file(type: TrackNodeType, name: String, index: Int) = TrackNode(
        type = type,
        name = name,
        relativePath = name,
        trackIndex = index,
        documentUri = "content://tree/primary%3AAsmrLib/$name",
        size = 100L,
        lastModified = 0L,
        children = emptyList(),
    )

    private fun folder(name: String, path: String, children: List<TrackNode>) = TrackNode(
        type = TrackNodeType.FOLDER,
        name = name,
        relativePath = path,
        trackIndex = null,
        documentUri = "content://tree/primary%3AAsmrLib",
        size = 0L,
        lastModified = 0L,
        children = children,
    )

    private val root = folder(
        "RJ180001",
        "",
        listOf(
            file(TrackNodeType.AUDIO, "track1.mp3", 1),
            folder(
                "movie",
                "movie",
                listOf(
                    file(TrackNodeType.VIDEO, "pv.mp4", 2),
                    file(TrackNodeType.VIDEO, "pv2.mkv", 3),
                ),
            ),
            file(TrackNodeType.AUDIO, "track2.mp3", 4),
            file(TrackNodeType.TEXT, "readme.txt", 5),
            file(TrackNodeType.IMAGE, "cover.jpg", 6),
        ),
    )

    @Test
    fun findsVideoNodeAtItsStableTrackIndex() {
        val found = VideoTrackFinder.find(root, 2)
        assertEquals("pv.mp4", found?.name)
        assertEquals(TrackNodeType.VIDEO, found?.type)
    }

    @Test
    fun findsVideoNestedInsideFolders() {
        assertEquals("pv2.mkv", VideoTrackFinder.find(root, 3)?.name)
    }

    @Test
    fun returnsNullForNonVideoIndex() {
        assertNull(VideoTrackFinder.find(root, 1))
        assertNull(VideoTrackFinder.find(root, 5))
        assertNull(VideoTrackFinder.find(root, 6))
    }

    @Test
    fun returnsNullForMissingOrOutOfRangeIndex() {
        assertNull(VideoTrackFinder.find(root, 7))
        assertNull(VideoTrackFinder.find(root, 0))
        assertNull(VideoTrackFinder.find(root, -1))
    }

    @Test
    fun returnsNullOnTreeWithNoVideo() {
        val noVideo = folder(
            "RJ000001",
            "",
            listOf(file(TrackNodeType.AUDIO, "a.mp3", 1)),
        )
        assertNull(VideoTrackFinder.find(noVideo, 1))
    }

    // ------------------------------------------------------------------
    // Entry decision
    // ------------------------------------------------------------------

    @Test
    fun emptySessionEntersVideoMode() {
        assertEquals(VideoEntryDecision.ENTER, VideoTrackFinder.decideEntry(null, "local:RJ180001", 2))
    }

    @Test
    fun differentTrackKeyEntersVideoMode() {
        assertEquals(
            VideoEntryDecision.ENTER,
            VideoTrackFinder.decideEntry("local:RJ180001:4", "local:RJ180001", 2),
        )
    }

    @Test
    fun sameVideoTrackKeyAttaches() {
        assertEquals(
            VideoEntryDecision.ATTACH,
            VideoTrackFinder.decideEntry("local:RJ180001:2", "local:RJ180001", 2),
        )
    }

    @Test
    fun sameTrackKeyDifferentWorkEnters() {
        assertEquals(
            VideoEntryDecision.ENTER,
            VideoTrackFinder.decideEntry("local:RJ999999:2", "local:RJ180001", 2),
        )
    }

    @Test
    fun malformedMediaIdEntersVideoMode() {
        assertEquals(VideoEntryDecision.ENTER, VideoTrackFinder.decideEntry("garbage", "local:RJ180001", 2))
    }
}
