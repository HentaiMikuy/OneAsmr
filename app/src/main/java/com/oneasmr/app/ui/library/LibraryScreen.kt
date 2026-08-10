package com.oneasmr.app.ui.library

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh as RefreshOutlined
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.oneasmr.app.data.local.AgeRating
import com.oneasmr.app.data.local.OneAsmrDatabase
import com.oneasmr.app.data.local.ProgressState
import com.oneasmr.app.data.local.ScrapeStatus
import com.oneasmr.app.data.local.WorkDao
import com.oneasmr.app.data.local.WorkFilter
import com.oneasmr.app.data.local.WorkListItem
import com.oneasmr.app.data.local.WorkOrder
import com.oneasmr.app.data.local.WorkPagingSourceFactory
import com.oneasmr.app.data.local.settings.LibraryViewMode
import com.oneasmr.app.data.local.settings.SettingsStore
import com.oneasmr.app.data.repository.BatchPhase
import com.oneasmr.app.data.repository.BatchScrapeState
import com.oneasmr.app.data.repository.CoverStore
import com.oneasmr.app.data.repository.CoverType
import com.oneasmr.app.data.repository.ScanRootRepository
import com.oneasmr.app.data.scanner.RescanSummary
import com.oneasmr.app.data.scanner.ScanBookkeepingStore
import com.oneasmr.app.data.scanner.ScanPhase
import com.oneasmr.app.data.scanner.ScanProgress
import com.oneasmr.app.data.scanner.ScanProgressStore
import com.oneasmr.app.data.scanner.removeWork
import com.oneasmr.app.navigation.LocalBottomClusterHeight
import coil3.compose.AsyncImage
import com.oneasmr.app.ui.common.formatScanSummary
import com.oneasmr.app.ui.common.progressLabel
import com.oneasmr.app.ui.common.rememberPressScale
import com.oneasmr.app.ui.common.sharedWorkCover
import com.oneasmr.app.ui.common.uiLabel
import com.oneasmr.app.worker.ScanController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Task 12 library home: empty-library onboarding (no roots + no works), the
 * scan trigger + live progress + completion summary, and the PAGED cover
 * grid / list (grid-list preference persisted, pull-to-refresh affordance).
 * Task 8's minimal list is replaced by the paged UI; the missing-grey-out,
 * manual-remove and Task 11 scrape flows carry over.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun LibraryScreen(
    onOpenRootFolders: () -> Unit = {},
    onOpenWork: (String) -> Unit = {},
    onOpenReviews: () -> Unit = {},
    onOpenBrowse: (String) -> Unit = {},
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedContentScope: AnimatedContentScope? = null,
    viewModel: LibraryViewModel = hiltViewModel(),
    scrapeViewModel: ScrapeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scrapeState by scrapeViewModel.uiState.collectAsStateWithLifecycle()
    val viewMode by viewModel.libraryViewMode.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val sortDescending by viewModel.sortDescending.collectAsStateWithLifecycle()
    val libraryFilter by viewModel.libraryFilter.collectAsStateWithLifecycle()
    var removeCandidate by remember { mutableStateOf<WorkListItem?>(null) }
    var forceRescrapeCandidate by remember { mutableStateOf<WorkListItem?>(null) }
    var showBatchMenu by remember { mutableStateOf(false) }
    var showDebugUrlDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coverStore = rememberCoverStore()

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
                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "作品库",
                style = MaterialTheme.typography.displaySmall,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(
                    onClick = { showBatchMenu = true },
                    enabled = state.workCount > 0 && scrapeState.batch.phase != BatchPhase.RUNNING,
                ) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(
                    expanded = showBatchMenu,
                    onDismissRequest = { showBatchMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("我标记的作品") },
                        onClick = {
                            showBatchMenu = false
                            onOpenReviews()
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("浏览社团") },
                        onClick = {
                            showBatchMenu = false
                            onOpenBrowse(com.oneasmr.app.ui.browse.BrowseDimensions.CIRCLE)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("浏览标签") },
                        onClick = {
                            showBatchMenu = false
                            onOpenBrowse(com.oneasmr.app.ui.browse.BrowseDimensions.TAG)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("浏览CV") },
                        onClick = {
                            showBatchMenu = false
                            onOpenBrowse(com.oneasmr.app.ui.browse.BrowseDimensions.VA)
                        },
                    )
                    HorizontalDivider()
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewModeButton(
                selected = viewMode == LibraryViewMode.GRID,
                icon = Icons.Filled.GridView,
                contentDescription = "网格视图",
                onClick = { viewModel.setLibraryViewMode(LibraryViewMode.GRID) },
            )
            ViewModeButton(
                selected = viewMode == LibraryViewMode.LIST,
                icon = Icons.AutoMirrored.Filled.ViewList,
                contentDescription = "列表视图",
                onClick = { viewModel.setLibraryViewMode(LibraryViewMode.LIST) },
            )
            if (state.workCount > 0) {
                SortMenu(
                    order = sortOrder,
                    descending = sortDescending,
                    onSelect = viewModel::setSort,
                )
            }
            if (state.workCount > 0) {
                FilterMenu(
                    filter = libraryFilter,
                    onSelect = viewModel::setFilter,
                )
            }
            if (state.hasRoots && state.progress.phase != ScanPhase.SCANNING) {
                IconButton(onClick = viewModel::startScan) {
                    Icon(Icons.Filled.Refresh, contentDescription = "扫描")
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
            state.workCount == 0 -> ScanPrompt(onScan = viewModel::startScan)
            else -> {
                val lastSummary = state.lastSummary
                if (lastSummary != null) {
                    Text(
                        "上次扫描：${formatScanSummary(lastSummary)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                val pagingItems = viewModel.pagingDataFlow.collectAsLazyPagingItems()
                // 返回本页时强制刷新一代:详情页刮削的写库发生在本页无
                // 收集器期间,cachedIn 只会向重挂载的差分器重放旧一代 ——
                // 刮削结果(标题/封面/评分)回来后不显示。下拉刷新走的是
                // 重扫(位置未变 = UNCHANGED,不写库),救不回,故在组合
                // 重建(含导航返回)时无条件 refresh;数据在本地 DB,代价
                // 只是一次分页查询。
                LaunchedEffect(Unit) { pagingItems.refresh() }
                LibraryContent(
                    items = pagingItems,
                    coverStore = coverStore,
                    viewMode = viewMode,
                    isRefreshing = state.progress.phase == ScanPhase.SCANNING,
                    onOpenWork = onOpenWork,
                    onRemove = { removeCandidate = it },
                    onScrape = { work ->
                        if (work.scrapeStatus == ScrapeStatus.OK) forceRescrapeCandidate = work
                        else scrapeViewModel.scrapeSingle(work.id)
                    },
                    onRefresh = viewModel::onPullRefresh,
                    scrapingWorkId = scrapeState.scrapingWorkId,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedContentScope = animatedContentScope,
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
    // 底部悬浮簇是覆盖层不占布局:居中前按实测簇高留白,否则视觉中心
    // 被压向下(空态"偏下"的根因)。
    Box(
        Modifier
            .fillMaxSize()
            .padding(bottom = LocalBottomClusterHeight.current),
        contentAlignment = Alignment.Center,
    ) {
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
    // 同 EmptyLibrary:先避让底部悬浮簇再居中。
    Box(
        Modifier
            .fillMaxSize()
            .padding(bottom = LocalBottomClusterHeight.current),
        contentAlignment = Alignment.Center,
    ) {
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
 * Task 12 paged library content: cover grid or list rows (persisted user
 * preference) driven by [LazyPagingItems] — only the pages around the
 * viewport are ever loaded (plan Must NOT: 一次性加载全库). Wrapped in the
 * pull-to-refresh affordance: pulling starts an incremental rescan (see
 * [LibraryViewModel.onPullRefresh]); [isRefreshing] tracks the scan phase.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun LibraryContent(
    items: LazyPagingItems<WorkListItem>,
    coverStore: CoverStore,
    viewMode: LibraryViewMode,
    isRefreshing: Boolean,
    onOpenWork: (String) -> Unit,
    onRemove: (WorkListItem) -> Unit,
    onScrape: (WorkListItem) -> Unit,
    onRefresh: () -> Unit,
    scrapingWorkId: String?,
    sharedTransitionScope: SharedTransitionScope?,
    animatedContentScope: AnimatedContentScope?,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        if (viewMode == LibraryViewMode.GRID) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 136.dp),
                modifier = Modifier.fillMaxSize(),
                // 底部导航改为覆盖层后不再占 layout 空间，用实测簇高度补底部
                // 留白，保证最后一行卡片不被悬浮的 mini player + 导航栏遮住。
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 4.dp,
                    bottom = 16.dp + LocalBottomClusterHeight.current,
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(
                    count = items.itemCount,
                    key = items.safeItemKey { it.id },
                ) { index ->
                    val item = items[index]
                    if (item != null) {
                        WorkGridCard(
                            item = item,
                            coverStore = coverStore,
                            onClick = { onOpenWork(item.id) },
                            onRemove = { onRemove(item) },
                            onScrape = { onScrape(item) },
                            scraping = scrapingWorkId == item.id,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedContentScope = animatedContentScope,
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                // 同上网格分支：为覆盖层底部簇预留底部留白。
                contentPadding = PaddingValues(bottom = LocalBottomClusterHeight.current),
            ) {
                items(
                    count = items.itemCount,
                    key = items.safeItemKey { it.id },
                ) { index ->
                    val item = items[index]
                    if (item != null) {
                        WorkListRow(
                            item = item,
                            coverStore = coverStore,
                            onClick = { onOpenWork(item.id) },
                            onRemove = { onRemove(item) },
                            onScrape = onScrape,
                            scrapingWorkId = scrapingWorkId,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedContentScope = animatedContentScope,
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/**
 * Wave B cover-forward grid card: no M3 Card chrome — the 1:1 cover (clipped
 * to [androidx.compose.material3.Shapes.medium]) IS the card. Title + work
 * code sit on a bottom gradient scrim (transparent → black @ 0.82 keeps the
 * white overlay text legible over any cover art in the dark-immersive
 * scheme); the rating rides the top-end corner as a primary pill, stacked
 * above the Task 11 scrape-status chip. Missing works keep the Task 8 dim +
 * 已失效 chip and additionally render the cover grayscale ([GrayscaleColorFilter]);
 * their bottom-end overlay action is the manual remove affordance (previously
 * a below-cover text button), since they have no scrape flow. Otherwise the
 * bottom-end action is scrape (cloud = needs scraping, refresh = force
 * rescrape), replacing the old 重新刮削 text button. Circle name is
 * deliberately off the card face — it lives on the detail page.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun WorkGridCard(
    item: WorkListItem,
    coverStore: CoverStore,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onScrape: () -> Unit,
    scraping: Boolean,
    sharedTransitionScope: SharedTransitionScope?,
    animatedContentScope: AnimatedContentScope?,
) {
    val greyed = item.missing
    val (interactionSource, pressScale) = rememberPressScale(0.97f)
    Column(
        modifier = Modifier
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clip(MaterialTheme.shapes.medium)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
    ) {
        Box(
            Modifier
                .sharedWorkCover(sharedTransitionScope, animatedContentScope, item.id)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.medium),
        ) {
            LibraryCoverImage(
                coverStore = coverStore,
                rjCode = item.rjCode,
                type = CoverType.THUMB_240,
                rootFolderUri = item.rootFolderUri,
                relativeDir = item.relativeDir,
                modifier = Modifier.fillMaxSize(),
                colorFilter = if (greyed) GrayscaleColorFilter else null,
            )
            // Bottom scrim gradient (see KDoc): legibility over any cover art.
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.45f)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f)),
                        ),
                    ),
            )
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    // The end padding clears the 28dp bottom-end overlay action.
                    .padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 40.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.rjCode,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
            if (item.progress != null && item.progress != ProgressState.none) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        progressLabel(item.progress),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
                horizontalAlignment = Alignment.End,
            ) {
                item.rateAverage2dp?.let { rating ->
                    Text(
                        "★ ${String.format(java.util.Locale.US, "%.2f", rating)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(percent = 50),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                when (item.scrapeStatus) {
                    ScrapeStatus.NOT_SCRAPED -> {
                        if (item.rateAverage2dp != null) Spacer(Modifier.height(4.dp))
                        StatusBadge(
                            label = "未刮削",
                            modifier = Modifier,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                    ScrapeStatus.FAILED -> {
                        if (item.rateAverage2dp != null) Spacer(Modifier.height(4.dp))
                        StatusBadge(
                            label = "刮削失败",
                            modifier = Modifier,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        )
                    }
                    ScrapeStatus.OK -> Unit
                }
            }
            if (greyed) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
                )
                AssistChip(
                    onClick = {},
                    label = { Text("已失效", style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.align(Alignment.Center),
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                )
                CoverActionButton(
                    onClick = onRemove,
                    icon = Icons.Outlined.Delete,
                    contentDescription = "移除",
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            } else {
                CoverActionButton(
                    onClick = onScrape,
                    icon = if (item.scrapeStatus == ScrapeStatus.OK) {
                        Icons.Outlined.RefreshOutlined
                    } else {
                        Icons.Outlined.CloudDownload
                    },
                    contentDescription = "刮削",
                    modifier = Modifier.align(Alignment.BottomEnd),
                    enabled = !scraping,
                )
            }
            if (scraping) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
                )
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * 28dp overlay action on the cover scrim (scrape / remove). White @ 0.9 keeps
 * the glyph legible over the scrim without competing with the cover art.
 */
@Composable
private fun CoverActionButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .padding(6.dp)
            .size(28.dp),
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/** Small corner chip for cover overlays (scrape status). */
@Composable
private fun StatusBadge(
    label: String,
    modifier: Modifier,
    containerColor: Color,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

/**
 * Wave B list row (library-local; the shared [com.oneasmr.app.ui.common.WorkListRow]
 * stays untouched for the browse pages): 56dp cover thumb clipped to
 * [androidx.compose.material3.Shapes.small], title + mono work code / circle
 * line, then end-aligned rating, scrape action, and — for missing works only —
 * the Task 8 已失效 chip + manual remove button. Missing rows share the grid's
 * visual language: grayscale thumb ([GrayscaleColorFilter]) + dimmed title.
 * A FAILED scrape tints the cloud icon error, the list-row counterpart of the
 * grid's 刮削失败 chip.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun WorkListRow(
    item: WorkListItem,
    coverStore: CoverStore,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onScrape: (WorkListItem) -> Unit,
    scrapingWorkId: String?,
    sharedTransitionScope: SharedTransitionScope?,
    animatedContentScope: AnimatedContentScope?,
) {
    val greyed = item.missing
    val contentColor = if (greyed) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val busy = scrapingWorkId == item.id
    val (interactionSource, pressScale) = rememberPressScale(0.97f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibraryCoverImage(
            coverStore = coverStore,
            rjCode = item.rjCode,
            type = CoverType.THUMB_240,
            rootFolderUri = item.rootFolderUri,
            relativeDir = item.relativeDir,
            modifier = Modifier
                .sharedWorkCover(sharedTransitionScope, animatedContentScope, item.id)
                .size(56.dp)
                .clip(MaterialTheme.shapes.small),
            colorFilter = if (greyed) GrayscaleColorFilter else null,
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(item.rjCode) }
                    item.circleName?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.progress != null && item.progress != ProgressState.none) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.padding(end = 4.dp),
            ) {
                Text(
                    progressLabel(item.progress),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
        if (greyed) {
            AssistChip(
                onClick = {},
                label = { Text("已失效", style = MaterialTheme.typography.labelMedium) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            )
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onRemove) { Text("移除") }
        } else {
            IconButton(onClick = { onScrape(item) }, enabled = !busy) {
                if (busy) {
                    CircularProgressIndicator(
                        Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        if (item.scrapeStatus == ScrapeStatus.OK) {
                            Icons.Outlined.RefreshOutlined
                        } else {
                            Icons.Outlined.CloudDownload
                        },
                        contentDescription = "刮削",
                        tint = if (item.scrapeStatus == ScrapeStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/**
 * Grayscale matrix applied to missing works' covers (grid card + list thumb)
 * — the Wave B replacement for relying on the dim overlay alone.
 */
private val GrayscaleColorFilter =
    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * [com.oneasmr.app.ui.common.CoverImage] variant that accepts a [ColorFilter]
 * (missing works render grayscale). The model resolution mirrors CoverImage's
 * local-first contract (cached file → bundled-folder cover → placeholder) —
 * duplicated here rather than extending CoverImage because the redesign scope
 * is this file only.
 */
@Composable
private fun LibraryCoverImage(
    coverStore: CoverStore,
    rjCode: String,
    type: CoverType,
    rootFolderUri: String?,
    relativeDir: String?,
    modifier: Modifier = Modifier,
    colorFilter: ColorFilter? = null,
) {
    var model by remember(rjCode, type, rootFolderUri, relativeDir) { mutableStateOf<Any?>(null) }
    LaunchedEffect(rjCode, type, rootFolderUri, relativeDir) {
        model = coverStore.coverModelFor(rjCode, type, rootFolderUri, relativeDir)
    }
    AsyncImage(
        model = model,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
        error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
        colorFilter = colorFilter,
    )
}

/**
 * Hilt singleton access for [CoverStore] inside composables (EntryPoint
 * pattern, same as ScanLibraryWorker). CoverImage's contract says the calling
 * screen supplies the store; remember{} keeps one instance per composition.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LibraryCoverEntryPoint {
    fun coverStore(): CoverStore
}

@Composable
internal fun rememberCoverStore(): CoverStore {
    val context = LocalContext.current
    return remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            LibraryCoverEntryPoint::class.java,
        ).coverStore()
    }
}

/**
 * Task 12 library home state: header/empty-state data (roots, live count,
 * scan progress, last summary). The actual works live in the separately
 * collected paged flow ([LibraryViewModel.pagingDataFlow]) — never a full
 * in-memory list (plan Must NOT: 一次性加载全库).
 */
data class LibraryUiState(
    val hasRoots: Boolean,
    val showOnboarding: Boolean,
    val progress: ScanProgress,
    val lastSummary: RescanSummary?,
    val workCount: Int,
) {
    companion object {
        val EMPTY = LibraryUiState(
            hasRoots = false,
            showOnboarding = true,
            progress = ScanProgress.IDLE,
            lastSummary = null,
            workCount = 0,
        )
    }
}

/** Task 13 library sort menu label for an order mode. */
internal fun WorkOrder.label(): String = when (this) {
    WorkOrder.ID -> "编号"
    WorkOrder.RELEASE_DATE -> "发售日期"
    WorkOrder.RATING -> "评分"
    WorkOrder.DL_COUNT -> "销量"
    WorkOrder.REVIEW_COUNT -> "评论数"
    WorkOrder.PRICE -> "价格"
    WorkOrder.RATE_AVERAGE_2DP -> "均价"
    WorkOrder.TITLE_SORT_KEY -> "标题"
    WorkOrder.RANDOM -> "随机"
}

/**
 * Bounds-safe replacement for [androidx.paging.compose.itemKey]: Paging 3.5's
 * built-in itemKey peeks the snapshot WITHOUT a bounds check, so a list that
 * still holds old items while the new PagingData arrives (query clear, sort
 * change shrinking the page, work removal) crashes with
 * "Illegal attempt to access index N in ItemSnapshotList of size M" — caught
 * on device, Task 13 (Robolectric never renders the composition). Fall back
 * to the raw index for out-of-range keys; such keys are only ever consulted
 * during the disposal of stale items.
 */
internal fun <T : Any> LazyPagingItems<T>.safeItemKey(key: (T) -> Any): (Int) -> Any = { index ->
    if (index in 0 until itemCount) {
        val item = get(index)
        if (item != null) key(item) else index
    } else {
        index
    }
}

/**
 * Task 13 sort menu with the Wave B compact trigger: an [Icons.Filled.SwapVert]
 * icon button opening the same dropdown as before; a 6dp primary dot marks a
 * non-default selection (order ≠ [WorkOrder.ID] or descending). Picking a
 * different field starts ascending; picking the current field toggles the
 * direction (direction is irrelevant for [WorkOrder.RANDOM]). The selection
 * persists via the DataStore-backed ViewModel and is shared with the search
 * page, which reuses this composable.
 */
@Composable
internal fun SortMenu(
    order: WorkOrder,
    descending: Boolean,
    onSelect: (WorkOrder, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { expanded = true }) {
            Box {
                Icon(Icons.Filled.SwapVert, contentDescription = "排序")
                if (order != WorkOrder.ID || descending) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            WorkOrder.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label()) },
                    trailingIcon = if (entry == order) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        // Picking a different field starts ascending; picking
                        // the current field toggles the direction.
                        val nextDescending = if (entry == order) {
                            if (entry == WorkOrder.RANDOM) false else !descending
                        } else {
                            false
                        }
                        onSelect(entry, nextDescending)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (descending) "降序（点击改为升序）" else "升序（点击改为降序）") },
                enabled = order != WorkOrder.RANDOM,
                onClick = {
                    expanded = false
                    onSelect(order, !descending)
                },
            )
        }
    }
}

/**
 * Task 15 library filter menu with the Wave B compact trigger: an
 * [Icons.Filled.FilterList] icon button opening the same dropdown as before —
 * no filter ("全部") / rated-only ("已评分") / one entry per progress state /
 * one entry per manual age rating (全年龄/R15/R18, "分级：…"); a 6dp primary
 * dot marks an active filter. The selection is DAO-level (a WHERE predicate
 * in the paging query, see [WorkDao.pagingSource]) — never in-memory.
 */
@Composable
internal fun FilterMenu(
    filter: WorkFilter?,
    onSelect: (WorkFilter?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { expanded = true }) {
            Box {
                Icon(Icons.Filled.FilterList, contentDescription = "筛选")
                if (filter != null) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("全部") },
                trailingIcon = if (filter == null) {
                    { Icon(Icons.Filled.Check, contentDescription = null) }
                } else {
                    null
                },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
            )
            DropdownMenuItem(
                text = { Text("已评分") },
                trailingIcon = if (filter == WorkFilter.Rated) {
                    { Icon(Icons.Filled.Check, contentDescription = null) }
                } else {
                    null
                },
                onClick = {
                    expanded = false
                    onSelect(WorkFilter.Rated)
                },
            )
            HorizontalDivider()
            ProgressState.entries.forEach { state ->
                DropdownMenuItem(
                    text = { Text("进度：${state.uiLabel()}") },
                    trailingIcon = if (filter == WorkFilter.Progress(state)) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onSelect(WorkFilter.Progress(state))
                    },
                )
            }
            HorizontalDivider()
            AgeRating.entries.forEach { rating ->
                DropdownMenuItem(
                    text = { Text("分级：${rating.label}") },
                    trailingIcon = if (filter == WorkFilter.Age(rating)) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onSelect(WorkFilter.Age(rating))
                    },
                )
            }
        }
    }
}

/**
 * Compact header view-mode button (Wave B): the active mode tints
 * [ColorScheme.primary], the inactive one onSurfaceVariant. The selection
 * persists via the DataStore-backed ViewModel.
 */
@Composable
private fun ViewModeButton(
    selected: Boolean,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * Task 12/13 library ViewModel: paged works ([pagingDataFlow], Paging 3 over
 * Room's built-in PagingSource — LIMIT/OFFSET, never the whole table; ordered
 * by the persisted sort selection, recreated on change) plus the header state
 * and the persisted grid/list + sort preferences.
 *
 * [pagingSourceFactory] is injected (Hilt provides the DAO-backed source) so
 * unit tests substitute a fake source and the Pager runs entirely on the
 * test's virtual scheduler — the repo flake convention, no real-time waits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val workDao: WorkDao,
    private val db: OneAsmrDatabase,
    rootRepository: ScanRootRepository,
    bookkeeping: ScanBookkeepingStore,
    private val scanController: ScanController,
    private val settingsStore: SettingsStore,
    pagingSourceFactory: WorkPagingSourceFactory,
) : ViewModel() {
    val uiState: StateFlow<LibraryUiState> = combine(
        rootRepository.entries.map { it.isNotEmpty() },
        workDao.countFlow(),
        ScanProgressStore.state,
        bookkeeping.lastSummary,
    ) { hasRoots, count, progress, lastSummary ->
        LibraryUiState(
            hasRoots = hasRoots,
            showOnboarding = !hasRoots && count == 0,
            progress = progress,
            lastSummary = lastSummary,
            workCount = count,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState.EMPTY)

    /** Persisted sort selection (Task 13); shared with the search page. */
    val sortOrder: StateFlow<WorkOrder> =
        settingsStore.librarySortOrder.stateIn(viewModelScope, SharingStarted.Eagerly, WorkOrder.ID)

    val sortDescending: StateFlow<Boolean> =
        settingsStore.librarySortDescending.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Session-stable random seed: generated once per ViewModel (= per process,
     * plan: 随机排序同一会话内稳定). Never persisted — a new session may get a
     * new random order; within the session every page shares the seed so the
     * order is coherent while scrolling.
     */
    private val randomSeed: Long = Random.nextLong()

    /** Task 15 review filter selection; session state (default: no filter). */
    private val filter = MutableStateFlow<WorkFilter?>(null)
    val libraryFilter: StateFlow<WorkFilter?> = filter.asStateFlow()

    fun setFilter(value: WorkFilter?) {
        filter.value = value
    }

    /** Paged works for the grid/list; recreated when sort or filter changes. */
    val pagingDataFlow: Flow<PagingData<WorkListItem>> =
        combine(sortOrder, sortDescending, filter) { order, descending, filter -> Triple(order, descending, filter) }
            .flatMapLatest { (order, descending, filter) ->
                Pager(
                    config = PagingConfig(pageSize = PAGE_SIZE),
                    pagingSourceFactory = {
                        pagingSourceFactory.create(order, descending, keyword = null, randomSeed = randomSeed, filter = filter)
                    },
                ).flow
            }
            .cachedIn(viewModelScope)

    /** Persisted grid/list preference (Task 12); survives process restarts. */
    val libraryViewMode: StateFlow<LibraryViewMode> =
        settingsStore.libraryViewMode
            .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryViewMode.GRID)

    fun setLibraryViewMode(mode: LibraryViewMode) {
        viewModelScope.launch { settingsStore.setLibraryViewMode(mode) }
    }

    fun setSort(order: WorkOrder, descending: Boolean) {
        viewModelScope.launch { settingsStore.setLibrarySort(order, descending) }
    }

    /**
     * Pull-to-refresh: triggers an incremental rescan of the authorized
     * roots — the same entry as the 扫描 button (the gesture means "refresh
     * the library from disk"; metadata scraping stays on its own explicit
     * per-work / batch entries in ScrapeViewModel). Gated exactly like the
     * button: no roots or a scan already running makes it a safe no-op.
     */
    fun onPullRefresh() {
        val state = uiState.value
        if (state.hasRoots && state.progress.phase != ScanPhase.SCANNING) {
            scanController.startScan()
        }
    }

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

    companion object {
        const val PAGE_SIZE = 30
    }
}
