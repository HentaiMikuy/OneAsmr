package com.oneasmr.app.data.lyrics

import com.oneasmr.app.data.text.TextFileReader
import java.nio.charset.Charset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 20 LRC loading: raw bytes -> charset auto-detect (juniversalchardet +
 * Task 14 round-trip guard) -> parsed lyrics. Fixture bytes with KNOWN
 * encodings must yield the exact expected text; corrupt/undecodable input
 * yields null, never a crash.
 */
class LrcLoaderTest {

    private fun encode(text: String, charset: String): ByteArray {
        val buffer = Charset.forName(charset).encode(text)
        val out = ByteArray(buffer.remaining())
        buffer.get(out)
        return out
    }

    private fun loader(bytes: ByteArray): LrcLoader = LrcLoader(TextFileReader { bytes })

    @Test
    fun `utf8 lrc parses to exact lyrics`() = runTest {
        val bytes = encode("[ti:测试]\n[00:01.00]第一行歌词\n[00:02.00]第二行歌词\n", "UTF-8")
        val lyrics = loader(bytes).load("content://x")!!

        assertEquals("测试", lyrics.metadata.title)
        assertEquals(listOf("第一行歌词", "第二行歌词"), lyrics.lines.map { it.text })
        assertEquals(listOf(1_000L, 2_000L), lyrics.lines.map { it.timestampMs })
    }

    @Test
    fun `shift_jis lrc decodes without mojibake`() = runTest {
        val bytes = encode("[00:01.00]おやすみなさい\n[00:02.00]また明日\n", "Shift_JIS")
        val lyrics = loader(bytes).load("content://x")!!

        assertEquals(listOf("おやすみなさい", "また明日"), lyrics.lines.map { it.text })
    }

    @Test
    fun `gbk lrc decodes without mojibake`() = runTest {
        val bytes = encode("[00:01.00]中文歌词测试\n[00:02.00]第二行\n", "GBK")
        val lyrics = loader(bytes).load("content://x")!!

        assertEquals(listOf("中文歌词测试", "第二行"), lyrics.lines.map { it.text })
    }

    @Test
    fun `corrupt rows survive loading with skipped count`() = runTest {
        val bytes = encode("[00:01.00]好行\ngarbage\n[00:99.00]坏行\n[00:02.00]好行二\n", "UTF-8")
        val lyrics = loader(bytes).load("content://x")!!

        assertEquals(listOf("好行", "好行二"), lyrics.lines.map { it.text })
        assertEquals(2, lyrics.skippedLines)
    }

    @Test
    fun `read failure returns null`() = runTest {
        val failing = LrcLoader(TextFileReader { throw java.io.IOException("boom") })
        assertNull(failing.load("content://x"))
    }

    @Test
    fun `undecodable bytes return null`() = runTest {
        val garbage = ByteArray(512) { i -> (i * 37 % 256).toByte() }
        assertNull(loader(garbage).load("content://x"))
    }

    @Test
    fun `metadata only lrc returns null`() = runTest {
        val bytes = encode("[ti:只有标题]\n[ar:某人]\n", "UTF-8")
        assertNull(loader(bytes).load("content://x"))
    }

    @Test
    fun `empty file returns null`() = runTest {
        assertNull(loader(ByteArray(0)).load("content://x"))
    }

    @Test
    fun `utf8 with bom detected and parsed`() = runTest {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val body = encode("[00:01.00]带BOM行\n", "UTF-8")
        val lyrics = loader(bom + body).load("content://x")!!

        assertEquals(listOf("带BOM行"), lyrics.lines.map { it.text })
        assertTrue(lyrics.lines.isNotEmpty())
        assertNotNull(lyrics)
    }
}
