package com.oneasmr.app.data.remote

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RequestPacer unit tests. Deterministic per the repo flake convention:
 * a fake clock advanced only by the recording fake paceDelay (NO real-time
 * waits), so the spacing assertions do not depend on the test scheduler.
 */
class RequestPacerTest {

    /** Deterministic clock, advanced only by tests / the fake paceDelay. */
    private class FakeClock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
        fun advance(by: Long) {
            now += by
        }
    }

    private class Harness(startAt: Long = 0L, intervalMillis: Long = 1_000L) {
        val clock = FakeClock(startAt)
        val delays = mutableListOf<Long>()
        val pacer = RequestPacer(
            clock = clock,
            paceDelay = { ms ->
                delays += ms
                clock.advance(ms)
            },
            minRequestIntervalMillis = intervalMillis,
        )
    }

    @Test
    fun `second pace waits only the remainder of the interval`() = runTest {
        val h = Harness()

        h.pacer.pace() // first-ever request: never delayed
        h.pacer.pace() // back-to-back: owes the full interval
        assertEquals(listOf(1_000L), h.delays)

        h.clock.advance(400) // 400ms of "real work" between requests
        h.pacer.pace() // owes only what is left of the interval
        assertEquals(listOf(1_000L, 600L), h.delays)
    }

    @Test
    fun `concurrent pace callers serialize - no two complete within the interval`() = runTest {
        val h = Harness()
        val completions = mutableListOf<Long>()

        val jobs = List(4) {
            launch {
                h.pacer.pace()
                completions += h.clock()
            }
        }
        jobs.forEach { it.join() }

        assertEquals(4, completions.size)
        completions.zipWithNext().forEach { (a, b) ->
            assertTrue("gap ${b - a}ms must be >= 1000ms", b - a >= 1_000L)
        }
    }

    @Test
    fun `prime makes the first pace immediate`() = runTest {
        val h = Harness(startAt = 5_000L)

        h.pacer.prime()
        h.pacer.pace()

        assertEquals(emptyList<Long>(), h.delays)
        assertEquals(5_000L, h.clock.now) // no time was burned waiting
    }
}
