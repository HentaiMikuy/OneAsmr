package com.oneasmr.app.ui.library

import android.app.Application
import android.content.pm.ApplicationInfo
import com.oneasmr.app.data.local.ScrapeStatus
import com.oneasmr.app.data.local.WorkDao
import com.oneasmr.app.data.local.settings.SettingsStore
import com.oneasmr.app.data.repository.BatchPhase
import com.oneasmr.app.data.repository.BatchScrapeState
import com.oneasmr.app.data.repository.ScrapeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope

/** Which works a batch run targets. */
enum class BatchTarget { NOT_SCRAPED, FAILED }

/**
 * Task 11 library scrape state, combined for the screen.
 *
 * [scrapingWorkId] drives the per-row in-progress indicator; [singleMessage]
 * is a one-shot success/error snackbar payload consumed via
 * [consumeSingleMessage]; [batch] carries the live queue state; [batchNotice]
 * is a one-shot "nothing to scrape" message.
 */
data class ScrapeUiState(
    val scrapingWorkId: String? = null,
    val singleMessage: String? = null,
    val batch: BatchScrapeState = BatchScrapeState.IDLE,
    val batchNotice: String? = null,
    /** 成功刮削完成计数(单条成功 + 批量收尾各 +1):列表页用它判断返回时是否需要刷新分页。 */
    val completedVersion: Int = 0,
) {
    companion object {
        val EMPTY = ScrapeUiState()
    }
}

/**
 * Task 11 scrape entry points for the library screen: single-work scrape
 * (with force-rescrape on already-OK works), batch queue over NOT_SCRAPED /
 * FAILED works, mid-queue cancellation, and the debug-only scraper base-url
 * override (FLAG_DEBUGGABLE gate — release builds cannot set it).
 */
@HiltViewModel
class ScrapeViewModel @Inject constructor(
    private val repo: ScrapeRepository,
    private val workDao: WorkDao,
    private val settingsStore: SettingsStore,
    application: Application,
) : androidx.lifecycle.ViewModel() {

    /** True only on debuggable builds — gates the base-url override UI. */
    val isDebugBuild: Boolean =
        (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    val scraperBaseUrlOverride: StateFlow<String> =
        settingsStore.scraperBaseUrlOverride
            .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _uiState = MutableStateFlow(ScrapeUiState.EMPTY)
    val uiState: StateFlow<ScrapeUiState> = _uiState.asStateFlow()

    private val batchFlow = MutableStateFlow(BatchScrapeState.IDLE)
    private var batchJob: Job? = null

    init {
        viewModelScope.launch {
            batchFlow.collect { state ->
                _uiState.update {
                    // 批量收尾(FINISHED)时 +1,列表页据此刷新分页。
                    val finished = state.phase == BatchPhase.FINISHED && it.batch.phase != BatchPhase.FINISHED
                    it.copy(
                        batch = state,
                        completedVersion = it.completedVersion + if (finished) 1 else 0,
                    )
                }
            }
        }
    }

    fun scrapeSingle(workId: String) {
        if (_uiState.value.scrapingWorkId != null) return
        _uiState.update { it.copy(scrapingWorkId = workId) }
        viewModelScope.launch {
            val outcome = repo.scrapeOne(workId)
            _uiState.update {
                it.copy(
                    scrapingWorkId = null,
                    singleMessage = when (outcome) {
                        is com.oneasmr.app.data.repository.ScrapeOutcome.Success ->
                            "刮削成功：${outcome.rjCode}"
                        is com.oneasmr.app.data.repository.ScrapeOutcome.Failed ->
                            "刮削失败：${failureLabel(outcome.kind)}"
                    },
                    // 只有成功才改库,版本号也随之 +1(失败不触发列表刷新)。
                    completedVersion = it.completedVersion +
                        (if (outcome is com.oneasmr.app.data.repository.ScrapeOutcome.Success) 1 else 0),
                )
            }
        }
    }

    fun startBatch(target: BatchTarget) {
        if (_uiState.value.batch.phase == BatchPhase.RUNNING) return
        batchJob?.cancel()
        batchJob = viewModelScope.launch {
            val targets = workDao.getAll()
                .filter { !it.missing && it.scrapeStatus == if (target == BatchTarget.FAILED) ScrapeStatus.FAILED else ScrapeStatus.NOT_SCRAPED }
                .map { it.id }
            if (targets.isEmpty()) {
                _uiState.update {
                    it.copy(batchNotice = if (target == BatchTarget.FAILED) "没有刮削失败的作品" else "没有未刮削的作品")
                }
                return@launch
            }
            runCatching { repo.batchScrape(targets, batchFlow) }
        }
    }

    fun cancelBatch() {
        if (_uiState.value.batch.phase == BatchPhase.RUNNING) {
            batchJob?.cancel()
        }
    }

    fun dismissBatchSummary() {
        if (_uiState.value.batch.phase == BatchPhase.FINISHED) {
            _uiState.update { it.copy(batch = BatchScrapeState.IDLE) }
        }
    }

    fun consumeSingleMessage() {
        _uiState.update { it.copy(singleMessage = null) }
    }

    fun consumeBatchNotice() {
        _uiState.update { it.copy(batchNotice = null) }
    }

    fun setScraperBaseUrlOverride(url: String) {
        viewModelScope.launch { settingsStore.setScraperBaseUrlOverride(url) }
    }

    /** Reads the current override synchronously for the debug dialog's initial value. */
    fun currentScraperBaseUrlOverride(): String = scraperBaseUrlOverride.value

    private fun failureLabel(kind: com.oneasmr.app.data.remote.dlsite.DlsiteScrapeException.Kind?): String =
        when (kind) {
            com.oneasmr.app.data.remote.dlsite.DlsiteScrapeException.Kind.NETWORK -> "网络错误"
            com.oneasmr.app.data.remote.dlsite.DlsiteScrapeException.Kind.NOT_FOUND -> "作品不存在(404)"
            com.oneasmr.app.data.remote.dlsite.DlsiteScrapeException.Kind.BLOCKED -> "被反爬拦截(403)"
            com.oneasmr.app.data.remote.dlsite.DlsiteScrapeException.Kind.PARSE_ERROR -> "页面解析失败"
            null -> "未知错误"
        }
}
