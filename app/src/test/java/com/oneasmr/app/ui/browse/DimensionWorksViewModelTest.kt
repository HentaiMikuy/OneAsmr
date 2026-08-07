package com.oneasmr.app.ui.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.ExperimentalPagingApi
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.asItemSnapshotListFlow
import androidx.room.Room
import com.oneasmr.app.data.local.Circle
import com.oneasmr.app.data.local.DimensionWorksPagingSourceFactory
import com.oneasmr.app.data.local.OneAsmrDatabase
import com.oneasmr.app.data.local.ScrapeStatus
import com.oneasmr.app.data.local.Work
import com.oneasmr.app.data.local.WorkListItem
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Task 16 DimensionWorksViewModel: paged works of one circle/tag/CV with an
 * INJECTED fake PagingSource so the Pager runs entirely on the test's virtual
 * scheduler (repo flake convention — no real IO threads, no real-time waits).
 * The dimension name lookup runs against REAL Room.
 */
@OptIn(ExperimentalPagingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DimensionWorksViewModelTest {

    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var db: OneAsmrDatabase
    private lateinit var viewModel: DimensionWorksViewModel
    private var factoryDimension: String? = null
    private var factoryId: String? = null
    private lateinit var viewModelSource: FakeDimensionSource

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
        if (::viewModel.isInitialized) {
            viewModel.viewModelScope.coroutineContext[Job]?.cancel()
        }
        scheduler.advanceUntilIdle()
        db.close()
        Dispatchers.resetMain()
    }

    private fun newViewModel(dimension: String, id: String, source: FakeDimensionSource) {
        factoryDimension = null
        factoryId = null
        viewModelSource = source
        viewModel = DimensionWorksViewModel(
            savedStateHandle = SavedStateHandle(
                mapOf(
                    Routes.BROWSE_ARG_DIMENSION to dimension,
                    Routes.BROWSE_ARG_ID to id,
                ),
            ),
            pagingSourceFactory = DimensionWorksPagingSourceFactory { dim, itemId ->
                factoryDimension = dim
                factoryId = itemId
                source
            },
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

    private fun sampleItems(count: Int): List<WorkListItem> = (1..count).map { i ->
        WorkListItem(
            id = "local:RJ${100000 + i}",
            title = "作品 $i",
            circleName = null,
            rateAverage2dp = null,
            missing = false,
            scrapeStatus = ScrapeStatus.NOT_SCRAPED,
            progress = null,
            rootFolderUri = "content://tree/rootA",
            relativeDir = "RJ${100000 + i}",
        )
    }

    private suspend fun awaitPagedItems(minItems: Int): List<WorkListItem> =
        viewModel.pagingDataFlow
            .asItemSnapshotListFlow()
            .first { it.filterNotNull().size >= minItems }
            .filterNotNull()

    /** Waits until the fake source has served its refresh load, then returns the snapshot. */
    private suspend fun awaitPagedSnapshot(source: FakeDimensionSource): List<WorkListItem> =
        viewModel.pagingDataFlow
            .asItemSnapshotListFlow()
            .first { source.loads >= 1 }
            .filterNotNull()

    @Test
    fun `works page streams the injected source with the dimension and id`() = runTest(scheduler) {
        newViewModel(BrowseDimensions.CIRCLE, "社团甲", FakeDimensionSource(sampleItems(50)))
        // initialLoadSize = pageSize * 3 = 90 > 50 — the whole sample lands in
        // one refresh; assert data flows with the right contents, not a count.
        val items = awaitPagedItems(30)
        assertTrue(items.size >= 30)
        assertEquals("local:RJ100001", items.first().id)
        assertEquals(BrowseDimensions.CIRCLE, factoryDimension)
        assertEquals("社团甲", factoryId)
    }

    @Test
    fun `empty dimension yields an empty page without crashing`() = runTest(scheduler) {
        newViewModel(BrowseDimensions.TAG, "T1", FakeDimensionSource(emptyList()))
        assertEquals(0, awaitPagedSnapshot(viewModelSource).size)
    }

    @Test
    fun `circle dimension name resolves from the real row`() = runTest(scheduler) {
        kotlinx.coroutines.runBlocking {
            db.circleDao().upsertAll(listOf(Circle("社团甲", "社团甲", "a")))
            db.workDao().upsertAll(listOf(work("local:RJ100001", "社团甲")))
        }
        newViewModel(BrowseDimensions.CIRCLE, "社团甲", FakeDimensionSource(sampleItems(1)))

        assertEquals("社团甲", viewModel.dimensionName.first { it != null })
    }

    @Test
    fun `missing dimension row keeps a null name and an empty page`() = runTest(scheduler) {
        newViewModel(BrowseDimensions.VA, "不存在的CV", FakeDimensionSource(emptyList()))
        assertEquals(0, awaitPagedSnapshot(viewModelSource).size)
        scheduler.advanceUntilIdle()
        assertNull(viewModel.dimensionName.value)
    }

    @Test
    fun `unknown dimension keeps a null name and an empty page`() = runTest(scheduler) {
        // The dimension->source mapping lives in the Hilt factory
        // (unknown -> EmptyDimensionPagingSource, covered by the DAO test);
        // here the injected source is empty and the VM must stay quiet.
        newViewModel("bogus", "x", FakeDimensionSource(emptyList()))
        assertEquals(0, awaitPagedSnapshot(viewModelSource).size)
        scheduler.advanceUntilIdle()
        assertNull(viewModel.dimensionName.value)
    }

    /** Pure suspend paging source over a fixed list — fully virtual-time friendly. */
    private class FakeDimensionSource(
        private val items: List<WorkListItem>,
    ) : PagingSource<Int, WorkListItem>() {
        var loads = 0

        override fun getRefreshKey(state: PagingState<Int, WorkListItem>): Int? = null

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, WorkListItem> {
            loads++
            val key = params.key ?: 0
            val from = key * params.loadSize
            if (from >= items.size) {
                return LoadResult.Page(emptyList(), prevKey = null, nextKey = null)
            }
            val to = minOf(from + params.loadSize, items.size)
            return LoadResult.Page(
                data = items.subList(from, to),
                prevKey = if (key == 0) null else key - 1,
                nextKey = if (to >= items.size) null else key + 1,
            )
        }
    }
}
