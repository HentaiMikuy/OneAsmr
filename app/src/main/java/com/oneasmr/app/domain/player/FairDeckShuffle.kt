package com.oneasmr.app.domain.player

import kotlin.random.Random

/**
 * Fair-deck shuffle (plan Task 18): a Fisher-Yates permutation of the
 * queue indices with an injectable [Random] (seeded → deterministic tests,
 * no real-time dependence).
 *
 * A shuffle order is a PERMUTATION of the indices, so walking it plays
 * every item exactly once before any repeat — the "no repeats until
 * exhausted" invariant that the shuffle QA asserts. ExoPlayer consumes the
 * permutation via `DefaultShuffleOrder(indices, seed)`: while it walks the
 * order ExoPlayer does not repeat an item; when exhausted it generates a
 * fresh permutation from [seed] (still a permutation, invariant holds).
 */
object FairDeckShuffle {

    /**
     * Fisher-Yates over 0..[size)-1. Returns an empty array for size 0 —
     * a valid (vacuous) permutation.
     */
    fun permutation(size: Int, random: Random = Random.Default): IntArray {
        val indices = IntArray(size) { it }
        for (i in size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = indices[i]
            indices[i] = indices[j]
            indices[j] = tmp
        }
        return indices
    }

    /** Seed for the order applied to the player (one seed per enable). */
    fun newSeed(): Long = Random.nextLong()
}
