package com.oneasmr.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec tests for [BoundedLruCache] — the per-work bundled-cover lookup cache. */
class BoundedLruCacheTest {

    @Test
    fun `oldest entry is evicted first when full`() {
        val cache = BoundedLruCache<String, String>(maxEntries = 3)
        cache["a"] = "1"
        cache["b"] = "2"
        cache["c"] = "3"
        cache["d"] = "4" // evicts "a"

        assertEquals(3, cache.size())
        assertNull(cache["a"])
        assertEquals("2", cache["b"])
        assertEquals("4", cache["d"])
    }

    @Test
    fun `access refreshes recency - touched entry survives eviction`() {
        val cache = BoundedLruCache<String, String>(maxEntries = 3)
        cache["a"] = "1"
        cache["b"] = "2"
        cache["c"] = "3"
        assertEquals("1", cache["a"]) // touch a → order b, c, a
        cache["d"] = "4" // evicts "b", not "a"

        assertNull(cache["b"])
        assertEquals("1", cache["a"])
        assertEquals("3", cache["c"])
    }

    @Test
    fun `update moves entry to most recent`() {
        val cache = BoundedLruCache<String, String>(maxEntries = 2)
        cache["a"] = "1"
        cache["b"] = "2"
        cache["a"] = "1-updated" // touch a
        cache["c"] = "3" // evicts "b"

        assertNull(cache["b"])
        assertEquals("1-updated", cache["a"])
        assertEquals("3", cache["c"])
    }

    @Test
    fun `null values are cacheable`() {
        val cache = BoundedLruCache<String, String?>(maxEntries = 2)
        cache["a"] = null
        cache["b"] = "x"
        assertNull(cache["a"]) // negative results cached, not re-looked-up
        assertEquals(2, cache.size())
    }

    @Test
    fun `clear empties`() {
        val cache = BoundedLruCache<String, String>(maxEntries = 3)
        cache["a"] = "1"
        cache["b"] = "2"
        cache.clear()
        assertEquals(0, cache.size())
        assertNull(cache["a"])
    }
}
