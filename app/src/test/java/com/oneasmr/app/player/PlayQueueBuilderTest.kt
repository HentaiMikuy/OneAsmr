package com.oneasmr.app.player

import com.oneasmr.app.data.scanner.TrackNode
import com.oneasmr.app.data.scanner.TrackNodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Queue construction from a live track tree (plan Task 17: player builds
 * MediaItems from the domain PlayQueue model). Synthetic trees only — the
 * tree building itself is covered by TrackTreeBuilderTest.
 */
class PlayQueueBuilderTest {

    private val builder = PlayQueueBuilder()

    private fun audio(name: String, index: Int, uri: String) = TrackNode(
        type = TrackNodeType.AUDIO,
        name = name,
        relativePath = name,
        trackIndex = index,
        documentUri = uri,
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

    /** RJ100200: CD1/{track1,track2}, track3, CD2/{vid,track4}, readme. */
    private fun workTree(): TrackNode = folder(
        "RJ100200", "",
        listOf(
            folder(
                "CD1", "CD1",
                listOf(
                    audio("track1.mp3", 1, "content://doc/t1"),
                    audio("track2.mp3", 2, "content://doc/t2"),
                ),
            ),
            audio("track3.wav", 3, "content://doc/t3"),
            folder(
                "CD2", "CD2",
                listOf(
                    TrackNode(TrackNodeType.VIDEO, "video.mp4", "CD2/video.mp4", 4, "content://doc/v", 1L, 0L, emptyList()),
                    audio("track4.flac", 5, "content://doc/t4"),
                ),
            ),
            TrackNode(TrackNodeType.TEXT, "readme.txt", "readme.txt", 6, "content://doc/r", 1L, 0L, emptyList()),
        ),
    )

    @Test
    fun `only audio nodes enter the queue in tree order`() {
        val queue = builder.build("local:RJ100200", "RJ100200 标题", workTree(), startTrackIndex = 1)
        assertEquals(
            listOf("track1.mp3", "track2.mp3", "track3.wav", "track4.flac"),
            queue.items.map { it.trackTitle },
        )
        // indices are the work's stable track indices, in DFS pre-order
        assertEquals(listOf(1, 2, 3, 5), queue.items.map { it.trackIndex })
    }

    @Test
    fun `start index maps to the tapped track`() {
        // Tap track4 (index 5): queue starts at its position
        val queue = builder.build("local:RJ100200", "w", workTree(), startTrackIndex = 5)
        assertEquals(3, queue.startIndex)
        assertEquals("track4.flac", queue.items[queue.startIndex].trackTitle)
    }

    @Test
    fun `first track tap starts at zero`() {
        val queue = builder.build("local:RJ100200", "w", workTree(), startTrackIndex = 1)
        assertEquals(0, queue.startIndex)
        assertTrue(queue.isPlayable)
    }

    @Test
    fun `unknown track index falls back to first item`() {
        val queue = builder.build("local:RJ100200", "w", workTree(), startTrackIndex = 99)
        assertEquals(0, queue.startIndex)
    }

    @Test
    fun `video tap still starts at first audio item`() {
        val queue = builder.build("local:RJ100200", "w", workTree(), startTrackIndex = 4)
        assertEquals(0, queue.startIndex)
    }

    @Test
    fun `track keys and uris ride the model for KeySpec mediaIds`() {
        val queue = builder.build("local:RJ100200", "w", workTree(), startTrackIndex = 1)
        val item = queue.items[1]
        assertEquals("local", item.sourceScope)
        assertEquals("RJ100200", item.rjCode)
        assertEquals("content://doc/t2", item.uri)
        assertFalse(item.isRemote)
        // The mapper derives the normative trackKey from these fields.
        assertEquals("local:RJ100200:2", item.toMediaItem().mediaId)
    }

    @Test
    fun `remote work id propagates the source scope`() {
        val queue = builder.build("srv1:RJ100200", "w", workTree(), startTrackIndex = 1)
        assertEquals("srv1", queue.items[0].sourceScope)
        assertEquals("srv1:RJ100200:1", queue.items[0].toMediaItem().mediaId)
    }

    @Test
    fun `malformed work id falls back to local scope`() {
        val queue = builder.build("not-a-key", "w", workTree(), startTrackIndex = 1)
        assertEquals("local", queue.items[0].sourceScope)
    }

    @Test
    fun `work without audio is not playable`() {
        val empty = folder("RJ000000", "", emptyList())
        val queue = builder.build("local:RJ000000", "w", empty, startTrackIndex = 1)
        assertFalse(queue.isPlayable)
        assertTrue(queue.items.isEmpty())
    }
}
