package com.oneasmr.app.player

import android.util.Log
import com.oneasmr.app.data.local.PlaybackState
import com.oneasmr.app.data.local.PlaybackStateDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Single-writer, last-write-wins persistence of playback positions (plan
 * Task 19).
 *
 * - A [poll] loop samples [positionProvider] every [pollMs] (cheap in-memory
 *   reads of the player) and records the latest sample as the pending write.
 * - Actual DB writes happen AT MOST once per [cadenceMs] (debounced) plus one
 *   forced [flush] on pause/stop/expiry — so progress tracking can never slow
 *   down playback (plan must-not: "不因进度写入频率拖慢播放").
 * - One [PlaybackStateDao] writer, last write wins; stale intermediate
 *   positions are never persisted.
 *
 * Determinism: [scope], [clock] and both intervals are injected so unit tests
 * drive the whole cadence on a virtual scheduler — no real-time waits (repo
 * flake convention, same shape as [SleepTimerController]).
 */
class PlaybackProgressWriter(
    private val dao: PlaybackStateDao,
    private val scope: CoroutineScope,
    private val positionProvider: () -> Sample,
    private val pollMs: Long = 1_000L,
    private val cadenceMs: Long = 5_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** One snapshot of the player: trackKey (mediaId), position, duration. */
    data class Sample(
        val trackKey: String?,
        val positionMs: Long,
        val durationMs: Long,
    )

    private var job: Job? = null
    private var lastWriteAt: Long? = null
    private var pending: Sample = Sample(trackKey = null, positionMs = 0L, durationMs = 0L)

    /** Number of DB writes performed (test/evidence channel). */
    var writeCount: Int = 0
        private set

    /** Starts the poll loop; replaces any running one. */
    fun start() {
        stop()
        job = scope.launch {
            while (true) {
                delay(pollMs)
                pending = positionProvider()
                maybeWrite(force = false)
            }
        }
    }

    /** Stops the poll loop; the pending position is NOT lost (see [flush]). */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Immediate best-effort write (pause / stop / sleep-timer expiry): samples
     * the player once more and forces the write through the debounce window —
     * the last known position always reaches the database.
     */
    suspend fun flush() {
        pending = positionProvider()
        maybeWrite(force = true)
    }

    private suspend fun maybeWrite(force: Boolean) {
        val sample = pending
        val key = sample.trackKey ?: return
        // No duration = the item is not prepared yet; nothing meaningful to
        // remember (and we must never clobber a remembered position with 0).
        if (sample.durationMs <= 0L) return
        val now = clock()
        val last = lastWriteAt
        if (!force && last != null && now - last < cadenceMs) return
        lastWriteAt = now
        writeCount += 1
        // Evidence channel (device QA): the written values land in logcat so
        // "written position" assertions don't rely on the DB pull alone.
        Log.i(TAG, "position saved: key=$key pos=${sample.positionMs}ms/${sample.durationMs}ms (write #$writeCount)")
        dao.upsert(
            PlaybackState(
                trackKey = key,
                positionMs = sample.positionMs.coerceIn(0L, sample.durationMs),
                durationMs = sample.durationMs,
                updatedAt = now,
            ),
        )
    }

    private companion object {
        const val TAG = "PlaybackProgressWriter"
    }
}
