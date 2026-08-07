package com.oneasmr.app.data.local

import androidx.paging.PagingSource
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Task 13 DAO-level sort + search composition over the REAL Room paging
 * source (Robolectric framework SQLite; FTS unavailable on the JVM, so the
 * keyword path exercises the documented LIKE fallback — the FTS trigram path
 * is verified on-device in Task 13's device QA).
 *
 * Locked behaviors: every deterministic order carries an id tiebreaker (works
 * with a NULL sort field fall back to id order — never a crash); keywords
 * restrict rows at the SQL level (never in-memory filtering); the seeded
 * "random" order is stable across pages for one seed and differs across
 * seeds (session-stable semantics).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkDaoSortSearchTest {

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

    private fun work(id: String, title: String, titleSortKey: String = SortKeyGenerator.generate(title)) =
        Work(
            id = id,
            rootFolderUri = "content://tree/root",
            relativeDir = title,
            title = title,
            titleSortKey = titleSortKey,
            circleId = null,
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
            addedAt = 1_000L,
            updatedAt = 1_000L,
        )

    private suspend fun fullWalk(source: PagingSource<Int, WorkListItem>): List<WorkListItem> {
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
    fun `orders by each numeric field ascending and descending with id tiebreak`() = runBlocking {
        db.workDao().upsertAll(
            listOf(
                work("local:RJ100", "a").copy(releaseDate = "2021-01-01", dlCount = 10, price = 3000, reviewCount = 5, rateAverage2dp = 3.5),
                work("local:RJ200", "b").copy(releaseDate = "2024-06-01", dlCount = 100, price = 500, reviewCount = 50, rateAverage2dp = 4.9),
                work("local:RJ300", "c").copy(releaseDate = "2020-03-15", dlCount = 1, price = 2000, reviewCount = 500, rateAverage2dp = 4.0),
            ),
        )
        val dao = db.workDao()

        assertEquals(
            listOf("local:RJ300", "local:RJ100", "local:RJ200"),
            fullWalk(dao.pagingSource(WorkOrder.RELEASE_DATE, descending = false, keyword = null, randomSeed = 1L)).map { it.id },
        )
        assertEquals(
            listOf("local:RJ200", "local:RJ100", "local:RJ300"),
            fullWalk(dao.pagingSource(WorkOrder.RELEASE_DATE, descending = true, keyword = null, randomSeed = 1L)).map { it.id },
        )
        assertEquals(
            listOf("local:RJ300", "local:RJ100", "local:RJ200"),
            fullWalk(dao.pagingSource(WorkOrder.DL_COUNT, descending = false, keyword = null, randomSeed = 1L)).map { it.id },
        )
        assertEquals(
            listOf("local:RJ200", "local:RJ100", "local:RJ300"),
            fullWalk(dao.pagingSource(WorkOrder.DL_COUNT, descending = true, keyword = null, randomSeed = 1L)).map { it.id },
        )
        assertEquals(
            listOf("local:RJ200", "local:RJ300", "local:RJ100"),
            fullWalk(dao.pagingSource(WorkOrder.PRICE, descending = false, keyword = null, randomSeed = 1L)).map { it.id },
        )
        assertEquals(
            listOf("local:RJ100", "local:RJ300", "local:RJ200"),
            fullWalk(dao.pagingSource(WorkOrder.PRICE, descending = true, keyword = null, randomSeed = 1L)).map { it.id },
        )
        assertEquals(
            listOf("local:RJ100", "local:RJ200", "local:RJ300"),
            fullWalk(dao.pagingSource(WorkOrder.REVIEW_COUNT, descending = false, keyword = null, randomSeed = 1L)).map { it.id },
        )
        assertEquals(
            listOf("local:RJ300", "local:RJ200", "local:RJ100"),
            fullWalk(dao.pagingSource(WorkOrder.REVIEW_COUNT, descending = true, keyword = null, randomSeed = 1L)).map { it.id },
        )
        assertEquals(
            listOf("local:RJ100", "local:RJ300", "local:RJ200"),
            fullWalk(dao.pagingSource(WorkOrder.RATING, descending = false, keyword = null, randomSeed = 1L)).map { it.id },
        )
        assertEquals(
            listOf("local:RJ200", "local:RJ300", "local:RJ100"),
            fullWalk(dao.pagingSource(WorkOrder.RATE_AVERAGE_2DP, descending = true, keyword = null, randomSeed = 1L)).map { it.id },
        )
    }

    @Test
    fun `title sort key orders chinese by pinyin`() = runBlocking {
        db.workDao().upsertAll(
            listOf(
                work("local:RJ200", "中", titleSortKey = "zhong"),
                work("local:RJ100", "阿", titleSortKey = "a"),
                work("local:RJ300", "ばか", titleSortKey = "baka"),
            ),
        )
        assertEquals(
            listOf("local:RJ100", "local:RJ300", "local:RJ200"),
            fullWalk(db.workDao().pagingSource(WorkOrder.TITLE_SORT_KEY, descending = false, keyword = null, randomSeed = 1L)).map { it.id },
        )
        assertEquals(
            listOf("local:RJ200", "local:RJ300", "local:RJ100"),
            fullWalk(db.workDao().pagingSource(WorkOrder.TITLE_SORT_KEY, descending = true, keyword = null, randomSeed = 1L)).map { it.id },
        )
    }

    @Test
    fun `works with a null sort field fall back to id order without crashing`() = runBlocking {
        // Plan failure path: 排序字段不存在时回退编号排序. Unscraped works have
        // null releaseDate/rating/etc — they must group deterministically by id.
        db.workDao().upsertAll(
            listOf(
                work("local:RJ300", "c"),
                work("local:RJ100", "a"),
                work("local:RJ200", "b"),
            ),
        )
        val dao = db.workDao()
        // Ascending: NULLs first, id-ordered.
        assertEquals(
            listOf("local:RJ100", "local:RJ200", "local:RJ300"),
            fullWalk(dao.pagingSource(WorkOrder.RELEASE_DATE, descending = false, keyword = null, randomSeed = 1L)).map { it.id },
        )
        assertEquals(
            listOf("local:RJ100", "local:RJ200", "local:RJ300"),
            fullWalk(dao.pagingSource(WorkOrder.DL_COUNT, descending = false, keyword = null, randomSeed = 1L)).map { it.id },
        )
        assertEquals(
            listOf("local:RJ100", "local:RJ200", "local:RJ300"),
            fullWalk(dao.pagingSource(WorkOrder.TITLE_SORT_KEY, descending = false, keyword = null, randomSeed = 1L)).map { it.id },
        )
        // Mixed: null-dated works sort FIRST in ASC (SQLite NULLs-first),
        // id-ordered within the NULL group; the dated work follows.
        db.workDao().upsert(
            work("local:RJ050", "x").copy(releaseDate = "2023-01-01"),
        )
        assertEquals(
            listOf("local:RJ100", "local:RJ200", "local:RJ300", "local:RJ050"),
            fullWalk(dao.pagingSource(WorkOrder.RELEASE_DATE, descending = false, keyword = null, randomSeed = 1L)).map { it.id },
        )
        // DESC: the dated work first, then the NULL group by id.
        assertEquals(
            listOf("local:RJ050", "local:RJ100", "local:RJ200", "local:RJ300"),
            fullWalk(dao.pagingSource(WorkOrder.RELEASE_DATE, descending = true, keyword = null, randomSeed = 1L)).map { it.id },
        )
    }

    @Test
    fun `keyword restricts rows at the sql level and keeps the requested order`() = runBlocking {
        db.workDao().upsertAll(
            listOf(
                work("local:RJ100", "Gentle Sleep Hypnosis"),
                work("local:RJ200", "Night Rain"),
                work("local:RJ300", "Sleep Hypnosis Remix"),
                work("local:RJ400", "Morning Cafe"),
            ),
        )
        val dao = db.workDao()
        val hits = fullWalk(dao.pagingSource(WorkOrder.ID, descending = false, keyword = "hypnosis", randomSeed = 1L))
        assertEquals(listOf("local:RJ100", "local:RJ300"), hits.map { it.id })

        // Search + title sort composition: keyword restricts, order applies.
        val ordered = fullWalk(dao.pagingSource(WorkOrder.TITLE_SORT_KEY, descending = true, keyword = "sleep", randomSeed = 1L))
        assertEquals(listOf("local:RJ300", "local:RJ100"), ordered.map { it.id })
    }

    @Test
    fun `keyword matches circle tag and va dimensions`() = runBlocking {
        db.circleDao().upsertAll(listOf(Circle("circle-1", "月亮社团", "yueliangshetuan")))
        db.tagDao().upsertAll(listOf(Tag("治愈系", "治愈系"), Tag("安眠誘導", "安眠誘導")))
        db.vaDao().upsertAll(listOf(Va("初音ミク", "初音ミク", "hatsunemiku")))
        db.workDao().upsertAll(
            listOf(
                work("local:RJ100", "A").copy(circleId = "circle-1"),
                work("local:RJ200", "B"),
                work("local:RJ300", "C"),
            ),
        )
        db.workTagDao().insertAll(listOf(WorkTag("local:RJ200", "治愈系")))
        db.workVaDao().insertAll(listOf(WorkVa("local:RJ300", "初音ミク")))

        val dao = db.workDao()
        assertEquals(listOf("local:RJ100"), fullWalk(dao.pagingSource(WorkOrder.ID, false, "月亮社团", 1L)).map { it.id })
        assertEquals(listOf("local:RJ200"), fullWalk(dao.pagingSource(WorkOrder.ID, false, "治愈系", 1L)).map { it.id })
        assertEquals(listOf("local:RJ300"), fullWalk(dao.pagingSource(WorkOrder.ID, false, "初音ミク", 1L)).map { it.id })
    }

    @Test
    fun `keyword wildcards are escaped not interpreted`() = runBlocking {
        db.workDao().upsertAll(
            listOf(
                work("local:RJ100", "100%純粋"),
                work("local:RJ200", "other"),
            ),
        )
        // A literal '%' must match only the literal-percent row (LIKE escape).
        assertEquals(
            listOf("local:RJ100"),
            fullWalk(db.workDao().pagingSource(WorkOrder.ID, false, "100%", 1L)).map { it.id },
        )
    }

    @Test
    fun `seeded random order is stable across pages within a session`() = runBlocking {
        db.workDao().upsertAll((1..30).map { work("local:RJ${100000 + it}", "t$it", titleSortKey = "t$it") })

        // Walk pages with one source (one seed) — the paging contract: page 2
        // must continue page 1's order, i.e. the concatenation equals a single
        // full query with the same seed.
        val sessionOrder = fullWalk(db.workDao().pagingSource(WorkOrder.RANDOM, descending = false, keyword = null, randomSeed = 42L))
        assertEquals(30, sessionOrder.size)

        // A fresh source with the SAME seed reproduces the identical order.
        val replay = fullWalk(db.workDao().pagingSource(WorkOrder.RANDOM, descending = false, keyword = null, randomSeed = 42L))
        assertEquals(sessionOrder.map { it.id }, replay.map { it.id })

        // A different seed produces a different order (and still covers all rows).
        val other = fullWalk(db.workDao().pagingSource(WorkOrder.RANDOM, descending = false, keyword = null, randomSeed = 43L))
        assertEquals(30, other.size)
        assertNotEquals(sessionOrder.map { it.id }, other.map { it.id })

        // Zero and negative seeds are clamped (deterministic, no crash).
        val zero = fullWalk(db.workDao().pagingSource(WorkOrder.RANDOM, descending = false, keyword = null, randomSeed = 0L))
        val zeroAgain = fullWalk(db.workDao().pagingSource(WorkOrder.RANDOM, descending = false, keyword = null, randomSeed = 0L))
        assertEquals(zero.map { it.id }, zeroAgain.map { it.id })
        assertTrue(fullWalk(db.workDao().pagingSource(WorkOrder.RANDOM, descending = false, keyword = null, randomSeed = -5L)).size == 30)
    }

    @Test
    fun `seeded random composes with a keyword`() = runBlocking {
        db.workDao().upsertAll(
            listOf(
                work("local:RJ100", "Alpha Dream"),
                work("local:RJ200", "Beta Dream"),
                work("local:RJ300", "Gamma Wake"),
            ),
        )
        val hits = fullWalk(db.workDao().pagingSource(WorkOrder.RANDOM, descending = false, keyword = "dream", randomSeed = 7L))
        assertEquals(setOf("local:RJ100", "local:RJ200"), hits.map { it.id }.toSet())
        // Same seed + keyword -> same order (session stability holds for search too).
        val replay = fullWalk(db.workDao().pagingSource(WorkOrder.RANDOM, descending = false, keyword = "dream", randomSeed = 7L))
        assertEquals(hits.map { it.id }, replay.map { it.id })
    }

    @Test
    fun `getListItemById returns the joined row or null`() = runBlocking {
        db.circleDao().upsertAll(listOf(Circle("circle-1", "月亮社团", "yueliangshetuan")))
        db.workDao().upsert(work("local:RJ123456", "夜晚助眠陪伴音声").copy(circleId = "circle-1", rateAverage2dp = 4.52))
        db.reviewDao().upsert(Review("local:RJ123456", rating = 5, reviewText = null, progress = ProgressState.listened, updatedAt = 1L))

        val item = db.workDao().getListItemById("local:RJ123456")
        assertEquals("夜晚助眠陪伴音声", item!!.title)
        assertEquals("月亮社团", item.circleName)
        assertEquals(4.52, item.rateAverage2dp!!, 0.001)
        assertEquals(ProgressState.listened, item.progress)
        assertEquals("RJ123456", item.rjCode)
        assertNull(db.workDao().getListItemById("local:RJ999999"))
    }

    @Test
    fun `unknown stored order falls back to id`() {
        assertEquals(WorkOrder.ID, WorkOrder.fromStored(null))
        assertEquals(WorkOrder.ID, WorkOrder.fromStored(""))
        assertEquals(WorkOrder.ID, WorkOrder.fromStored("CAROUSEL"))
        assertEquals(WorkOrder.TITLE_SORT_KEY, WorkOrder.fromStored("TITLE_SORT_KEY"))
        assertEquals(WorkOrder.RANDOM, WorkOrder.fromStored("RANDOM"))
    }
}
