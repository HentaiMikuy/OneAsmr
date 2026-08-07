package com.oneasmr.app.ui.library

import androidx.lifecycle.viewModelScope
import androidx.paging.ExperimentalPagingApi
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.asItemSnapshotListFlow
import androidx.room.Room
import com.oneasmr.app.data.local.OneAsmrDatabase
import com.oneasmr.app.data.local.PlaybackState
import com.oneasmr.app.data.local.ProgressState
import com.oneasmr.app.data.local.Review
import com.oneasmr.app.data.local.ScrapeStatus
import com.oneasmr.app.data.local.TestDataStoreFile
import com.oneasmr.app.data.local.WorkListItem
import com.oneasmr.app.data.local.WorkPagingSourceFactory
import com.oneasmr.app.data.local.settings.LibraryViewMode
import com.oneasmr.app.data.local.settings.SettingsStore
import com.oneasmr.app.data.repository.RootDisplayNameResolver
import com.oneasmr.app.data.repository.ScanRootPermissionStore
import com.oneasmr.app.data.repository.ScanRootRepository
import com.oneasmr.app.data.scanner.RescanSummary
import com.oneasmr.app.data.scanner.RoomScanPersister
import com.oneasmr.app.data.scanner.ScanBookkeepingStore
import com.oneasmr.app.data.scanner.ScanProgressStore
import com.oneasmr.app.data.scanner.WorkCandidate
import com.oneasmr.app.worker.ScanController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Task 12 LibraryViewModel over REAL Room + REAL DataStore stores (fakes only
 * for the Android-only bits: permission store, name resolver, scan scheduler)
 * with an INJECTED fake PagingSource so the Pager runs entirely on the test's
 * virtual scheduler — deterministic, no real-time waits (repo flake
 * convention). Page contents come from the fake list; DB-backed signals
 * (workCount, removeWork, scan state) are asserted against the real database,
 * never from UI claims.
 */
@OptIn(ExperimentalPagingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: OneAsmrDatabase
    private lateinit var rootsStore: TestDataStoreFile
    private lateinit var bookkeepingStore: TestDataStoreFile
    private lateinit var settingsStore: TestDataStoreFile
    private lateinit var settings: SettingsStore
    private lateinit var bookkeeping: ScanBookkeepingStore
    private lateinit var rootRepository: ScanRootRepository
    private lateinit var controller: FakeScanController
    private lateinit var viewModel: LibraryViewModel
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
        rootsStore = TestDataStoreFile(tmp.newFile("roots.preferences_pb"))
        bookkeepingStore = TestDataStoreFile(tmp.newFile("bookkeeping.preferences_pb"))
        settingsStore = TestDataStoreFile(tmp.newFile("settings.preferences_pb"))
        settings = SettingsStore(settingsStore.open())
        bookkeeping = ScanBookkeepingStore(bookkeepingStore.open())
        rootRepository = ScanRootRepository(
            permissionStore = FakePermissionStore(),
            rootsStore = com.oneasmr.app.data.repository.ScanRootsStore(rootsStore.open()),
            displayNameResolver = FakeResolver(),
        )
        controller = FakeScanController()
        ScanProgressStore.reset()
        newViewModel(sampleItems = emptyList())
    }

    /** Constructs a fresh VM; the old scope (if any) is cancelled + joined first. */
    private fun newViewModel(sampleItems: List<WorkListItem>) {
        cancelScope()
        viewModel = LibraryViewModel(
            workDao = db.workDao(),
            db = db,
            rootRepository = rootRepository,
            bookkeeping = bookkeeping,
            scanController = controller,
            settingsStore = settings,
            pagingSourceFactory = WorkPagingSourceFactory { FakePagingSource(sampleItems) },
        )
    }

    private fun cancelScope() {
        if (!::viewModel.isInitialized) return
        val job = viewModel.viewModelScope.coroutineContext[Job]
        job?.cancel()
        // The VM's collectors (cachedIn paging cache, Eagerly view-mode) run on
        // the virtual Main dispatcher: a runBlocking join from the test body
        // would deadlock (cancellation continuations queue on the virtual
        // clock, which cannot advance while the thread is blocked). Draining
        // the scheduler lets the cancelled children unwind deterministically.
        scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        cancelScope()
        rootsStore.restart()
        bookkeepingStore.restart()
        settingsStore.restart()
        ScanProgressStore.reset()
        db.close()
        Dispatchers.resetMain()
    }

    private class FakePermissionStore : ScanRootPermissionStore {
        override fun takePersistable(treeUri: String): Boolean = true
        override fun releasePersistable(treeUri: String) = Unit
        override fun persistedTreeUris(): Set<String> = emptySet()
    }

    private class FakeResolver : RootDisplayNameResolver {
        override fun resolve(treeUri: String, fallback: String): String =
            treeUri.substringAfterLast('/').ifEmpty { fallback }
    }

    private class FakeScanController : ScanController {
        var startCalls = 0
        var cancelCalls = 0
        override fun startScan(): Int {
            startCalls++
            return 1
        }

        override fun cancelScan() {
            cancelCalls++
        }
    }

    /** Pure suspend paging source over a fixed list — fully virtual-time friendly. */
    private class FakePagingSource(
        private val items: List<WorkListItem>,
    ) : PagingSource<Int, WorkListItem>() {
        override fun getRefreshKey(state: PagingState<Int, WorkListItem>): Int? = null

        override suspend fun load(params: LoadParams<Int>): PagingSource.LoadResult<Int, WorkListItem> {
            val key = params.key ?: 0
            val from = key * params.loadSize
            if (from >= items.size) {
                return PagingSource.LoadResult.Page(emptyList(), prevKey = null, nextKey = null)
            }
            val to = minOf(from + params.loadSize, items.size)
            return PagingSource.LoadResult.Page(
                data = items.subList(from, to),
                prevKey = if (key == 0) null else key - 1,
                nextKey = if (to >= items.size) null else key + 1,
            )
        }
    }

    private fun sampleItems(count: Int): List<WorkListItem> = (1..count).map { i ->
        WorkListItem(
            id = "local:RJ${100000 + i}",
            title = "作品 $i",
            circleName = "社团$i",
            rateAverage2dp = if (i % 2 == 0) 4.5 else null,
            missing = false,
            scrapeStatus = ScrapeStatus.NOT_SCRAPED,
            progress = if (i % 3 == 0) ProgressState.listening else null,
            rootFolderUri = "content://tree/primary%3AAsmrLib",
            relativeDir = "RJ${100000 + i}",
        )
    }

    private suspend fun awaitState(predicate: (LibraryUiState) -> Boolean): LibraryUiState =
        viewModel.uiState.first { predicate(it) }

    /** Suspends until the pager has presented at least [minItems] loaded rows. */
    private suspend fun awaitPagedItems(minItems: Int): List<WorkListItem> =
        viewModel.pagingDataFlow
            .asItemSnapshotListFlow()
            .first { it.filterNotNull().size >= minItems }
            .filterNotNull()

    private fun commitWork(rjCode: String, rootUri: String = "content://tree/rootA") {
        kotlinx.coroutines.runBlocking {
            RoomScanPersister(db).commitWork(WorkCandidate(rjCode, rjCode, rjCode), rootUri, 1_000L)
        }
    }

    private fun addRoot(treeUri: String) {
        kotlinx.coroutines.runBlocking {
            rootRepository.addRoot(treeUri, null)
        }
    }

    @Test
    fun `no roots and no works shows onboarding`() = runTest(scheduler) {
        val state = awaitState { true }
        assertTrue(state.showOnboarding)
        assertFalse(state.hasRoots)
        assertEquals(0, state.workCount)
    }

    @Test
    fun `adding a root hides onboarding`() = runTest(scheduler) {
        awaitState { true }
        addRoot("content://tree/primary%3AAsmrLib")
        val state = awaitState { it.hasRoots }
        assertFalse(state.showOnboarding)
        assertEquals(0, state.workCount)
    }

    @Test
    fun `paged items stream from the injected source in id order`() = runTest(scheduler) {
        newViewModel(sampleItems(100))
        // PagingConfig default initialLoadSize = pageSize * 3 = 90.
        val items = awaitPagedItems(90)
        assertEquals(90, items.size)
        assertEquals("local:RJ100001", items.first().id)
        assertEquals("local:RJ100090", items.last().id)
        assertEquals("社团1", items.first().circleName)
        assertEquals(4.5, items[1].rateAverage2dp)
        assertEquals(ProgressState.listening, items[2].progress)
    }

    @Test
    fun `paging source with fewer rows than the initial page still presents them`() = runTest(scheduler) {
        newViewModel(sampleItems(5))
        val items = awaitPagedItems(5)
        assertEquals(5, items.size)
        assertEquals("local:RJ100001", items.first().id)
        assertEquals("local:RJ100005", items.last().id)
    }

    @Test
    fun `scan start and cancel are forwarded to the controller`() = runTest(scheduler) {
        viewModel.startScan()
        viewModel.cancelScan()
        assertEquals(1, controller.startCalls)
        assertEquals(1, controller.cancelCalls)
    }

    @Test
    fun `running scan is reflected in the ui state`() = runTest(scheduler) {
        ScanProgressStore.begin(1)
        ScanProgressStore.update("AsmrLib", "RJ111111 test", 3)
        val state = awaitState { it.progress.worksFound == 3 }
        assertEquals(com.oneasmr.app.data.scanner.ScanPhase.SCANNING, state.progress.phase)
        assertEquals(3, state.progress.worksFound)
        assertEquals("AsmrLib", state.progress.rootDisplayName)
    }

    @Test
    fun `completed scan surfaces the persisted summary`() = runTest(scheduler) {
        kotlinx.coroutines.runBlocking {
            bookkeeping.setLastSummary(RescanSummary(2, 1, 3, 4, listOf("unreadable dir")))
        }
        val state = awaitState { it.lastSummary != null }
        assertEquals(2, state.lastSummary!!.added)
        assertEquals(3, state.lastSummary!!.missing)
        assertEquals(listOf("unreadable dir"), state.lastSummary!!.warnings)
    }

    @Test
    fun `removeWork deletes the row and its review and playback entries`() = runTest(scheduler) {
        addRoot("content://tree/primary%3AAsmrLib")
        commitWork("RJ111111", "content://tree/primary%3AAsmrLib")
        val id = "local:RJ111111"
        kotlinx.coroutines.runBlocking {
            db.reviewDao().upsert(Review(id, rating = 5, reviewText = "nice", progress = ProgressState.none, updatedAt = 1L))
            db.playbackStateDao().upsert(PlaybackState("$id:3", 500L, 1_000L, 1L))
        }
        val state = awaitState { it.workCount == 1 }
        assertEquals(1, state.workCount)

        viewModel.removeWork(id)
        awaitState { it.workCount == 0 }

        val after = kotlinx.coroutines.runBlocking {
            Triple(db.workDao().getAll(), db.reviewDao().getByWorkId(id), db.playbackStateDao().get("$id:3"))
        }
        assertTrue(after.first.isEmpty())
        assertNull(after.second)
        assertNull(after.third)
    }

    @Test
    fun `rescan after mutation reflects the new row count`() = runTest(scheduler) {
        addRoot("content://tree/primary%3AAsmrLib")
        commitWork("RJ111111")
        commitWork("RJ222222")
        val before = awaitState { it.workCount == 2 }
        assertEquals(2, before.workCount)

        commitWork("RJ333333")
        val after = awaitState { it.workCount == 3 }
        assertEquals(3, after.workCount)
    }

    @Test
    fun `cancel resets the progress to idle`() = runTest(scheduler) {
        ScanProgressStore.begin(1)
        ScanProgressStore.update("AsmrLib", "dir", 1)
        val scanning = awaitState { it.progress.phase == com.oneasmr.app.data.scanner.ScanPhase.SCANNING }
        assertEquals(com.oneasmr.app.data.scanner.ScanPhase.SCANNING, scanning.progress.phase)

        ScanProgressStore.reset()
        val idle = awaitState { it.progress.phase == com.oneasmr.app.data.scanner.ScanPhase.IDLE }
        assertEquals(com.oneasmr.app.data.scanner.ScanPhase.IDLE, idle.progress.phase)
    }

    @Test
    fun `view mode defaults to GRID`() = runTest(scheduler) {
        assertEquals(LibraryViewMode.GRID, viewModel.libraryViewMode.value)
    }

    @Test
    fun `view mode selection persists across view model restarts`() = runTest(scheduler) {
        viewModel.setLibraryViewMode(LibraryViewMode.LIST)
        // Await the DataStore write itself (the VM's setter is fire-and-forget
        // on the virtual scheduler); suspension lets the write land.
        settings.libraryViewMode.first { it == LibraryViewMode.LIST }
        // New store over the same DataStore file == cold restart; the old VM's
        // Eagerly collector must unwind first or the file is locked.
        cancelScope()
        val restarted = SettingsStore(settingsStore.restart())
        val newVm = LibraryViewModel(
            workDao = db.workDao(),
            db = db,
            rootRepository = rootRepository,
            bookkeeping = bookkeeping,
            scanController = controller,
            settingsStore = restarted,
            pagingSourceFactory = WorkPagingSourceFactory { FakePagingSource(emptyList()) },
        )
        assertEquals(LibraryViewMode.LIST, newVm.libraryViewMode.first { it == LibraryViewMode.LIST })
        newVm.viewModelScope.cancel()
    }

    @Test
    fun `pull refresh stub is a safe no-op`() = runTest(scheduler) {
        viewModel.onPullRefresh()
        val state = awaitState { true }
        assertEquals(0, state.workCount)
    }
}
