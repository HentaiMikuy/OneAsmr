package com.oneasmr.app.player

import com.oneasmr.app.data.local.PlaybackState
import com.oneasmr.app.data.local.PlaybackStateDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Debounced playback-position writer (plan Task 19): 5s write cadence over a
 * 1s poll loop, forced flush on pause, last-write-wins, never clobbers a
 * remembered position with an unprepared item (duration <= 0). Fully virtual:
 * the TestCoroutineScheduler drives both loops and the injected clock — no
 * real-time waits (repo flake convention).
 */
class PlaybackProgressWriterTest {

    /** In-memory PlaybackStateDao fake (last-write-wins via the map). */
    private class FakePlaybackStateDao : PlaybackStateDao {
        val rows = mutableMapOf<String, PlaybackState>()
        override suspend fun upsert(state: PlaybackState) {
            rows[state.trackKey] = state
        }

        override suspend fun get(trackKey: String): PlaybackState? = rows[trackKey]
        override suspend fun delete(trackKey: String) {
            rows.remove(trackKey)
        }

        override suspend fun getAllForWork(prefix: String): List<PlaybackState> =
            rows.values.filter { it.trackKey.startsWith(prefix) }

        override fun getAllForWorkFlow(prefix: String): Flow<List<PlaybackState>> =
            flowOf(getAllForWorkSafe(prefix))

        private fun getAllForWorkSafe(prefix: String): List<PlaybackState> =
            rows.values.filter { it.trackKey.startsWith(prefix) }

        override suspend fun deleteForWorkPrefix(prefix: String) {
            rows.keys.filter { it.startsWith(prefix) }.forEach { rows.remove(it) }
        }
    }

    private var sample = PlaybackProgressWriter.Sample(null, 0L, 0L)
    private val dao = FakePlaybackStateDao()

    private fun TestScope.writer(
        cadenceMs: Long = 5_000L,
        pollMs: Long = 1_000L,
    ): PlaybackProgressWriter {
        val w = PlaybackProgressWriter(
            dao = dao,
            scope = this,
            positionProvider = { sample },
            pollMs = pollMs,
            cadenceMs = cadenceMs,
            clock = { testScheduler.currentTime },
        )
        w.start()
        return w
    }

    private fun playing(key: String, position: Long, duration: Long) {
        sample = PlaybackProgressWriter.Sample(key, position, duration)
    }

    @Test
    fun `writes at most once per cadence despite per-second polls`() = runTest {
        val writer = writer()
        playing("local:RJ123456:3", 1_000L, 600_000L)
        advanceTimeBy(1_000) // poll 1: first write (outside debounce window)
        runCurrent()
        assertEquals(1, writer.writeCount)

        advanceTimeBy(3_000) // polls 2-4 within the 5s window: no writes
        runCurrent()
        assertEquals(1, writer.writeCount)

        advanceTimeBy(2_000) // t=6000: 5s after the first write -> second write
        runCurrent()
        assertEquals(2, writer.writeCount)
        assertEquals(1_000L, dao.rows["local:RJ123456:3"]!!.positionMs)
        writer.stop()
    }

    @Test
    fun `intermediate positions never persist - last write wins`() = runTest {
        val writer = writer()
        playing("local:RJ123456:3", 10_000L, 600_000L)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(10_000L, dao.rows["local:RJ123456:3"]!!.positionMs)

        playing("local:RJ123456:3", 20_000L, 600_000L)
        advanceTimeBy(4_000) // still inside the window: 20s must NOT be written
        runCurrent()
        assertEquals(10_000L, dao.rows["local:RJ123456:3"]!!.positionMs)

        playing("local:RJ123456:3", 30_000L, 600_000L)
        advanceTimeBy(1_000) // cadence elapsed: the 30s sample lands
        runCurrent()
        assertEquals(30_000L, dao.rows["local:RJ123456:3"]!!.positionMs)
        writer.stop()
    }

    @Test
    fun `pause flush forces a write through the debounce window`() = runTest {
        val writer = writer()
        playing("local:RJ123456:3", 42_000L, 600_000L)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, writer.writeCount)

        // Pause 1s after the last write: flush() must still persist.
        playing("local:RJ123456:3", 43_000L, 600_000L)
        writer.flush()
        assertEquals(2, writer.writeCount)
        assertEquals(43_000L, dao.rows["local:RJ123456:3"]!!.positionMs)
        writer.stop()
    }

    @Test
    fun `unprepared item never clobbers a remembered position`() = runTest {
        val writer = writer()
        // Player knows nothing yet (duration <= 0): nothing written.
        playing("local:RJ123456:3", 0L, 0L)
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(0, writer.writeCount)
        assertNull(dao.rows["local:RJ123456:3"])

        // A remembered row exists; a zero-duration sample must not wipe it.
        dao.rows["local:RJ123456:3"] = PlaybackState("local:RJ123456:3", 50_000L, 600_000L, 1L)
        playing("local:RJ123456:3", 0L, 0L)
        writer.flush()
        assertEquals(50_000L, dao.rows["local:RJ123456:3"]!!.positionMs)
        writer.stop()
    }

    @Test
    fun `position clamps to duration`() = runTest {
        val writer = writer()
        playing("local:RJ123456:3", 999_999L, 600_000L)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(600_000L, dao.rows["local:RJ123456:3"]!!.positionMs)
        writer.stop()
    }

    @Test
    fun `track change persists the old key and writes the new one next cadence`() = runTest {
        val writer = writer()
        playing("local:RJ123456:1", 5_000L, 60_000L)
        advanceTimeBy(1_000)
        runCurrent()
        playing("local:RJ123456:2", 1_000L, 60_000L)
        advanceTimeBy(4_000)
        runCurrent()
        // Old key still has its last write; new key not yet written (window).
        assertEquals(5_000L, dao.rows["local:RJ123456:1"]!!.positionMs)
        assertNull(dao.rows["local:RJ123456:2"])
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1_000L, dao.rows["local:RJ123456:2"]!!.positionMs)
        writer.stop()
    }

    @Test
    fun `stop ends the loop and a later flush still writes`() = runTest {
        val writer = writer()
        playing("local:RJ123456:3", 5_000L, 60_000L)
        advanceTimeBy(1_000)
        runCurrent()
        writer.stop()
        playing("local:RJ123456:3", 7_000L, 60_000L)
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(dao.rows["local:RJ123456:3"]!!.positionMs == 5_000L)
        writer.flush()
        assertEquals(7_000L, dao.rows["local:RJ123456:3"]!!.positionMs)
        writer.stop()
    }
}
