package com.oneasmr.app.data.local

import androidx.paging.PagingSource
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
 * Task 16 dimension browse, DAO-level: the circle/tag/CV lists with work
 * counts (GROUP BY aggregate, work count DESC — never in-memory) and the
 * dimension-works paging sources (Task 12 WorkListItem shape restricted to
 * one dimension). Loads are exercised directly with suspend load() calls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DimensionBrowseDaoTest {

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

    private fun seedWorks(vararg specs: Pair<String, String?>) {
        runBlocking {
            specs.forEach { (id, circleId) ->
                db.workDao().upsert(work(id, circleId))
            }
        }
    }

    private fun linkTag(workId: String, tagId: String) {
        runBlocking {
            db.workTagDao().insertAll(listOf(WorkTag(workId, tagId)))
        }
    }

    private fun linkVa(workId: String, vaId: String) {
        runBlocking {
            db.workVaDao().insertAll(listOf(WorkVa(workId, vaId)))
        }
    }

    private suspend fun refreshPage(
        source: PagingSource<Int, WorkListItem>,
        loadSize: Int = 10,
    ): PagingSource.LoadResult.Page<Int, WorkListItem> =
        source.load(PagingSource.LoadParams.Refresh(key = null, loadSize = loadSize, placeholdersEnabled = false))
            as PagingSource.LoadResult.Page<Int, WorkListItem>

    @Test
    fun `circle list is ordered by work count desc`() = runBlocking {
        runBlocking {
            db.circleDao().upsertAll(
                listOf(
                    Circle("社团甲", "社团甲", "shetuanjia"),
                    Circle("社团乙", "社团乙", "shetuaneryi"),
                    Circle("社团丙", "社团丙", "shetuanbing"),
                ),
            )
        }
        seedWorks(
            "local:RJ100001" to "社团甲",
            "local:RJ100002" to "社团乙",
            "local:RJ100003" to "社团乙",
            "local:RJ100004" to "社团乙",
            "local:RJ100005" to "社团丙",
            "local:RJ100006" to "社团丙",
        )

        val rows = db.circleDao().getAllWithCountsFlow().first()
        assertEquals(listOf("社团乙", "社团丙", "社团甲"), rows.map { it.name })
        assertEquals(listOf(3, 2, 1), rows.map { it.workCount })
    }

    @Test
    fun `tag list is ordered by work count desc with zero-work tag last`() = runBlocking {
        runBlocking {
            db.tagDao().upsertAll(listOf(Tag("T1", "T1"), Tag("T2", "T2"), Tag("T3", "T3")))
        }
        seedWorks("local:RJ100001" to null, "local:RJ100002" to null, "local:RJ100003" to null)
        linkTag("local:RJ100001", "T1")
        linkTag("local:RJ100002", "T1")
        linkTag("local:RJ100003", "T3")

        val rows = db.tagDao().getAllWithCountsFlow().first()
        assertEquals(listOf("T1", "T3", "T2"), rows.map { it.name })
        assertEquals(listOf(2, 1, 0), rows.map { it.workCount })
    }

    @Test
    fun `va list is ordered by work count desc`() = runBlocking {
        runBlocking {
            db.vaDao().upsertAll(listOf(Va("V1", "V1", "v1"), Va("V2", "V2", "v2")))
        }
        seedWorks("local:RJ100001" to null, "local:RJ100002" to null, "local:RJ100003" to null, "local:RJ100004" to null)
        linkVa("local:RJ100001", "V2")
        linkVa("local:RJ100002", "V2")
        linkVa("local:RJ100003", "V2")
        linkVa("local:RJ100004", "V1")

        val rows = db.vaDao().getAllWithCountsFlow().first()
        assertEquals(listOf("V2", "V1"), rows.map { it.name })
        assertEquals(listOf(3, 1), rows.map { it.workCount })
    }

    @Test
    fun `counts reorder live when a work joins a dimension`() = runBlocking {
        runBlocking {
            db.circleDao().upsertAll(listOf(Circle("社团甲", "社团甲", "a"), Circle("社团乙", "社团乙", "b")))
        }
        seedWorks("local:RJ100001" to "社团乙", "local:RJ100002" to "社团乙")

        val before = db.circleDao().getAllWithCountsFlow().first()
        assertEquals(listOf("社团乙", "社团甲"), before.map { it.name })

        seedWorks("local:RJ100003" to "社团甲", "local:RJ100004" to "社团甲", "local:RJ100005" to "社团甲")
        val after = db.circleDao().getAllWithCountsFlow().first { it.first().name == "社团甲" }
        assertEquals(3, after.first().workCount)
        assertEquals(2, after.last().workCount)
    }

    @Test
    fun `paging source by circle returns only that circle's works in id order`() = runBlocking {
        runBlocking {
            db.circleDao().upsertAll(listOf(Circle("社团甲", "社团甲", "a"), Circle("社团乙", "社团乙", "b")))
        }
        seedWorks(
            "local:RJ100002" to "社团甲",
            "local:RJ100004" to "社团乙",
            "local:RJ100001" to "社团甲",
            "local:RJ100003" to "社团甲",
        )

        val page = refreshPage(db.workDao().pagingSourceByCircle("社团甲"))
        assertEquals(listOf("local:RJ100001", "local:RJ100002", "local:RJ100003"), page.data.map { it.id })
        assertNull(page.nextKey)
        assertEquals("社团甲", page.data.first().circleName)
    }

    @Test
    fun `paging source by tag returns only matching works`() = runBlocking {
        runBlocking {
            db.tagDao().upsertAll(listOf(Tag("T1", "T1"), Tag("T2", "T2")))
        }
        seedWorks("local:RJ100001" to null, "local:RJ100002" to null, "local:RJ100003" to null)
        linkTag("local:RJ100001", "T1")
        linkTag("local:RJ100003", "T2")

        val page = refreshPage(db.workDao().pagingSourceByTag("T1"))
        assertEquals(listOf("local:RJ100001"), page.data.map { it.id })
        assertNull(page.nextKey)
    }

    @Test
    fun `paging source by va returns only matching works`() = runBlocking {
        runBlocking {
            db.vaDao().upsertAll(listOf(Va("V1", "V1", "v1"), Va("V2", "V2", "v2")))
        }
        seedWorks("local:RJ100001" to null, "local:RJ100002" to null)
        linkVa("local:RJ100001", "V1")
        linkVa("local:RJ100002", "V1")
        linkVa("local:RJ100002", "V2")

        val page = refreshPage(db.workDao().pagingSourceByVa("V2"))
        assertEquals(listOf("local:RJ100002"), page.data.map { it.id })
        assertNull(page.nextKey)
    }

    @Test
    fun `dimension with no works yields an empty page`() = runBlocking {
        runBlocking {
            db.circleDao().upsertAll(listOf(Circle("社团空", "社团空", "kong")))
        }
        val page = refreshPage(db.workDao().pagingSourceByCircle("社团空"))
        assertTrue(page.data.isEmpty())
        assertNull(page.nextKey)
    }

    @Test
    fun `unknown dimension maps to the always-empty source`() = runBlocking {
        val page = refreshPage(EmptyDimensionPagingSource)
        assertTrue(page.data.isEmpty())
        assertNull(page.nextKey)
    }
}
