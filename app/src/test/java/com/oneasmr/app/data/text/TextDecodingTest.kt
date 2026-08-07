package com.oneasmr.app.data.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 14 text decoding: fixture bytes with KNOWN encodings must decode to
 * the exact expected text (plan: "assert the decoded text in tests (fixture
 * bytes with known encodings)"), and the mojibake guard must reject charsets
 * that cannot be byte-faithful.
 */
class TextDecodingTest {

    private fun encode(text: String, charset: String): ByteArray {
        val buffer = java.nio.charset.Charset.forName(charset).encode(text)
        val out = ByteArray(buffer.remaining())
        buffer.get(out)
        return out
    }

    @Test
    fun `utf8 fixture decodes exactly and detects utf8`() {
        val bytes = encode("こんにちは、世界。", "UTF-8")

        assertEquals("UTF-8", TextDecoding.detectEncoding(bytes))
        val decoded = TextDecoding.decodeAndVerify(bytes, "UTF-8")
        assertEquals("こんにちは、世界。", decoded.text)
        assertTrue(decoded.verified)
    }

    @Test
    fun `shift_jis fixture decodes exactly`() {
        val bytes = encode("おやすみなさい、また明日。", "Shift_JIS")

        assertNotNull("juniversalchardet should identify Shift_JIS content", TextDecoding.detectEncoding(bytes))
        val detected = TextDecoding.detectEncoding(bytes)!!
        val decoded = TextDecoding.decodeAndVerify(bytes, detected)
        assertEquals("おやすみなさい、また明日。", decoded.text)
        assertTrue(decoded.verified)
    }

    @Test
    fun `gbk fixture decodes exactly`() {
        val bytes = encode("你好，世界。这是一段中文文本。", "GBK")

        assertNotNull("juniversalchardet should identify GBK/GB18030 content", TextDecoding.detectEncoding(bytes))
        val detected = TextDecoding.detectEncoding(bytes)!!
        val decoded = TextDecoding.decodeAndVerify(bytes, detected)
        assertEquals("你好，世界。这是一段中文文本。", decoded.text)
        assertTrue(decoded.verified)
    }

    @Test
    fun `wrong charset on shift_jis bytes fails strict decode`() {
        val bytes = encode("おやすみなさい、また明日。", "Shift_JIS")

        assertFalse(TextDecoding.verifyRoundTrip(bytes, "UTF-8"))
    }

    @Test
    fun `decode with an incompatible charset throws DecodeFailure`() {
        val bytes = encode("こんにちは", "Shift_JIS")

        val thrown = runCatching { TextDecoding.decode(bytes, "UTF-8") }.exceptionOrNull()
        assertTrue("expected DecodeFailure, got $thrown", thrown is DecodeFailure)
    }

    @Test
    fun `empty file has no detectable encoding`() {
        assertNull(TextDecoding.detectEncoding(ByteArray(0)))
    }

    @Test
    fun `utf8 bom is authoritative over content`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val bytes = bom + encode("hello", "UTF-8")

        assertEquals("UTF-8", TextDecoding.bomEncoding(bytes))
        val decoded = TextDecoding.decodeAndVerify(bytes, "UTF-8")
        assertEquals("hello", decoded.text)
        assertTrue(decoded.verified)
    }

    @Test
    fun `utf16le bom decodes and round-trips`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + encode("テスト", "UTF-16LE")

        assertEquals("UTF-16LE", TextDecoding.bomEncoding(bytes))
        val decoded = TextDecoding.decodeAndVerify(bytes, "UTF-16LE")
        assertEquals("テスト", decoded.text)
        assertTrue(decoded.verified)
    }

    @Test
    fun `corrupt binary bytes never produce verified text`() {
        // Not valid UTF-8 (C3 28 is an illegal continuation), not valid
        // Shift_JIS/GBK/EUC-JP either (leads without valid trail bytes).
        val bytes = byteArrayOf(
            0xC3.toByte(), 0x28.toByte(), 0xE2.toByte(), 0x82.toByte(), 0xAC.toByte(),
            0xC0.toByte(), 0xAF.toByte(), 0xF5.toByte(), 0x80.toByte(), 0x80.toByte(),
        )

        val detected = TextDecoding.detectEncoding(bytes)
        if (detected != null) {
            // Whatever the detector claims, it must not round-trip to garbage.
            val decoded = runCatching { TextDecoding.decodeAndVerify(bytes, detected) }.getOrNull()
            if (decoded != null) {
                assertFalse("corrupt bytes must fail verification, charset=$detected", decoded.verified)
            }
        }
    }

    @Test
    fun `candidates always include the main CJK encodings`() {
        val candidates = TextDecoding.CANDIDATES
        assertTrue(candidates.contains("UTF-8"))
        assertTrue(candidates.contains("Shift_JIS"))
        assertTrue(candidates.contains("GB18030"))
        assertTrue(candidates.contains("EUC-JP"))
    }
}
