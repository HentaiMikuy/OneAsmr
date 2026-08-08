package com.oneasmr.app.domain.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** Repeat mode semantics (plan Task 18): cycle + storage mapping. */
class RepeatModeTest {

    @Test
    fun `cycle goes off all one off`() {
        assertEquals(RepeatMode.ALL, RepeatMode.OFF.next())
        assertEquals(RepeatMode.ONE, RepeatMode.ALL.next())
        assertEquals(RepeatMode.OFF, RepeatMode.ONE.next())
    }

    @Test
    fun `storage round trip preserves the mode`() {
        RepeatMode.entries.forEach { mode ->
            assertEquals(mode, RepeatMode.fromStored(RepeatMode.toStored(mode)))
        }
    }

    @Test
    fun `unknown stored value falls back to off`() {
        assertEquals(RepeatMode.OFF, RepeatMode.fromStored(null))
        assertEquals(RepeatMode.OFF, RepeatMode.fromStored(42))
        assertEquals(RepeatMode.OFF, RepeatMode.fromStored(-1))
    }
}
