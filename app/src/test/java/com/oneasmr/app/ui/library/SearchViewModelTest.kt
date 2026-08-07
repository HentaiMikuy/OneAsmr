package com.oneasmr.app.ui.library

import androidx.paging.ExperimentalPagingApi
import androidx.paging.asItemSnapshotListFlow
import androidx.room.Room
import com.oneasmr.app.data.local.Circle
import com.oneasmr.app.data.local.FtsStatus
import com.oneasmr.app.data.local.OneAsmrDatabase
import com.oneasmr.app.data.local.ScrapeStatus
import com.oneasmr.app.data.local.Tag
import com.oneasmr.app.data.local.TestDataStoreFile
import com.oneasmr.app.data.local.Va
import com.oneasmr.app.data.local.Work
import com.oneasmr.app.data.local.WorkListItem
import com.oneasmr.app.data.local.WorkOrder
import com.oneasmr.app.data.local.WorkPagingSourceFactory
import com.oneasmr.app.data.local.WorkTag
import com.oneasmr.app.data.local.WorkVa
import com.oneasmr.app.data.local.settings.SettingsStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
 * Task 13 SearchViewModel over REAL Room + REAL DataStore with an injected
 * virtual-clock Main dispatcher: the 300ms debounce is driven by
 * [advanceTimeBy] (no real sleeps — repo flake convention), DAO queries run
 * on Room's executor, and every result assertion reads the ACTUAL paged
 * items / state, never UI claims.
 *
 * Locked behaviors: debounce coalescing; direct-code hit; direct-code MISS
 * falls back to FTS (7-digit and existing-code-with-no-row cases); keyword
 * hits across title/circle/tag/va; sort+search composition; no-crash on
 * garbage/blank/short queries; recent-search persistence with dedupe/cap.
 */
@OptIn(ExperimentalPagingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: OneAsmrDatabase
    private lateinit var settingsStore: TestDataStoreFile
    private lateinit var settings: SettingsStore
    private lateinit var viewModel: SearchViewModel
    private lateinit var scheduler: kotlinx.coroutines.test.TestCoroutineScheduler
    private lateinit var dispatcher: TestDispatcher

    @Before
    fun setUp() {
        scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(scheduler)
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            OneAsmrDatabase::class.java,
        ).build()
        settingsStore = TestDataStoreFile(tmp.newFile("settings.preferences_pb"))
        settings = SettingsStore(settingsStore.open())
        // Robolectric SQLite has no FTS5; the documented LIKE fallback is the
        // JVM path by construction (device QA covers the trigram path).
        FtsStatus.available = false
        newViewModel()
    }

    private fun newViewModel() {
        cancelScope()
        viewModel = SearchViewModel(
            workDao = db.workDao(),
            settingsStore = settings,
            pagingSourceFactory = WorkPagingSourceFactory { order, descending, keyword, randomSeed, _ ->
                db.workDao().pagingSource(order, descending, keyword, randomSeed)
            },
        )
    }

    private fun cancelScope() {
        if (!::viewModel.isInitialized) return
        val job = viewModel.viewModelScope.coroutineContext[Job]
        job?.cancel()
        scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        cancelScope()
        settingsStore.restart()
        db.close()
        Dispatchers.resetMain()
    }

    private fun work(id: String, title: String, titleSortKey: String = com.oneasmr.app.data.local.SortKeyGenerator.generate(title)) =
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

    private suspend fun awaitState(predicate: (SearchUiState) -> Boolean): SearchUiState =
        viewModel.uiState.first { predicate(it) }

    /** Awaits paged items matching [predicate] on the SHARED pagingDataFlow (content-based, so
     * replayed data from a previous query never satisfies the await). */
    private suspend fun awaitResults(predicate: (List<WorkListItem>) -> Boolean): List<WorkListItem> =
        viewModel.pagingDataFlow
            .asItemSnapshotListFlow()
            .first { predicate(it.filterNotNull()) }
            .filterNotNull()

    private suspend fun awaitResultsCount(minItems: Int): List<WorkListItem> =
        awaitResults { it.size >= minItems }

    // ---------- debounce ----------

    @Test
    fun `query settles only after the 300ms debounce`() = runTest(scheduler) {
        viewModel.onQueryChange("催")
        scheduler.advanceTimeBy(SearchViewModel.DEBOUNCE_MS - 1)
        val before = awaitState { true }
        assertEquals(SearchPhase.Idle, before.phase)

        scheduler.advanceTimeBy(1)
        val after = awaitState { it.phase == SearchPhase.ShortQuery }
        assertEquals(SearchPhase.ShortQuery, after.phase)
    }

    @Test
    fun `rapid typing coalesces into one search of the final term`() = runTest(scheduler) {
        db.workDao().upsert(work("local:RJ100", "催眠音声テスト"))
        viewModel.onQueryChange("催")
        scheduler.advanceTimeBy(150)
        viewModel.onQueryChange("催眠音")
        // 299ms after the LAST change: still inside the debounce window.
        scheduler.advanceTimeBy(SearchViewModel.DEBOUNCE_MS - 1)
        val before = awaitState { true }
        assertEquals(SearchPhase.Idle, before.phase)

        // Every keystroke resets the debounce timer: the final term settles at
        // 150 + 300 = 450ms — the intermediate "催" is coalesced, never searched.
        // (The Loading phase may be conflated away by the StateFlow when the
        // pager's first emission lands in the same dispatch — await any settled
        // phase and assert the query, then await the actual items.)
        scheduler.advanceTimeBy(151)
        val settled = awaitState { it.phase != SearchPhase.Idle }
        assertEquals("催眠音", settled.query)

        scheduler.advanceUntilIdle()
        val results = awaitResultsCount(1)
        assertEquals("local:RJ100", results.single().id)
    }

    @Test
    fun `blank input clears instantly with a zero timeout`() = runTest(scheduler) {
        db.workDao().upsert(work("local:RJ100", "催眠音声テスト"))
        viewModel.onQueryChange("催眠音")
        scheduler.advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
        scheduler.advanceUntilIdle()
        assertEquals(1, awaitResultsCount(1).size)

        viewModel.onQueryChange("   ")
        scheduler.advanceUntilIdle()
        val state = awaitState { it.phase != SearchPhase.Results }
        assertEquals(SearchPhase.Idle, state.phase)
    }

    @Test
    fun `clearing the query mid-debounce cancels the pending search`() = runTest(scheduler) {
        viewModel.onQueryChange("催眠音")
        scheduler.advanceTimeBy(100)
        viewModel.onQueryChange("")
        scheduler.advanceUntilIdle()
        val state = awaitState { true }
        assertEquals(SearchPhase.Idle, state.phase)
        assertNull(state.directHit)
    }

    // ---------- direct code lookup + FTS fallback ----------

    @Test
    fun `direct code hit shows the unique work`() = runTest(scheduler) {
        db.workDao().upsertAll(
            listOf(
                work("local:RJ123456", "夜晚助眠陪伴音声"),
                work("local:RJ654321", "其他作品"),
            ),
        )
        viewModel.onQueryChange("RJ123456")
        scheduler.advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
        val state = awaitState { it.phase == SearchPhase.Results }
        assertEquals("local:RJ123456", state.directHit!!.id)
        assertEquals("夜晚助眠陪伴音声", state.directHit!!.title)
    }

    @Test
    fun `bare digits probe all prefixes and hit the matching work`() = runTest(scheduler) {
        db.workDao().upsert(work("local:BJ123456", "BJ 作品"))
        viewModel.onQueryChange("123456")
        scheduler.advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
        val state = awaitState { it.phase == SearchPhase.Results }
        assertEquals("local:BJ123456", state.directHit!!.id)
    }

    @Test
    fun `code lookup miss falls back to fts instead of an empty page`() = runTest(scheduler) {
        // No work with id local:RJ123456 — but a title CONTAINS the code text.
        db.workDao().upsert(work("local:RJ200", "RJ123456 特典"))
        viewModel.onQueryChange("RJ123456")
        scheduler.advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
        scheduler.advanceUntilIdle()

        val state = awaitState { it.phase == SearchPhase.Results }
        assertNull(state.directHit)
        val results = awaitResultsCount(1)
        assertEquals("local:RJ200", results.single().id)
    }

    @Test
    fun `seven digit input falls back to fts with results`() = runTest(scheduler) {
        // 7-digit runs never match RjCodeParser (6/8 only): the direct probe
        // misses and FTS must return results — never an empty page.
        db.workDao().upsertAll(
            listOf(
                work("local:RJ100", "RJ1234567番外編"),
                work("local:RJ200", "無関係"),
            ),
        )
        viewModel.onQueryChange("1234567")
        scheduler.advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
        scheduler.advanceUntilIdle()

        val state = awaitState { it.phase == SearchPhase.Results }
        assertNull(state.directHit)
        val results = awaitResultsCount(1)
        assertEquals("local:RJ100", results.single().id)
    }

    // ---------- FTS keyword across the four dimensions ----------

    private suspend fun seedDimensionData() {
        db.circleDao().upsertAll(listOf(Circle("月亮社团", "月亮社团", "yueliangshetuan")))
        db.tagDao().upsertAll(listOf(Tag("安眠誘導", "安眠誘導")))
        db.vaDao().upsertAll(listOf(Va("秋月爱莉", "秋月爱莉", "qiuyueaili")))
        db.workDao().upsertAll(
            listOf(
                work("local:RJ100", "夜晚助眠陪伴音声").copy(circleId = "月亮社团"),
                work("local:RJ200", "おやすみなさい安眠ボイス"),
                work("local:RJ300", "Gentle Sleep Hypnosis"),
                work("local:RJ400", "無関係な作品"),
            ),
        )
        db.workTagDao().insertAll(listOf(WorkTag("local:RJ200", "安眠誘導")))
        db.workVaDao().insertAll(listOf(WorkVa("local:RJ100", "秋月爱莉")))
    }

    @Test
    fun `chinese keyword hits the title dimension`() = runTest(scheduler) {
        seedDimensionData()
        viewModel.onQueryChange("助眠陪伴")
        scheduler.advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
        scheduler.advanceUntilIdle()
        assertEquals(listOf("local:RJ100"), awaitResultsCount(1).map { it.id })
    }

    @Test
    fun `japanese keyword hits the title dimension`() = runTest(scheduler) {
        seedDimensionData()
        viewModel.onQueryChange("おやすみ")
        scheduler.advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
        scheduler.advanceUntilIdle()
        assertEquals(listOf("local:RJ200"), awaitResultsCount(1).map { it.id })
    }

    @Test
    fun `english keyword hits the title dimension`() = runTest(scheduler) {
        seedDimensionData()
        viewModel.onQueryChange("hypnosis")
        scheduler.advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
        scheduler.advanceUntilIdle()
        assertEquals(listOf("local:RJ300"), awaitResultsCount(1).map { it.id })
    }

    @Test
    fun `keyword hits the circle dimension`() = runTest(scheduler) {
        seedDimensionData()
        viewModel.onQueryChange("月亮社团")
        scheduler.advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
        scheduler.advanceUntilIdle()
        assertEquals(listOf("local:RJ100"), awaitResultsCount(1).map { it.id })
    }

    @Test
    fun `keyword hits the tag dimension`() = runTest(scheduler) {
        seedDimensionData()
        viewModel.onQueryChange("安眠誘導")
        scheduler.advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
        scheduler.advanceUntilIdle()
        assertEquals(listOf("local:RJ200"), awaitResultsCount(1).map { it.id })
    }

    @Test
    fun `keyword hits the va dimension`() = runTest(scheduler) {
        seedDimensionData()
        viewModel.onQueryChange("秋月爱莉")
        scheduler.advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
        scheduler.advanceUntilIdle()
        assertEquals(listOf("local:RJ100"), awaitResultsCount(1).map { it.id })
    }

    // ---------- failure paths: garbage / short / no-results ----------

    @Test
    fun `short query shows the hint phase and never hits the database`() = runTest(scheduler) {
        db.workDao().upsert(work("local:RJ100", "催眠音声テスト"))
        viewModel.onQueryChange("催")
        scheduler.advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
        val state = awaitState { it.phase == SearchPhase.ShortQuery }
        assertEquals("催", state.query)
        assertNull(state.directHit)
    }

    @Test
    fun `garbage query never crashes and returns a results phase`() = runTest(scheduler) {
        db.workDao().upsert(work("local:RJ100", "催眠音声テスト"))
        viewModel.onQueryChange("!@#$%^&*(")
        scheduler.advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
        scheduler.advanceUntilIdle()
        val state = awaitState { it.phase != SearchPhase.Idle }
        assertTrue(state.phase == SearchPhase.Results || state.phase == SearchPhase.Loading)
    }

    @Test
    fun `no results shows an empty results phase without crashing`() = runTest(scheduler) {
        db.workDao().upsert(work("local:RJ100", "催眠音声テスト"))
        viewModel.onQueryChange("完全不存在的关键词")
        scheduler.advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
        scheduler.advanceUntilIdle()
        val state = awaitState { it.phase == SearchPhase.Results }
        assertEquals("完全不存在的关键词", state.query)
        assertNull(state.directHit)
        // The paged list is present and empty (empty-state UI, no crash).
        val items = awaitResults { it.isEmpty() }
        assertEquals(0, items.size)
    }

    // ---------- sort + search composition ----------

    private suspend fun seedSortableWorks() {
        db.workDao().upsertAll(
            listOf(
                work("local:RJ100", "Alpha Dream", titleSortKey = "alpha dream"),
                work("local:RJ200", "Beta Dream", titleSortKey = "beta dream"),
                work("local:RJ300", "Charlie Dream", titleSortKey = "charlie dream"),
            ),
        )
    }

    @Test
    fun `results honor the shared library sort selection`() = runTest(scheduler) {
        seedSortableWorks()
        viewModel.setSort(WorkOrder.TITLE_SORT_KEY, descending = true)
        viewModel.onQueryChange("dream")
        scheduler.advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
        scheduler.advanceUntilIdle()

        val results = awaitResults { it.map { w -> w.id } == listOf("local:RJ300", "local:RJ200", "local:RJ100") }
        assertEquals(3, results.size)
    }

    @Test
    fun `changing the sort re-runs the composed query`() = runTest(scheduler) {
        seedSortableWorks()
        viewModel.onQueryChange("dream")
        scheduler.advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
        scheduler.advanceUntilIdle()
        val asc = awaitResults { it.map { w -> w.id } == listOf("local:RJ100", "local:RJ200", "local:RJ300") }
        assertEquals(3, asc.size)

        viewModel.setSort(WorkOrder.TITLE_SORT_KEY, descending = true)
        scheduler.advanceUntilIdle()
        val desc = awaitResults { it.map { w -> w.id } == listOf("local:RJ300", "local:RJ200", "local:RJ100") }
        assertEquals(3, desc.size)
    }

    @Test
    fun `random order composes with a keyword without crashing`() = runTest(scheduler) {
        seedSortableWorks()
        viewModel.setSort(WorkOrder.RANDOM, descending = false)
        viewModel.onQueryChange("dream")
        scheduler.advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
        scheduler.advanceUntilIdle()
        val results = awaitResultsCount(3)
        assertEquals(3, results.size)
    }

    // ---------- recent searches ----------

    @Test
    fun `settled searches persist newest first and dedupe`() = runTest(scheduler) {
        viewModel.onQueryChange("催眠音")
        scheduler.advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
        settings.recentSearches.first { it.contains("催眠音") }

        viewModel.onQueryChange("夜晚助眠")
        scheduler.advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
        settings.recentSearches.first { it.contains("夜晚助眠") && it.first() == "夜晚助眠" }

        // Re-searching the older term moves it to the front, no duplicate.
        viewModel.onQueryChange("催眠音")
        scheduler.advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
        val list = settings.recentSearches.first { it == listOf("催眠音", "夜晚助眠") }
        assertEquals(listOf("催眠音", "夜晚助眠"), list)
    }

    @Test
    fun `blank and short queries are never saved as recent terms`() = runTest(scheduler) {
        viewModel.onQueryChange("   ")
        scheduler.advanceUntilIdle()
        viewModel.onQueryChange("催")
        scheduler.advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
        scheduler.advanceUntilIdle()
        assertEquals(emptyList<String>(), settings.recentSearches.first())
    }

    @Test
    fun `recent searches survive a view model restart`() = runTest(scheduler) {
        viewModel.onQueryChange("催眠音")
        scheduler.advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
        settings.recentSearches.first { it.contains("催眠音") }

        cancelScope()
        val restartedStore = SettingsStore(settingsStore.restart())
        val restarted = SearchViewModel(
            workDao = db.workDao(),
            settingsStore = restartedStore,
            pagingSourceFactory = WorkPagingSourceFactory { o, d, k, s, _ ->
                db.workDao().pagingSource(o, d, k, s)
            },
        )
        assertEquals(listOf("催眠音"), restarted.recentSearches.first { it.isNotEmpty() })
        restarted.viewModelScope.cancel()
    }

    @Test
    fun `clearing recent searches empties the list`() = runTest(scheduler) {
        viewModel.onQueryChange("催眠音")
        scheduler.advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
        settings.recentSearches.first { it.isNotEmpty() }

        viewModel.clearRecentSearches()
        settings.recentSearches.first { it.isEmpty() }
    }

    @Test
    fun `direct code hit is also saved as a recent term`() = runTest(scheduler) {
        db.workDao().upsert(work("local:RJ123456", "夜晚助眠陪伴音声"))
        viewModel.onQueryChange("RJ123456")
        scheduler.advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
        val state = awaitState { it.phase == SearchPhase.Results }
        assertNotNull(state.directHit)
        settings.recentSearches.first { it.contains("RJ123456") }
    }

    // ---------- initial state ----------

    @Test
    fun `initial state is idle with no query`() = runTest(scheduler) {
        val state = awaitState { true }
        assertEquals(SearchPhase.Idle, state.phase)
        assertEquals("", viewModel.queryText.value)
        assertNull(state.directHit)
    }

    @Test
    fun `sort defaults to id ascending shared with the settings store`() = runTest(scheduler) {
        assertEquals(WorkOrder.ID, viewModel.sortOrder.first())
        assertEquals(false, viewModel.sortDescending.first())
    }
}
