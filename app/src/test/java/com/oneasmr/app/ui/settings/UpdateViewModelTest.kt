package com.oneasmr.app.ui.settings

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.oneasmr.app.data.update.UpdateCache
import com.oneasmr.app.data.update.UpdateCheckResult
import com.oneasmr.app.data.update.UpdateDownloader
import com.oneasmr.app.data.update.UpdateDownloadState
import com.oneasmr.app.data.update.UpdateInfo
import com.oneasmr.app.data.update.UpdateRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateViewModelTest {
    private val info = UpdateInfo("99.0.0", "https://example.com/app.apk", 100, "说明")
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var observers: CoroutineScope
    private lateinit var downloader: FakeDownloader
    private lateinit var cache: UpdateCache
    private var checkCount = 0
    private val viewModels = mutableListOf<UpdateViewModel>()

    private class FakeDownloader : UpdateDownloader {
        val state = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.None)
        val enqueued = mutableListOf<UpdateInfo>()
        var refreshed = false
        override fun observe() = state
        override suspend fun enqueue(info: UpdateInfo) {
            enqueued += info
            state.value = UpdateDownloadState.Downloading(info, 0, info.sizeBytes)
        }
        override fun refresh() { refreshed = true }
    }

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        observers = CoroutineScope(StandardTestDispatcher(scheduler))
        downloader = FakeDownloader()
        cache = UpdateCache()
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        observers.cancel()
        scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    private fun createViewModel() = UpdateViewModel(
        updateRepository = object : UpdateRepository {
            override suspend fun checkForUpdate(): UpdateCheckResult {
                checkCount++
                return UpdateCheckResult.NewVersion(info)
            }
        },
        updateCache = cache,
        updateDownloader = downloader,
        appContext = RuntimeEnvironment.getApplication(),
    ).also {
        viewModels += it
        it.state.launchIn(observers)
    }

    @Test
    fun `restored completed task takes priority over startup check and keeps installation available`() = runTest(scheduler) {
        val file = File("/download/update.apk")
        downloader.state.value = UpdateDownloadState.Downloaded(info, file)
        cache.publish(UpdateCheckResult.NewVersion(info))
        val vm = createViewModel()
        runCurrent()
        vm.checkForUpdate()
        assertEquals(UpdateUiState.Downloaded(info, file), vm.state.value)
        assertEquals(0, checkCount)
        vm.refreshDownloadState()
        assertTrue(downloader.refreshed)
        assertEquals(UpdateUiState.Downloaded(info, file), vm.state.value)
    }

    @Test
    fun `download retry uses saved release without another API request`() = runTest(scheduler) {
        downloader.state.value = UpdateDownloadState.Failed(info, "网络失败")
        val vm = createViewModel()
        runCurrent()
        vm.retryAfterError()
        runCurrent()
        assertEquals(listOf(info), downloader.enqueued)
        assertEquals(0, checkCount)
        assertTrue(vm.state.value is UpdateUiState.Downloading)
    }

    @Test
    fun `new settings view model observes existing progress without enqueueing another task`() = runTest(scheduler) {
        downloader.state.value = UpdateDownloadState.Downloading(info, 40, 100, "等待网络连接")
        val first = createViewModel()
        runCurrent()
        first.viewModelScope.cancel()
        val restored = createViewModel()
        runCurrent()
        assertEquals(UpdateUiState.Downloading(info, 40, 100, "等待网络连接"), restored.state.value)
        assertTrue(downloader.enqueued.isEmpty())
    }

    @Test
    fun `double click download only starts one task`() = runTest(scheduler) {
        cache.publish(UpdateCheckResult.NewVersion(info))
        val vm = createViewModel()
        runCurrent()
        vm.download()
        vm.download()
        runCurrent()
        assertEquals(listOf(info), downloader.enqueued)
    }

    @Test
    fun `missing task clears stale progress after a terminal event was missed while in background`() = runTest(scheduler) {
        cache.publish(UpdateCheckResult.NewVersion(info))
        downloader.state.value = UpdateDownloadState.Downloading(info, 40, 100)
        val vm = createViewModel()
        runCurrent()
        downloader.state.value = UpdateDownloadState.None
        runCurrent()
        assertEquals(UpdateUiState.Available(info), vm.state.value)
    }

    @Test
    fun `installation permission follows real system grant and does not default to allowed`() = runTest(scheduler) {
        val vm = createViewModel()
        val pm = RuntimeEnvironment.getApplication().packageManager
        shadowOf(pm).setCanRequestPackageInstalls(false)
        assertFalse(vm.canRequestInstallPermission())
        shadowOf(pm).setCanRequestPackageInstalls(true)
        assertTrue(vm.canRequestInstallPermission())
    }
}
