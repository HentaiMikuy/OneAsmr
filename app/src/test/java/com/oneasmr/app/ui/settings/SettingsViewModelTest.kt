package com.oneasmr.app.ui.settings

import com.oneasmr.app.data.local.ScrapeStatus
import com.oneasmr.app.data.local.TestDataStoreFile
import com.oneasmr.app.data.local.Work
import com.oneasmr.app.data.local.settings.SettingsStore
import com.oneasmr.app.data.repository.BundledCoverLocator
import com.oneasmr.app.data.repository.CoverDownloadResult
import com.oneasmr.app.data.repository.CoverDownloader
import com.oneasmr.app.data.repository.CoverStore
import com.oneasmr.app.data.repository.RootDisplayNameResolver
import com.oneasmr.app.data.repository.ScanRootPermissionStore
import com.oneasmr.app.data.repository.ScanRootRepository
import com.oneasmr.app.data.repository.ScanRootsStore
import com.oneasmr.app.data.local.PlaybackStateDao
import com.oneasmr.app.data.local.PlaybackState
import com.oneasmr.app.data.local.Review
import com.oneasmr.app.data.local.ProgressState
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * SettingsViewModel spec (plan Task 27): cache management semantics.
 *
 * - Cover-cache clear is CONFIRM-GATED and touches covers/ ONLY: reviews and
 *   playback_state rows survive a clear; cancel leaves everything intact.
 * - The playback-progress clear is a SEPARATE optional action, also
 *   confirm-gated, and touches playback_state only.
 * - Usage and clear work off DISK truth (covers seeded outside the store, as
 *   after a process restart) and survive the covers/ dir being deleted
 *   externally (no crash, usage reports 0).
 * - Batch-scrape entry status counts reflect the real batch target predicate
 *   (missing works excluded).
 *
 * All collaborators are real or in-memory fakes; the VM's Main dispatcher is a
 * virtual scheduler — no real-time waits (repo flake convention).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var settingsDataStore: TestDataStoreFile
    private lateinit var rootsDataStore: TestDataStoreFile
    private lateinit var settings: SettingsStore
    private lateinit var rootsScope: kotlinx.coroutines.CoroutineScope
    private lateinit var rootsRepo: ScanRootRepository
    private lateinit var coverStore: CoverStore
    private lateinit var coverDir: File
    private var capBytes: Long = Long.MAX_VALUE
    private lateinit var workDao: FakeWorkDao
    private lateinit var playbackDao: FakePlaybackStateDao
    private lateinit var harness: Harness

    private class FakeWorkDao : com.oneasmr.app.data.local.WorkDao {
        val rows = LinkedHashMap<String, Work>()
        override suspend fun countByScrapeStatus(status: ScrapeStatus): Int =
            rows.values.count { !it.missing && it.scrapeStatus == status }

        override suspend fun upsertAll(works: List<Work>) = works.forEach { rows[it.id] = it }
        override suspend fun upsert(work: Work) {
            rows[work.id] = work
        }

        override suspend fun getById(id: String): Work? = rows[id]
        override fun getByIdFlow(id: String): kotlinx.coroutines.flow.Flow<Work?> =
            kotlinx.coroutines.flow.MutableStateFlow(rows[id])
        override suspend fun deleteById(id: String) {
            rows.remove(id)
        }

        override suspend fun count(): Int = rows.size
        override suspend fun getAll(): List<Work> = rows.values.toList()
        override fun getAllFlow(): kotlinx.coroutines.flow.Flow<List<Work>> =
            kotlinx.coroutines.flow.MutableStateFlow(rows.values.toList())
        override fun countFlow(): kotlinx.coroutines.flow.Flow<Int> =
            kotlinx.coroutines.flow.MutableStateFlow(rows.size)
        override fun pagingSourceById(): androidx.paging.PagingSource<Int, com.oneasmr.app.data.local.WorkListItem> =
            throw UnsupportedOperationException("not used by SettingsViewModel")
        override fun pagingSourceRaw(query: androidx.sqlite.db.SupportSQLiteQuery): androidx.paging.PagingSource<Int, com.oneasmr.app.data.local.WorkListItem> =
            throw UnsupportedOperationException("not used by SettingsViewModel")
        override fun pagingSource(
            order: com.oneasmr.app.data.local.WorkOrder,
            descending: Boolean,
            keyword: String?,
            randomSeed: Long,
            filter: com.oneasmr.app.data.local.WorkFilter?,
        ): androidx.paging.PagingSource<Int, com.oneasmr.app.data.local.WorkListItem> =
            throw UnsupportedOperationException("not used by SettingsViewModel")
        override suspend fun getListItemById(id: String): com.oneasmr.app.data.local.WorkListItem? = null
        override suspend fun markMissingInternal(ids: List<String>, now: Long) = Unit
        override suspend fun updateAgeRating(workId: String, rating: com.oneasmr.app.data.local.AgeRating?, now: Long) {
            rows[workId]?.let { rows[workId] = it.copy(ageRating = rating, updatedAt = now) }
        }
        override suspend fun getPageRaw(query: androidx.sqlite.db.SupportSQLiteQuery): List<Work> = emptyList()
        override suspend fun searchRaw(query: androidx.sqlite.db.SupportSQLiteQuery): List<com.oneasmr.app.data.local.WorkSearchHit> = emptyList()
        override suspend fun getWorksByCircle(circleId: String): List<Work> = emptyList()
        override suspend fun getWorksByTag(tagId: String): List<Work> = emptyList()
        override suspend fun getWorksByVa(vaId: String): List<Work> = emptyList()
        override fun pagingSourceByCircle(circleId: String): androidx.paging.PagingSource<Int, com.oneasmr.app.data.local.WorkListItem> =
            throw UnsupportedOperationException("not used by SettingsViewModel")
        override fun pagingSourceByTag(tagId: String): androidx.paging.PagingSource<Int, com.oneasmr.app.data.local.WorkListItem> =
            throw UnsupportedOperationException("not used by SettingsViewModel")
        override fun pagingSourceByVa(vaId: String): androidx.paging.PagingSource<Int, com.oneasmr.app.data.local.WorkListItem> =
            throw UnsupportedOperationException("not used by SettingsViewModel")
    }

    private class FakePlaybackStateDao : PlaybackStateDao {
        val rows = mutableMapOf<String, PlaybackState>()
        override suspend fun upsert(state: PlaybackState) {
            rows[state.trackKey] = state
        }

        override suspend fun get(trackKey: String): PlaybackState? = rows[trackKey]
        override suspend fun delete(trackKey: String) {
            rows.remove(trackKey)
        }

        override suspend fun getAllForWork(prefix: String): List<PlaybackState> =
            rows.values.filter { it.trackKey.startsWith(prefix) }

        override fun getAllForWorkFlow(prefix: String): kotlinx.coroutines.flow.Flow<List<PlaybackState>> =
            kotlinx.coroutines.flow.flowOf(rows.values.filter { it.trackKey.startsWith(prefix) })

        override suspend fun deleteForWorkPrefix(prefix: String) {
            rows.keys.filter { it.startsWith(prefix) }.forEach { rows.remove(it) }
        }

        override suspend fun clearAll() {
            rows.clear()
        }
    }

    private class FakePermissionStore : ScanRootPermissionStore {
        override fun takePersistable(treeUri: String): Boolean = true
        override fun releasePersistable(treeUri: String) = Unit
        override fun persistedTreeUris(): Set<String> = emptySet()
    }

    private class FakeResolver : RootDisplayNameResolver {
        override fun resolve(treeUri: String, fallback: String): String = fallback
    }

    private class Harness(
        settings: SettingsStore,
        rootsRepository: ScanRootRepository,
        coverStore: CoverStore,
        workDao: FakeWorkDao,
        playbackDao: FakePlaybackStateDao,
        val viewModel: SettingsViewModel,
    )

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        settingsDataStore = TestDataStoreFile(tmp.newFile("settings.preferences_pb"))
        rootsDataStore = TestDataStoreFile(tmp.newFile("scan_roots.preferences_pb"))
        settings = SettingsStore(settingsDataStore.open())
        rootsScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        rootsRepo = ScanRootRepository(
            permissionStore = FakePermissionStore(),
            rootsStore = ScanRootsStore(rootsDataStore.open()),
            displayNameResolver = FakeResolver(),
            scope = rootsScope,
        )
        coverDir = tmp.newFolder("covers")
        capBytes = Long.MAX_VALUE
        coverStore = CoverStore(
            coversDir = coverDir,
            downloader = object : CoverDownloader {
                override suspend fun download(url: String, referer: String?, target: File): CoverDownloadResult =
                    CoverDownloadResult.NOT_FOUND
            },
            bundledLocator = object : BundledCoverLocator {
                override suspend fun findBundledCover(rootFolderUri: String, relativeDir: String): String? = null
            },
            cacheCapBytes = { capBytes },
            // 磁盘枚举/删除走虚拟调度器:与 Main 共享同一 scheduler,runCurrent 可确定推进。
            ioDispatcher = StandardTestDispatcher(scheduler),
        )
        workDao = FakeWorkDao()
        playbackDao = FakePlaybackStateDao()
        harness = Harness(settings, rootsRepo, coverStore, workDao, playbackDao, buildViewModel())
    }

    /** Builds the VM over the current fakes; call AFTER seeding rows that its init reads. */
    private fun buildViewModel(): SettingsViewModel = SettingsViewModel(
        settingsStore = settings,
        scanRootRepository = rootsRepo,
        coverStore = coverStore,
        playbackStateDao = playbackDao,
        workDao = workDao,
    )

    @After
    fun tearDown() {
        // 晚到续体竞态的彻底拆除(套件变大后两种形态都复现过:直接崩在
        // 下个用例 / 记作"测试开始前的未捕获异常"):VM 里 withContext(IO)
        // 的收尾在真实 IO 线程完成后要向 Main 派发,resetMain 之后这次
        // 派发必炸。cancel 不等待、advanceUntilIdle 管不到真实线程 ——
        // 必须在 Main 仍有效期间,交替推调度器 + 真实等待,直到 VM 作业
        // 树完全终止;roots 收集器同理 join;最后才 resetMain。
        val vmJob = harness.viewModel.viewModelScope.coroutineContext[kotlinx.coroutines.Job]
        vmJob?.cancel()
        runBlocking {
            kotlinx.coroutines.withTimeout(5_000) {
                while (vmJob?.isCompleted == false) {
                    scheduler.advanceUntilIdle()
                    kotlinx.coroutines.delay(10)
                }
            }
            rootsScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
        scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    private fun seedCoverFiles(vararg sizes: Int): Long {
        sizes.forEachIndexed { index, size ->
            File(coverDir, "RJ10000${index + 1}_img_main.jpg").writeBytes(ByteArray(size))
        }
        return sizes.sumOf { it.toLong() }
    }

    private fun work(id: String, status: ScrapeStatus, missing: Boolean = false) = Work(
        id = id,
        rootFolderUri = "content://tree/rootA",
        relativeDir = "dir/$id",
        title = id,
        titleSortKey = id,
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
        missing = missing,
        addedAt = 1L,
        updatedAt = 1L,
    )

    private fun review(workId: String, rating: Int) =
        Review(workId, rating, null, ProgressState.listened, 1L)

    private fun playback(trackKey: String) =
        PlaybackState(trackKey, positionMs = 12_000L, durationMs = 600_000L, updatedAt = 1L)

    // ---- usage / clear against DISK truth ----------------------------------

    @Test
    fun `usage reports covers seeded outside the store`() = runTest(scheduler) {
        val expected = seedCoverFiles(100, 2048, 4096)
        harness.viewModel.refreshCacheUsage()
        assertEquals(expected, harness.viewModel.cacheUsageBytes.first { it == expected })
    }

    @Test
    fun `confirm clears covers but never touches reviews or playback state`() = runTest(scheduler) {
        val expected = seedCoverFiles(100, 2048)
        workDao.rows["local:RJ100001"] = work("local:RJ100001", ScrapeStatus.OK)
        playbackDao.rows["local:RJ100001:3"] = playback("local:RJ100001:3")
        harness.viewModel.refreshCacheUsage()
        runCurrent()
        assertEquals(expected, harness.viewModel.cacheUsageBytes.value)

        harness.viewModel.requestClearCoverCache()
        assertTrue(harness.viewModel.clearCoverCacheDialogVisible.value)
        harness.viewModel.confirmClearCoverCache()
        runCurrent()

        assertEquals(0L, harness.viewModel.cacheUsageBytes.value)
        assertEquals(0, coverDir.listFiles()?.size ?: -1)
        assertTrue(!harness.viewModel.clearCoverCacheDialogVisible.value)
        assertEquals(1, workDao.rows.size)
        assertEquals(1, playbackDao.rows.size)
    }

    @Test
    fun `cancel leaves covers reviews and playback state intact`() = runTest(scheduler) {
        val expected = seedCoverFiles(100, 2048)
        playbackDao.rows["local:RJ100001:3"] = playback("local:RJ100001:3")
        harness.viewModel.refreshCacheUsage()
        runCurrent()
        assertEquals(expected, harness.viewModel.cacheUsageBytes.value)

        harness.viewModel.requestClearCoverCache()
        harness.viewModel.cancelClearCoverCache()
        runCurrent()

        assertTrue(!harness.viewModel.clearCoverCacheDialogVisible.value)
        assertEquals(expected, harness.viewModel.cacheUsageBytes.value)
        assertEquals(2, coverDir.listFiles()?.size)
        assertEquals(1, playbackDao.rows.size)
    }

    @Test
    fun `playback progress clear is a separate confirmed action touching playback only`() = runTest(scheduler) {
        seedCoverFiles(100, 2048)
        workDao.rows["local:RJ100001"] = work("local:RJ100001", ScrapeStatus.OK)
        playbackDao.rows["local:RJ100001:3"] = playback("local:RJ100001:3")

        harness.viewModel.requestClearPlaybackProgress()
        assertTrue(harness.viewModel.clearPlaybackProgressDialogVisible.value)
        harness.viewModel.confirmClearPlaybackProgress()
        runCurrent()

        assertEquals(0, playbackDao.rows.size)
        assertEquals(1, workDao.rows.size)
        assertEquals(2, coverDir.listFiles()?.size)
    }

    @Test
    fun `playback progress clear cancel keeps every row`() = runTest(scheduler) {
        playbackDao.rows["local:RJ100001:3"] = playback("local:RJ100001:3")

        harness.viewModel.requestClearPlaybackProgress()
        harness.viewModel.cancelClearPlaybackProgress()
        runCurrent()

        assertTrue(!harness.viewModel.clearPlaybackProgressDialogVisible.value)
        assertEquals(1, playbackDao.rows.size)
    }

    @Test
    fun `clear still works when the covers dir was deleted externally`() = runTest(scheduler) {
        seedCoverFiles(100)
        assertTrue(coverDir.deleteRecursively())
        assertTrue(!coverDir.exists())

        harness.viewModel.refreshCacheUsage()
        runCurrent()
        assertEquals(0L, harness.viewModel.cacheUsageBytes.value)

        harness.viewModel.requestClearCoverCache()
        harness.viewModel.confirmClearCoverCache()
        runCurrent()

        assertEquals(0L, harness.viewModel.cacheUsageBytes.value)
        assertTrue(!coverDir.exists())
    }

    // ---- batch-scrape entry status ------------------------------------------

    @Test
    fun `batch status counts follow the batch target predicate`() = runTest(scheduler) {
        workDao.rows["local:RJ100001"] = work("local:RJ100001", ScrapeStatus.NOT_SCRAPED)
        workDao.rows["local:RJ100002"] = work("local:RJ100002", ScrapeStatus.NOT_SCRAPED)
        workDao.rows["local:RJ100003"] = work("local:RJ100003", ScrapeStatus.FAILED)
        workDao.rows["local:RJ100004"] = work("local:RJ100004", ScrapeStatus.OK)
        workDao.rows["local:RJ100005"] = work("local:RJ100005", ScrapeStatus.NOT_SCRAPED, missing = true)

        val vm = buildViewModel()
        assertEquals(2, vm.batchPendingCount.first { it == 2 })
        assertEquals(1, vm.batchFailedCount.first { it == 1 })
    }

    // ---- cap stepping -------------------------------------------------------

    @Test
    fun `cap step persists through the store with clamping`() = runTest(scheduler) {
        harness.viewModel.stepCacheCap(100)
        assertEquals(600, harness.viewModel.cacheCapMb.first { it == 600 })

        harness.viewModel.stepCacheCap(-1_000)
        assertEquals(1, harness.viewModel.cacheCapMb.first { it == 1 })
    }

    // ---- byte formatting ----------------------------------------------------

    @Test
    fun `formatCacheBytes renders b kb and mb`() {
        assertEquals("0 B", formatCacheBytes(0))
        assertEquals("512 B", formatCacheBytes(512))
        assertEquals("2 KB", formatCacheBytes(2048))
        assertEquals("1.5 MB", formatCacheBytes(1572864L))
    }

    // ---- asmr.one fallback source ------------------------------------------

    @Test
    fun `asmr one settings emit defaults`() = runTest(scheduler) {
        assertEquals(true, harness.viewModel.asmrOneFallbackEnabled.first())
        assertEquals("", harness.viewModel.asmrOneBaseUrl.first())
    }

    @Test
    fun `fallback toggle persists through the store`() = runTest(scheduler) {
        harness.viewModel.setAsmrOneFallbackEnabled(false)
        assertEquals(false, harness.viewModel.asmrOneFallbackEnabled.first { !it })
        assertEquals(false, settings.asmrOneFallbackEnabled.first())

        harness.viewModel.setAsmrOneFallbackEnabled(true)
        assertEquals(true, harness.viewModel.asmrOneFallbackEnabled.first { it })
    }

    @Test
    fun `mirror base url persists and blank restores default`() = runTest(scheduler) {
        harness.viewModel.setAsmrOneBaseUrl("https://api.asmr-100.com")
        assertEquals(
            "https://api.asmr-100.com",
            harness.viewModel.asmrOneBaseUrl.first { it.isNotBlank() },
        )
        assertEquals("https://api.asmr-100.com", settings.asmrOneBaseUrl.first())

        harness.viewModel.setAsmrOneBaseUrl("")
        assertEquals("", harness.viewModel.asmrOneBaseUrl.first { it.isBlank() })
    }

    @Test
    fun `mirror dialog visibility toggles and confirm hides it`() = runTest(scheduler) {
        assertTrue(!harness.viewModel.asmrOneMirrorDialogVisible.value)

        harness.viewModel.showAsmrOneMirrorDialog()
        assertTrue(harness.viewModel.asmrOneMirrorDialogVisible.value)

        harness.viewModel.cancelAsmrOneMirrorDialog()
        assertTrue(!harness.viewModel.asmrOneMirrorDialogVisible.value)

        harness.viewModel.showAsmrOneMirrorDialog()
        harness.viewModel.setAsmrOneBaseUrl("https://api.asmr-100.com")
        assertTrue(!harness.viewModel.asmrOneMirrorDialogVisible.value)
        assertEquals(
            "https://api.asmr-100.com",
            harness.viewModel.asmrOneBaseUrl.first { it.isNotBlank() },
        )
    }

    // ---- NSFW safe mode toggle ----------------------------------------------

    @Test
    fun `nsfw toggle emits default true and persists through the store`() = runTest(scheduler) {
        assertEquals(true, harness.viewModel.nsfwEnabled.first())

        harness.viewModel.setNsfwEnabled(false)
        assertEquals(false, harness.viewModel.nsfwEnabled.first { !it })
        assertEquals(false, settings.nsfwEnabled.first())

        harness.viewModel.setNsfwEnabled(true)
        assertEquals(true, harness.viewModel.nsfwEnabled.first { it })
    }

    // ---- 冷启动启动动画开关 --------------------------------------------------

    @Test
    fun `launch animation toggle emits default true and persists through the store`() = runTest(scheduler) {
        assertEquals(true, harness.viewModel.launchAnimationEnabled.first())

        harness.viewModel.setLaunchAnimationEnabled(false)
        assertEquals(false, harness.viewModel.launchAnimationEnabled.first { !it })
        assertEquals(false, settings.launchAnimationEnabled.first())

        harness.viewModel.setLaunchAnimationEnabled(true)
        assertEquals(true, harness.viewModel.launchAnimationEnabled.first { it })
    }
}
