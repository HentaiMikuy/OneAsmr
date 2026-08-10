package com.oneasmr.app.data.local

import androidx.paging.PagingSource
import androidx.room.Room
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PagingInvalidationTest {

    private lateinit var db: OneAsmrDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            OneAsmrDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() { db.close() }

    private fun work(id: String, title: String) = Work(
        id = id, rootFolderUri = "r", relativeDir = id, title = title,
        titleSortKey = title, circleId = null, nsfw = false, releaseDate = null,
        dlCount = null, price = null, reviewCount = null, rateCount = null,
        rateAverage2dp = null, rateCountDetailJson = null, seriesName = null,
        scrapeStatus = ScrapeStatus.NOT_SCRAPED, missing = false, addedAt = 1L, updatedAt = 1L,
    )

    @Test
    fun `raw paging source invalidates on work upsert`() = runBlocking {
        db.workDao().upsert(work("local:RJ000001", "old title"))
        val source = db.workDao().pagingSource(WorkOrder.ID, descending = false, keyword = null, randomSeed = 0L)
        val page = source.load(PagingSource.LoadParams.Refresh(null, 10, false))
        val items = (page as PagingSource.LoadResult.Page).data
        assertEquals("old title", items.single().title)

        db.workDao().upsert(work("local:RJ000001", "scraped title"))
        var waited = 0
        while (!source.invalid && waited < 5000) { delay(50); waited += 50 }
        assertTrue("paging source was NOT invalidated after upsert", source.invalid)

        val fresh = db.workDao().pagingSource(WorkOrder.ID, false, null, 0L)
        val freshPage = fresh.load(PagingSource.LoadParams.Refresh(null, 10, false))
        assertEquals("scraped title", (freshPage as PagingSource.LoadResult.Page).data.single().title)
    }
}
