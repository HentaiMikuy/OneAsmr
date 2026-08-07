package com.oneasmr.app.ui.library

import com.oneasmr.app.ui.library.SearchIntentClassifier.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 13 hit-rule classification (pure JVM): full-string (RJ|BJ|VJ)?\d{6,8}
 * is a direct code lookup (with prefix = one probe; bare digits = all three
 * prefixes), anything else is an FTS query. The 7-digit case classifies as a
 * code lookup whose probes MUST miss (DB keys are 6/8 digits) — the caller
 * then falls back to FTS (locked by SearchViewModelTest).
 */
class SearchIntentClassifierTest {

    private fun direct(input: String) = SearchIntentClassifier.classify(input) as Intent.DirectCode
    private fun fts(input: String) = SearchIntentClassifier.classify(input) as Intent.FtsQuery

    @Test
    fun `full code with prefix is a single direct lookup`() {
        assertEquals(listOf("RJ123456"), direct("RJ123456").codes)
        assertEquals(listOf("BJ012345"), direct("BJ012345").codes)
        assertEquals(listOf("VJ12345678"), direct("VJ12345678").codes)
    }

    @Test
    fun `lowercase prefix normalizes to uppercase`() {
        assertEquals(listOf("RJ123456"), direct("rj123456").codes)
        assertEquals(listOf("VJ12345678"), direct("vj12345678").codes)
    }

    @Test
    fun `bare 6 and 8 digit strings probe all three prefixes`() {
        assertEquals(listOf("RJ123456", "BJ123456", "VJ123456"), direct("123456").codes)
        assertEquals(listOf("RJ12345678", "BJ12345678", "VJ12345678"), direct("12345678").codes)
    }

    @Test
    fun `7 digit input is a code lookup that will miss and fall back to fts`() {
        // 7-digit runs match the (RJ|BJ|VJ)?\d{6,8} pattern but no DB key can
        // exist for them — the ViewModel probes (miss) then runs FTS.
        assertEquals(listOf("RJ1234567", "BJ1234567", "VJ1234567"), direct("1234567").codes)
        assertEquals(listOf("RJ1234567"), direct("RJ1234567").codes)
    }

    @Test
    fun `surrounding whitespace is trimmed before classification`() {
        assertEquals(listOf("RJ123456"), direct("  RJ123456  ").codes)
        assertEquals(Intent.FtsQuery("催眠音声"), fts("  催眠音声  "))
    }

    @Test
    fun `non code inputs are fts queries`() {
        for (input in listOf(
            "催眠音声", "おやすみ", "hypnosis", "12345", "RJ12345",
            "RJ123456 extra", "RJ123456789", "!@#$", "a", "RJ 123456",
        )) {
            assertTrue("expected FTS for '$input'", SearchIntentClassifier.classify(input) is Intent.FtsQuery)
        }
    }

    @Test
    fun `blank and null inputs are empty fts queries`() {
        assertEquals("", fts("").term)
        assertEquals("", fts("   ").term)
        assertEquals("", (SearchIntentClassifier.classify(null) as Intent.FtsQuery).term)
    }

    @Test
    fun `mixed alphanumeric with a code inside is not a direct lookup`() {
        // Only a FULL-string match qualifies; embedded codes search via FTS.
        assertTrue(SearchIntentClassifier.classify("test RJ123456") is Intent.FtsQuery)
        assertTrue(SearchIntentClassifier.classify("RJ123456-01") is Intent.FtsQuery)
    }
}
