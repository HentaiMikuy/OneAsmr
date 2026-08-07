package com.oneasmr.app.ui.work

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.oneasmr.app.data.local.Circle
import com.oneasmr.app.data.local.OneAsmrDatabase
import com.oneasmr.app.data.local.ScrapeStatus
import com.oneasmr.app.data.local.Tag
import com.oneasmr.app.data.local.TestDataStoreFile
import com.oneasmr.app.data.local.Va
import com.oneasmr.app.data.local.Work
import com.oneasmr.app.data.local.WorkTag
import com.oneasmr.app.data.local.WorkVa
import com.oneasmr.app.data.remote.dlsite.DlsiteScrapeException
import com.oneasmr.app.data.repository.RootDisplayNameResolver
import com.oneasmr.app.data.repository.ScanRootPermissionStore
import com.oneasmr.app.data.repository.ScanRootRepository
import com.oneasmr.app.data.repository.ScrapeOutcome
import com.oneasmr.app.data.repository.SingleWorkScraper
import com.oneasmr.app.data.scanner.FakeDocumentFs
import com.oneasmr.app.data.scanner.TrackNodeType
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
import org.junit.Assert.assertFalse
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
 * Task 14 WorkDetailViewModel over REAL Room + real ScanRootRepository (fakes
 * only for the Android-only bits) + a fake in-memory filesystem for the track
 * tree. Virtual scheduler everywhere — no real-time waits (repo flake
 * convention).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkDetailViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var db: OneAsmrDatabase
    private lateinit var rootRepository: ScanRootRepository
    private lateinit var rootsStore: TestDataStoreFile
    private lateinit var fs: FakeDocumentFs
    private lateinit var scraper: FakeSingleWorkScraper
    private lateinit var viewModel: WorkDetailViewModel

    private val workId = "local:RJ000001"
    private val rootUri = "content://tree/primary%3AAsmrLib"

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            OneAsmrDatabase::class.java,
        ).build()
        rootsStore = TestDataStoreFile(tmp.newFile("roots.preferences_pb"))
        rootRepository = ScanRootRepository(
            permissionStore = FakePermissionStore(),
            rootsStore = com.oneasmr.app.data.repository.ScanRootsStore(rootsStore.open()),
            displayNameResolver = FakeResolver(),
        )
        fs = FakeDocumentFs()
        scraper = FakeSingleWorkScraper()
        newViewModel()
    }

    private fun newViewModel() {
        cancelScope()
        viewModel = WorkDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("workId" to workId)),
            workDao = db.workDao(),
            db = db,
            circleDao = db.circleDao(),
            tagDao = db.tagDao(),
            vaDao = db.vaDao(),
            workTagDao = db.workTagDao(),
            workVaDao = db.workVaDao(),
            rootRepository = rootRepository,
            scraper = scraper,
            fsFactory = DocumentFsFactory { fs },
            ioDispatcher = StandardTestDispatcher(scheduler),
            clock = { 1_000_000L },
        )
    }

    private fun cancelScope() {
        if (!::viewModel.isInitialized) return
        viewModel.viewModelScope.coroutineContext[Job]?.cancel()
        scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        cancelScope()
        rootsStore.restart()
        db.close()
        Dispatchers.resetMain()
    }

    private fun seedWork(
        id: String = workId,
        title: String = "测试作品",
        titleSortKey: String = "ceshi zuopin",
        relativeDir: String = "RJ000001",
        rootFolderUri: String = rootUri,
        missing: Boolean = false,
        scrapeStatus: ScrapeStatus = ScrapeStatus.NOT_SCRAPED,
        circleId: String? = null,
        seriesName: String? = null,
        releaseDate: String? = "2024-03-15",
        dlCount: Int? = 1234,
        price: Int? = 1210,
        rateAverage2dp: Double? = 4.52,
        reviewCount: Int? = 88,
    ) {
        kotlinx.coroutines.runBlocking {
            db.workDao().upsert(
                Work(
                    id = id,
                    rootFolderUri = rootFolderUri,
                    relativeDir = relativeDir,
                    title = title,
                    titleSortKey = titleSortKey,
                    circleId = circleId,
                    nsfw = false,
                    releaseDate = releaseDate,
                    dlCount = dlCount,
                    price = price,
                    reviewCount = reviewCount,
                    rateCount = 100,
                    rateAverage2dp = rateAverage2dp,
                    rateCountDetailJson = null,
                    seriesName = seriesName,
                    scrapeStatus = scrapeStatus,
                    missing = missing,
                    addedAt = 1L,
                    updatedAt = 1L,
                ),
            )
        }
    }

    private fun seedMetadata() {
        kotlinx.coroutines.runBlocking {
            db.circleDao().upsertAll(listOf(Circle("社团A", "社团A", "shetuan a")))
            db.tagDao().upsertAll(listOf(Tag("催眠", "催眠"), Tag("バイノーラル", "バイノーラル")))
            db.vaDao().upsertAll(listOf(Va("声优酱", "声优酱", "shengyou jiang")))
            db.workTagDao().insertAll(listOf(WorkTag(workId, "催眠"), WorkTag(workId, "バイノーラル")))
            db.workVaDao().insertAll(listOf(WorkVa(workId, "声优酱")))
            db.workDao().upsert(db.workDao().getById(workId)!!.copy(circleId = "社团A"))
        }
    }

    /** Builds the multi-level mixed-type fixture from plan Task 14 QA. */
    private fun seedWorkFolder() {
        val work = fs.addDirectory(emptyList(), "RJ000001")
        fs.addFile(work, "track1.mp3")
        fs.addFile(work, "track2.wav")
        fs.addFile(work, "readme.txt")
        val cd1 = fs.addDirectory(work, "CD1")
        fs.addFile(cd1, "track3.flac")
        fs.addFile(cd1, "cover.jpg")
        val sub = fs.addDirectory(cd1, "Sub")
        fs.addFile(sub, "track4.ogg")
        fs.addFile(sub, "lyrics.lrc")
    }

    private suspend fun awaitState(): WorkDetailUiState =
        viewModel.uiState.first { !it.loading }

    /** Awaits the WORK row (Room emissions land on real threads — never trust advanceUntilIdle alone). */
    private suspend fun awaitWork(): WorkDetailUiState =
        viewModel.uiState.first { it.work != null }

    private suspend fun awaitTreeReady(): TrackTreeUiState.Ready =
        viewModel.uiState.first { it.tree is TrackTreeUiState.Ready }.tree as TrackTreeUiState.Ready

    /** Authorizes a root so refreshWork has something to scan. */
    private fun addRoot(treeUri: String) {
        kotlinx.coroutines.runBlocking { rootRepository.addRoot(treeUri, null) }
        // entries stateIn runs on the repository's real Default scope — await the
        // recompute instead of racing refreshWork's entries.value read.
        kotlinx.coroutines.runBlocking {
            rootRepository.entries.first {
                it.any { entry -> entry.root.treeUri == treeUri && entry.status == com.oneasmr.app.data.repository.RootGrantStatus.AUTHORIZED }
            }
        }
    }

    private fun assertFileTypes(tree: TrackTreeUiState.Ready) {
        val types = mutableListOf<TrackNodeType>()
        fun walk(node: com.oneasmr.app.data.scanner.TrackNode) {
            node.children.forEach { walk(it) }
            if (!node.isFolder) types += node.type
        }
        walk(tree.root)
        assertEquals(4, types.count { it == TrackNodeType.AUDIO })
        assertEquals(2, types.count { it == TrackNodeType.TEXT })
        assertEquals(1, types.count { it == TrackNodeType.IMAGE })
        assertEquals(7, types.size)
    }

    @Test
    fun `loads work with full metadata`() = runTest(scheduler) {
        seedWork(seriesName = "系列X")
        seedMetadata()
        scheduler.advanceUntilIdle()

        val state = awaitState()
        assertEquals("测试作品", state.work?.title)
        assertEquals("RJ000001", state.work?.id?.let { com.oneasmr.app.data.local.KeySpec.parseWorkId(it)?.rjCode })
        assertEquals("系列X", state.work?.seriesName)
        assertEquals("2024-03-15", state.work?.releaseDate)
        assertEquals(1234, state.work?.dlCount)
        assertEquals(1210, state.work?.price)
        assertEquals(4.52, state.work?.rateAverage2dp)
        assertEquals(88, state.work?.reviewCount)
        assertNull(state.invalid)
        // metadata join; tags/vas come from getTagIdsByWork which orders by id (= name)
        val meta = viewModel.uiState.first { it.circleName != null }
        assertEquals("社团A", meta.circleName)
        assertEquals(listOf("バイノーラル", "催眠"), meta.tags.map { it.name })
        assertEquals(listOf("声优酱"), meta.vas.map { it.name })
    }

    @Test
    fun `builds track tree from fake fs with default deep-collapsed folders`() = runTest(scheduler) {
        seedWork()
        seedWorkFolder()
        scheduler.advanceUntilIdle()

        val tree = awaitTreeReady()
        assertEquals("RJ000001", tree.root.name)
        assertFileTypes(tree)
        assertEquals(7, countFiles(tree.root))
        // natural order: CD1 first (C < t), then readme < track (r < t)
        val topNames = tree.root.children.map { it.name }
        assertEquals(listOf("CD1", "readme.txt", "track1.mp3", "track2.wav"), topNames)
        // default expansion: root + depth-1 folders; deep folders collapsed
        assertTrue(tree.expanded.contains(""))
        assertTrue(tree.expanded.contains("CD1"))
        assertFalse("deep folder must default to collapsed", tree.expanded.contains("CD1/Sub"))
    }

    @Test
    fun `toggleFolder expands and collapses a folder`() = runTest(scheduler) {
        seedWork()
        seedWorkFolder()
        scheduler.advanceUntilIdle()
        awaitTreeReady()

        viewModel.toggleFolder("CD1/Sub")
        val expanded = awaitTreeReady()
        assertTrue(expanded.expanded.contains("CD1/Sub"))

        viewModel.toggleFolder("CD1/Sub")
        val collapsed = viewModel.uiState.first {
            val t = it.tree as? TrackTreeUiState.Ready
            t != null && !t.expanded.contains("CD1/Sub")
        }
        assertFalse((collapsed.tree as TrackTreeUiState.Ready).expanded.contains("CD1/Sub"))
    }

    @Test
    fun `missing work shows invalid state and no tree build`() = runTest(scheduler) {
        seedWork(missing = true)
        scheduler.advanceUntilIdle()

        val state = awaitState()
        assertEquals(InvalidReason.MissingWork, state.invalid)
        assertTrue(state.tree is TrackTreeUiState.Idle)
    }

    @Test
    fun `folder gone shows FolderUnavailable invalid state`() = runTest(scheduler) {
        seedWork() // fs has no RJ000001 folder
        scheduler.advanceUntilIdle()

        val state = viewModel.uiState.first { it.tree is TrackTreeUiState.Error }
        assertTrue(state.invalid is InvalidReason.FolderUnavailable)
        assertTrue(state.tree is TrackTreeUiState.Error)
    }

    @Test
    fun `refreshWork finds the folder and reloads the tree`() = runTest(scheduler) {
        seedWork(missing = true)
        seedWorkFolder()
        addRoot(rootUri)
        scheduler.advanceUntilIdle()
        awaitWork()
        assertEquals(InvalidReason.MissingWork, awaitState().invalid)

        viewModel.refreshWork()
        val state = viewModel.uiState.first {
            it.rescanMessage == "已重新找到该作品并刷新" && it.invalid == null && it.tree is TrackTreeUiState.Ready
        }

        assertEquals("已重新找到该作品并刷新", state.rescanMessage)
        assertNull(state.invalid)
        assertFalse("missing flag must be cleared in the DB", kotlinx.coroutines.runBlocking { db.workDao().getById(workId)!!.missing })
        assertTrue(state.tree is TrackTreeUiState.Ready)
    }

    @Test
    fun `refreshWork marks missing when the folder is gone`() = runTest(scheduler) {
        seedWork()
        addRoot(rootUri)
        scheduler.advanceUntilIdle()
        awaitWork()

        viewModel.refreshWork()
        val state = viewModel.uiState.first {
            it.rescanMessage == "未找到该作品文件夹，已标记为失效" && it.invalid == InvalidReason.MissingWork
        }

        assertEquals("未找到该作品文件夹，已标记为失效", state.rescanMessage)
        assertEquals(InvalidReason.MissingWork, state.invalid)
        assertTrue(kotlinx.coroutines.runBlocking { db.workDao().getById(workId)!!.missing })
    }

    @Test
    fun `refreshWork fails without authorized roots`() = runTest(scheduler) {
        seedWork()
        seedWorkFolder()
        scheduler.advanceUntilIdle()
        awaitWork()

        viewModel.refreshWork()
        val state = viewModel.uiState.first { it.rescanMessage != null }

        assertTrue(state.rescanMessage!!.contains("没有已授权"))
        assertNull(state.invalid)
    }

    @Test
    fun `refreshWork unchanged still rebuilds a stale error tree`() = runTest(scheduler) {
        // Work row exists but fs is empty -> tree Error (FolderUnavailable).
        // Row must be byte-identical to what commitWork would write, so the
        // refresh returns CommitKind.UNCHANGED (no Room emission at all).
        val sortKey = com.oneasmr.app.data.local.SortKeyGenerator.generate("RJ000001")
        seedWork(title = "RJ000001", titleSortKey = sortKey)
        addRoot(rootUri)
        scheduler.advanceUntilIdle()
        viewModel.uiState.first { it.tree is TrackTreeUiState.Error }

        // Folder appears; refreshWork finds it but commits NOTHING (row already
        // identical -> CommitKind.UNCHANGED -> no Room emission). The stale
        // error tree must still rebuild.
        seedWorkFolder()
        viewModel.refreshWork()
        val state = viewModel.uiState.first {
            it.rescanMessage == "作品位置未变化" && it.tree is TrackTreeUiState.Ready
        }

        assertEquals("作品位置未变化", state.rescanMessage)
        assertNull(state.invalid)
        assertTrue(state.tree is TrackTreeUiState.Ready)
    }

    @Test
    fun `scrape success shows message and metadata stays`() = runTest(scheduler) {
        seedWork()
        seedWorkFolder()
        scheduler.advanceUntilIdle()
        awaitWork()
        scraper.outcome = ScrapeOutcome.Success("RJ000001", emptyList())

        viewModel.scrape()
        val state = viewModel.uiState.first { it.scrapeMessage != null }

        assertEquals("刮削成功：RJ000001", state.scrapeMessage)
        assertFalse(state.scraping)
    }

    @Test
    fun `scrape failure shows failure label`() = runTest(scheduler) {
        seedWork()
        scheduler.advanceUntilIdle()
        awaitWork()
        scraper.outcome = ScrapeOutcome.Failed("RJ000001", DlsiteScrapeException.Kind.NETWORK, "timeout")

        viewModel.scrape()
        val state = viewModel.uiState.first { it.scrapeMessage != null }

        assertEquals("刮削失败：网络错误", state.scrapeMessage)
        assertFalse(state.scraping)
    }

    @Test
    fun `concurrent scrape is ignored while running`() = runTest(scheduler) {
        seedWork()
        scheduler.advanceUntilIdle()
        awaitWork()
        scraper.hold = true

        viewModel.scrape()
        viewModel.uiState.first { it.scraping }
        // Drain so the launched scrape actually runs (and suspends on the hold).
        scheduler.advanceUntilIdle()
        assertEquals(1, scraper.calls)

        viewModel.scrape() // second call must be a no-op
        scheduler.advanceUntilIdle()
        assertEquals(1, scraper.calls)
    }

    @Test
    fun `unknown work id shows not-in-library`() = runTest(scheduler) {
        // No seed: workId never appears in the DB.
        val state = viewModel.uiState.first { !it.loading && it.work == null }
        assertNull(state.work)
    }

    private class FakePermissionStore : ScanRootPermissionStore {
        var persisted: Set<String> = emptySet()
        override fun takePersistable(treeUri: String): Boolean {
            persisted = persisted + treeUri
            return true
        }

        override fun releasePersistable(treeUri: String) {
            persisted = persisted - treeUri
        }

        override fun persistedTreeUris(): Set<String> = persisted
    }

    private class FakeResolver : RootDisplayNameResolver {
        override fun resolve(treeUri: String, fallback: String): String =
            treeUri.substringAfterLast('/').ifEmpty { fallback }
    }

    private class FakeSingleWorkScraper : SingleWorkScraper {
        var outcome: ScrapeOutcome = ScrapeOutcome.Failed("RJ000001", null, "not configured")
        var hold = false
        var calls = 0

        override suspend fun scrapeOne(workId: String): ScrapeOutcome {
            calls++
            if (hold) {
                kotlinx.coroutines.awaitCancellation()
            }
            return outcome
        }
    }
}
