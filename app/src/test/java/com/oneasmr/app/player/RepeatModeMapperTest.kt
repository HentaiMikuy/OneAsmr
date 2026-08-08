package com.oneasmr.app.player

import androidx.media3.common.Player
import com.oneasmr.app.domain.player.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** Repeat mode <-> media3 int mapping (plan Task 18). */
class RepeatModeMapperTest {

    @Test
    fun `maps to media3 constants`() {
        assertEquals(Player.REPEAT_MODE_OFF, RepeatMode.OFF.toMedia3())
        assertEquals(Player.REPEAT_MODE_ALL, RepeatMode.ALL.toMedia3())
        assertEquals(Player.REPEAT_MODE_ONE, RepeatMode.ONE.toMedia3())
    }

    @Test
    fun `round trips through media3 ints`() {
        RepeatMode.entries.forEach { mode ->
            assertEquals(mode, mode.toMedia3().toRepeatMode())
        }
    }

    @Test
    fun `unknown media3 int falls back to off`() {
        assertEquals(RepeatMode.OFF, (-1).toRepeatMode())
        assertEquals(RepeatMode.OFF, 99.toRepeatMode())
    }
}
