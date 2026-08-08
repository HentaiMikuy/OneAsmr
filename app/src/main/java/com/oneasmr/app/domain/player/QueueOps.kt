package com.oneasmr.app.domain.player

/**
 * Pure queue operations (plan Task 18) on the immutable [PlayQueue] model —
 * insert-at-position / remove / reorder / replace-all, with the model's
 * [PlayQueue.startIndex] kept consistent through every mutation.
 *
 * During live playback the media3 player is the single source of truth for
 * the CURRENT queue (the UI/service mutate the player directly); these ops
 * are what the UI builds from (Task 21 queue panel) and what pure-logic
 * tests lock down. [PlayQueue.startIndex] stays meaningful for the pre-play
 * phase: it points at the item playback starts from.
 */
object QueueOps {

    /** Inserts [item] at [index] (0-based, clamped to 0..size). */
    fun insert(queue: PlayQueue, index: Int, item: PlayQueueItem): PlayQueue {
        val at = index.coerceIn(0, queue.items.size)
        val items = queue.items.toMutableList().apply { add(at, item) }
        return queue.copy(
            items = items,
            startIndex = if (at <= queue.startIndex) queue.startIndex + 1 else queue.startIndex,
        )
    }

    /** Removes the item at [index]; returns the queue unchanged for an invalid index. */
    fun removeAt(queue: PlayQueue, index: Int): PlayQueue {
        if (index !in queue.items.indices) return queue
        val items = queue.items.toMutableList().apply { removeAt(index) }
        val start = when {
            items.isEmpty() -> 0
            index < queue.startIndex -> queue.startIndex - 1
            index == queue.startIndex -> index.coerceAtMost(items.lastIndex)
            else -> queue.startIndex
        }
        return queue.copy(items = items, startIndex = start)
    }

    /**
     * Reorders: moves the item at [from] to [to] (0-based, both clamped).
     * The start index is recomputed by identity so it always points at the
     * same item, wherever the move pushed it.
     */
    fun move(queue: PlayQueue, from: Int, to: Int): PlayQueue {
        if (from !in queue.items.indices) return queue
        val target = to.coerceIn(0, queue.items.lastIndex)
        if (from == target) return queue
        val items = queue.items.toMutableList()
        val startItem = queue.startIndex.takeIf { it in items.indices }?.let { items[it] }
        items.add(target, items.removeAt(from))
        val start = startItem?.let { items.indexOf(it).coerceAtLeast(0) } ?: 0
        return queue.copy(items = items, startIndex = start)
    }

    /** Replaces the whole queue with [items]; start resets to 0. */
    fun replaceAll(queue: PlayQueue, items: List<PlayQueueItem>): PlayQueue =
        queue.copy(items = items, startIndex = 0)

    /**
     * Index of the item that plays after [currentIndex] given [repeatMode]
     * — the pure mirror of ExoPlayer's media-item transition rules:
     * - [RepeatMode.ONE]: stay on the current item.
     * - [RepeatMode.OFF]: next, or -1 (end) when already at the last item.
     * - [RepeatMode.ALL]: next, wrapping to 0 after the last item.
     * Shuffle does not change the index arithmetic — the shuffle ORDER (a
     * permutation of indices, see [FairDeckShuffle]) decides which physical
     * index plays next; this function answers the structural question.
     */
    fun nextIndex(currentIndex: Int, size: Int, repeatMode: RepeatMode): Int {
        if (size == 0) return -1
        return when (repeatMode) {
            RepeatMode.ONE -> currentIndex
            RepeatMode.OFF -> if (currentIndex >= size - 1) -1 else currentIndex + 1
            RepeatMode.ALL -> (currentIndex + 1) % size
        }
    }
}
