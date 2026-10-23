package com.oneasmr.app.player

import com.oneasmr.app.data.local.ListeningSession
import com.oneasmr.app.data.local.ListeningSessionDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningStatsRecorderTest {
    private class FakeDao : ListeningSessionDao {
        val rows = mutableMapOf<String, ListeningSession>()

        override suspend fun upsert(session: ListeningSession) {
            rows[session.id] = session
        }

        override fun observeAll(): Flow<List<ListeningSession>> = flowOf(sortedRows())
        override suspend fun getAll(): List<ListeningSession> = sortedRows()
        override suspend fun clearAll() = rows.clear()

        private fun sortedRows(): List<ListeningSession> =
            rows.values.sortedByDescending { it.startedAt }
    }

    private val dao = FakeDao()
    private var nextId = 0

    private fun TestScope.recorder(flushIntervalMs: Long = 15_000L): ListeningStatsRecorder =
        ListeningStatsRecorder(
            dao = dao,
            scope = this,
            wallClock = { 1_000_000L + testScheduler.currentTime },
            elapsedClock = { testScheduler.currentTime },
            flushIntervalMs = flushIntervalMs,
            idFactory = { "session-${++nextId}" },
        )

    private fun playing(
        key: String = "local:RJ123456:1",
        trackTitle: String = "track 1",
        workTitle: String = "work A",
    ) = ListeningStatsRecorder.Snapshot(
        trackKey = key,
        trackTitle = trackTitle,
        workTitle = workTitle,
        isPlaying = true,
    )

    @Test
    fun `duration uses elapsed playing time and stops at pause`() = runTest {
        val recorder = recorder()
        val snapshot = playing()

        recorder.update(snapshot)
        advanceTimeBy(45_000L)
        recorder.update(snapshot)
        advanceTimeBy(15_000L)
        recorder.update(snapshot.copy(isPlaying = false))
        advanceTimeBy(300_000L)
        recorder.update(snapshot.copy(isPlaying = false))

        val session = dao.rows.values.single()
        assertEquals(60_000L, session.durationMs)
        assertEquals(1_000_000L, session.startedAt)
        assertEquals(1_060_000L, session.endedAt)
        assertEquals("local:RJ123456", session.workId)
        recorder.stop()
    }

    @Test
    fun `track change closes old interval and starts a new one`() = runTest {
        val recorder = recorder()
        val first = playing()
        val second = playing(key = "local:RJ123456:2", trackTitle = "track 2")

        recorder.update(first)
        advanceTimeBy(30_000L)
        recorder.update(second)
        advanceTimeBy(20_000L)
        recorder.update(second)

        assertEquals(2, dao.rows.size)
        assertEquals(30_000L, dao.rows.getValue("session-1").durationMs)
        assertEquals(20_000L, dao.rows.getValue("session-2").durationMs)
        assertEquals("track 2", dao.rows.getValue("session-2").trackTitle)
        recorder.stop()
    }

    @Test
    fun `paused or malformed snapshots never start history`() = runTest {
        val recorder = recorder()

        recorder.update(playing().copy(isPlaying = false))
        recorder.update(playing().copy(trackKey = "not-a-track-key"))
        advanceTimeBy(60_000L)
        recorder.update(playing().copy(trackKey = null))

        assertTrue(dao.rows.isEmpty())
        recorder.stop()
    }

    @Test
    fun `single-file ranking title uses the file title rather than channel subtitle`() = runTest {
        val recorder = recorder()
        val single = playing(
            key = "single:42:1",
            trackTitle = "rain video",
            workTitle = "channel name",
        )

        recorder.update(single)
        advanceTimeBy(1_000L)
        recorder.update(single)

        val session = dao.rows.values.single()
        assertEquals("single:42", session.workId)
        assertEquals("rain video", session.workTitle)
        recorder.stop()
    }

    @Test
    fun `periodic ticker persists the active interval without a pause event`() = runTest {
        val recorder = recorder(flushIntervalMs = 15_000L)
        val snapshot = playing()
        recorder.start { snapshot }
        runCurrent()
        assertEquals(0L, dao.rows.getValue("session-1").durationMs)

        advanceTimeBy(15_000L)
        runCurrent()

        assertEquals(15_000L, dao.rows.getValue("session-1").durationMs)
        recorder.stop()
    }
}
