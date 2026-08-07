package com.oneasmr.app.data.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the numeric-aware ordering (plan Task 6: track1 < track2 < track10,
 * 对齐 kikoeru natural-orderby). Every expected order below is hand-derived
 * from the documented rules; a comparator change must update BOTH.
 */
class NaturalOrderComparatorTest {

    private fun assertOrdered(expected: List<String>) {
        assertEquals(expected, expected.sortedWith(NaturalOrderComparator))
    }

    private fun assertLess(a: String, b: String) {
        assertTrue("expected '$a' < '$b'", NaturalOrderComparator.compare(a, b) < 0)
    }

    @Test
    fun `acceptance track1 track2 track10 natural order`() {
        assertOrdered(listOf("track1.mp3", "track2.mp3", "track3.flac", "track10.mp3", "track20.mp3"))
    }

    @Test
    fun `acceptance pairwise track1 less track2 less track10`() {
        assertLess("track1.mp3", "track2.mp3")
        assertLess("track2.mp3", "track10.mp3")
        assertLess("track1", "track10")
    }

    @Test
    fun `digit runs compare by numeric value anywhere in the name`() {
        assertLess("album2", "album10")
        assertLess("file 2 final", "file 10 final")
        assertLess("ch2", "ch12")
    }

    @Test
    fun `numbers sort before text`() {
        assertOrdered(listOf("1.mp3", "2.mp3", "10.mp3", "a.mp3", "b.mp3"))
    }

    @Test
    fun `equal values with different zero padding break ties deterministically`() {
        // 1 == 01 numerically; tie broken by raw string: "01" < "1"
        assertLess("file01", "file1")
        assertOrdered(listOf("file01", "file1", "file02", "file2", "file10"))
    }

    @Test
    fun `case-insensitive primary with case-sensitive tiebreak`() {
        assertLess("abc", "ABD") // ignoreCase: abc < abd
        assertLess("ABC", "abc") // equal ignoreCase -> case-sensitive: 'A'(65) < 'a'(97)
        assertOrdered(listOf("a.mp3", "B.mp3", "c.mp3"))
    }

    @Test
    fun `prefix sorts before longer string`() {
        assertLess("a", "ab")
        assertLess("track", "track1")
    }

    @Test
    fun `empty string sorts first`() {
        assertLess("", "a")
        assertEquals(-1, NaturalOrderComparator.compare("", "a"))
        assertEquals(0, NaturalOrderComparator.compare("", ""))
    }

    @Test
    fun `same string compares equal and antisymmetry holds`() {
        assertEquals(0, NaturalOrderComparator.compare("track10.mp3", "track10.mp3"))
        assertEquals(
            NaturalOrderComparator.compare("track10.mp3", "track2.mp3"),
            -NaturalOrderComparator.compare("track2.mp3", "track10.mp3"),
        )
    }

    @Test
    fun `mixed kitchen-sink list is totally ordered without throwing`() {
        assertOrdered(
            listOf("01", "1", "2", "10", "A1", "a01", "a1", "a2", "a10", "b"),
        )
    }

    @Test
    fun `repeatability determinism across runs`() {
        val names = listOf("track1", "track2", "track10", "album2", "album10", "a", "A")
        val once = names.sortedWith(NaturalOrderComparator)
        val twice = names.sortedWith(NaturalOrderComparator)
        assertEquals(once, twice)
    }
}
