package com.oneasmr.app.data.lyrics

import com.oneasmr.app.data.scanner.TrackNode
import com.oneasmr.app.data.scanner.TrackNodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Lyrics/subtitle matching: same directory, case-insensitive, candidate
 * priority lrc > vtt > srt > txt with replaced-extension naming
 * (`track1.lrc`) preferred over appended naming (`track1.mp3.lrc`) within a
 * format; first candidate in tree order wins for one name.
 */
class LyricsMatcherTest {

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
        val node = LyricsMatcher.findLyrics(root, "track1.mp3")
        assertEquals("track1.lrc", node?.name)
    }

    @Test
    fun `match inside a subdirectory`() {
        val node = LyricsMatcher.findLyrics(root, "disk1/track2.wav")
        assertEquals("disk1/track2.lrc", node?.relativePath)
    }

    @Test
    fun `case insensitive lrc extension and basename`() {
        val root2 = folder(
            "RJ_TEST", "",
            file("TRACK9.MP3", "TRACK9.MP3", 1, TrackNodeType.AUDIO),
            file("Track9.LRC", "Track9.LRC", 2),
        )
        val node = LyricsMatcher.findLyrics(root2, "TRACK9.MP3")
        assertEquals("Track9.LRC", node?.name)
    }

    @Test
    fun `no candidate for the track returns null`() {
        assertNull(LyricsMatcher.findLyrics(root, "track3.mp3"))
    }

    @Test
    fun `candidate with different basename is not a match`() {
        val root2 = folder(
            "RJ_TEST", "",
            file("trackX.lrc", "trackX.lrc", 1),
            file("track1.mp3", "track1.mp3", 2, TrackNodeType.AUDIO),
        )
        assertNull(LyricsMatcher.findLyrics(root2, "track1.mp3"))
    }

    @Test
    fun `candidate in a different directory is not a match`() {
        assertNull(LyricsMatcher.findLyrics(root, "track2.mp3")) // track2.lrc lives in disk1/
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
        val node = LyricsMatcher.findLyrics(root2, "A.mp3")
        assertEquals("a.lrc", node?.name)
    }

    @Test
    fun `non text file with lrc name is not a match`() {
        val root2 = folder(
            "RJ_TEST", "",
            file("a.lrc", "a.lrc", 1, TrackNodeType.OTHER),
            file("a.mp3", "a.mp3", 2, TrackNodeType.AUDIO),
        )
        assertNull(LyricsMatcher.findLyrics(root2, "a.mp3"))
    }

    @Test
    fun `appended extension lrc naming matched`() {
        val root2 = folder(
            "RJ_TEST", "",
            file("track1.mp3", "track1.mp3", 1, TrackNodeType.AUDIO),
            file("track1.mp3.lrc", "track1.mp3.lrc", 2),
        )
        val node = LyricsMatcher.findLyrics(root2, "track1.mp3")
        assertEquals("track1.mp3.lrc", node?.name)
    }

    @Test
    fun `dlsite appended vtt naming matched for mp3 and wav`() {
        val root2 = folder(
            "RJ_TEST", "",
            file("track1.mp3", "track1.mp3", 1, TrackNodeType.AUDIO),
            file("track1.mp3.vtt", "track1.mp3.vtt", 2),
            file("track2.wav", "track2.wav", 3, TrackNodeType.AUDIO),
            file("track2.wav.vtt", "track2.wav.vtt", 4),
        )
        assertEquals("track1.mp3.vtt", LyricsMatcher.findLyrics(root2, "track1.mp3")?.name)
        assertEquals("track2.wav.vtt", LyricsMatcher.findLyrics(root2, "track2.wav")?.name)
    }

    @Test
    fun `replaced extension vtt naming matched`() {
        val root2 = folder(
            "RJ_TEST", "",
            file("track1.mp3", "track1.mp3", 1, TrackNodeType.AUDIO),
            file("track1.vtt", "track1.vtt", 2),
        )
        assertEquals("track1.vtt", LyricsMatcher.findLyrics(root2, "track1.mp3")?.name)
    }

    @Test
    fun `srt and txt candidates matched as fallback`() {
        val srtRoot = folder(
            "RJ_TEST", "",
            file("a.mp3", "a.mp3", 1, TrackNodeType.AUDIO),
            file("a.srt", "a.srt", 2),
        )
        assertEquals("a.srt", LyricsMatcher.findLyrics(srtRoot, "a.mp3")?.name)

        val txtRoot = folder(
            "RJ_TEST", "",
            file("a.mp3", "a.mp3", 1, TrackNodeType.AUDIO),
            file("a.mp3.txt", "a.mp3.txt", 2),
        )
        assertEquals("a.mp3.txt", LyricsMatcher.findLyrics(txtRoot, "a.mp3")?.name)
    }

    @Test
    fun `format priority lrc beats vtt beats txt regardless of tree order`() {
        val root2 = folder(
            "RJ_TEST", "",
            file("a.txt", "a.txt", 1),
            file("a.mp3.vtt", "a.mp3.vtt", 2),
            file("a.mp3.lrc", "a.mp3.lrc", 3),
            file("a.mp3", "a.mp3", 4, TrackNodeType.AUDIO),
        )
        assertEquals("a.mp3.lrc", LyricsMatcher.findLyrics(root2, "a.mp3")?.name)
    }

    @Test
    fun `replaced naming beats appended naming within one format`() {
        val root2 = folder(
            "RJ_TEST", "",
            file("a.mp3.vtt", "a.mp3.vtt", 1),
            file("a.vtt", "a.vtt", 2),
            file("a.mp3", "a.mp3", 3, TrackNodeType.AUDIO),
        )
        assertEquals("a.vtt", LyricsMatcher.findLyrics(root2, "a.mp3")?.name)
    }
}
