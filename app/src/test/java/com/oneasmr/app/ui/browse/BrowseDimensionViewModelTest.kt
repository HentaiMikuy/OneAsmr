package com.oneasmr.app.ui.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.oneasmr.app.data.local.Circle
import com.oneasmr.app.data.local.OneAsmrDatabase
import com.oneasmr.app.data.local.ScrapeStatus
import com.oneasmr.app.data.local.Tag
import com.oneasmr.app.data.local.Va
import com.oneasmr.app.data.local.Work
import com.oneasmr.app.data.local.WorkTag
import com.oneasmr.app.data.local.WorkVa
import com.oneasmr.app.navigation.Routes
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Task 16 BrowseDimensionViewModel over REAL Room with the virtual scheduler
 * (repo flake convention — the Room emissions land on real executor threads,
 * assertions suspend on the flow instead of real-time waits).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseDimensionViewModelTest {

    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var db: OneAsmrDatabase

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            OneAsmrDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        // Eagerly/WhileSubscribed collectors unwind on the virtual dispatcher.
        if (::viewModel.isInitialized) {
            viewModel.viewModelScope.coroutineContext[Job]?.cancel()
        }
        scheduler.advanceUntilIdle()
        db.close()
        Dispatchers.resetMain()
    }

    private lateinit var viewModel: BrowseDimensionViewModel

    private fun viewModelFor(dimension: String) {
        viewModel = BrowseDimensionViewModel(
            savedStateHandle = SavedStateHandle(mapOf(Routes.BROWSE_ARG_DIMENSION to dimension)),
            circleDao = db.circleDao(),
            tagDao = db.tagDao(),
            vaDao = db.vaDao(),
        )
    }

    private fun work(id: String, circleId: String? = null) = Work(
        id = id,
        rootFolderUri = "content://tree/rootA",
        relativeDir = id,
        title = "作品 $id",
        titleSortKey = id,
        circleId = circleId,
        nsfw = false,
        releaseDate = null,
        dlCount = null,
        price = null,
        reviewCount = null,
        rateCount = null,
        rateAverage2dp = null,
        rateCountDetailJson = null,
        seriesName = null,
        scrapeStatus = ScrapeStatus.NOT_SCRAPED,
        missing = false,
        addedAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun `circle dimension streams the list ordered by work count desc`() = runTest(scheduler) {
        kotlinx.coroutines.runBlocking {
            db.circleDao().upsertAll(
                listOf(
                    Circle("社团甲", "社团甲", "a"),
                    Circle("社团乙", "社团乙", "b"),
                    Circle("社团丙", "社团丙", "c"),
                ),
            )
            db.workDao().upsertAll(
                listOf(
                    work("local:RJ100001", "社团甲"),
                    work("local:RJ100002", "社团乙"),
                    work("local:RJ100003", "社团乙"),
                    work("local:RJ100004", "社团乙"),
                    work("local:RJ100005", "社团丙"),
                    work("local:RJ100006", "社团丙"),
                ),
            )
        }
        viewModelFor(BrowseDimensions.CIRCLE)

        val rows = viewModel.items.first { it.size == 3 }
        assertEquals(listOf("社团乙", "社团丙", "社团甲"), rows.map { it.name })
        assertEquals(listOf(3, 2, 1), rows.map { it.workCount })
    }

    @Test
    fun `tag dimension streams the list ordered by work count desc`() = runTest(scheduler) {
        kotlinx.coroutines.runBlocking {
            db.tagDao().upsertAll(listOf(Tag("T1", "T1"), Tag("T2", "T2")))
            db.workDao().upsertAll(listOf(work("local:RJ100001"), work("local:RJ100002"), work("local:RJ100003")))
            db.workTagDao().insertAll(
                listOf(
                    WorkTag("local:RJ100001", "T1"),
                    WorkTag("local:RJ100002", "T1"),
                    WorkTag("local:RJ100003", "T1"),
                ),
            )
        }
        viewModelFor(BrowseDimensions.TAG)

        val rows = viewModel.items.first { it.size == 2 }
        assertEquals(listOf("T1", "T2"), rows.map { it.name })
        assertEquals(listOf(3, 0), rows.map { it.workCount })
    }

    @Test
    fun `va dimension streams the list ordered by work count desc`() = runTest(scheduler) {
        kotlinx.coroutines.runBlocking {
            db.vaDao().upsertAll(listOf(Va("V1", "V1", "v1"), Va("V2", "V2", "v2")))
            db.workDao().upsertAll(listOf(work("local:RJ100001"), work("local:RJ100002")))
            db.workVaDao().insertAll(
                listOf(
                    WorkVa("local:RJ100001", "V2"),
                    WorkVa("local:RJ100002", "V2"),
                    WorkVa("local:RJ100002", "V1"),
                ),
            )
        }
        viewModelFor(BrowseDimensions.VA)

        val rows = viewModel.items.first { it.size == 2 }
        assertEquals(listOf("V2", "V1"), rows.map { it.name })
        assertEquals(listOf(2, 1), rows.map { it.workCount })
    }

    @Test
    fun `empty library yields an empty list`() = runTest(scheduler) {
        viewModelFor(BrowseDimensions.CIRCLE)
        assertTrue(viewModel.items.first().isEmpty())
    }

    @Test
    fun `unknown dimension yields an empty list`() = runTest(scheduler) {
        kotlinx.coroutines.runBlocking {
            db.circleDao().upsertAll(listOf(Circle("社团甲", "社团甲", "a")))
        }
        viewModelFor("bogus")
        assertTrue(viewModel.items.first().isEmpty())
    }
}
