package com.oneasmr.app.data.repository

import com.oneasmr.app.data.local.Circle
import com.oneasmr.app.data.local.CircleDao
import com.oneasmr.app.data.local.KeySpec
import com.oneasmr.app.data.local.ScrapeStatus
import com.oneasmr.app.data.local.SortKeyGenerator
import com.oneasmr.app.data.local.Tag
import com.oneasmr.app.data.local.TagDao
import com.oneasmr.app.data.local.Va
import com.oneasmr.app.data.local.VaDao
import com.oneasmr.app.data.local.Work
import com.oneasmr.app.data.local.WorkDao
import com.oneasmr.app.data.local.WorkTag
import com.oneasmr.app.data.local.WorkTagDao
import com.oneasmr.app.data.local.WorkVa
import com.oneasmr.app.data.local.WorkVaDao
import com.oneasmr.app.data.remote.dlsite.DlsiteScrapeException
import com.oneasmr.app.data.remote.dlsite.DlsiteScraperApi
import com.oneasmr.app.data.remote.dlsite.ScrapedWork
import com.oneasmr.app.domain.rjcode.RjCodeParser
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Batch queue lifecycle. */
enum class BatchPhase { IDLE, RUNNING, FINISHED }

/** Live batch-queue state, surfaced to the library UI (progress bar + counters). */
data class BatchScrapeState(
    val phase: BatchPhase = BatchPhase.IDLE,
    val total: Int = 0,
    val done: Int = 0,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val currentCode: String? = null,
    val cancelled: Boolean = false,
) {
    companion object {
        val IDLE = BatchScrapeState()
    }
}

/** Terminal summary of one batch run. */
data class BatchScrapeResult(
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val cancelled: Boolean,
)

/** Outcome of one manual scrape; [Success.coverFiles] = files CoverStore wrote. */
sealed interface ScrapeOutcome {
    data class Success(val rjCode: String, val coverFiles: List<File>) : ScrapeOutcome
    data class Failed(
        val rjCode: String,
        val kind: DlsiteScrapeException.Kind?,
        val message: String,
    ) : ScrapeOutcome
}

/**
 * Task 11 scrape entry point: single-work manual scrape and the batch queue.
 *
 * Single scrape = one [DlsiteScraperApi] call + cover download + DB persist
 * (fields + circle/tags/vas cross rows, then the work row LAST as the atomic
 * commit marker; a crash before it leaves the work NOT_SCRAPED/FAILED with no
 * half-written metadata — every DAO write is its own driver-native transaction
 * on the bundled driver, see learnings.md). Any failure marks the work
 * FAILED (retryable) and never throws to the caller.
 *
 * Batch = worker pool of [maxConcurrency] coroutines pulling ids from a
 * channel. Rate-limit contract (Task 9): NO request starts within
 * [minRequestIntervalMillis] of the previous request's start — enforced by a
 * shared mutex so the spacing is GLOBAL across workers, not per worker —
 * and at most [maxConcurrency] requests in flight. Cancelling the calling
 * coroutine stops the queue; already-committed works stay, in-flight works are
 * cancelled cooperatively, and nothing is half-persisted.
 *
 * All timing (pacing decision + request start stamps) reads [clock]; the
 * actual wait is injected via [paceDelay] (defaults to [delay]). Tests inject
 * a fake clock + an instant delay that advances it — no real-time waits.
 */
@Singleton
class ScrapeRepository @Inject constructor(
    private val workDao: WorkDao,
    private val circleDao: CircleDao,
    private val tagDao: TagDao,
    private val vaDao: VaDao,
    private val workTagDao: WorkTagDao,
    private val workVaDao: WorkVaDao,
    private val coverStore: CoverStore,
    private val scraperFactory: suspend () -> DlsiteScraperApi,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val paceDelay: suspend (Long) -> Unit = { delay(it) },
    private val maxConcurrency: Int = 2,
    private val minRequestIntervalMillis: Long = 1_000L,
) {
    private val paceMutex = Mutex()
    private var lastRequestStart = Long.MIN_VALUE / 2

    /**
     * Scrapes ONE work. OK on the work row (scrapeStatus=OK + fields) or
     * FAILED — never throws (except [CancellationException], rethrown so the
     * caller's job state stays honest).
     */
    suspend fun scrapeOne(workId: String): ScrapeOutcome {
        val scraper = scraperFactory()
        return withContext(ioDispatcher) {
            runScrape(workId, scraper)
        }
    }

    /**
     * Runs the batch queue over [ids] (pre-filtered NOT_SCRAPED or FAILED by
     * the caller — the queue never scrapes OK works). Progress is pushed into
     * [state] on every transition. Cancellation marks the state cancelled
     * (when it was already RUNNING) then rethrows.
     */
    suspend fun batchScrape(ids: List<String>, state: MutableStateFlow<BatchScrapeState>): BatchScrapeResult {
        val scraper = scraperFactory()
        state.value = BatchScrapeState(phase = BatchPhase.RUNNING, total = ids.size)
        if (ids.isEmpty()) {
            state.value = BatchScrapeState.IDLE
            return BatchScrapeResult(0, 0, 0, cancelled = false)
        }
        lastRequestStart = clock() - minRequestIntervalMillis
        val done = AtomicInteger(0)
        val succeeded = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val channel = Channel<String>(Channel.UNLIMITED)
        return try {
            coroutineScope {
                repeat(maxConcurrency) {
                    launch(ioDispatcher) {
                        for (id in channel) {
                            pace()
                            state.update { it.copy(currentCode = rjCodeOf(id)) }
                            val outcome = runScrape(id, scraper)
                            val d = done.incrementAndGet()
                            if (outcome is ScrapeOutcome.Success) succeeded.incrementAndGet()
                            else failed.incrementAndGet()
                            state.update { it.copy(done = d, succeeded = succeeded.get(), failed = failed.get()) }
                        }
                    }
                }
                ids.forEach { channel.send(it) }
                channel.close()
            }
            state.value = state.value.copy(phase = BatchPhase.FINISHED, currentCode = null)
            BatchScrapeResult(ids.size, succeeded.get(), failed.get(), cancelled = false)
        } catch (e: CancellationException) {
            state.value = state.value.copy(phase = BatchPhase.FINISHED, currentCode = null, cancelled = true)
            throw e
        }
    }

    /** Global request gate: blocks until [minRequestIntervalMillis] since the last request START. */
    private suspend fun pace() {
        paceMutex.withLock {
            val now = clock()
            val wait = minRequestIntervalMillis - (now - lastRequestStart)
            if (wait > 0) paceDelay(wait)
            lastRequestStart = clock()
        }
    }

    private suspend fun runScrape(workId: String, scraper: DlsiteScraperApi): ScrapeOutcome {
        val work = workDao.getById(workId)
            ?: return ScrapeOutcome.Failed(workId, null, "作品不在库中")
        val rjCode = KeySpec.parseWorkId(workId)?.rjCode ?: workId
        val code = RjCodeParser.parse(rjCode)
            ?: return ScrapeOutcome.Failed(rjCode, null, "无法解析作品编号 $rjCode")
        return try {
            val scraped = scraper.scrape(code)
            val covers = coverStore.downloadCovers(scraped)
            persistScraped(work, scraped)
            ScrapeOutcome.Success(scraped.rjCode, covers)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DlsiteScrapeException) {
            markFailed(workId, e)
            ScrapeOutcome.Failed(rjCode, e.kind, e.message ?: "刮削失败")
        } catch (e: Exception) {
            markFailed(workId, e)
            ScrapeOutcome.Failed(rjCode, null, e.message ?: "刮削失败")
        }
    }

    /**
     * Persists the scrape result: circle/tags/vas + cross links first, the
     * work row LAST as the commit marker (a crash mid-way leaves the work's
     * own status untouched — the single atomic commit point).
     */
    private suspend fun persistScraped(work: Work, scraped: ScrapedWork) {
        val circleId = scraped.circle?.let { name ->
            circleDao.upsertAll(listOf(Circle(id = name, name = name, nameSortKey = SortKeyGenerator.generate(name))))
            name
        }
        val tags = scraped.tags.distinct()
        if (tags.isNotEmpty()) {
            tagDao.upsertAll(tags.map { Tag(id = it, name = it) })
        }
        val vas = scraped.vas.distinct()
        if (vas.isNotEmpty()) {
            vaDao.upsertAll(vas.map { Va(id = it, name = it, nameSortKey = SortKeyGenerator.generate(it)) })
        }
        workTagDao.deleteByWorkId(work.id)
        if (tags.isNotEmpty()) {
            workTagDao.insertAll(tags.map { WorkTag(workId = work.id, tagId = it) })
        }
        workVaDao.deleteByWorkId(work.id)
        if (vas.isNotEmpty()) {
            workVaDao.insertAll(vas.map { WorkVa(workId = work.id, vaId = it) })
        }
        workDao.upsert(
            work.copy(
                title = scraped.title,
                titleSortKey = SortKeyGenerator.generate(scraped.title),
                circleId = circleId,
                nsfw = scraped.nsfw,
                releaseDate = scraped.releaseDate,
                dlCount = scraped.dlCount,
                price = scraped.price,
                reviewCount = scraped.reviewCount,
                rateCount = scraped.rateCount,
                rateAverage2dp = scraped.rateAverage2dp,
                rateCountDetailJson = if (scraped.rateCountDetail.isEmpty()) null else SCRAPE_JSON.encodeToString(scraped.rateCountDetail),
                seriesName = scraped.seriesName,
                scrapeStatus = ScrapeStatus.OK,
                missing = false,
                updatedAt = clock(),
            ),
        )
    }

    private suspend fun markFailed(workId: String, cause: Throwable) {
        val work = workDao.getById(workId) ?: return
        workDao.upsert(work.copy(scrapeStatus = ScrapeStatus.FAILED, updatedAt = clock()))
    }

    private fun rjCodeOf(workId: String): String =
        KeySpec.parseWorkId(workId)?.rjCode ?: workId

    private companion object {
        val SCRAPE_JSON = Json { encodeDefaults = false }
    }
}
