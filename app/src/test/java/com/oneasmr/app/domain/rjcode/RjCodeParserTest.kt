package com.oneasmr.app.domain.rjcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec tests for [RjCodeParser].
 *
 * Contract under test (plan Task 3):
 * - prefixes RJ / BJ / VJ, case-insensitive, followed by exactly 6 OR 8 digits
 * - first match in the string wins
 * - 5- and 9-digit runs MUST NOT match; 7-digit runs MUST NOT match either
 * - BJ/VJ numbers are NOT mixed into the RJ namespace: prefix is preserved and
 *   the local DB key is prefix + digits (see [RjCode.canonical])
 * - kikoeru legacy: original kikoeru-express only handled `RJ(\d{6})`
 *   (filesystem/utils.js `folder.match(/RJ(\d{6})/)`); this parser extends to
 *   RJ/BJ/VJ × 6/8 digits. The 8-digit and BJ/VJ tests below pin that extension.
 */
class RjCodeParserTest {

    // --- 6-digit / 8-digit core ---

    @Test
    fun `six digit rj code parses`() {
        assertEquals(RjCode("RJ", "123456"), RjCodeParser.parse("RJ123456"))
    }

    @Test
    fun `eight digit rj code parses`() {
        assertEquals(RjCode("RJ", "12345678"), RjCodeParser.parse("RJ12345678"))
    }

    @Test
    fun `eight digit code not truncated to six`() {
        // 6-digit alternative must not win over the full 8-digit run
        assertEquals("12345678", RjCodeParser.parse("RJ12345678")?.digits)
    }

    // --- prefix coverage: BJ / VJ ---

    @Test
    fun `bj prefix parses`() {
        assertEquals(RjCode("BJ", "012345"), RjCodeParser.parse("BJ012345"))
    }

    @Test
    fun `vj prefix parses`() {
        assertEquals(RjCode("VJ", "98765432"), RjCodeParser.parse("vj98765432"))
    }

    @Test
    fun `bj eight digit parses`() {
        assertEquals(RjCode("BJ", "11111111"), RjCodeParser.parse("BJ11111111"))
    }

    // --- case insensitivity ---

    @Test
    fun `lowercase prefix normalized to uppercase`() {
        assertEquals("RJ", RjCodeParser.parse("rj123456")?.prefix)
    }

    @Test
    fun `mixed case prefix normalized to uppercase`() {
        assertEquals("RJ", RjCodeParser.parse("Rj12345678")?.prefix)
        assertEquals("VJ", RjCodeParser.parse("vJ123456")?.prefix)
    }

    @Test
    fun `canonical display is normalized prefix plus digits`() {
        assertEquals("RJ123456", RjCodeParser.parse("rj123456")?.canonical)
        assertEquals("VJ98765432", RjCodeParser.parse("vj98765432")?.canonical)
    }

    // --- position of the code in the string ---

    @Test
    fun `code at start of string`() {
        assertEquals("RJ123456", RjCodeParser.parse("RJ123456xyz")?.canonical)
    }

    @Test
    fun `code in middle of string`() {
        assertEquals("RJ123456", RjCodeParser.parse("xxxRJ123456yyy")?.canonical)
    }

    @Test
    fun `code at end of string`() {
        assertEquals("RJ123456", RjCodeParser.parse("yyyRJ123456")?.canonical)
    }

    @Test
    fun `code in folder name with dots`() {
        assertEquals("RJ123456", RjCodeParser.parse("folder.RJ123456.extra")?.canonical)
    }

    // --- no match / defensive inputs ---

    @Test
    fun `no code present returns null`() {
        assertNull(RjCodeParser.parse("no code here"))
    }

    @Test
    fun `empty string returns null`() {
        assertNull(RjCodeParser.parse(""))
    }

    @Test
    fun `null input returns null`() {
        assertNull(RjCodeParser.parse(null))
    }

    @Test
    fun `blank input returns null`() {
        assertNull(RjCodeParser.parse("   \t "))
    }

    @Test
    fun `audio file name with number does not match`() {
        assertNull(RjCodeParser.parse("track01.mp3"))
    }

    // --- 5 / 7 / 9 digit rejection ---

    @Test
    fun `five digit run is rejected`() {
        assertNull(RjCodeParser.parse("RJ12345"))
    }

    @Test
    fun `seven digit run is rejected`() {
        assertNull(RjCodeParser.parse("RJ1234567"))
    }

    @Test
    fun `nine digit run is rejected`() {
        assertNull(RjCodeParser.parse("RJ123456789"))
    }

    @Test
    fun `nine digit run rejected for bj as well`() {
        assertNull(RjCodeParser.parse("BJ123456789"))
    }

    @Test
    fun `five digit run rejected for vj as well`() {
        assertNull(RjCodeParser.parse("VJ12345"))
    }

    @Test
    fun `prefix directly adjacent to another digit is rejected`() {
        // digit immediately before the prefix: not a folder code, reject
        assertNull(RjCodeParser.parse("2RJ123456"))
    }

    // --- first match wins ---

    @Test
    fun `first match wins among multiple codes`() {
        assertEquals("RJ123456", RjCodeParser.parse("RJ123456 RJ654321")?.canonical)
    }

    @Test
    fun `first match wins across different prefixes`() {
        assertEquals("BJ111111", RjCodeParser.parse("BJ111111 RJ222222")?.canonical)
    }

    @Test
    fun `first match wins across digit lengths`() {
        assertEquals("RJ12345678", RjCodeParser.parse("RJ12345678 RJ123456")?.canonical)
    }

    // --- namespace isolation (no BJ/VJ → RJ mixing) ---

    @Test
    fun `bj prefix is preserved in result`() {
        assertEquals("BJ333333", RjCodeParser.parse("BJ333333")?.canonical)
    }

    @Test
    fun `vj prefix is preserved in result`() {
        assertEquals("VJ44444444", RjCodeParser.parse("VJ44444444")?.canonical)
    }

    // --- kikoeru legacy note ---

    @Test
    fun `kikoeru legacy rj6 still works while parser extends to rj8`() {
        // kikoeru-express filesystem/utils.js: folder.match(/RJ(\d{6})/) — RJ + 6 only.
        // Legacy form keeps working…
        assertEquals("RJ123456", RjCodeParser.parse("RJ123456")?.canonical)
        // …and our extension additionally accepts 8-digit RJ.
        assertEquals("RJ12345678", RjCodeParser.parse("RJ12345678")?.canonical)
    }
}
