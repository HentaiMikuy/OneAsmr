package com.oneasmr.app.domain.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 20 LRC parser: timestamp lines, metadata lines, multi-timestamp lines,
 * offset, sorting, and the failure path — malformed rows are skipped and
 * counted, never thrown. Deterministic string fixtures only (no wall-clock).
 */
class LrcParserTest {

    private fun parse(text: String): LrcLyrics = LrcParser.parse(text)

    // ------------------------------------------------------------------
    // Timestamp forms
    // ------------------------------------------------------------------

    @Test
    fun `mm ss xx two digit fraction`() {
        val lyrics = parse("[00:12.34]hello\n[00:13.00]world\n")
        assertEquals(
            listOf(LrcLine(12_340, "hello"), LrcLine(13_000, "world")),
            lyrics.lines,
        )
        assertEquals(0, lyrics.skippedLines)
    }

    @Test
    fun `mm ss without fraction`() {
        val lyrics = parse("[00:12]no fraction\n")
        assertEquals(listOf(LrcLine(12_000, "no fraction")), lyrics.lines)
    }

    @Test
    fun `mm ss xxx three digit fraction`() {
        val lyrics = parse("[01:02.345]three\n")
        assertEquals(listOf(LrcLine(62_345, "three")), lyrics.lines)
    }

    @Test
    fun `single digit fraction scales to hundreds`() {
        val lyrics = parse("[00:01.5]one digit\n")
        assertEquals(listOf(LrcLine(1_500, "one digit")), lyrics.lines)
    }

    @Test
    fun `colon fraction separator tolerated`() {
        val lyrics = parse("[00:12:34]colon\n")
        assertEquals(listOf(LrcLine(12_340, "colon")), lyrics.lines)
    }

    @Test
    fun `minutes beyond 59 accepted for long tracks`() {
        val lyrics = parse("[100:00.00]long\n")
        assertEquals(listOf(LrcLine(6_000_000, "long")), lyrics.lines)
    }

    @Test
    fun `multi timestamp line yields one line per timestamp`() {
        val lyrics = parse("[00:12.00][00:34.00]repeat\n")
        assertEquals(
            listOf(LrcLine(12_000, "repeat"), LrcLine(34_000, "repeat")),
            lyrics.lines,
        )
    }

    @Test
    fun `leading whitespace before bracket tolerated`() {
        val lyrics = parse("   [00:05.00]indented\n")
        assertEquals(listOf(LrcLine(5_000, "indented")), lyrics.lines)
    }

    @Test
    fun `crlf line endings`() {
        val lyrics = parse("[00:01.00]a\r\n[00:02.00]b\r\n")
        assertEquals(2, lyrics.lines.size)
        assertEquals("b", lyrics.lines[1].text)
    }

    // ------------------------------------------------------------------
    // Metadata
    // ------------------------------------------------------------------

    @Test
    fun `metadata tags parsed and not emitted as lines`() {
        val lyrics = parse("[ti:测试曲]\n[ar:歌手A]\n[al:专辑B]\n[by:制作者C]\n[00:01.00]only real line\n")
        assertEquals("测试曲", lyrics.metadata.title)
        assertEquals("歌手A", lyrics.metadata.artist)
        assertEquals("专辑B", lyrics.metadata.album)
        assertEquals("制作者C", lyrics.metadata.author)
        assertEquals(listOf(LrcLine(1_000, "only real line")), lyrics.lines)
        assertEquals(0, lyrics.skippedLines)
    }

    @Test
    fun `metadata tags case insensitive`() {
        val lyrics = parse("[TI:Upper]\n[Ar:Case]\n[00:01.00]x\n")
        assertEquals("Upper", lyrics.metadata.title)
        assertEquals("Case", lyrics.metadata.artist)
    }

    @Test
    fun `unknown extended tag is tolerated as metadata not garbage`() {
        val lyrics = parse("[language:jp]\n[00:01.00]x\n")
        assertEquals(1, lyrics.lines.size)
        assertEquals(0, lyrics.skippedLines)
    }

    @Test
    fun `positive offset shifts timestamps later`() {
        val lyrics = parse("[offset:+500]\n[00:01.00]a\n[00:02.00]b\n")
        assertEquals(500L, lyrics.metadata.offsetMs)
        assertEquals(listOf(LrcLine(1_500, "a"), LrcLine(2_500, "b")), lyrics.lines)
    }

    @Test
    fun `negative offset shifts timestamps earlier and clamps at zero`() {
        val lyrics = parse("[offset:-300]\n[00:01.00]a\n[00:00.10]b\n")
        assertEquals(listOf(LrcLine(0L, "b"), LrcLine(700, "a")), lyrics.lines)
    }

    @Test
    fun `malformed offset value is ignored`() {
        val lyrics = parse("[offset:abc]\n[00:01.00]a\n")
        assertEquals(0L, lyrics.metadata.offsetMs)
        assertEquals(listOf(LrcLine(1_000, "a")), lyrics.lines)
    }

    // ------------------------------------------------------------------
    // Ordering
    // ------------------------------------------------------------------

    @Test
    fun `out of order timestamps sorted ascending`() {
        val lyrics = parse("[00:03.00]c\n[00:01.00]a\n[00:02.00]b\n")
        assertEquals(listOf("a", "b", "c"), lyrics.lines.map { it.text })
        assertTrue(lyrics.lines[0].timestampMs <= lyrics.lines[1].timestampMs)
        assertTrue(lyrics.lines[1].timestampMs <= lyrics.lines[2].timestampMs)
    }

    @Test
    fun `equal timestamps keep file order stable`() {
        val lyrics = parse("[00:01.00]first\n[00:01.00]second\n")
        assertEquals(listOf("first", "second"), lyrics.lines.map { it.text })
    }

    // ------------------------------------------------------------------
    // Malformed tolerance (failure path: skip bad rows, never crash)
    // ------------------------------------------------------------------

    @Test
    fun `garbage lines skipped and counted`() {
        val lyrics = parse("this is not a lyric\n[00:01.00]good\nrandom noise\n")
        assertEquals(listOf(LrcLine(1_000, "good")), lyrics.lines)
        assertEquals(2, lyrics.skippedLines)
    }

    @Test
    fun `illegal seconds skipped and counted`() {
        val lyrics = parse("[00:99.00]bad seconds\n[00:01.00]good\n")
        assertEquals(listOf(LrcLine(1_000, "good")), lyrics.lines)
        assertEquals(1, lyrics.skippedLines)
    }

    @Test
    fun `four digit fraction skipped`() {
        val lyrics = parse("[00:01.1234]bad fraction\n[00:02.00]good\n")
        assertEquals(listOf(LrcLine(2_000, "good")), lyrics.lines)
        assertEquals(1, lyrics.skippedLines)
    }

    @Test
    fun `unclosed bracket skipped`() {
        val lyrics = parse("[00:01.00broken\n[00:02.00]good\n")
        assertEquals(listOf(LrcLine(2_000, "good")), lyrics.lines)
        assertEquals(1, lyrics.skippedLines)
    }

    @Test
    fun `non bracket noise skipped`() {
        val lyrics = parse("歌词没有时间戳\n[00:01.00]good\n")
        assertEquals(listOf(LrcLine(1_000, "good")), lyrics.lines)
        assertEquals(1, lyrics.skippedLines)
    }

    @Test
    fun `blank lines are not counted as bad rows`() {
        val lyrics = parse("[00:01.00]a\n\n\n[00:02.00]b\n")
        assertEquals(2, lyrics.lines.size)
        assertEquals(0, lyrics.skippedLines)
    }

    @Test
    fun `corrupted mix parses good rows and counts bad`() {
        val lyrics = parse(
            "[ti:损坏测试]\n" +
                "[00:01.00]第一行\n" +
                "garbage~~~~\n" +
                "[00:99.99]非法时间戳\n" +
                "[00:02.00]第二行\n" +
                "]\n",
        )
        assertEquals(listOf("第一行", "第二行"), lyrics.lines.map { it.text })
        assertEquals(3, lyrics.skippedLines)
        assertEquals(0, lyrics.lines.sumOf { it.timestampMs } % 1000) // all clean ms
    }

    @Test
    fun `empty input yields empty lyrics`() {
        val lyrics = parse("")
        assertTrue(lyrics.lines.isEmpty())
        assertEquals(0, lyrics.skippedLines)
    }

    // ------------------------------------------------------------------
    // indexAt: active-line resolution from a playback position
    // ------------------------------------------------------------------

    private val fixture = LrcParser.parse(
        "[00:01.00]one\n[00:02.00]two\n[00:04.00]three\n",
    )

    @Test
    fun `indexAt before first line is -1`() {
        assertEquals(-1, fixture.indexAt(0L))
        assertEquals(-1, fixture.indexAt(999L))
    }

    @Test
    fun `indexAt exact timestamp`() {
        assertEquals(0, fixture.indexAt(1_000L))
        assertEquals(2, fixture.indexAt(4_000L))
    }

    @Test
    fun `indexAt between lines picks the earlier line`() {
        assertEquals(0, fixture.indexAt(1_500L))
        assertEquals(1, fixture.indexAt(2_999L))
    }

    @Test
    fun `indexAt after last line stays on last`() {
        assertEquals(2, fixture.indexAt(4_001L))
        assertEquals(2, fixture.indexAt(999_999L))
    }

    @Test
    fun `indexAt on empty lyrics is -1`() {
        assertEquals(-1, LrcParser.parse("").indexAt(5_000L))
    }

    @Test
    fun `indexAt before first line of sorted shuffled input`() {
        val shuffled = LrcParser.parse("[00:05.00]five\n[00:02.00]two\n[00:08.00]eight\n")
        assertEquals(0, shuffled.indexAt(2_000L))
        assertEquals(1, shuffled.indexAt(5_000L))
        assertEquals(2, shuffled.indexAt(8_000L))
        assertEquals(-1, shuffled.indexAt(1_999L))
    }
}
