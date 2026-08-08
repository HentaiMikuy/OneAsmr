package com.oneasmr.app.domain.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Queue operations (plan Task 18) — pure logic, no media3, no timing.
 * Locks insert-at-position / remove / reorder / replace-all and the
 * startIndex bookkeeping on the immutable [PlayQueue] model.
 */
class PlayQueueOpsTest {

    private fun item(i: Int) = PlayQueueItem(
        sourceScope = "local",
        rjCode = "RJ100200",
        trackIndex = i,
        trackTitle = "track$i.mp3",
        workTitle = "w",
        uri = "content://doc/$i",
    )

    private fun queue(size: Int, startIndex: Int = 0) = PlayQueue(
        workId = "local:RJ100200",
        items = (1..size).map(::item),
        startIndex = startIndex,
    )

    @Test
    fun `insert at position keeps order and bumps start index when inserting before it`() {
        val q = QueueOps.insert(queue(3, startIndex = 2), index = 1, item = item(9))
        assertEquals(listOf(1, 9, 2, 3), q.items.map { it.trackIndex })
        assertEquals(3, q.startIndex)
    }

    @Test
    fun `insert at end appends and leaves start index alone`() {
        val q = QueueOps.insert(queue(3, startIndex = 1), index = 99, item = item(9))
        assertEquals(listOf(1, 2, 3, 9), q.items.map { it.trackIndex })
        assertEquals(1, q.startIndex)
    }

    @Test
    fun `insert at position zero shifts everything`() {
        val q = QueueOps.insert(queue(2, startIndex = 0), index = 0, item = item(9))
        assertEquals(listOf(9, 1, 2), q.items.map { it.trackIndex })
        assertEquals(1, q.startIndex)
    }

    @Test
    fun `remove shifts start index down when removing before it`() {
        val q = QueueOps.removeAt(queue(5, startIndex = 3), index = 1)
        assertEquals(listOf(1, 3, 4, 5), q.items.map { it.trackIndex })
        assertEquals(2, q.startIndex)
    }

    @Test
    fun `remove of the start item clamps to the next item`() {
        val q = QueueOps.removeAt(queue(5, startIndex = 3), index = 3)
        assertEquals(listOf(1, 2, 3, 5), q.items.map { it.trackIndex })
        assertEquals(3, q.startIndex)
    }

    @Test
    fun `remove of the last item leaves start index untouched`() {
        val q = QueueOps.removeAt(queue(4, startIndex = 1), index = 3)
        assertEquals(listOf(1, 2, 3), q.items.map { it.trackIndex })
        assertEquals(1, q.startIndex)
    }

    @Test
    fun `remove everything resets start index to zero`() {
        val q = QueueOps.removeAt(queue(1), index = 0)
        assertEquals(emptyList<Int>(), q.items.map { it.trackIndex })
        assertEquals(0, q.startIndex)
        assertEquals(false, q.isPlayable)
    }

    @Test
    fun `invalid remove index is a no-op on the same instance`() {
        val q = queue(3)
        assertSame(q, QueueOps.removeAt(q, index = 7))
    }

    @Test
    fun `move reorders and start index follows the moved item`() {
        val q = QueueOps.move(queue(4, startIndex = 0), from = 0, to = 3)
        assertEquals(listOf(2, 3, 4, 1), q.items.map { it.trackIndex })
        assertEquals(3, q.startIndex)
    }

    @Test
    fun `move of a non-start item keeps the start index`() {
        val q = QueueOps.move(queue(4, startIndex = 1), from = 2, to = 0)
        assertEquals(listOf(3, 1, 2, 4), q.items.map { it.trackIndex })
        assertEquals(2, q.startIndex)
    }

    @Test
    fun `move to the same position is a no-op on the same instance`() {
        val q = queue(3)
        assertSame(q, QueueOps.move(q, from = 1, to = 1))
    }

    @Test
    fun `replace all resets start to zero`() {
        val q = QueueOps.replaceAll(queue(4, startIndex = 2), listOf(item(7), item(8)))
        assertEquals(listOf(7, 8), q.items.map { it.trackIndex })
        assertEquals(0, q.startIndex)
    }

    // ------------------------------------------------------------------
    // nextIndex: pure mirror of ExoPlayer's media-item transition rules
    // ------------------------------------------------------------------

    @Test
    fun `next index off stops at the end`() {
        assertEquals(2, QueueOps.nextIndex(1, size = 3, RepeatMode.OFF))
        assertEquals(-1, QueueOps.nextIndex(2, size = 3, RepeatMode.OFF))
    }

    @Test
    fun `next index all wraps around`() {
        assertEquals(1, QueueOps.nextIndex(0, size = 3, RepeatMode.ALL))
        assertEquals(0, QueueOps.nextIndex(2, size = 3, RepeatMode.ALL))
    }

    @Test
    fun `next index one stays on the current item`() {
        assertEquals(1, QueueOps.nextIndex(1, size = 3, RepeatMode.ONE))
    }

    @Test
    fun `next index on an empty queue is -1 in every mode`() {
        assertEquals(-1, QueueOps.nextIndex(0, size = 0, RepeatMode.OFF))
        assertEquals(-1, QueueOps.nextIndex(0, size = 0, RepeatMode.ALL))
        assertEquals(-1, QueueOps.nextIndex(0, size = 0, RepeatMode.ONE))
    }
}
