package com.oneasmr.app.data.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure diff rules of [RescanDiffComputer] (plan Task 7): a work is marked
 * missing only when its root was fully enumerated, it was not discovered this
 * run, and it is not already missing. Plain JUnit — no Room, no fs.
 */
class RescanDiffComputerTest {

    private val rootA = "content://tree/rootA"
    private val rootB = "content://tree/rootB"

    @Test
    fun `disappeared work under a fully scanned root is marked missing`() {
        val missing = RescanDiffComputer.computeMissing(
            discovered = setOf("local:RJ111111"),
            stored = listOf(
                StoredWorkRef("local:RJ111111", rootA, missing = false),
                StoredWorkRef("local:RJ222222", rootA, missing = false),
            ),
            completeRootUris = setOf(rootA),
        )
        assertEquals(listOf("local:RJ222222"), missing)
    }

    @Test
    fun `discovered work is never marked missing`() {
        val missing = RescanDiffComputer.computeMissing(
            discovered = setOf("local:RJ111111"),
            stored = listOf(StoredWorkRef("local:RJ111111", rootA, missing = false)),
            completeRootUris = setOf(rootA),
        )
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `already missing work is not re-marked`() {
        val missing = RescanDiffComputer.computeMissing(
            discovered = setOf("local:RJ111111"),
            stored = listOf(StoredWorkRef("local:RJ222222", rootA, missing = true)),
            completeRootUris = setOf(rootA),
        )
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `work under a root that was not fully enumerated is never marked`() {
        val missing = RescanDiffComputer.computeMissing(
            discovered = setOf(),
            stored = listOf(StoredWorkRef("local:RJ222222", rootB, missing = false)),
            completeRootUris = setOf(rootA),
        )
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `work whose root is not among the scanned roots is never marked`() {
        // A root removed from settings: its works were not part of this run.
        val missing = RescanDiffComputer.computeMissing(
            discovered = setOf(),
            stored = listOf(StoredWorkRef("local:RJ222222", rootB, missing = false)),
            completeRootUris = setOf(rootA),
        )
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `per root granularity marks only works of enumerated roots`() {
        val missing = RescanDiffComputer.computeMissing(
            discovered = setOf("local:RJ111111"),
            stored = listOf(
                StoredWorkRef("local:RJ222222", rootA, missing = false),
                StoredWorkRef("local:RJ333333", rootB, missing = false),
                StoredWorkRef("local:RJ444444", rootA, missing = false),
            ),
            completeRootUris = setOf(rootA),
        ).toSet()
        assertEquals(setOf("local:RJ222222", "local:RJ444444"), missing)
    }

    @Test
    fun `empty inputs produce an empty result`() {
        assertTrue(
            RescanDiffComputer.computeMissing(
                discovered = emptySet(),
                stored = emptyList(),
                completeRootUris = emptySet(),
            ).isEmpty(),
        )
    }
}
