package com.oneasmr.app.ui.player

import com.oneasmr.app.data.local.KeySpec
import com.oneasmr.app.domain.player.PlayQueue
import com.oneasmr.app.domain.player.PlayQueueItem
import com.oneasmr.app.domain.player.QueueOps

/**
 * Pure queue-panel logic (plan Task 21 "队列面板 —— 复用 QueueOps 的重排/插入/移除").
 *
 * The session (Task 18) remains the single source of truth: the panel builds
 * a DISPLAY mirror of the session timeline and computes every mutation through
 * [QueueOps] — then applies the same remove-at/insert-at semantics to the
 * session via `MediaController.moveMediaItem(from, to)` /
 * `removeMediaItem(index)` (ExoPlayer's move is defined exactly as
 * remove-then-insert, the same semantics [QueueOps.move] models). The service
 * persists every session mutation (captureSession -> QueueStore), so the
 * panel edits the persisted queue without ever holding its own copy.
 *
 * All functions are pure JVM — unit-tested without a player instance.
 */
object QueuePanelOps {

    /** One timeline window as the panel sees it (mediaId IS the trackKey). */
    data class Entry(val mediaId: String, val trackTitle: String, val workTitle: String)

    /** Panel row for display; [isCurrent] is patched from the session index. */
    data class QueuePanelItem(
        val index: Int,
        val trackTitle: String,
        val workTitle: String,
        val isCurrent: Boolean,
    )

    /**
     * Builds the [PlayQueue] mirror of the session timeline (workId from the
     * first entry's trackKey; startIndex = the current window). The mirror is
     * display-only — mutations are resolved against it but applied to the
     * session. Unparseable mediaIds keep a passthrough key so the mirror's
     * size/indices always equal the session's.
     */
    fun mirror(entries: List<Entry>, currentIndex: Int): PlayQueue {
        var workId = ""
        val items = entries.mapIndexed { index, e ->
            val parts = KeySpec.parseTrackKey(e.mediaId)
            if (index == 0 && parts != null) {
                workId = KeySpec.workId(parts.sourceScope, parts.rjCode)
            }
            PlayQueueItem(
                sourceScope = parts?.sourceScope ?: "local",
                rjCode = parts?.rjCode ?: e.mediaId,
                trackIndex = parts?.trackIndex ?: (index + 1),
                trackTitle = e.trackTitle,
                workTitle = e.workTitle,
                uri = "",
            )
        }
        return PlayQueue(
            workId = workId,
            items = items,
            startIndex = currentIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0)),
        )
    }

    /**
     * Panel rows with the current item flagged — [currentIndex] is the
     * session's window index (never guessed from the mirror).
     */
    fun rows(entries: List<Entry>, currentIndex: Int): List<QueuePanelItem> =
        entries.mapIndexed { index, e ->
            QueuePanelItem(
                index = index,
                trackTitle = e.trackTitle,
                workTitle = e.workTitle,
                isCurrent = index == currentIndex,
            )
        }

    /**
     * Resolves a move request (from -> to) through [QueueOps.move] semantics
     * (both indices clamped; a same-position or invalid move is a no-op).
     * @return the controller args `(from, to)` when the move changes the
     *   order, null otherwise — the caller then issues exactly one
     *   `moveMediaItem(from, to)` on the session.
     */
    fun resolveMove(queue: PlayQueue, from: Int, to: Int): Pair<Int, Int>? {
        val moved = QueueOps.move(queue, from, to)
        if (moved === queue) return null // invalid index or same position: no-op
        // QueueOps clamped `to` into range — hand the controller the same
        // clamped target so the session mutation matches the mirror exactly.
        return from to to.coerceIn(0, queue.items.lastIndex)
    }

    /**
     * Resolves a remove request through [QueueOps.removeAt] semantics.
     * @return the controller index when legal, null when out of range.
     */
    fun resolveRemove(queue: PlayQueue, index: Int): Int? {
        val removed = QueueOps.removeAt(queue, index)
        return if (removed === queue) null else index
    }
}
