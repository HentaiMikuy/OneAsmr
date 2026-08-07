package com.oneasmr.app.ui.library

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.oneasmr.app.data.local.OneAsmrDatabase
import com.oneasmr.app.data.local.ScrapeStatus
import com.oneasmr.app.data.local.TestDataStoreFile
import com.oneasmr.app.data.local.Work
import com.oneasmr.app.data.local.settings.SettingsStore
import com.oneasmr.app.data.remote.dlsite.AjaxFields
import com.oneasmr.app.data.remote.dlsite.DlsiteCovers
import com.oneasmr.app.data.remote.dlsite.DlsiteScrapeException
import com.oneasmr.app.data.remote.dlsite.DlsiteScraperApi
import com.oneasmr.app.data.remote.dlsite.ScrapedWork
import com.oneasmr.app.data.repository.BatchPhase
import com.oneasmr.app.data.repository.BundledCoverLocator
import com.oneasmr.app.data.repository.CoverDownloadResult
import com.oneasmr.app.data.repository.CoverDownloader
import com.oneasmr.app.data.repository.CoverStore
import com.oneasmr.app.data.repository.ScrapeRepository
import com.oneasmr.app.domain.rjcode.RjCode
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Task 11 ScrapeViewModel over REAL Room + REAL ScrapeRepository (fakes only
 * for the scraper, the cover downloader and the DataStore file): single-scrape
 * DB persistence + messages, batch target filtering (NOT_SCRAPED vs FAILED),
 * empty-target notice, live counters, mid-queue cancellation with committed
 * results kept. Suspension-based awaits only (`uiState.first { predicate }`)
 * — no real-time waits, per the repo flake convention.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScrapeViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: TestDispatcher
    private lateinit var db: OneAsmrDatabase
    private lateinit var settingsStore: TestDataStoreFile
    private lateinit var clock: FakeClock
    private lateinit var scraper: FakeScraper
    private lateinit var coverDir: File
    private lateinit var viewModel: ScrapeViewModel

    private class FakeClock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
        fun advance(by: Long) {
            now += by
        }
    }

    private class FakeScraper(private val clock: FakeClock) : DlsiteScraperApi {
        val calls = mutableListOf<String>()
        val failKinds = mutableMapOf<String, DlsiteScrapeException.Kind>()
        var gate: CompletableDeferred<Unit>? = null
        var holdAfterCalls: Int? = null

        override suspend fun scrape(code: RjCode): ScrapedWork {
            calls += code.canonical
            if (holdAfterCalls != null && calls.size > holdAfterCalls!!) gate?.await()
            failKinds[code.canonical]?.let { throw DlsiteScrapeException(it, "fake failure for ${code.canonical}") }
            return ScrapedWork(
                rjCode = code.canonical,
                title = "Title-${code.canonical}",
                circle = "Circle-${code.canonical}",
                nsfw = true,
                releaseDate = "2024-01-02",
                seriesName = null,
                tags = listOf("tag-a"),
                vas = listOf("va-x"),
                covers = DlsiteCovers("http://cover/main.jpg", null, null, null),
                dlCount = 100,
                price = 500,
                reviewCount = 3,
                rateCount = 5,
                rateAverage2dp = 4.5,
                rateCountDetail = listOf(AjaxFields.RateCountDetail(5, 2, 40)),
            )
        }
    }

    private class FakeDownloader : CoverDownloader {
        override suspend fun download(url: String, referer: String?, target: File): CoverDownloadResult {
            target.writeBytes(ByteArray(100))
            return CoverDownloadResult.OK
        }
    }

    private class FakeLocator : BundledCoverLocator {
        override suspend fun findBundledCover(rootFolderUri: String, relativeDir: String): String? = null
    }

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(scheduler)
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            OneAsmrDatabase::class.java,
        ).build()
        settingsStore = TestDataStoreFile(tmp.newFile("settings.preferences_pb"))
        clock = FakeClock()
        scraper = FakeScraper(clock)
        coverDir = tmp.newFolder("covers")
        val settings = SettingsStore(settingsStore.open())
        val repo = ScrapeRepository(
            workDao = db.workDao(),
            circleDao = db.circleDao(),
            tagDao = db.tagDao(),
            vaDao = db.vaDao(),
            workTagDao = db.workTagDao(),
            workVaDao = db.workVaDao(),
            coverStore = CoverStore(coverDir, FakeDownloader(), FakeLocator(), { Long.MAX_VALUE }, clock),
            scraperFactory = { scraper },
            ioDispatcher = dispatcher,
            clock = clock,
            paceDelay = { ms -> clock.advance(ms) },
        )
        viewModel = ScrapeViewModel(repo, db.workDao(), settings, RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        settingsStore.restart()
        db.close()
        Dispatchers.resetMain()
    }

    private fun seed(rjCode: String, status: ScrapeStatus, title: String = "Folder $rjCode") {
        runBlocking {
            db.workDao().upsert(
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

    private suspend fun awaitState(predicate: (ScrapeUiState) -> Boolean): ScrapeUiState =
        viewModel.uiState.first { predicate(it) }

    // ---- single scrape ---------------------------------------------------

    @Test
    fun `single scrape success persists fields and emits success message`() = runTest(scheduler) {
        seed("RJ111111", ScrapeStatus.NOT_SCRAPED)

        viewModel.scrapeSingle("local:RJ111111")
        val state = awaitState { it.scrapingWorkId == null && it.singleMessage != null }

        assertEquals("刮削成功：RJ111111", state.singleMessage)
        val work = db.workDao().getById("local:RJ111111")!!
        assertEquals(ScrapeStatus.OK, work.scrapeStatus)
        assertEquals("Title-RJ111111", work.title)
        assertEquals(100, work.dlCount)
        assertEquals("Circle-RJ111111", work.circleId)
        assertTrue(File(coverDir, "RJ111111_img_main.jpg").exists())
    }

    @Test
    fun `single scrape failure marks FAILED and emits failure message`() = runTest(scheduler) {
        seed("RJ111111", ScrapeStatus.NOT_SCRAPED)
        scraper.failKinds["RJ111111"] = DlsiteScrapeException.Kind.NETWORK

        viewModel.scrapeSingle("local:RJ111111")
        val state = awaitState { it.scrapingWorkId == null && it.singleMessage != null }

        assertEquals("刮削失败：网络错误", state.singleMessage)
        assertEquals(ScrapeStatus.FAILED, db.workDao().getById("local:RJ111111")!!.scrapeStatus)
    }

    @Test
    fun `single scrape on an already-OK work force-rescrapes`() = runTest(scheduler) {
        seed("RJ111111", ScrapeStatus.OK, title = "Old Title")

        viewModel.scrapeSingle("local:RJ111111")
        awaitState { it.singleMessage != null }

        assertEquals("Title-RJ111111", db.workDao().getById("local:RJ111111")!!.title)
        assertEquals(ScrapeStatus.OK, db.workDao().getById("local:RJ111111")!!.scrapeStatus)
    }

    // ---- batch -----------------------------------------------------------

    @Test
    fun `batch targets NOT_SCRAPED works only`() = runTest(scheduler) {
        seed("RJ111111", ScrapeStatus.NOT_SCRAPED)
        seed("RJ222222", ScrapeStatus.OK)
        seed("RJ333333", ScrapeStatus.FAILED)

        viewModel.startBatch(BatchTarget.NOT_SCRAPED)
        awaitState { it.batch.phase == BatchPhase.FINISHED }

        assertEquals(listOf("RJ111111"), scraper.calls)
        assertEquals(ScrapeStatus.OK, db.workDao().getById("local:RJ111111")!!.scrapeStatus)
        // Untouched rows keep their status and title.
        assertEquals(ScrapeStatus.OK, db.workDao().getById("local:RJ222222")!!.scrapeStatus)
        assertEquals(ScrapeStatus.FAILED, db.workDao().getById("local:RJ333333")!!.scrapeStatus)
    }

    @Test
    fun `batch retry targets FAILED works only`() = runTest(scheduler) {
        seed("RJ111111", ScrapeStatus.NOT_SCRAPED)
        seed("RJ333333", ScrapeStatus.FAILED)

        viewModel.startBatch(BatchTarget.FAILED)
        awaitState { it.batch.phase == BatchPhase.FINISHED }

        assertEquals(listOf("RJ333333"), scraper.calls)
        assertEquals(ScrapeStatus.OK, db.workDao().getById("local:RJ333333")!!.scrapeStatus)
        assertEquals(ScrapeStatus.NOT_SCRAPED, db.workDao().getById("local:RJ111111")!!.scrapeStatus)
    }

    @Test
    fun `batch with no targets shows a notice and stays idle`() = runTest(scheduler) {
        seed("RJ111111", ScrapeStatus.OK)

        viewModel.startBatch(BatchTarget.NOT_SCRAPED)
        val state = awaitState { it.batchNotice != null }

        assertEquals("没有未刮削的作品", state.batchNotice)
        assertEquals(BatchPhase.IDLE, state.batch.phase)
        assertTrue(scraper.calls.isEmpty())
    }

    @Test
    fun `batch reports live counters and final summary`() = runTest(scheduler) {
        seed("RJ111111", ScrapeStatus.NOT_SCRAPED)
        seed("RJ222222", ScrapeStatus.NOT_SCRAPED)
        seed("RJ333333", ScrapeStatus.NOT_SCRAPED)
        scraper.failKinds["RJ222222"] = DlsiteScrapeException.Kind.PARSE_ERROR

        viewModel.startBatch(BatchTarget.NOT_SCRAPED)
        val finished = awaitState { it.batch.phase == BatchPhase.FINISHED }

        assertEquals(3, finished.batch.total)
        assertEquals(3, finished.batch.done)
        assertEquals(2, finished.batch.succeeded)
        assertEquals(1, finished.batch.failed)
        assertEquals(setOf(ScrapeStatus.OK, ScrapeStatus.FAILED, ScrapeStatus.OK),
            setOf(
                db.workDao().getById("local:RJ111111")!!.scrapeStatus,
                db.workDao().getById("local:RJ222222")!!.scrapeStatus,
                db.workDao().getById("local:RJ333333")!!.scrapeStatus,
            ),
        )
    }

    @Test
    fun `cancelBatch mid-queue keeps committed results and cancels the rest`() = runTest(scheduler) {
        seed("RJ111111", ScrapeStatus.NOT_SCRAPED)
        seed("RJ222222", ScrapeStatus.NOT_SCRAPED)
        seed("RJ333333", ScrapeStatus.NOT_SCRAPED)
        seed("RJ444444", ScrapeStatus.NOT_SCRAPED)
        scraper.gate = CompletableDeferred<Unit>()
        scraper.holdAfterCalls = 2

        viewModel.startBatch(BatchTarget.NOT_SCRAPED)
        awaitState { it.batch.done == 2 }

        viewModel.cancelBatch()
        val cancelled = awaitState { it.batch.cancelled }

        assertTrue(cancelled.batch.cancelled)
        assertEquals(2, cancelled.batch.done)
        assertEquals(ScrapeStatus.OK, db.workDao().getById("local:RJ111111")!!.scrapeStatus)
        assertEquals(ScrapeStatus.OK, db.workDao().getById("local:RJ222222")!!.scrapeStatus)
        assertEquals(ScrapeStatus.NOT_SCRAPED, db.workDao().getById("local:RJ333333")!!.scrapeStatus)
        assertEquals(ScrapeStatus.NOT_SCRAPED, db.workDao().getById("local:RJ444444")!!.scrapeStatus)
    }

    // ---- debug override --------------------------------------------------

    @Test
    fun `debug scraper base url override is persisted and readable`() = runTest(scheduler) {
        viewModel.setScraperBaseUrlOverride("http://10.0.2.2:7890")
        viewModel.scraperBaseUrlOverride.first { it == "http://10.0.2.2:7890" }
        assertEquals("http://10.0.2.2:7890", viewModel.currentScraperBaseUrlOverride())

        viewModel.setScraperBaseUrlOverride("")
        viewModel.scraperBaseUrlOverride.first { it.isEmpty() }
        assertEquals("", viewModel.currentScraperBaseUrlOverride())
    }
}
