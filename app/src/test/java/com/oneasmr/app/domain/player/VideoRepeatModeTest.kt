package com.oneasmr.app.domain.player

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoRepeatModeTest {

    @Test
    fun `cycle includes pause at end without changing audio repeat cycle`() {
        assertEquals(VideoRepeatMode.ALL, VideoRepeatMode.OFF.next())
        assertEquals(VideoRepeatMode.ONE, VideoRepeatMode.ALL.next())
        assertEquals(VideoRepeatMode.PAUSE_AT_END, VideoRepeatMode.ONE.next())
        assertEquals(VideoRepeatMode.OFF, VideoRepeatMode.PAUSE_AT_END.next())
        assertEquals(RepeatMode.OFF, RepeatMode.ONE.next())
    }

    @Test
    fun `storage round trip preserves every video mode`() {
        VideoRepeatMode.entries.forEach { mode ->
            assertEquals(mode, VideoRepeatMode.fromStored(VideoRepeatMode.toStored(mode)))
        }
    }

    @Test
    fun `pause at end disables repeat and requests end pause`() {
        assertEquals(RepeatMode.OFF, VideoRepeatMode.PAUSE_AT_END.baseMode)
        assertEquals(
            listOf(VideoRepeatMode.PAUSE_AT_END),
            VideoRepeatMode.entries.filter { it.pauseAtEnd },
        )
    }

    @Test
    fun `unknown stored value falls back to off`() {
        assertEquals(VideoRepeatMode.OFF, VideoRepeatMode.fromStored(null))
        assertEquals(VideoRepeatMode.OFF, VideoRepeatMode.fromStored(42))
        assertEquals(VideoRepeatMode.OFF, VideoRepeatMode.fromStored(-1))
    }
}
