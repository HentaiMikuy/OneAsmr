package com.oneasmr.app.domain.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec tests for [MediaClassifier] (plan Task 3 whitelist, case-insensitive):
 * AUDIO: mp3/wav/flac/ogg/opus/aac/m4a
 * VIDEO: mp4/webm/mkv/mov
 * TEXT:  lrc/vtt/srt/ass/txt
 * IMAGE: jpg/jpeg/png/webp
 * OTHER: everything else, including missing/blank extension.
 */
class MediaClassifierTest {

    @Test
    fun `full audio extension table`() {
        val expected = mapOf(
            "a.mp3" to MediaType.AUDIO,
            "b.wav" to MediaType.AUDIO,
            "c.flac" to MediaType.AUDIO,
            "d.ogg" to MediaType.AUDIO,
            "e.opus" to MediaType.AUDIO,
            "f.aac" to MediaType.AUDIO,
            "g.m4a" to MediaType.AUDIO,
        )
        expected.forEach { (name, type) -> assertEquals(type, MediaClassifier.classify(name)) }
    }

    @Test
    fun `full video extension table`() {
        val expected = mapOf(
            "a.mp4" to MediaType.VIDEO,
            "b.webm" to MediaType.VIDEO,
            "c.mkv" to MediaType.VIDEO,
            "d.mov" to MediaType.VIDEO,
        )
        expected.forEach { (name, type) -> assertEquals(type, MediaClassifier.classify(name)) }
    }

    @Test
    fun `full text extension table`() {
        val expected = mapOf(
            "a.lrc" to MediaType.TEXT,
            "b.srt" to MediaType.TEXT,
            "c.ass" to MediaType.TEXT,
            "d.txt" to MediaType.TEXT,
            "e.vtt" to MediaType.TEXT,
        )
        expected.forEach { (name, type) -> assertEquals(type, MediaClassifier.classify(name)) }
    }

    @Test
    fun `appended vtt subtitle naming is text`() {
        // DLsite 官方字幕命名：完整音频文件名 + .vtt（按最后一段扩展名分类）。
        assertEquals(MediaType.TEXT, MediaClassifier.classify("track01.mp3.vtt"))
        assertEquals(MediaType.TEXT, MediaClassifier.classify("track01.wav.vtt"))
    }

    @Test
    fun `full image extension table`() {
        val expected = mapOf(
            "a.jpg" to MediaType.IMAGE,
            "b.jpeg" to MediaType.IMAGE,
            "c.png" to MediaType.IMAGE,
            "d.webp" to MediaType.IMAGE,
        )
        expected.forEach { (name, type) -> assertEquals(type, MediaClassifier.classify(name)) }
    }

    @Test
    fun `uppercase extensions are classified`() {
        assertEquals(MediaType.AUDIO, MediaClassifier.classify("TRACK.MP3"))
        assertEquals(MediaType.VIDEO, MediaClassifier.classify("CLIP.MP4"))
        assertEquals(MediaType.TEXT, MediaClassifier.classify("LYRICS.LRC"))
        assertEquals(MediaType.IMAGE, MediaClassifier.classify("COVER.JPG"))
    }

    @Test
    fun `mixed case extensions are classified`() {
        assertEquals(MediaType.AUDIO, MediaClassifier.classify(".Flac"))
        assertEquals(MediaType.AUDIO, MediaClassifier.classify("Track.Opus"))
        assertEquals(MediaType.TEXT, MediaClassifier.classify("SUB.Srt"))
        assertEquals(MediaType.IMAGE, MediaClassifier.classify("Art.PnG"))
    }

    @Test
    fun `no extension is other`() {
        assertEquals(MediaType.OTHER, MediaClassifier.classify("noext"))
    }

    @Test
    fun `hidden file with extension is classified by that extension`() {
        assertEquals(MediaType.AUDIO, MediaClassifier.classify(".Flac"))
    }

    @Test
    fun `null input is other`() {
        assertEquals(MediaType.OTHER, MediaClassifier.classify(null))
    }

    @Test
    fun `empty string is other`() {
        assertEquals(MediaType.OTHER, MediaClassifier.classify(""))
    }

    @Test
    fun `blank string is other`() {
        assertEquals(MediaType.OTHER, MediaClassifier.classify("   "))
    }

    @Test
    fun `trailing dot is other`() {
        assertEquals(MediaType.OTHER, MediaClassifier.classify("track."))
    }

    @Test
    fun `unknown extension is other`() {
        assertEquals(MediaType.OTHER, MediaClassifier.classify("track.rar"))
        assertEquals(MediaType.OTHER, MediaClassifier.classify("readme.md"))
        assertEquals(MediaType.OTHER, MediaClassifier.classify("archive.zip"))
    }

    @Test
    fun `last extension wins in multi dot name`() {
        assertEquals(MediaType.AUDIO, MediaClassifier.classify("track.2024.mp3"))
        assertEquals(MediaType.OTHER, MediaClassifier.classify("track.mp3.txt.bak"))
    }

    @Test
    fun `video extension with dots in name`() {
        assertEquals(MediaType.VIDEO, MediaClassifier.classify("RJ123456.mkv"))
    }
}
