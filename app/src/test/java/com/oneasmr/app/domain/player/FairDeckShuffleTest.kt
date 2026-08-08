package com.oneasmr.app.domain.player

import java.util.BitSet
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fair-deck shuffle (plan Task 18): the generated order is a PERMUTATION,
 * so walking it plays every item exactly once before any repeat — the
 * "shuffle no-repeat until exhausted" QA invariant. Deterministic under a
 * fixed seed: no real-time dependence, no flake.
 */
class FairDeckShuffleTest {

    @Test
    fun `permutation covers every index exactly once`() {
        repeat(20) { size ->
            val order = FairDeckShuffle.permutation(size, Random(42)).toList()
            assertEquals(size, order.size)
            assertEquals((0 until size).toSet(), order.toSet())
        }
    }

    @Test
    fun `empty queue yields an empty permutation`() {
        assertTrue(FairDeckShuffle.permutation(0, Random(1)).isEmpty())
    }

    @Test
    fun `same seed produces the same order`() {
        val a = FairDeckShuffle.permutation(10, Random(7)).toList()
        val b = FairDeckShuffle.permutation(10, Random(7)).toList()
        assertEquals(a, b)
    }

    @Test
    fun `different seeds shuffle differently`() {
        val a = FairDeckShuffle.permutation(10, Random(1)).toList()
        val b = FairDeckShuffle.permutation(10, Random(2)).toList()
        assertNotEquals(a, b)
    }

    @Test
    fun `walking the order never repeats until exhausted`() {
        val size = 5
        val order = FairDeckShuffle.permutation(size, Random(99))
        val played = BitSet(size)
        var playedCount = 0
        var repeatsBeforeExhaustion = 0
        // Simulate playback: start at a random position in the cycle, then
        // step through the order wrapping only after the whole deck is played.
        var cycle = 0
        var position = 0
        val totalSteps = size * 3 + 2 // more than one full cycle
        repeat(totalSteps) {
            val next = order[position % size]
            if (played.get(next)) repeatsBeforeExhaustion++
            played.set(next)
            playedCount++
            position++
            if (playedCount == size) {
                // exhausted: assert the full deck was seen, then start a fresh cycle
                assertTrue("exhausted with $playedCount played", playedCount == size)
                assertEquals(size, played.cardinality())
                played.clear()
                playedCount = 0
                cycle++
            }
        }
        assertEquals(0, repeatsBeforeExhaustion)
        assertTrue(cycle >= 3)
    }

    @Test
    fun `newSeed produces fresh seeds`() {
        val a = FairDeckShuffle.newSeed()
        val b = FairDeckShuffle.newSeed()
        // Not guaranteed distinct, but overwhelmingly likely; guards against a
        // constant-seed regression which WOULD make every session identical.
        assertNotEquals(a, b)
    }
}
