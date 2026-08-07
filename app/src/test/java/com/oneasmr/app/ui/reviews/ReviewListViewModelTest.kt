package com.oneasmr.app.ui.reviews

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.oneasmr.app.data.local.OneAsmrDatabase
import com.oneasmr.app.data.local.ProgressState
import com.oneasmr.app.data.local.Review
import com.oneasmr.app.data.scanner.RoomScanPersister
import com.oneasmr.app.data.scanner.WorkCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Task 15 ReviewListViewModel over REAL Room: the "我标记的作品" list ordered
 * by updatedAt desc with joined work display data, missing works included.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewListViewModelTest {

    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var db: OneAsmrDatabase
    private lateinit var viewModel: ReviewListViewModel

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            OneAsmrDatabase::class.java,
        ).build()
        viewModel = ReviewListViewModel(reviewDao = db.reviewDao())
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.coroutineContext[Job]?.cancel()
        scheduler.advanceUntilIdle()
        db.close()
        Dispatchers.resetMain()
    }

    private fun commitWork(rjCode: String) {
        kotlinx.coroutines.runBlocking {
            RoomScanPersister(db).commitWork(WorkCandidate(rjCode, rjCode, rjCode), "content://tree/rootA", 1_000L)
        }
    }

    private fun review(workId: String, rating: Int?, progress: ProgressState, text: String? = null, updatedAt: Long) {
        kotlinx.coroutines.runBlocking {
            db.reviewDao().upsert(Review(workId, rating, text, progress, updatedAt))
        }
    }

    @Test
    fun `empty library shows no reviews`() = runTest(scheduler) {
        assertEquals(emptyList<Any>(), viewModel.reviews.value)
    }

    @Test
    fun `reviews are ordered by updatedAt desc with joined titles`() = runTest(scheduler) {
        commitWork("RJ100001")
        commitWork("RJ100002")
        commitWork("RJ100003")
        review("local:RJ100001", rating = 5, progress = ProgressState.listened, text = "五星好评", updatedAt = 100L)
        review("local:RJ100002", rating = null, progress = ProgressState.marked, updatedAt = 300L)
        review("local:RJ100003", rating = 1, progress = ProgressState.none, updatedAt = 200L)

        val rows = viewModel.reviews.first { it.size == 3 }
        assertEquals(listOf("local:RJ100002", "local:RJ100003", "local:RJ100001"), rows.map { it.workId })
        assertEquals("RJ100002", rows[0].title)
        assertNull(rows[0].reviewText)
        assertEquals("五星好评", rows[2].reviewText)
        assertEquals("RJ100003", rows[1].rjCode)
    }

    @Test
    fun `an update reorders the list live`() = runTest(scheduler) {
        commitWork("RJ100001")
        commitWork("RJ100002")
        review("local:RJ100001", rating = 5, progress = ProgressState.listened, updatedAt = 100L)
        review("local:RJ100002", rating = 4, progress = ProgressState.listening, updatedAt = 200L)
        viewModel.reviews.first { it.size == 2 }
        assertEquals("local:RJ100002", viewModel.reviews.value.first().workId)

        review("local:RJ100001", rating = 5, progress = ProgressState.listened, updatedAt = 500L)
        val after = viewModel.reviews.first { it.first().workId == "local:RJ100001" }
        assertEquals(500L, after.first().updatedAt)
    }

    @Test
    fun `missing and review-only works stay visible`() = runTest(scheduler) {
        commitWork("RJ100001")
        kotlinx.coroutines.runBlocking {
            db.workDao().upsert(db.workDao().getById("local:RJ100001")!!.copy(missing = true))
            db.reviewDao().upsert(Review("local:RJ100001", rating = 2, null, ProgressState.listening, 1L))
            // Review-only orphan: no work row at all.
            db.reviewDao().upsert(Review("local:RJ999999", rating = 4, "orphan", ProgressState.postponed, 2L))
        }

        val rows = viewModel.reviews.first { it.size == 2 }
        // Orphan has the newer updatedAt (2L > 1L) -> rows[0]; it has no work row.
        assertNull(rows[0].title)
        assertEquals("RJ999999", rows[0].rjCode)
        assertTrue(rows[1].missing)
        assertEquals("RJ100001", rows[1].rjCode)
    }

    @Test
    fun `deleting a review removes it from the list`() = runTest(scheduler) {
        commitWork("RJ100001")
        review("local:RJ100001", rating = 3, progress = ProgressState.none, updatedAt = 1L)
        viewModel.reviews.first { it.size == 1 }

        kotlinx.coroutines.runBlocking {
            db.reviewDao().deleteByWorkId("local:RJ100001")
        }
        viewModel.reviews.first { it.isEmpty() }
    }
}
