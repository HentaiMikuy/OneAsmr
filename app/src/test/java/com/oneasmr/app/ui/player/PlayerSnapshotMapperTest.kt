package com.oneasmr.app.ui.player

import com.oneasmr.app.domain.player.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the pure session->UI derivation rules (plan Task 21): mini bar
 * visibility, current-item identity from the trackKey, buffered clamping and
 * the progress fractions. No Android runtime, no real-time waits (repo flake
 * convention).
 */
class PlayerSnapshotMapperTest {

    private fun map(
        connected: Boolean = true,
        count: Int = 3,
        index: Int = 1,
        mediaId: String? = "local:RJ123456:2",
        isPlaying: Boolean = true,
        state: Int = PlayerSnapshot.STATE_READY,
        positionMs: Long = 5_000,
        bufferedMs: Long = 7_000,
        durationMs: Long = 10_000,
        repeatMode: RepeatMode = RepeatMode.OFF,
        shuffle: Boolean = false,
        speed: Float = 1f,
    ) = PlayerSnapshotMapper.map(
        connected = connected,
        mediaItemCount = count,
        currentIndex = index,
        currentMediaId = mediaId,
        trackTitle = "track2.mp3",
        workTitle = "RJ123456 测试作品",
        isPlaying = isPlaying,
        playbackState = state,
        positionMs = positionMs,
        bufferedPositionMs = bufferedMs,
        durationMs = durationMs,
        repeatMode = repeatMode,
        shuffleEnabled = shuffle,
        speed = speed,
    )

    @Test
    fun `hasSession requires connection and content`() {
        assertFalse("disconnected -> no session", map(connected = false).hasSession)
        assertFalse("empty queue -> no session", map(count = 0, mediaId = null).hasSession)
        assertFalse("idle current item -> no session", map(mediaId = null).hasSession)
        assertTrue("connected with content -> session", map().hasSession)
    }

    @Test
    fun `current item identity comes from the trackKey`() {
        val s = map(mediaId = "local:RJ123456:3")
        assertEquals("local:RJ123456", s.workId)
        assertEquals("RJ123456", s.rjCode)
        assertEquals(3, s.trackIndex)

        val remote = map(mediaId = "srv1:RJ654321:7")
        assertEquals("srv1:RJ654321", remote.workId)
        assertEquals("RJ654321", remote.rjCode)
        assertEquals(7, remote.trackIndex)

        assertNull("unparseable mediaId -> no identity", map(mediaId = "garbage").workId)
    }

    @Test
    fun `buffered position is clamped to the duration`() {
        val over = map(bufferedMs = 12_000, durationMs = 10_000)
        assertEquals(10_000L, over.bufferedPositionMs)
        val under = map(bufferedMs = -5, durationMs = 10_000)
        assertEquals(0L, under.bufferedPositionMs)
        assertEquals(1f, over.bufferedFraction, 0f)
    }

    @Test
    fun `progress fractions are bounded`() {
        val s = map(positionMs = 5_000, bufferedMs = 8_000, durationMs = 10_000)
        assertEquals(0.5f, s.progressFraction, 0f)
        assertEquals(0.8f, s.bufferedFraction, 0f)

        val unknown = map(durationMs = 0)
        assertEquals(0f, unknown.progressFraction, 0f)
        assertEquals(0f, unknown.bufferedFraction, 0f)
    }

    @Test
    fun `buffering flag mirrors the media3 state`() {
        assertTrue(map(state = PlayerSnapshot.STATE_BUFFERING).isBuffering)
        assertFalse(map(state = PlayerSnapshot.STATE_READY).isBuffering)
    }

    @Test
    fun `negative positions and index are sanitized`() {
        val s = map(positionMs = -100, index = -2, durationMs = 0)
        assertEquals(0L, s.positionMs)
        assertEquals(0, s.currentIndex)
    }
}
