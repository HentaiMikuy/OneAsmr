package com.oneasmr.app.ui.singles

import com.oneasmr.app.data.local.ScrapeStatus
import com.oneasmr.app.data.local.SingleFile
import com.oneasmr.app.data.local.SingleFileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SinglesSortTest {
    private fun row(id: Long, addedAt: Long, titleKey: String, durationMs: Long?) = SingleFileRow(
        file = SingleFile(
            id = id,
            rootFolderUri = "content://tree/singles",
            relativePath = "$id.mp4",
            fileName = "$id.mp4",
            displayTitle = "Title $id",
            titleSortKey = titleKey,
            kind = SingleFileKind.VIDEO,
            youtubeId = null,
            channel = null,
            uploadDate = null,
            durationMs = durationMs,
            sizeBytes = 1L,
            lastModified = 0L,
            thumbSource = null,
            sourceUrl = null,
            scrapeStatus = ScrapeStatus.NOT_SCRAPED,
            missing = false,
            addedAt = addedAt,
            updatedAt = addedAt,
        ),
        isFavorite = false,
        progressFraction = null,
    )

    @Test
    fun `all fields support both directions`() {
        val rows = listOf(
            row(3, 1_000L, "c", 300L),
            row(1, 3_000L, "b", 100L),
            row(2, 2_000L, "a", 200L),
        )
        val expectedAscending = mapOf(
            SinglesOrder.ADDED to listOf(3L, 2L, 1L),
            SinglesOrder.TITLE to listOf(2L, 1L, 3L),
            SinglesOrder.DURATION to listOf(1L, 2L, 3L),
        )
        expectedAscending.forEach { (order, expected) ->
            assertEquals(expected, SinglesSort(order, descending = false).sort(rows).map { it.file.id })
            assertEquals(expected.reversed(), SinglesSort(order, descending = true).sort(rows).map { it.file.id })
        }
    }

    @Test
    fun `unknown durations stay at the end in both directions`() {
        val rows = listOf(
            row(5, 0L, "", null),
            row(3, 0L, "", 300L),
            row(1, 0L, "", 100L),
            row(4, 0L, "", null),
            row(2, 0L, "", 200L),
        )
        assertEquals(
            listOf(1L, 2L, 3L, 4L, 5L),
            SinglesSort(SinglesOrder.DURATION, false).sort(rows).map { it.file.id },
        )
        assertEquals(
            listOf(3L, 2L, 1L, 4L, 5L),
            SinglesSort(SinglesOrder.DURATION, true).sort(rows).map { it.file.id },
        )
    }

    @Test
    fun `equal keys keep stable id order in both directions`() {
        val rows = listOf(3L, 1L, 2L).map { row(it, 1_000L, "same", 100L) }
        for (order in SinglesOrder.entries) {
            for (descending in listOf(false, true)) {
                assertEquals(listOf(1L, 2L, 3L), SinglesSort(order, descending).sort(rows).map { it.file.id })
            }
        }
    }

    @Test
    fun `reselecting reverses direction and switching fields uses their defaults`() {
        val initial = SinglesSort()
        assertEquals(SinglesOrder.ADDED, initial.order)
        assertTrue(initial.descending)
        assertFalse(initial.select(SinglesOrder.ADDED).descending)

        val title = initial.select(SinglesOrder.TITLE)
        assertFalse(title.descending)
        assertTrue(title.select(SinglesOrder.TITLE).descending)
        assertTrue(title.select(SinglesOrder.DURATION).descending)
    }
}
