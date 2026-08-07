package com.oneasmr.app.data.repository

import java.util.LinkedHashMap

/**
 * Tiny thread-safe LRU cache used for the per-work bundled-cover lookup
 * results (plan Task 10: "兜底查找结果按作品缓存，禁止在列表滚动中重复 IO").
 * The map is deliberately BOUNDED ([maxEntries], default 256): evicting the
 * least-recently-used entry keeps memory flat no matter how many works the
 * library holds. Negative results (null) are cached too — a folder with no
 * cover must not be re-listed on every scroll frame.
 */
class BoundedLruCache<K, V>(private val maxEntries: Int = 256) {

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val map = object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean =
            size > maxEntries
    }

    @Synchronized
    operator fun get(key: K): V? = map[key]

    /** True when the key is present — a cached value may legitimately be null. */
    @Synchronized
    fun contains(key: K): Boolean = map.containsKey(key)

    @Synchronized
    operator fun set(key: K, value: V) {
        map[key] = value
    }

    @Synchronized
    fun size(): Int = map.size

    @Synchronized
    fun clear() {
        map.clear()
    }
}
