package com.oneasmr.app.data.local

import androidx.paging.PagingSource
import androidx.room.Room
import com.oneasmr.app.data.scanner.RoomScanPersister
import com.oneasmr.app.data.scanner.WorkCandidate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Task 15 filter dimension of the library paging query (DAO-level, never
 * in-memory): rated-only, per-progress-state, "none" (incl. review-less
 * works), and composition with search + sort — all through the REAL Room
 * PagingSource.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkDaoFilterTest {

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

    private fun review(workId: String, rating: Int?, progress: ProgressState) {
        runBlocking {
            db.reviewDao().upsert(Review(workId, rating, null, progress, 1L))
        }
    }

    private suspend fun loadAll(filter: WorkFilter?, order: WorkOrder = WorkOrder.ID, keyword: String? = null): List<WorkListItem> {
        val source = db.workDao().pagingSource(order, descending = false, keyword = keyword, randomSeed = 1L, filter = filter)
        val seen = mutableListOf<WorkListItem>()
        var nextKey: Int? = null
        do {
            val params = if (nextKey == null) {
                PagingSource.LoadParams.Refresh<Int>(null, 10, false)
            } else {
                PagingSource.LoadParams.Append<Int>(nextKey!!, 10, false)
            }
            val result = source.load(params) as PagingSource.LoadResult.Page<Int, WorkListItem>
            seen += result.data
            nextKey = result.nextKey
        } while (nextKey != null)
        return seen
    }

    @Test
    fun `no filter returns every work`() = runBlocking {
        commitWork("RJ100001")
        commitWork("RJ100002")
        commitWork("RJ100003")
        assertEquals(3, loadAll(filter = null).size)
    }

    @Test
    fun `rated filter returns only works with a rating`() = runBlocking {
        commitWork("RJ100001")
        commitWork("RJ100002")
        commitWork("RJ100003")
        // RJ100001: rated 5. RJ100002: review row but NO rating. RJ100003: no review at all.
        review("local:RJ100001", rating = 5, progress = ProgressState.listened)
        review("local:RJ100002", rating = null, progress = ProgressState.marked)

        val rated = loadAll(filter = WorkFilter.Rated)
        assertEquals(listOf("local:RJ100001"), rated.map { it.id })
    }

    @Test
    fun `progress filter returns only works in that state`() = runBlocking {
        commitWork("RJ100001")
        commitWork("RJ100002")
        commitWork("RJ100003")
        commitWork("RJ100004")
        review("local:RJ100001", rating = 5, progress = ProgressState.listening)
        review("local:RJ100002", rating = null, progress = ProgressState.listening)
        review("local:RJ100003", rating = 1, progress = ProgressState.listened)
        // RJ100004: no review.

        val listening = loadAll(filter = WorkFilter.Progress(ProgressState.listening))
        assertEquals(listOf("local:RJ100001", "local:RJ100002"), listening.map { it.id })
        assertEquals(ProgressState.listening, listening[0].progress)
    }

    @Test
    fun `none progress filter includes explicit none and review-less works`() = runBlocking {
        commitWork("RJ100001")
        commitWork("RJ100002")
        commitWork("RJ100003")
        review("local:RJ100001", rating = 4, progress = ProgressState.none)
        review("local:RJ100002", rating = 4, progress = ProgressState.listened)

        val none = loadAll(filter = WorkFilter.Progress(ProgressState.none))
        // RJ100001 (explicit none) + RJ100003 (no review row) — NOT RJ100002.
        assertEquals(listOf("local:RJ100001", "local:RJ100003"), none.map { it.id })
    }

    @Test
    fun `rated filter composes with keyword search`() = runBlocking {
        commitWork("RJ100001")
        commitWork("RJ100002")
        review("local:RJ100001", rating = 5, progress = ProgressState.none)
        review("local:RJ100002", rating = null, progress = ProgressState.marked)

        // "RJ100001" as a title hit (LIKE fallback path on Robolectric).
        val hits = loadAll(filter = WorkFilter.Rated, keyword = "RJ100001")
        assertEquals(listOf("local:RJ100001"), hits.map { it.id })
    }

    @Test
    fun `filter composes with a non-id sort order`() = runBlocking {
        commitWork("RJ100003")
        commitWork("RJ100001")
        commitWork("RJ100002")
        review("local:RJ100001", rating = 5, progress = ProgressState.listening)
        review("local:RJ100003", rating = 2, progress = ProgressState.listening)
        // RJ100002 has no review — excluded by the filter.
        runBlocking {
            db.workDao().upsert(db.workDao().getById("local:RJ100001")!!.copy(titleSortKey = "zzz"))
            db.workDao().upsert(db.workDao().getById("local:RJ100003")!!.copy(titleSortKey = "aaa"))
        }

        val sorted = loadAll(filter = WorkFilter.Progress(ProgressState.listening), order = WorkOrder.TITLE_SORT_KEY)
        assertEquals(listOf("local:RJ100003", "local:RJ100001"), sorted.map { it.id })
    }
}
