package com.oneasmr.app.domain.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VTT/SRT cue parsing: timing-line detection, start-timestamp forms
 * (`mm:ss.ttt` / `hh:mm:ss[.,]ttt`), header/index/identifier tolerance, tag
 * stripping, and the LrcParser-aligned failure policy (bad timing rows are
 * skipped + counted, never a crash).
 */
class SubtitleCueParserTest {

    @Test
    fun `vtt with header and short timestamps`() {
        val vtt = """
            WEBVTT

            00:01.000 --> 00:04.000
            こんにちは

            01:05.500 --> 01:08.000
            第二句
        """.trimIndent()
        val lyrics = SubtitleCueParser.parse(vtt)

        assertEquals(listOf("こんにちは", "第二句"), lyrics.lines.map { it.text })
        assertEquals(listOf(1_000L, 65_500L), lyrics.lines.map { it.timestampMs })
        assertEquals(0, lyrics.skippedLines)
    }

    @Test
    fun `hour form timestamps parsed`() {
        val vtt = "01:02:03.400 --> 01:02:05.000\n长音频行\n"
        val lyrics = SubtitleCueParser.parse(vtt)

        assertEquals(1, lyrics.lines.size)
        assertEquals(3_723_400L, lyrics.lines[0].timestampMs)
    }

    @Test
    fun `srt index lines and comma fractions`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:04,000
            第一句

            2
            00:00:05,000 --> 00:00:08,000
            第二句
        """.trimIndent()
        val lyrics = SubtitleCueParser.parse(srt)

        assertEquals(listOf("第一句", "第二句"), lyrics.lines.map { it.text })
        assertEquals(listOf(1_000L, 5_000L), lyrics.lines.map { it.timestampMs })
    }

    @Test
    fun `vtt cue identifier and settings ignored`() {
        val vtt = """
            WEBVTT

            intro-cue
            00:01.000 --> 00:04.000 align:start position:10%
            带标识与设置的行
        """.trimIndent()
        val lyrics = SubtitleCueParser.parse(vtt)

        assertEquals(listOf("带标识与设置的行"), lyrics.lines.map { it.text })
        assertEquals(1_000L, lyrics.lines[0].timestampMs)
    }

    @Test
    fun `payload tags stripped and entities unescaped`() {
        val vtt = "00:01.000 --> 00:02.000\n<v 少女A><i>ささやき</i></v> &amp; <b>吐息</b>\n"
        val lyrics = SubtitleCueParser.parse(vtt)

        assertEquals("ささやき & 吐息", lyrics.lines[0].text)
    }

    @Test
    fun `multi line payload joined with space`() {
        val vtt = "00:01.000 --> 00:02.000\n第一行\n第二行\n"
        val lyrics = SubtitleCueParser.parse(vtt)

        assertEquals("第一行 第二行", lyrics.lines[0].text)
    }

    @Test
    fun `malformed timing line skipped and counted`() {
        val vtt = "abc --> def\n坏行\n\n00:01.000 --> 00:02.000\n好行\n"
        val lyrics = SubtitleCueParser.parse(vtt)

        assertEquals(listOf("好行"), lyrics.lines.map { it.text })
        assertEquals(1, lyrics.skippedLines)
    }

    @Test
    fun `missing blank separator before next timing line tolerated`() {
        val vtt = "00:03.000 --> 00:04.000\n后句\n00:01.000 --> 00:02.000\n前句\n"
        val lyrics = SubtitleCueParser.parse(vtt)

        // Cues also come back timestamp-sorted.
        assertEquals(listOf("前句", "后句"), lyrics.lines.map { it.text })
        assertEquals(listOf(1_000L, 3_000L), lyrics.lines.map { it.timestampMs })
    }

    @Test
    fun `empty payload cue dropped`() {
        val vtt = "00:01.000 --> 00:02.000\n\n00:03.000 --> 00:04.000\n有内容\n"
        val lyrics = SubtitleCueParser.parse(vtt)

        assertEquals(listOf("有内容"), lyrics.lines.map { it.text })
        assertEquals(0, lyrics.skippedLines)
    }

    @Test
    fun `empty input yields no lines`() {
        assertTrue(SubtitleCueParser.parse("").lines.isEmpty())
        assertTrue(SubtitleCueParser.parse("WEBVTT\n\nNOTE 只有注释\n").lines.isEmpty())
    }
}
