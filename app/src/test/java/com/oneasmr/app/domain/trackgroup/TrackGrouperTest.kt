package com.oneasmr.app.domain.trackgroup

import com.oneasmr.app.data.scanner.TrackNode
import com.oneasmr.app.data.scanner.TrackNodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音轨分组：优先级链（特典 > 类型 > 音频扩展名）、整条 relativePath 匹配、
 * 组序与组内 DFS 先序。合成树 fixture，无 Android 依赖。
 */
class TrackGrouperTest {

    private var nextIndex = 1

    private fun file(path: String, type: TrackNodeType): TrackNode = TrackNode(
        type = type,
        name = path.substringAfterLast('/'),
        relativePath = path,
        trackIndex = nextIndex++,
        documentUri = "content://test/$path",
        size = 100L,
        lastModified = 0L,
        children = emptyList(),
    )

    private fun folder(path: String, vararg children: TrackNode): TrackNode = TrackNode(
        type = TrackNodeType.FOLDER,
        name = path.substringAfterLast('/'),
        relativePath = path,
        trackIndex = null,
        documentUri = "",
        size = 0L,
        lastModified = 0L,
        children = children.toList(),
    )

    private fun rootOf(vararg children: TrackNode): TrackNode =
        folder("", *children)

    private fun groupKeys(root: TrackNode): List<TrackGroup> =
        TrackGrouper.group(root).map { it.group }

    private fun filesOf(root: TrackNode, group: TrackGroup): List<String> =
        TrackGrouper.group(root).first { it.group == group }.files.map { it.relativePath }

    // ------------------------------------------------------------------
    // 基本归类
    // ------------------------------------------------------------------

    @Test
    fun `flat work with wav mp3 jpg txt splits into format and type groups`() {
        val root = rootOf(
            file("01.wav", TrackNodeType.AUDIO),
            file("01.mp3", TrackNodeType.AUDIO),
            file("cover.jpg", TrackNodeType.IMAGE),
            file("readme.txt", TrackNodeType.TEXT),
        )
        assertEquals(
            listOf(TrackGroup.WAV, TrackGroup.MP3, TrackGroup.IMAGE, TrackGroup.TEXT),
            groupKeys(root),
        )
    }

    @Test
    fun `nested format folders with SE variants land in their format groups`() {
        val root = rootOf(
            folder(
                "WAV/SEあり",
                file("WAV/SEあり/01.wav", TrackNodeType.AUDIO),
                file("WAV/SEあり/02.wav", TrackNodeType.AUDIO),
            ),
            folder(
                "MP3/SEなし",
                file("MP3/SEなし/01.mp3", TrackNodeType.AUDIO),
            ),
        )
        assertEquals(listOf(TrackGroup.WAV, TrackGroup.MP3), groupKeys(root))
        // 组内保持原树 DFS 先序。
        assertEquals(listOf("WAV/SEあり/01.wav", "WAV/SEあり/02.wav"), filesOf(root, TrackGroup.WAV))
        assertEquals(listOf("MP3/SEなし/01.mp3"), filesOf(root, TrackGroup.MP3))
    }

    // ------------------------------------------------------------------
    // 特典优先级
    // ------------------------------------------------------------------

    @Test
    fun `bonus folder swallows audio and images into one tokuten group`() {
        val root = rootOf(
            folder(
                "特典/おまけ",
                file("特典/おまけ/freetalk.wav", TrackNodeType.AUDIO),
                file("特典/おまけ/photo.jpg", TrackNodeType.IMAGE),
            ),
            file("01.wav", TrackNodeType.AUDIO),
        )
        assertEquals(listOf(TrackGroup.WAV, TrackGroup.BONUS), groupKeys(root))
        assertEquals(
            listOf("特典/おまけ/freetalk.wav", "特典/おまけ/photo.jpg"),
            filesOf(root, TrackGroup.BONUS),
        )
    }

    @Test
    fun `format word in mid level folder does not matter - extension decides`() {
        val root = rootOf(
            folder("1-帰り道mp3", file("1-帰り道mp3/track.mp3", TrackNodeType.AUDIO)),
        )
        assertEquals(listOf(TrackGroup.MP3), groupKeys(root))
    }

    @Test
    fun `bonus keyword in folder beats audio format`() {
        val root = rootOf(
            folder("特典音源", file("特典音源/track.wav", TrackNodeType.AUDIO)),
        )
        assertEquals(listOf(TrackGroup.BONUS), groupKeys(root))
    }

    @Test
    fun `bonus keyword matching is case insensitive`() {
        val root = rootOf(
            folder("BONUS", file("BONUS/track.wav", TrackNodeType.AUDIO)),
        )
        assertEquals(listOf(TrackGroup.BONUS), groupKeys(root))
    }

    // ------------------------------------------------------------------
    // 边界
    // ------------------------------------------------------------------

    @Test
    fun `empty tree yields no groups`() {
        assertTrue(TrackGrouper.group(rootOf()).isEmpty())
    }

    @Test
    fun `unknown audio extensions fall into other-audio group`() {
        val root = rootOf(
            file("01.ogg", TrackNodeType.AUDIO),
            file("02.m4a", TrackNodeType.AUDIO),
        )
        assertEquals(listOf(TrackGroup.OTHER_AUDIO), groupKeys(root))
        assertEquals(listOf("01.ogg", "02.m4a"), filesOf(root, TrackGroup.OTHER_AUDIO))
    }

    @Test
    fun `video and other nodes classify by type after bonus check`() {
        val root = rootOf(
            file("pv.mp4", TrackNodeType.VIDEO),
            file("archive.bin", TrackNodeType.OTHER),
        )
        assertEquals(listOf(TrackGroup.VIDEO, TrackGroup.OTHER), groupKeys(root))
    }

    // ------------------------------------------------------------------
    // 可触达的内部规则
    // ------------------------------------------------------------------

    @Test
    fun `isBonusPath matches keyword anywhere in the full path`() {
        assertTrue(TrackGrouper.isBonusPath("WAV/フリートーク/01.wav"))
        assertTrue(TrackGrouper.isBonusPath("キャストトーク.mp3"))
        assertTrue(TrackGrouper.isBonusPath("Extras/photo.jpg".lowercase()))
        assertFalse(TrackGrouper.isBonusPath("WAV/SEあり/01.wav"))
    }

    @Test
    fun `audioGroupForPath maps lowercase extensions`() {
        assertEquals(TrackGroup.WAV, TrackGrouper.audioGroupForPath("a/b/01.WAV"))
        assertEquals(TrackGroup.FLAC, TrackGrouper.audioGroupForPath("01.FlAc"))
        assertEquals(TrackGroup.OTHER_AUDIO, TrackGrouper.audioGroupForPath("01.opus"))
    }
}
