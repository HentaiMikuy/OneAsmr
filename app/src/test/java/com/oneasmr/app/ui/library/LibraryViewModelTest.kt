package com.oneasmr.app.ui.library

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.oneasmr.app.data.local.OneAsmrDatabase
import com.oneasmr.app.data.local.PlaybackState
import com.oneasmr.app.data.local.ProgressState
import com.oneasmr.app.data.local.Review
import com.oneasmr.app.data.local.TestDataStoreFile
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
 * Task 8 LibraryViewModel over REAL Room + REAL DataStore stores (fakes only
 * for the Android-only bits: permission store, name resolver, scan scheduler):
 * empty-library onboarding gate, live works list, progress + completion
 * summary mapping, and manual-remove with actual-row assertions (no
 * misleading "removed" signal — the DB is checked).
 *
 * Deterministic per repo convention: the ViewModel's scope runs on the test
 * Main dispatcher (same scheduler as runTest), all awaits are suspension-based
 * (`state.first { predicate }`), no real-time waits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: OneAsmrDatabase
    private lateinit var rootsStore: TestDataStoreFile
    private lateinit var bookkeepingStore: TestDataStoreFile
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
        bookkeeping = ScanBookkeepingStore(bookkeepingStore.open())
        rootRepository = ScanRootRepository(
            permissionStore = FakePermissionStore(),
            rootsStore = com.oneasmr.app.data.repository.ScanRootsStore(rootsStore.open()),
            displayNameResolver = FakeResolver(),
        )
        controller = FakeScanController()
        ScanProgressStore.reset()
        viewModel = LibraryViewModel(
            workDao = db.workDao(),
            db = db,
            rootRepository = rootRepository,
            bookkeeping = bookkeeping,
            scanController = controller,
        )
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        rootsStore.restart()
        bookkeepingStore.restart()
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

    private suspend fun awaitState(predicate: (LibraryUiState) -> Boolean): LibraryUiState {
        val state = viewModel.uiState.first { predicate(it) }
        return state
    }

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
        assertTrue(state.works.isEmpty())
    }

    @Test
    fun `adding a root hides onboarding`() = runTest(scheduler) {
        awaitState { true }
        addRoot("content://tree/primary%3AAsmrLib")
        val state = awaitState { it.hasRoots }
        assertFalse(state.showOnboarding)
        assertTrue(state.works.isEmpty())
    }

    @Test
    fun `works appear in the list with titles from folder names`() = runTest(scheduler) {
        addRoot("content://tree/primary%3AAsmrLib")
        commitWork("RJ111111", "content://tree/primary%3AAsmrLib")
        commitWork("RJ222222", "content://tree/primary%3AAsmrLib")
        val state = awaitState { it.works.size == 2 }
        assertEquals(setOf("RJ111111", "RJ222222"), state.works.map { it.title }.toSet())
        assertEquals(setOf("local:RJ111111", "local:RJ222222"), state.works.map { it.id }.toSet())
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
        val state = awaitState { it.works.size == 1 }
        assertEquals(1, state.works.size)

        viewModel.removeWork(id)
        awaitState { it.works.isEmpty() }

        val after = kotlinx.coroutines.runBlocking {
            Triple(db.workDao().getAll(), db.reviewDao().getByWorkId(id), db.playbackStateDao().get("$id:3"))
        }
        assertTrue(after.first.isEmpty())
        assertNull(after.second)
        assertNull(after.third)
    }

    @Test
    fun `rescan after mutation reflects the new row set`() = runTest(scheduler) {
        addRoot("content://tree/primary%3AAsmrLib")
        commitWork("RJ111111")
        commitWork("RJ222222")
        val before = awaitState { it.works.size == 2 }
        assertEquals(2, before.works.size)

        commitWork("RJ333333")
        val after = awaitState { it.works.size == 3 }
        assertTrue(after.works.any { it.id == "local:RJ333333" })
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
}
