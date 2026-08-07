package com.oneasmr.app.data.local

import androidx.room.Room
import com.oneasmr.app.data.scanner.RoomScanPersister
import com.oneasmr.app.data.scanner.WorkCandidate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Task 15 ReviewDao over REAL in-memory Room: upsert semantics (REPLACE on
 * the workId PK), the DAO as the SINGLE rating validation point (0/6 rejected
 * — plan QA failure path), the live review flow, and the "我标记的作品"
 * joined list ordered by updatedAt desc.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewDaoTest {

    private lateinit var db: OneAsmrDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            OneAsmrDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun commitWork(rjCode: String) {
        runBlocking {
            RoomScanPersister(db).commitWork(WorkCandidate(rjCode, rjCode, rjCode), "content://tree/rootA", 1_000L)
        }
    }

    private fun review(workId: String, rating: Int? = null, progress: ProgressState = ProgressState.none, text: String? = null, updatedAt: Long = 1L) =
        Review(workId, rating, text, progress, updatedAt)

    @Test
    fun `upsert inserts then replaces on the same workId`() = runBlocking {
        val dao = db.reviewDao()
        dao.upsert(review("local:RJ100001", rating = 4, progress = ProgressState.listening, updatedAt = 1L))
        assertEquals(4, dao.getByWorkId("local:RJ100001")!!.rating)

        dao.upsert(review("local:RJ100001", rating = 5, progress = ProgressState.listened, text = "更爱了", updatedAt = 2L))
        val row = dao.getByWorkId("local:RJ100001")!!
        assertEquals(5, row.rating)
        assertEquals(ProgressState.listened, row.progress)
        assertEquals("更爱了", row.reviewText)
        assertEquals(2L, row.updatedAt)
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun `rating out of range is rejected at the dao`() = runBlocking {
        val dao = db.reviewDao()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { dao.upsert(review("local:RJ100001", rating = 0)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { dao.upsert(review("local:RJ100001", rating = 6)) }
        }
        // Nothing was persisted by the rejected writes.
        assertNull(dao.getByWorkId("local:RJ100001"))
        // Valid range still works after rejections.
        dao.upsert(review("local:RJ100001", rating = 1))
        dao.upsert(review("local:RJ100001", rating = 5))
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun `null rating and any progress are accepted`() = runBlocking {
        val dao = db.reviewDao()
        dao.upsert(review("local:RJ100001", rating = null, progress = ProgressState.marked))
        assertEquals(null, dao.getByWorkId("local:RJ100001")!!.rating)
        assertEquals(ProgressState.marked, dao.getByWorkId("local:RJ100001")!!.progress)
    }

    @Test
    fun `getByWorkIdFlow emits the live row`() = runBlocking {
        val dao = db.reviewDao()
        dao.upsert(review("local:RJ100001", rating = 3))
        val emitted = dao.getByWorkIdFlow("local:RJ100001").first()
        assertEquals(3, emitted?.rating)

        dao.upsert(review("local:RJ100001", rating = 2))
        assertEquals(2, dao.getByWorkId("local:RJ100001")!!.rating)
    }

    @Test
    fun `joined list orders by updatedAt desc and joins work display data`() = runBlocking {
        commitWork("RJ100001")
        commitWork("RJ100002")
        commitWork("RJ100003")
        val dao = db.reviewDao()
        dao.upsert(review("local:RJ100001", rating = 5, progress = ProgressState.listened, updatedAt = 100L))
        dao.upsert(review("local:RJ100002", rating = null, progress = ProgressState.marked, text = "看中了", updatedAt = 300L))
        dao.upsert(review("local:RJ100003", rating = 1, progress = ProgressState.none, updatedAt = 200L))

        val rows = dao.getAllJoinedFlow().first()
        assertEquals(listOf("local:RJ100002", "local:RJ100003", "local:RJ100001"), rows.map { it.workId })
        val second = rows[1]
        assertEquals("RJ100003", second.rjCode)
        assertEquals("RJ100003", second.title)
        assertEquals(1, second.rating)
        assertTrue(!second.missing)
        assertEquals("看中了", rows[0].reviewText)
        assertNull(rows[1].reviewText)
        assertEquals("local:RJ100001", rows[2].workId)
    }

    @Test
    fun `joined list keeps a review whose work row is missing`() = runBlocking {
        val dao = db.reviewDao()
        // Review-only orphan: no work row at all.
        dao.upsert(review("local:RJ999999", rating = 4, progress = ProgressState.postponed, text = "orphan", updatedAt = 5L))
        // Missing-flagged work: row exists, folder gone.
        commitWork("RJ100001")
        runBlocking {
            db.workDao().upsert(db.workDao().getById("local:RJ100001")!!.copy(missing = true))
            dao.upsert(review("local:RJ100001", rating = 2, progress = ProgressState.listening, updatedAt = 9L))
        }

        val rows = dao.getAllJoinedFlow().first()
        assertEquals(listOf("local:RJ100001", "local:RJ999999"), rows.map { it.workId })
        assertTrue(rows[0].missing)
        assertTrue(rows[0].title != null)
        // The orphan has no work row: title falls back, cover data null.
        assertNull(rows[1].title)
        assertEquals("RJ999999", rows[1].rjCode)
    }

    @Test
    fun `deleteByWorkId removes only the target row`() = runBlocking {
        val dao = db.reviewDao()
        dao.upsert(review("local:RJ100001", rating = 1))
        dao.upsert(review("local:RJ100002", rating = 2))
        dao.deleteByWorkId("local:RJ100001")
        assertNull(dao.getByWorkId("local:RJ100001"))
        assertEquals(2, dao.getByWorkId("local:RJ100002")!!.rating)
        // Idempotent: deleting again is a no-op.
        dao.deleteByWorkId("local:RJ100001")
        assertEquals(1, dao.getAll().size)
    }
}
