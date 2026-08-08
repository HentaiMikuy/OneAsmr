package com.oneasmr.app.data.repository

import androidx.sqlite.db.SupportSQLiteQuery
import com.oneasmr.app.data.local.Circle
import com.oneasmr.app.data.local.CircleDao
import com.oneasmr.app.data.local.DimensionListItem
import com.oneasmr.app.data.local.ScrapeStatus
import com.oneasmr.app.data.local.Tag
import com.oneasmr.app.data.local.TagDao
import com.oneasmr.app.data.local.Va
import com.oneasmr.app.data.local.VaDao
import com.oneasmr.app.data.local.Work
import com.oneasmr.app.data.local.WorkDao
import com.oneasmr.app.data.local.WorkSearchHit
import com.oneasmr.app.data.local.WorkTag
import com.oneasmr.app.data.local.WorkTagDao
import com.oneasmr.app.data.local.WorkVa
import com.oneasmr.app.data.local.WorkVaDao
import com.oneasmr.app.data.remote.dlsite.AjaxFields
import com.oneasmr.app.data.remote.dlsite.DlsiteCovers
import com.oneasmr.app.data.remote.dlsite.DlsiteScrapeException
import com.oneasmr.app.data.remote.dlsite.DlsiteScraperApi
import com.oneasmr.app.data.remote.dlsite.ScrapedWork
import com.oneasmr.app.domain.rjcode.RjCode
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import androidx.paging.PagingSource
import com.oneasmr.app.data.local.WorkListItem
import com.oneasmr.app.data.local.WorkOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Task 11 ScrapeRepository queue tests. Fully deterministic per the repo
 * flake convention: fake DAOs (no Room threads), fake scraper with a call log
 * stamped by an injected clock, injected paceDelay that advances the same
 * clock (NO real-time waits), and the repo's ioDispatcher on the test
 * scheduler. Concurrency is measured from the call log's [start, end)
 * intervals; pacing from consecutive start stamps.
 */
class ScrapeRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Deterministic clock, advanced only by tests / the fake paceDelay. */
    private class FakeClock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
        fun advance(by: Long) {
            now += by
        }
    }

    private class FakeScraper(private val clock: FakeClock) : DlsiteScraperApi {
        data class Call(val code: String, val startAt: Long, val durationMs: Long)

        val calls = mutableListOf<Call>()
        val failKinds = mutableMapOf<String, DlsiteScrapeException.Kind>()
        var durationMs: Long = 0
        var holdAfterCalls: Int? = null
        var gate: CompletableDeferred<Unit>? = null
        var concurrent = 0
        var maxConcurrent = 0

        override suspend fun scrape(code: RjCode): ScrapedWork {
            concurrent++
            if (concurrent > maxConcurrent) maxConcurrent = concurrent
            calls += Call(code.canonical, clock(), durationMs)
            if (holdAfterCalls != null && calls.size > holdAfterCalls!!) gate?.await()
            if (durationMs > 0) kotlinx.coroutines.delay(durationMs)
            concurrent--
            failKinds[code.canonical]?.let { throw DlsiteScrapeException(it, "fake failure for ${code.canonical}") }
            return testScrapedWork(code.canonical)
        }
    }

    private class FakeDownloader(
        var result: (String) -> CoverDownloadResult = { CoverDownloadResult.OK },
    ) : CoverDownloader {
        override suspend fun download(url: String, referer: String?, target: File): CoverDownloadResult =
            when (result(url)) {
                CoverDownloadResult.OK -> {
                    target.writeBytes(ByteArray(100))
                    CoverDownloadResult.OK
                }
                CoverDownloadResult.NOT_FOUND -> CoverDownloadResult.NOT_FOUND
                CoverDownloadResult.FAILED -> CoverDownloadResult.FAILED
            }
    }

    private class FakeLocator : BundledCoverLocator {
        override suspend fun findBundledCover(rootFolderUri: String, relativeDir: String): String? = null
    }

    private class FakeWorkDao : WorkDao {
        val rows = LinkedHashMap<String, Work>()
        override suspend fun upsertAll(works: List<Work>) = works.forEach { rows[it.id] = it }
        override suspend fun upsert(work: Work) {
            rows[work.id] = work
        }
        override suspend fun getById(id: String): Work? = rows[id]
        override fun getByIdFlow(id: String): Flow<Work?> =
            MutableStateFlow(rows[id])
        override suspend fun deleteById(id: String) {
            rows.remove(id)
        }
        override suspend fun count(): Int = rows.size
        override suspend fun countByScrapeStatus(status: ScrapeStatus): Int =
            rows.values.count { !it.missing && it.scrapeStatus == status }
        override suspend fun getAll(): List<Work> = rows.values.toList()
        override fun getAllFlow(): Flow<List<Work>> = MutableStateFlow(rows.values.toList())
        override fun countFlow(): Flow<Int> = MutableStateFlow(rows.size)
        override fun pagingSourceById(): PagingSource<Int, WorkListItem> =
            throw UnsupportedOperationException("not used by ScrapeRepository")
        override fun pagingSourceRaw(query: SupportSQLiteQuery): PagingSource<Int, WorkListItem> =
            throw UnsupportedOperationException("not used by ScrapeRepository")
        override fun pagingSource(
            order: WorkOrder,
            descending: Boolean,
            keyword: String?,
            randomSeed: Long,
            filter: com.oneasmr.app.data.local.WorkFilter?,
        ): PagingSource<Int, WorkListItem> = throw UnsupportedOperationException("not used by ScrapeRepository")
        override suspend fun getListItemById(id: String): WorkListItem? = null
        override suspend fun markMissingInternal(ids: List<String>, now: Long) = Unit
        override suspend fun getPageRaw(query: SupportSQLiteQuery): List<Work> = emptyList()
        override suspend fun searchRaw(query: SupportSQLiteQuery): List<WorkSearchHit> = emptyList()
        override suspend fun getWorksByCircle(circleId: String): List<Work> = emptyList()
        override suspend fun getWorksByTag(tagId: String): List<Work> = emptyList()
        override suspend fun getWorksByVa(vaId: String): List<Work> = emptyList()
        override fun pagingSourceByCircle(circleId: String): PagingSource<Int, WorkListItem> =
            throw UnsupportedOperationException("not used by ScrapeRepository")
        override fun pagingSourceByTag(tagId: String): PagingSource<Int, WorkListItem> =
            throw UnsupportedOperationException("not used by ScrapeRepository")
        override fun pagingSourceByVa(vaId: String): PagingSource<Int, WorkListItem> =
            throw UnsupportedOperationException("not used by ScrapeRepository")
    }

    private class FakeCircleDao : CircleDao {
        val rows = HashMap<String, Circle>()
        override suspend fun upsertAll(circles: List<Circle>) = circles.forEach { rows[it.id] = it }
        override suspend fun getById(id: String): Circle? = rows[id]
        override suspend fun getAll(): List<Circle> = rows.values.toList()
        override fun getAllWithCountsFlow(): Flow<List<DimensionListItem>> = MutableStateFlow(emptyList())
    }

    private class FakeTagDao : TagDao {
        val rows = HashMap<String, Tag>()
        override suspend fun upsertAll(tags: List<Tag>) = tags.forEach { rows[it.id] = it }
        override suspend fun getById(id: String): Tag? = rows[id]
        override suspend fun getAll(): List<Tag> = rows.values.toList()
        override fun getAllWithCountsFlow(): Flow<List<DimensionListItem>> = MutableStateFlow(emptyList())
    }

    private class FakeVaDao : VaDao {
        val rows = HashMap<String, Va>()
        override suspend fun upsertAll(vas: List<Va>) = vas.forEach { rows[it.id] = it }
        override suspend fun getById(id: String): Va? = rows[id]
        override suspend fun getAll(): List<Va> = rows.values.toList()
        override fun getAllWithCountsFlow(): Flow<List<DimensionListItem>> = MutableStateFlow(emptyList())
    }

    private class FakeWorkTagDao : WorkTagDao {
        val rows = HashSet<Pair<String, String>>()
        override suspend fun insertAll(links: List<WorkTag>) = links.forEach { rows += it.workId to it.tagId }
        override suspend fun getWorkIdsByTag(tagId: String): List<String> =
            rows.filter { it.second == tagId }.map { it.first }.sorted()
        override suspend fun getTagIdsByWork(workId: String): List<String> =
            rows.filter { it.first == workId }.map { it.second }.sorted()
        override suspend fun deleteByWorkId(workId: String) {
            rows.removeAll { it.first == workId }
        }
    }

    private class FakeWorkVaDao : WorkVaDao {
        val rows = HashSet<Pair<String, String>>()
        override suspend fun insertAll(links: List<WorkVa>) = links.forEach { rows += it.workId to it.vaId }
        override suspend fun getWorkIdsByVa(vaId: String): List<String> =
            rows.filter { it.second == vaId }.map { it.first }.sorted()
        override suspend fun getVaIdsByWork(workId: String): List<String> =
            rows.filter { it.first == workId }.map { it.second }.sorted()
        override suspend fun deleteByWorkId(workId: String) {
            rows.removeAll { it.first == workId }
        }
    }

    private class Harness(
        val tmp: TemporaryFolder,
        val scheduler: TestCoroutineScheduler = TestCoroutineScheduler(),
        val clock: FakeClock = FakeClock(),
        downloaderResult: (String) -> CoverDownloadResult = { CoverDownloadResult.OK },
    ) {
        val workDao = FakeWorkDao()
        val circleDao = FakeCircleDao()
        val tagDao = FakeTagDao()
        val vaDao = FakeVaDao()
        val workTagDao = FakeWorkTagDao()
        val workVaDao = FakeWorkVaDao()
        val scraper = FakeScraper(clock)
        val state = MutableStateFlow(BatchScrapeState.IDLE)

        val repo: ScrapeRepository = ScrapeRepository(
            workDao = workDao,
            circleDao = circleDao,
            tagDao = tagDao,
            vaDao = vaDao,
            workTagDao = workTagDao,
            workVaDao = workVaDao,
            coverStore = CoverStore(
                coversDir = tmp.newFolder("covers"),
                downloader = FakeDownloader(downloaderResult),
                bundledLocator = FakeLocator(),
                cacheCapBytes = { Long.MAX_VALUE },
                clock = clock,
            ),
            scraperFactory = { scraper },
            ioDispatcher = StandardTestDispatcher(scheduler),
            clock = clock,
            paceDelay = { ms -> clock.advance(ms) },
        )

        suspend fun seedWork(rjCode: String, status: ScrapeStatus = ScrapeStatus.NOT_SCRAPED, title: String = "Folder $rjCode") {
            workDao.upsert(
                Work(
                    id = "local:$rjCode",
                    rootFolderUri = "content://tree/root",
                    relativeDir = rjCode,
                    title = title,
                    titleSortKey = title.lowercase(),
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
                    scrapeStatus = status,
                    missing = false,
                    addedAt = 1L,
                    updatedAt = 1L,
                ),
            )
        }
    }

    private fun ids(vararg codes: String): List<String> = codes.map { "local:$it" }

    private companion object {
        fun testScrapedWork(code: String): ScrapedWork = ScrapedWork(
        rjCode = code,
        title = "Title-$code",
        circle = "Circle-$code",
        nsfw = true,
        releaseDate = "2024-01-02",
        seriesName = "Series-$code",
        tags = listOf("tag-a", "tag-b"),
        vas = listOf("va-x"),
        covers = DlsiteCovers("http://cover/main.jpg", "http://cover/sam.jpg", null, null),
        dlCount = 100,
        price = 500,
        reviewCount = 3,
        rateCount = 5,
        rateAverage2dp = 4.5,
        rateCountDetail = listOf(AjaxFields.RateCountDetail(5, 2, 40), AjaxFields.RateCountDetail(4, 3, 60)),
        )
    }

    // ---- single scrape ---------------------------------------------------

    @Test
    fun `single scrape persists fields, status OK and cover files`() = runTest {
        val h = Harness(tmp, scheduler = testScheduler)
        h.seedWork("RJ111111")

        val outcome = h.repo.scrapeOne("local:RJ111111")

        assertTrue(outcome is ScrapeOutcome.Success)
        assertEquals("RJ111111", (outcome as ScrapeOutcome.Success).rjCode)
        assertEquals(2, outcome.coverFiles.size) // main + sam
        val work = h.workDao.getById("local:RJ111111")!!
        assertEquals(ScrapeStatus.OK, work.scrapeStatus)
        assertEquals("Title-RJ111111", work.title)
        assertEquals("Circle-RJ111111", work.circleId)
        assertEquals("Series-RJ111111", work.seriesName)
        assertEquals("2024-01-02", work.releaseDate)
        assertEquals(100, work.dlCount)
        assertEquals(500, work.price)
        assertEquals(3, work.reviewCount)
        assertEquals(5, work.rateCount)
        assertEquals(4.5, work.rateAverage2dp!!, 0.001)
        assertNotNull(work.rateCountDetailJson)
        assertTrue(work.nsfw)
        assertEquals("Circle-RJ111111", h.circleDao.getById("Circle-RJ111111")!!.name)
        assertEquals(setOf("tag-a", "tag-b"), h.workTagDao.getTagIdsByWork("local:RJ111111").toSet())
        assertEquals(listOf("va-x"), h.workVaDao.getVaIdsByWork("local:RJ111111"))
    }

    @Test
    fun `single scrape failure marks the work FAILED and returns the kind`() = runTest {
        val h = Harness(tmp, scheduler = testScheduler)
        h.seedWork("RJ111111")
        h.scraper.failKinds["RJ111111"] = DlsiteScrapeException.Kind.NETWORK

        val outcome = h.repo.scrapeOne("local:RJ111111")

        assertTrue(outcome is ScrapeOutcome.Failed)
        assertEquals(DlsiteScrapeException.Kind.NETWORK, (outcome as ScrapeOutcome.Failed).kind)
        assertEquals(ScrapeStatus.FAILED, h.workDao.getById("local:RJ111111")!!.scrapeStatus)
        assertEquals("Folder RJ111111", h.workDao.getById("local:RJ111111")!!.title) // metadata untouched
        assertTrue(h.workTagDao.getTagIdsByWork("local:RJ111111").isEmpty())
    }

    @Test
    fun `single scrape of unknown work id fails gracefully`() = runTest {
        val h = Harness(tmp, scheduler = testScheduler)
        val outcome = h.repo.scrapeOne("local:RJ999999")
        assertTrue(outcome is ScrapeOutcome.Failed)
        assertEquals(0, h.scraper.calls.size)
    }

    @Test
    fun `single scrape on an already-OK work force-rescrapes and overwrites`() = runTest {
        val h = Harness(tmp, scheduler = testScheduler)
        h.seedWork("RJ111111", status = ScrapeStatus.OK, title = "Old Title")

        val outcome = h.repo.scrapeOne("local:RJ111111")

        assertTrue(outcome is ScrapeOutcome.Success)
        assertEquals("Title-RJ111111", h.workDao.getById("local:RJ111111")!!.title)
        assertEquals(ScrapeStatus.OK, h.workDao.getById("local:RJ111111")!!.scrapeStatus)
    }

    @Test
    fun `cover download failure does not fail the scrape`() = runTest {
        val h = Harness(tmp, scheduler = testScheduler, downloaderResult = { CoverDownloadResult.NOT_FOUND })
        h.seedWork("RJ111111")

        val outcome = h.repo.scrapeOne("local:RJ111111")

        assertTrue(outcome is ScrapeOutcome.Success)
        assertEquals(0, (outcome as ScrapeOutcome.Success).coverFiles.size)
        assertEquals(ScrapeStatus.OK, h.workDao.getById("local:RJ111111")!!.scrapeStatus)
    }

    // ---- batch -----------------------------------------------------------

    @Test
    fun `batch scrapes all works, reports counters and final state`() = runTest {
        val h = Harness(tmp, scheduler = testScheduler)
        h.seedWork("RJ111111")
        h.seedWork("RJ222222")
        h.seedWork("RJ333333")

        val result = h.repo.batchScrape(ids("RJ111111", "RJ222222", "RJ333333"), h.state)

        assertEquals(BatchScrapeResult(3, 3, 0, false), result)
        assertEquals(BatchPhase.FINISHED, h.state.value.phase)
        assertEquals(3, h.state.value.done)
        assertEquals(3, h.state.value.succeeded)
        assertEquals(0, h.state.value.failed)
        assertEquals(null, h.state.value.currentCode)
        assertEquals(setOf(ScrapeStatus.OK, ScrapeStatus.OK, ScrapeStatus.OK), h.workDao.getAll().map { it.scrapeStatus }.toSet())
        assertTrue(h.workDao.getAll().all { it.title.startsWith("Title-") })
    }

    @Test
    fun `batch failure marks only the failing work FAILED`() = runTest {
        val h = Harness(tmp, scheduler = testScheduler)
        h.seedWork("RJ111111")
        h.seedWork("RJ222222")
        h.seedWork("RJ333333")
        h.scraper.failKinds["RJ222222"] = DlsiteScrapeException.Kind.PARSE_ERROR

        val result = h.repo.batchScrape(ids("RJ111111", "RJ222222", "RJ333333"), h.state)

        assertEquals(BatchScrapeResult(3, 2, 1, false), result)
        assertEquals(2, h.state.value.succeeded)
        assertEquals(1, h.state.value.failed)
        assertEquals(ScrapeStatus.OK, h.workDao.getById("local:RJ111111")!!.scrapeStatus)
        assertEquals(ScrapeStatus.FAILED, h.workDao.getById("local:RJ222222")!!.scrapeStatus)
        assertEquals(ScrapeStatus.OK, h.workDao.getById("local:RJ333333")!!.scrapeStatus)
    }

    @Test
    fun `batch paces requests at least 1s apart`() = runTest {
        val h = Harness(tmp, scheduler = testScheduler)
        h.seedWork("RJ111111")
        h.seedWork("RJ222222")
        h.seedWork("RJ333333")
        h.seedWork("RJ444444")

        h.repo.batchScrape(ids("RJ111111", "RJ222222", "RJ333333", "RJ444444"), h.state)

        val starts = h.scraper.calls.map { it.startAt }
        assertEquals(4, starts.size)
        assertEquals(listOf("RJ111111", "RJ222222", "RJ333333", "RJ444444"), h.scraper.calls.map { it.code })
        starts.zipWithNext().forEach { (a, b) ->
            assertTrue("gap ${b - a}ms must be >= 1000ms", b - a >= 1_000L)
        }
    }

    @Test
    fun `batch with long requests never exceeds two concurrent`() = runTest {
        val h = Harness(tmp, scheduler = testScheduler)
        h.seedWork("RJ111111")
        h.seedWork("RJ222222")
        h.seedWork("RJ333333")
        h.seedWork("RJ444444")
        h.scraper.durationMs = 2_500 // longer than two pacing intervals

        h.repo.batchScrape(ids("RJ111111", "RJ222222", "RJ333333", "RJ444444"), h.state)

        // The 2-worker pool is the concurrency cap: while one request runs for
        // 2.5s, the second worker starts another — never a third in flight.
        assertEquals(2, h.scraper.maxConcurrent)
        assertEquals(4, h.scraper.calls.size)
        // Global pacing still holds across the overlapping workers.
        h.scraper.calls.map { it.startAt }.zipWithNext().forEach { (a, b) ->
            assertTrue("gap ${b - a}ms must be >= 1000ms", b - a >= 1_000L)
        }
    }

    @Test
    fun `batch holds in-flight starts while the gate is up - at most two start`() = runTest {
        val h = Harness(tmp, scheduler = testScheduler)
        h.seedWork("RJ111111")
        h.seedWork("RJ222222")
        h.seedWork("RJ333333")
        h.seedWork("RJ444444")
        h.scraper.gate = CompletableDeferred<Unit>()
        h.scraper.holdAfterCalls = 0 // hold EVERY request at the gate

        val job = launch { h.repo.batchScrape(ids("RJ111111", "RJ222222", "RJ333333", "RJ444444"), h.state) }
        runCurrent()

        // Both workers started exactly one request each and are now held.
        assertEquals(2, h.scraper.calls.size)
        assertEquals(BatchPhase.RUNNING, h.state.value.phase)

        h.scraper.gate!!.complete(Unit)
        job.join()
        assertEquals(4, h.scraper.calls.size)
        assertEquals(BatchPhase.FINISHED, h.state.value.phase)
    }

    @Test
    fun `batch cancel keeps committed results and cancels the rest`() = runTest {
        val h = Harness(tmp, scheduler = testScheduler)
        h.seedWork("RJ111111")
        h.seedWork("RJ222222")
        h.seedWork("RJ333333")
        h.seedWork("RJ444444")
        h.scraper.gate = CompletableDeferred<Unit>()
        h.scraper.holdAfterCalls = 2 // first two pass, the rest hold

        val job = launch { h.repo.batchScrape(ids("RJ111111", "RJ222222", "RJ333333", "RJ444444"), h.state) }
        runCurrent()

        // Two completed, two held at the gate.
        assertEquals(2, h.state.value.done)
        assertEquals(2, h.state.value.succeeded)

        job.cancel()
        job.join()

        assertTrue(h.state.value.cancelled)
        assertEquals(BatchPhase.FINISHED, h.state.value.phase)
        assertEquals(2, h.state.value.done)
        // Committed results stay; held works were never persisted.
        assertEquals(ScrapeStatus.OK, h.workDao.getById("local:RJ111111")!!.scrapeStatus)
        assertEquals(ScrapeStatus.OK, h.workDao.getById("local:RJ222222")!!.scrapeStatus)
        assertEquals(ScrapeStatus.NOT_SCRAPED, h.workDao.getById("local:RJ333333")!!.scrapeStatus)
        assertEquals(ScrapeStatus.NOT_SCRAPED, h.workDao.getById("local:RJ444444")!!.scrapeStatus)
    }

    @Test
    fun `empty batch completes without queueing and stays idle`() = runTest {
        val h = Harness(tmp, scheduler = testScheduler)
        val result = h.repo.batchScrape(emptyList(), h.state)
        assertEquals(BatchScrapeResult(0, 0, 0, false), result)
        assertEquals(BatchPhase.IDLE, h.state.value.phase)
        assertEquals(0, h.scraper.calls.size)
    }

    @Test
    fun `batch rescrapes works passed in even when already OK`() = runTest {
        val h = Harness(tmp, scheduler = testScheduler)
        h.seedWork("RJ111111", status = ScrapeStatus.OK, title = "Already")
        h.seedWork("RJ222222")

        h.repo.batchScrape(ids("RJ111111", "RJ222222"), h.state)

        assertEquals(2, h.scraper.calls.size)
        assertEquals(ScrapeStatus.OK, h.workDao.getById("local:RJ111111")!!.scrapeStatus)
        assertEquals("Title-RJ111111", h.workDao.getById("local:RJ111111")!!.title) // overwritten
    }

    @Test
    fun `persisted metadata includes circle tags and va rows`() = runTest {
        val h = Harness(tmp, scheduler = testScheduler)
        h.seedWork("RJ111111")
        h.repo.scrapeOne("local:RJ111111")

        assertNotNull(h.circleDao.getById("Circle-RJ111111"))
        assertEquals(setOf("tag-a", "tag-b"), h.tagDao.rows.keys)
        assertEquals(setOf("va-x"), h.vaDao.rows.keys)
    }
}
