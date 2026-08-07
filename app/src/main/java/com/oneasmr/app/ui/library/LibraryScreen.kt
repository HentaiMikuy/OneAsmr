package com.oneasmr.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.oneasmr.app.data.local.KeySpec
import com.oneasmr.app.data.local.OneAsmrDatabase
import com.oneasmr.app.data.local.ScrapeStatus
import com.oneasmr.app.data.local.Work
import com.oneasmr.app.data.local.WorkDao
import com.oneasmr.app.data.repository.BatchPhase
import com.oneasmr.app.data.repository.BatchScrapeState
import com.oneasmr.app.data.repository.ScanRootRepository
import com.oneasmr.app.data.scanner.RescanSummary
import com.oneasmr.app.data.scanner.ScanBookkeepingStore
import com.oneasmr.app.data.scanner.ScanPhase
import com.oneasmr.app.data.scanner.ScanProgress
import com.oneasmr.app.data.scanner.ScanProgressStore
import com.oneasmr.app.data.scanner.removeWork
import com.oneasmr.app.ui.common.formatScanSummary
import com.oneasmr.app.worker.ScanController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Task 8 library home: empty-library onboarding (no roots + no works), the
 * scan trigger + live progress + completion summary, and the MINIMAL work
 * list (title / RJ / missing state) so the missing-grey-out and manual-remove
 * flows are real. Task 12 replaces the list with the full grid/paging UI.
 */
@Composable
fun LibraryScreen(
    onOpenRootFolders: () -> Unit = {},
    onOpenWork: (String) -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
    scrapeViewModel: ScrapeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scrapeState by scrapeViewModel.uiState.collectAsStateWithLifecycle()
    var removeCandidate by remember { mutableStateOf<Work?>(null) }
    var forceRescrapeCandidate by remember { mutableStateOf<Work?>(null) }
    var showBatchMenu by remember { mutableStateOf(false) }
    var showDebugUrlDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(scrapeState.singleMessage) {
        scrapeState.singleMessage?.let {
            snackbarHostState.showSnackbar(it)
            scrapeViewModel.consumeSingleMessage()
        }
    }
    LaunchedEffect(scrapeState.batchNotice) {
        scrapeState.batchNotice?.let {
            snackbarHostState.showSnackbar(it)
            scrapeViewModel.consumeBatchNotice()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "作品库",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            if (state.hasRoots && state.progress.phase != ScanPhase.SCANNING) {
                TextButton(onClick = viewModel::startScan) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("扫描")
                }
            }
            Box {
                IconButton(
                    onClick = { showBatchMenu = true },
                    enabled = state.works.isNotEmpty() && scrapeState.batch.phase != BatchPhase.RUNNING,
                ) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(
                    expanded = showBatchMenu,
                    onDismissRequest = { showBatchMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("批量刮削未刮削作品") },
                        onClick = {
                            showBatchMenu = false
                            scrapeViewModel.startBatch(BatchTarget.NOT_SCRAPED)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("批量重试刮削失败作品") },
                        onClick = {
                            showBatchMenu = false
                            scrapeViewModel.startBatch(BatchTarget.FAILED)
                        },
                    )
                    if (scrapeViewModel.isDebugBuild) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("刮削地址（调试）") },
                            onClick = {
                                showBatchMenu = false
                                showDebugUrlDialog = true
                            },
                        )
                    }
                }
            }
        }

        if (state.progress.phase == ScanPhase.SCANNING) {
            ScanProgressBanner(progress = state.progress, onCancel = viewModel::cancelScan)
        }

        BatchBanner(
            state = scrapeState.batch,
            onCancel = scrapeViewModel::cancelBatch,
            onDismiss = scrapeViewModel::dismissBatchSummary,
        )

        when {
            state.showOnboarding -> EmptyLibrary(onOpenRootFolders = onOpenRootFolders)
            state.works.isEmpty() -> ScanPrompt(onScan = viewModel::startScan)
            else -> {
                val lastSummary = state.lastSummary
                if (lastSummary != null) {
                    Text(
                        "上次扫描：${formatScanSummary(lastSummary)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                WorkList(
                    works = state.works,
                    onOpenWork = onOpenWork,
                    onRemove = { removeCandidate = it },
                    onScrape = { work ->
                        if (work.scrapeStatus == ScrapeStatus.OK) forceRescrapeCandidate = work
                        else scrapeViewModel.scrapeSingle(work.id)
                    },
                    scrapingWorkId = scrapeState.scrapingWorkId,
                )
            }
        }

    }

    // The LazyColumn above claims all remaining height, so a snackbar as a
    // Column child would be measured at zero height and never render (caught
    // by device QA). Host it in an overlay Box instead.
    Box(Modifier.fillMaxSize()) {
        SnackbarHost(
            snackbarHostState,
            Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }

    removeCandidate?.let { work ->
        AlertDialog(
            onDismissRequest = { removeCandidate = null },
            title = { Text("移除作品？") },
            text = { Text("将从作品库移除「${work.title}」（含评分与进度记录），不会删除磁盘上的文件。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeWork(work.id)
                        removeCandidate = null
                    },
                ) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { removeCandidate = null }) { Text("取消") }
            },
        )
    }

    forceRescrapeCandidate?.let { work ->
        AlertDialog(
            onDismissRequest = { forceRescrapeCandidate = null },
            title = { Text("强制重新刮削？") },
            text = { Text("「${work.title}」已刮削过。重新刮削将覆盖现有元数据与封面。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scrapeViewModel.scrapeSingle(work.id)
                        forceRescrapeCandidate = null
                    },
                ) { Text("刮削") }
            },
            dismissButton = {
                TextButton(onClick = { forceRescrapeCandidate = null }) { Text("取消") }
            },
        )
    }

    if (showDebugUrlDialog) {
        var url by remember { mutableStateOf(scrapeViewModel.currentScraperBaseUrlOverride()) }
        AlertDialog(
            onDismissRequest = { showDebugUrlDialog = false },
            title = { Text("刮削地址（调试）") },
            text = {
                Column {
                    Text(
                        "留空使用 DLsite 官方地址；模拟器上可指向 http://10.0.2.2:端口 以复用宿主代理。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("base URL") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scrapeViewModel.setScraperBaseUrlOverride(url)
                        showDebugUrlDialog = false
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showDebugUrlDialog = false }) { Text("取消") }
            },
        )
    }
}

/**
 * Task 11 batch-queue banner: live progress + counters + cancel while
 * RUNNING, terminal summary + dismiss when FINISHED (cancelled or complete).
 */
@Composable
private fun BatchBanner(state: BatchScrapeState, onCancel: () -> Unit, onDismiss: () -> Unit) {
    when (state.phase) {
        BatchPhase.RUNNING -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            val progress = if (state.total == 0) 0f else state.done.toFloat() / state.total
            LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "批量刮削中：${state.currentCode ?: ""} · 成功 ${state.succeeded} · 失败 ${state.failed} · 剩余 ${state.total - state.done}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel) { Text("取消") }
            }
        }
        BatchPhase.FINISHED -> {
            val summary = if (state.cancelled) {
                "批量刮削已取消：已完成 ${state.done} 个（成功 ${state.succeeded}，失败 ${state.failed}）"
            } else {
                "批量刮削完成：成功 ${state.succeeded}，失败 ${state.failed}"
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
        BatchPhase.IDLE -> Unit
    }
}

@Composable
private fun ScanProgressBanner(progress: ScanProgress, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "正在扫描：${progress.rootDisplayName ?: ""} / " +
                    if (progress.currentDir.isEmpty()) "（根目录）" else progress.currentDir,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text("取消") }
        }
        Text(
            "已发现 ${progress.worksFound} 个作品" +
                if (progress.warningCount > 0) " · 警告 ${progress.warningCount}" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyLibrary(onOpenRootFolders: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("作品库是空的", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "添加一个根文件夹，OneAsmr 会扫描其中的 RJ 作品文件夹。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpenRootFolders) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("添加根文件夹")
            }
        }
    }
}

@Composable
private fun ScanPrompt(onScan: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("尚未扫描作品", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "已授权根文件夹，开始扫描以发现作品。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onScan) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("开始扫描")
            }
        }
    }
}

/**
 * Minimal Task 8 work list: title + RJ code + missing state. Missing rows are
 * greyed, still tappable (to the Task 14 detail stub) and carry a manual
 * remove action. Task 12 replaces this with the full grid.
 *
 * Task 11 adds the scrape affordance: a status chip (未刮削/刮削失败) plus a
 * per-row 刮削/重试/重新刮削 button (force-rescrape confirm lives in the
 * screen, OK rows only).
 */
@Composable
private fun WorkList(
    works: List<Work>,
    onOpenWork: (String) -> Unit,
    onRemove: (Work) -> Unit,
    onScrape: (Work) -> Unit,
    scrapingWorkId: String?,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(works, key = { it.id }) { work ->
            WorkRow(
                work = work,
                onClick = { onOpenWork(work.id) },
                onRemove = { onRemove(work) },
                onScrape = { onScrape(work) },
                scrapingWorkId = scrapingWorkId,
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun WorkRow(
    work: Work,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onScrape: (Work) -> Unit,
    scrapingWorkId: String?,
) {
    val greyed = work.missing
    val contentColor = if (greyed) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = work.title,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = KeySpec.parseWorkId(work.id)?.rjCode ?: work.id,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (greyed) {
            AssistChip(
                onClick = {},
                label = {
                    Text("已失效", style = MaterialTheme.typography.labelMedium)
                },
                colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            )
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onRemove) { Text("移除") }
        } else {
            ScrapeRowActions(
                work = work,
                scrapingWorkId = scrapingWorkId,
                onScrape = onScrape,
            )
        }
    }
}

/** Task 11 per-row scrape actions: status chip + 刮削/重试/重新刮削 button. */
@Composable
private fun ScrapeRowActions(
    work: Work,
    scrapingWorkId: String?,
    onScrape: (Work) -> Unit,
) {
    val busy = scrapingWorkId == work.id
    val buttonLabel = when (work.scrapeStatus) {
        ScrapeStatus.OK -> "重新刮削"
        ScrapeStatus.FAILED -> "重试"
        ScrapeStatus.NOT_SCRAPED -> "刮削"
    }
    when (work.scrapeStatus) {
        ScrapeStatus.OK -> Unit
        ScrapeStatus.NOT_SCRAPED -> {
            AssistChip(onClick = {}, label = { Text("未刮削", style = MaterialTheme.typography.labelMedium) })
            Spacer(Modifier.width(4.dp))
        }
        ScrapeStatus.FAILED -> {
            AssistChip(
                onClick = {},
                label = { Text("刮削失败", style = MaterialTheme.typography.labelMedium) },
                colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            )
            Spacer(Modifier.width(4.dp))
        }
    }
    TextButton(onClick = { onScrape(work) }, enabled = !busy) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        } else {
            Text(buttonLabel)
        }
    }
}

/** Task 8 library home state; [showOnboarding] gates the empty-library CTA. */
data class LibraryUiState(
    val works: List<Work>,
    val hasRoots: Boolean,
    val showOnboarding: Boolean,
    val progress: ScanProgress,
    val lastSummary: RescanSummary?,
) {
    companion object {
        val EMPTY = LibraryUiState(
            works = emptyList(),
            hasRoots = false,
            showOnboarding = true,
            progress = ScanProgress.IDLE,
            lastSummary = null,
        )
    }
}

/** Task 8 library ViewModel: combines works/roots/progress/summary into [uiState]. */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val workDao: WorkDao,
    private val db: OneAsmrDatabase,
    rootRepository: ScanRootRepository,
    bookkeeping: ScanBookkeepingStore,
    private val scanController: ScanController,
) : ViewModel() {
    val uiState: StateFlow<LibraryUiState> = combine(
        workDao.getAllFlow(),
        rootRepository.entries.map { it.isNotEmpty() },
        ScanProgressStore.state,
        bookkeeping.lastSummary,
    ) { works, hasRoots, progress, lastSummary ->
        LibraryUiState(
            works = works,
            hasRoots = hasRoots,
            showOnboarding = !hasRoots && works.isEmpty(),
            progress = progress,
            lastSummary = lastSummary,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState.EMPTY)

    fun startScan() {
        scanController.startScan()
    }

    fun cancelScan() {
        scanController.cancelScan()
    }

    /** Manual removal of a missing work (Task 7 API): row + review + playback, one transaction. */
    fun removeWork(workId: String) {
        viewModelScope.launch { removeWork(db, workId) }
    }
}
