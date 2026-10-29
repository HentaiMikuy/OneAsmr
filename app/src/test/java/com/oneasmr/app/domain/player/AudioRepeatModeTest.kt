package com.oneasmr.app.domain.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 音频四档循环（顺序 / 列表循环 / 单曲循环 / 播完暂停）。
 * 「播完暂停」是 media3 repeat 之外的 player 级开关，所以重点锁三件事：
 * 档位顺序、它只和 OFF 基档组合、[AudioRepeatMode.of] 的合并规则。
 */
class AudioRepeatModeTest {

    @Test
    fun `cycle goes off all one pause then back to off`() {
        assertEquals(AudioRepeatMode.ALL, AudioRepeatMode.OFF.next())
        assertEquals(AudioRepeatMode.ONE, AudioRepeatMode.ALL.next())
        assertEquals(AudioRepeatMode.PAUSE_AT_END, AudioRepeatMode.ONE.next())
        assertEquals(AudioRepeatMode.OFF, AudioRepeatMode.PAUSE_AT_END.next())
    }

    @Test
    fun `pause at end is the only mode that flips the player flag`() {
        assertEquals(RepeatMode.OFF, AudioRepeatMode.PAUSE_AT_END.baseMode)
        assertEquals(
            listOf(AudioRepeatMode.PAUSE_AT_END),
            AudioRepeatMode.entries.filter { it.pauseAtEnd },
        )
    }

    @Test
    fun `merge keeps the flag only on the off base mode`() {
        assertEquals(AudioRepeatMode.PAUSE_AT_END, AudioRepeatMode.of(RepeatMode.OFF, pauseAtEnd = true))
        assertEquals(AudioRepeatMode.OFF, AudioRepeatMode.of(RepeatMode.OFF, pauseAtEnd = false))
        // 非 OFF 档与「播完暂停」互斥：开关再真也只按基档算（服务端会主动清开关）。
        assertEquals(AudioRepeatMode.ALL, AudioRepeatMode.of(RepeatMode.ALL, pauseAtEnd = true))
        assertEquals(AudioRepeatMode.ONE, AudioRepeatMode.of(RepeatMode.ONE, pauseAtEnd = true))
    }

    @Test
    fun `fromBase drops the pause dimension`() {
        RepeatMode.entries.forEach { base ->
            val mode = AudioRepeatMode.fromBase(base)
            assertEquals(base, mode.baseMode)
            assertFalse(mode.pauseAtEnd)
        }
    }
}
