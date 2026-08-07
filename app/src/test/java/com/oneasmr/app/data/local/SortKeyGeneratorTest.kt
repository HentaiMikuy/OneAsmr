package com.oneasmr.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for the CJK sort key generator (TinyPinyin + kana romaji).
 */
class SortKeyGeneratorTest {

    @Test
    fun `chinese becomes pinyin`() {
        assertEquals("zhongwen", SortKeyGenerator.generate("中文"))
    }

    @Test
    fun `chinese phrase becomes concatenated pinyin`() {
        assertEquals("zhiyuxi", SortKeyGenerator.generate("治愈系"))
    }

    @Test
    fun `hiragana becomes romaji`() {
        assertEquals("oyasumi", SortKeyGenerator.generate("おやすみ"))
        assertEquals("de", SortKeyGenerator.generate("で"))
    }

    @Test
    fun `katakana becomes romaji`() {
        assertEquals("arisu", SortKeyGenerator.generate("アリス"))
        // Prolonged sound mark (ー) is transparent in sort keys.
        assertEquals("garu", SortKeyGenerator.generate("ガール"))
    }

    @Test
    fun `mixed cjk and kana stays ordered`() {
        assertEquals("cuimianyinshengdeoyasumi", SortKeyGenerator.generate("催眠音声でおやすみ"))
    }

    @Test
    fun `latin is lowercased`() {
        assertEquals("asmr", SortKeyGenerator.generate("ASMR"))
        assertEquals("rj123456", SortKeyGenerator.generate("RJ123456"))
    }

    @Test
    fun `mixed latin and cjk`() {
        assertEquals("asmrzhiyu", SortKeyGenerator.generate("ASMR治愈"))
    }

    @Test
    fun `empty input yields empty key`() {
        assertEquals("", SortKeyGenerator.generate(""))
    }

    @Test
    fun `deterministic for same input`() {
        assertEquals(SortKeyGenerator.generate("星空に包まれて"), SortKeyGenerator.generate("星空に包まれて"))
    }

    @Test
    fun `sort order groups chinese phonetically`() {
        // "阿" (a) sorts before "中" (zhong) — pinyin order, not codepoint order.
        val a = SortKeyGenerator.generate("阿")
        val zhong = SortKeyGenerator.generate("中")
        assertEquals("a", a)
        assert(a < zhong) { "pinyin key '$a' should sort before '$zhong'" }
    }
}
