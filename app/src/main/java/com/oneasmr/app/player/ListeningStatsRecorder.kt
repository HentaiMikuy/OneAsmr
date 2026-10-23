package com.oneasmr.app.player

import com.oneasmr.app.data.local.KeySpec
import com.oneasmr.app.data.local.ListeningSession
import com.oneasmr.app.data.local.ListeningSessionDao
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persists actual listening time independently from playback-position memory.
 * One row is one continuous actually-playing interval: pause ends the row,
 * changing tracks closes the old row and starts a new one. Duration comes from
 * a monotonic clock, so seeking and playback speed cannot inflate statistics.
 *
 * The active row is refreshed every [flushIntervalMs] (15s by default), bounding
 * data loss if the process is killed without a final pause callback. All calls
 * are serialized because Media3 listener callbacks and the periodic flush can
 * otherwise race the active-row replacement.
 */
class ListeningStatsRecorder(
    private val dao: ListeningSessionDao,
    private val scope: CoroutineScope,
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    private val elapsedClock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val flushIntervalMs: Long = 15_000L,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    data class Snapshot(
        val trackKey: String?,
        val trackTitle: String = "",
        val workTitle: String = "",
        val isPlaying: Boolean = false,
    )

    private data class Active(
        val session: ListeningSession,
        val elapsedAt: Long,
    )

    private val mutex = Mutex()
    private var active: Active? = null
    private var ticker: Job? = null

    init {
        require(flushIntervalMs > 0L) { "flushIntervalMs must be positive" }
    }

    /** Starts periodic active-row persistence; replaces an existing ticker. */
    fun start(snapshotProvider: () -> Snapshot) {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                update(snapshotProvider())
                delay(flushIntervalMs)
            }
        }
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
    }

    /** Media3 event entry point; DAO work never runs inside the listener. */
    fun onPlaybackChanged(snapshot: Snapshot) {
        scope.launch { update(snapshot) }
    }

    /** Visible for deterministic tests and periodic/event-driven snapshots. */
    suspend fun update(snapshot: Snapshot) {
        val elapsedNow = elapsedClock()
        mutex.withLock {
            val trackKey = snapshot.trackKey
            val parts = trackKey?.let(KeySpec::parseTrackKey)
            if (!snapshot.isPlaying || trackKey == null || parts == null) {
                active?.let { advance(it, elapsedNow) }
                active = null
                return@withLock
            }

            val current = active
            if (current != null && current.session.trackKey != trackKey) {
                advance(current, elapsedNow)
                active = null
            }

            val running = active
            if (running == null) {
                val startedAt = wallClock()
                val workTitle = if (parts.sourceScope == KeySpec.SINGLE_SOURCE) {
                    snapshot.trackTitle
                } else {
                    snapshot.workTitle.ifBlank { snapshot.trackTitle }
                }
                val session = ListeningSession(
                    id = idFactory(),
                    trackKey = trackKey,
                    workId = KeySpec.workId(parts.sourceScope, parts.rjCode),
                    trackTitle = snapshot.trackTitle,
                    workTitle = workTitle,
                    startedAt = startedAt,
                    endedAt = startedAt,
                    durationMs = 0L,
                )
                dao.upsert(session)
                active = Active(session, elapsedNow)
            } else {
                active = advance(running, elapsedNow)
            }
        }
    }

    /** Adds only monotonic elapsed time and persists the continuous interval. */
    private suspend fun advance(current: Active, elapsedNow: Long): Active {
        val delta = (elapsedNow - current.elapsedAt).coerceAtLeast(0L)
        val duration = current.session.durationMs + delta
        val updated = current.session.copy(
            endedAt = current.session.startedAt + duration,
            durationMs = duration,
        )
        dao.upsert(updated)
        return Active(updated, elapsedNow)
    }
}
