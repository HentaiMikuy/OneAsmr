package com.oneasmr.app.player

import com.oneasmr.app.data.local.ProgressState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Six-state progress toggle from the media notification (plan Task 17). */
class ProgressStateCycleTest {

    @Test
    fun `cycles through all six states in declaration order`() {
        var current = ProgressState.none
        val seen = mutableListOf<ProgressState>()
        repeat(6) {
            current = ProgressStateCycle.next(current)
            seen += current
        }
        assertEquals(
            listOf(
                ProgressState.marked,
                ProgressState.listening,
                ProgressState.listened,
                ProgressState.replay,
                ProgressState.postponed,
                ProgressState.none,
            ),
            seen,
        )
    }

    @Test
    fun `toggling never stays on the same state`() {
        ProgressState.entries.forEach { from ->
            val next = ProgressStateCycle.next(from)
            assertEquals(1, (ProgressState.entries.indexOf(next) - ProgressState.entries.indexOf(from) + 6) % 6)
        }
    }

    @Test
    fun `six toggles return to the starting state`() {
        var current = ProgressState.listening
        repeat(6) { current = ProgressStateCycle.next(current) }
        assertEquals(ProgressState.listening, current)
    }
}
