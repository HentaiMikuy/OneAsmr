package com.oneasmr.app.data.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Track-tree building over a fake filesystem (plan Task 6): natural sort of
 * tree children, per-type classification, stable depth-first trackIndex,
 * documentUri passthrough, unreadable-directory failure path, cancellation.
 */
class TrackTreeBuilderTest {

    private val workPath = FsPath(listOf("RJ111111"))

    /** Fixture: a mixed work folder; returns (fs, builder). */
    private fun mixedWorkFs(): FakeDocumentFs {
        val fs = FakeDocumentFs()
        val work = fs.addDirectory(name = "RJ111111")
        fs.addFile(work, "01 - intro.wav")
        val pics = fs.addDirectory(work, "pics")
        fs.addFile(pics, ".DS_Store")
        fs.addFile(pics, "art.png")
        fs.addFile(pics, "cover.jpg")
        fs.addFile(work, "readme.txt")
        val sub = fs.addDirectory(work, "sub")
        val deep = fs.addDirectory(sub, "deep")
        fs.addFile(deep, "track4.ogg")
        fs.addFile(sub, "track3.flac")
        fs.addFile(work, "track1.mp3")
        fs.addFile(work, "track2.mp3")
        fs.addFile(work, "track10.mp3")
        fs.addFile(work, "unknown.xyz")
        val video = fs.addDirectory(work, "video")
        fs.addFile(video, "clip.mkv")
        fs.addFile(video, "movie.mp4")
        fs.addFile(work, "歌词.lrc")
        return fs
    }

    private fun byRelativePath(root: TrackNode): Map<String, TrackNode> {
        val map = mutableMapOf<String, TrackNode>()
        fun walk(node: TrackNode) {
            map[node.relativePath] = node
            node.children.forEach { walk(it) }
        }
        walk(root)
        return map
    }

    @Test
    fun `children sorted naturally track1 track2 track10`() {
        val fs = mixedWorkFs()
        val result = TrackTreeBuilder(fs).build(workPath, "RJ111111", "content://fake/RJ111111")

        val rootNames = result.root.children.map { it.name }
        assertEquals(
            listOf(
                "01 - intro.wav",
                "pics",
                "readme.txt",
                "sub",
                "track1.mp3",
                "track2.mp3",
                "track10.mp3",
                "unknown.xyz",
                "video",
                "歌词.lrc",
            ),
            rootNames,
        )
        assertEquals(listOf(".DS_Store", "art.png", "cover.jpg"), result.root.children.first { it.name == "pics" }.children.map { it.name })
        assertEquals(listOf("deep", "track3.flac"), result.root.children.first { it.name == "sub" }.children.map { it.name })
        assertEquals(listOf("clip.mkv", "movie.mp4"), result.root.children.first { it.name == "video" }.children.map { it.name })
    }

    @Test
    fun `every file classified by extension folders and other types correct`() {
        val fs = mixedWorkFs()
        val result = TrackTreeBuilder(fs).build(workPath, "RJ111111", "content://fake/RJ111111")
        val byPath = byRelativePath(result.root)

        fun type(path: String) = byPath.getValue(path).type

        assertEquals(TrackNodeType.AUDIO, type("01 - intro.wav"))
        assertEquals(TrackNodeType.AUDIO, type("track1.mp3"))
        assertEquals(TrackNodeType.AUDIO, type("sub/track3.flac"))
        assertEquals(TrackNodeType.AUDIO, type("sub/deep/track4.ogg"))
        assertEquals(TrackNodeType.VIDEO, type("video/clip.mkv"))
        assertEquals(TrackNodeType.VIDEO, type("video/movie.mp4"))
        assertEquals(TrackNodeType.TEXT, type("readme.txt"))
        assertEquals(TrackNodeType.TEXT, type("歌词.lrc"))
        assertEquals(TrackNodeType.IMAGE, type("pics/art.png"))
        assertEquals(TrackNodeType.IMAGE, type("pics/cover.jpg"))
        assertEquals(TrackNodeType.OTHER, type("unknown.xyz"))
        assertEquals(TrackNodeType.OTHER, type("pics/.DS_Store"))
        assertEquals(TrackNodeType.FOLDER, type("pics"))
        assertEquals(TrackNodeType.FOLDER, type("sub"))
    }

    @Test
    fun `stable trackIndex depth first over files only folders null`() {
        val fs = mixedWorkFs()
        val result = TrackTreeBuilder(fs).build(workPath, "RJ111111", "content://fake/RJ111111")
        val byPath = byRelativePath(result.root)

        // DFS pre-order over naturally sorted children; folders carry no index.
        assertNull(byPath.getValue("").trackIndex)
        assertNull(byPath.getValue("pics").trackIndex)
        assertNull(byPath.getValue("sub").trackIndex)
        assertNull(byPath.getValue("video").trackIndex)

        val expectedIndex = mapOf(
            "01 - intro.wav" to 1,
            "pics/.DS_Store" to 2,
            "pics/art.png" to 3,
            "pics/cover.jpg" to 4,
            "readme.txt" to 5,
            "sub/deep/track4.ogg" to 6,
            "sub/track3.flac" to 7,
            "track1.mp3" to 8,
            "track2.mp3" to 9,
            "track10.mp3" to 10,
            "unknown.xyz" to 11,
            "video/clip.mkv" to 12,
            "video/movie.mp4" to 13,
            "歌词.lrc" to 14,
        )
        expectedIndex.forEach { (path, index) ->
            assertEquals("trackIndex of $path", index, byPath.getValue(path).trackIndex)
        }
        assertEquals("fileCount equals the highest index", 14, result.fileCount)
        // Natural-order indices too: track1 < track2 < track10 by index.
        assertTrue(byPath.getValue("track1.mp3").trackIndex!! < byPath.getValue("track2.mp3").trackIndex!!)
        assertTrue(byPath.getValue("track2.mp3").trackIndex!! < byPath.getValue("track10.mp3").trackIndex!!)
    }

    @Test
    fun `documentUri is carried through to every node`() {
        val fs = mixedWorkFs()
        val result = TrackTreeBuilder(fs).build(workPath, "RJ111111", "content://fake/RJ111111")
        val byPath = byRelativePath(result.root)

        assertEquals("content://fake/RJ111111", byPath.getValue("").documentUri)
        assertEquals("content://fake/RJ111111/track1.mp3", byPath.getValue("track1.mp3").documentUri)
        assertEquals("content://fake/RJ111111/video/movie.mp4", byPath.getValue("video/movie.mp4").documentUri)
    }

    @Test
    fun `empty work folder yields folder root with no files`() {
        val fs = FakeDocumentFs()
        fs.addDirectory(name = "RJ111111")
        val result = TrackTreeBuilder(fs).build(workPath, "RJ111111", "content://fake/RJ111111")

        assertEquals(0, result.fileCount)
        assertEquals(TrackNodeType.FOLDER, result.root.type)
        assertEquals("RJ111111", result.root.name)
        assertTrue(result.root.children.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `failure QA unreadable subdirectory is warned and the rest of the tree builds`() {
        val fs = mixedWorkFs()
        fs.markUnreadable(listOf("RJ111111", "video"))

        val result = TrackTreeBuilder(fs).build(workPath, "RJ111111", "content://fake/RJ111111")

        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().contains("video"))
        // The unreadable dir becomes an empty folder node; everything else survives.
        val video = result.root.children.first { it.name == "video" }
        assertTrue(video.children.isEmpty())
        assertEquals(12, result.fileCount) // 14 - clip.mkv - movie.mp4
        assertTrue(result.root.children.any { it.name == "track1.mp3" })
    }

    @Test
    fun `cancel mid build aborts with ScanAbortedException`() {
        val fs = mixedWorkFs()
        var calls = 0
        val isActive = { calls++ < 3 }

        try {
            TrackTreeBuilder(fs).build(workPath, "RJ111111", "content://fake/RJ111111", isActive)
            fail("expected ScanAbortedException")
        } catch (e: ScanAbortedException) {
            assertTrue(e.message!!.contains("cancelled"))
        }
    }
}
