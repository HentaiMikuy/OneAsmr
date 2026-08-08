package com.oneasmr.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the queue-panel derivation (plan Task 21): the display mirror of the
 * session timeline and the QueueOps-based move/remove resolution. The SESSION
 * stays the single source of truth — these functions only compute what the
 * panel shows and which controller command a gesture maps to.
 */
class QueuePanelOpsTest {

    private val entries = listOf(
        QueuePanelOps.Entry("local:RJ123456:1", "track1.mp3", "测试作品"),
        QueuePanelOps.Entry("local:RJ123456:2", "track2.mp3", "测试作品"),
        QueuePanelOps.Entry("local:RJ123456:3", "track3.mp3", "测试作品"),
        QueuePanelOps.Entry("local:RJ123456:4", "track4.mp3", "测试作品"),
    )

    @Test
    fun `mirror keeps session order and work identity`() {
        val mirror = QueuePanelOps.mirror(entries, currentIndex = 2)
        assertEquals(4, mirror.items.size)
        assertEquals("local:RJ123456", mirror.workId)
        assertEquals(2, mirror.startIndex)
        assertEquals("track3.mp3", mirror.items[2].trackTitle)
        assertEquals(3, mirror.items[2].trackIndex)
    }

    @Test
    fun `mirror handles an empty timeline`() {
        val mirror = QueuePanelOps.mirror(emptyList(), currentIndex = 0)
        assertEquals(0, mirror.items.size)
        assertEquals(0, mirror.startIndex)
        assertTrue(!mirror.isPlayable)
    }

    @Test
    fun `rows flag only the current index`() {
        val rows = QueuePanelOps.rows(entries, currentIndex = 2)
        assertEquals(listOf(false, false, true, false), rows.map { it.isCurrent })
    }

    @Test
    fun `move resolves through QueueOps and changes order`() {
        val mirror = QueuePanelOps.mirror(entries, currentIndex = 1)
        val move = QueuePanelOps.resolveMove(mirror, from = 0, to = 2)
        assertNotNull(move)
        assertEquals(0, move!!.first)
        assertEquals(2, move.second)

        // QueueOps.move semantics: remove-at then insert-at.
        val moved = com.oneasmr.app.domain.player.QueueOps.move(mirror, 0, 2)
        assertEquals("track2.mp3", moved.items[0].trackTitle)
        assertEquals("track3.mp3", moved.items[1].trackTitle)
        assertEquals("track1.mp3", moved.items[2].trackTitle)
    }

    @Test
    fun `move with invalid or equal indices is a no-op`() {
        val mirror = QueuePanelOps.mirror(entries, currentIndex = 1)
        assertNull("negative from -> null", QueuePanelOps.resolveMove(mirror, -1, 2))
        assertNull("same position -> null", QueuePanelOps.resolveMove(mirror, 1, 1))
    }

    @Test
    fun `move clamps the target like QueueOps`() {
        val mirror = QueuePanelOps.mirror(entries, currentIndex = 1)
        // QueueOps.move clamps `to` into range: 9 -> last index (3).
        val move = QueuePanelOps.resolveMove(mirror, from = 0, to = 9)
        assertNotNull(move)
        assertEquals(0, move!!.first)
        assertEquals(3, move.second)
    }

    @Test
    fun `remove resolves only in range`() {
        val mirror = QueuePanelOps.mirror(entries, currentIndex = 3)
        assertEquals(1, QueuePanelOps.resolveRemove(mirror, 1))
        assertNull(QueuePanelOps.resolveRemove(mirror, 4))
        assertNull(QueuePanelOps.resolveRemove(mirror, -1))
    }
}
