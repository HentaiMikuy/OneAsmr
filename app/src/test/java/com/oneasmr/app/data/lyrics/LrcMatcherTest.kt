package com.oneasmr.app.data.lyrics

import com.oneasmr.app.data.scanner.TrackNode
import com.oneasmr.app.data.scanner.TrackNodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Task 20 LRC matching: kikoeru check-lrc semantics — same directory, same
 * base filename, `.lrc` extension, case-insensitive; first candidate in tree
 * order wins.
 */
class LrcMatcherTest {

    private fun file(
        name: String,
        relativePath: String,
        index: Int,
        type: TrackNodeType = TrackNodeType.TEXT,
    ) = TrackNode(
        type = type,
        name = name,
        relativePath = relativePath,
        trackIndex = index,
        documentUri = "content://test/$relativePath",
        size = 1L,
        lastModified = 0L,
        children = emptyList(),
    )

    private fun folder(name: String, relativePath: String, vararg children: TrackNode) = TrackNode(
        type = TrackNodeType.FOLDER,
        name = name,
        relativePath = relativePath,
        trackIndex = null,
        documentUri = "content://test/$relativePath",
        size = 0L,
        lastModified = 0L,
        children = children.toList(),
    )

    private val root = folder(
        "RJ_TEST", "",
        file("track1.mp3", "track1.mp3", 1, TrackNodeType.AUDIO),
        file("track1.lrc", "track1.lrc", 2),
        file("track2.mp3", "track2.mp3", 3, TrackNodeType.AUDIO),
        file("track3.mp3", "track3.mp3", 4, TrackNodeType.AUDIO),
        file("notes.txt", "notes.txt", 5),
        folder(
            "disk1", "disk1",
            file("track2.wav", "disk1/track2.wav", 6, TrackNodeType.AUDIO),
            file("track2.lrc", "disk1/track2.lrc", 7),
            file("other.lrc", "disk1/other.lrc", 8),
        ),
    )

    @Test
    fun `same directory same basename matched`() {
        val node = LrcMatcher.findLrc(root, "track1.mp3")
        assertEquals("track1.lrc", node?.name)
    }

    @Test
    fun `match inside a subdirectory`() {
        val node = LrcMatcher.findLrc(root, "disk1/track2.wav")
        assertEquals("disk1/track2.lrc", node?.relativePath)
    }

    @Test
    fun `case insensitive lrc extension and basename`() {
        val root2 = folder(
            "RJ_TEST", "",
            file("TRACK9.MP3", "TRACK9.MP3", 1, TrackNodeType.AUDIO),
            file("Track9.LRC", "Track9.LRC", 2),
        )
        val node = LrcMatcher.findLrc(root2, "TRACK9.MP3")
        assertEquals("Track9.LRC", node?.name)
    }

    @Test
    fun `no lrc for the track returns null`() {
        assertNull(LrcMatcher.findLrc(root, "track3.mp3"))
    }

    @Test
    fun `lrc with different basename is not a match`() {
        val root2 = folder(
            "RJ_TEST", "",
            file("trackX.lrc", "trackX.lrc", 1),
            file("track1.mp3", "track1.mp3", 2, TrackNodeType.AUDIO),
        )
        assertNull(LrcMatcher.findLrc(root2, "track1.mp3"))
    }

    @Test
    fun `lrc in a different directory is not a match`() {
        assertNull(LrcMatcher.findLrc(root, "track2.mp3")) // track2.lrc lives in disk1/
    }

    @Test
    fun `first candidate in tree order wins`() {
        val root2 = folder(
            "RJ_TEST", "",
            file("a.lrc", "a.lrc", 1),
            file("A.lrc", "A.lrc", 2),
            file("A.mp3", "A.mp3", 3, TrackNodeType.AUDIO),
        )
        // Both a.lrc and A.lrc match case-insensitively; the FIRST in the
        // tree's child order wins.
        val node = LrcMatcher.findLrc(root2, "A.mp3")
        assertEquals("a.lrc", node?.name)
    }

    @Test
    fun `non text file with lrc name is not a match`() {
        val root2 = folder(
            "RJ_TEST", "",
            file("a.lrc", "a.lrc", 1, TrackNodeType.OTHER),
            file("a.mp3", "a.mp3", 2, TrackNodeType.AUDIO),
        )
        assertNull(LrcMatcher.findLrc(root2, "a.mp3"))
    }
}
