package com.oneasmr.app.ui.work

import android.os.Build
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Reviews
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneasmr.app.data.local.AgeRating
import com.oneasmr.app.data.local.KeySpec
import com.oneasmr.app.data.local.ProgressState
import com.oneasmr.app.data.local.ScrapeStatus
import com.oneasmr.app.data.local.Tag
import com.oneasmr.app.data.local.Va
import com.oneasmr.app.data.local.Work
import com.oneasmr.app.data.repository.CoverStore
import com.oneasmr.app.data.repository.CoverType
import com.oneasmr.app.data.scanner.TrackNode
import com.oneasmr.app.data.scanner.TrackNodeType
import com.oneasmr.app.domain.trackgroup.TrackGroup
import com.oneasmr.app.domain.trackgroup.TrackGroupResult
import com.oneasmr.app.domain.trackgroup.TrackGrouper
import com.oneasmr.app.ui.common.sharedWorkCover
import com.oneasmr.app.ui.library.rememberCoverStore

/**
 * Task 14 work detail page: cover, title, RJ code, circle/CV/tags (tappable →
 * Task 16 browse route), dynamic metadata, series, subtle scrape-status hint +
 * manual scrape entry in a top-right overflow menu (Task 11 ScrapeRepository), the live track tree with per-type
 * viewers, a Task 15 review slot, and the invalid-state + rescan entry for
 * missing/moved works — never a white screen.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun WorkDetailScreen(
    viewModel: WorkDetailViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onOpenPlayer: (workId: String, trackIndex: Int) -> Unit,
    onOpenVideoPlayer: (workId: String, trackIndex: Int) -> Unit,
    onOpenText: (workId: String, documentUri: String) -> Unit,
    onOpenImage: (workId: String, documentUri: String) -> Unit,
    onOpenBrowse: (dimension: String, id: String) -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedContentScope: AnimatedContentScope? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val coverStore = rememberCoverStore()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmRescrape by remember { mutableStateOf(false) }

    LaunchedEffect(state.scrapeMessage) {
        state.scrapeMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeScrapeMessage()
        }
    }
    LaunchedEffect(state.rescanMessage) {
        state.rescanMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeRescanMessage()
        }
    }

    val work = state.work
    when {
        state.loading -> CenteredBox { CircularProgressIndicator() }
        work == null -> CenteredBox {
            Text("作品不在库中", style = MaterialTheme.typography.titleMedium)
        }
        state.invalid != null -> InvalidWorkState(
            work = work,
            invalid = state.invalid!!,
            refreshing = state.refreshing,
            onRescan = viewModel::refreshWork,
            reviewSection = {
                ReviewEditorSection(
                    state = state,
                    onRating = viewModel::setRating,
                    onProgress = viewModel::setProgress,
                    onTextChange = viewModel::onReviewTextChange,
                    onSaveText = viewModel::saveReviewText,
                    onClear = viewModel::clearReview,
                )
            },
        )
        else -> DetailContent(
            state = state,
            coverStore = coverStore,
            onBack = onBack,
            onScrapeClick = {
                if (work.scrapeStatus == ScrapeStatus.OK) confirmRescrape = true
                else viewModel.scrape()
            },
            onToggleFolder = viewModel::toggleFolder,
            onOpenPlayer = onOpenPlayer,
            onOpenVideoPlayer = onOpenVideoPlayer,
            onOpenText = onOpenText,
            onOpenImage = onOpenImage,
            onOpenBrowse = onOpenBrowse,
            onReviewRating = viewModel::setRating,
            onReviewProgress = viewModel::setProgress,
            onReviewTextChange = viewModel::onReviewTextChange,
            onReviewSaveText = viewModel::saveReviewText,
            onReviewClear = viewModel::clearReview,
            onSetAgeRating = viewModel::setAgeRating,
            sharedTransitionScope = sharedTransitionScope,
            animatedContentScope = animatedContentScope,
        )
    }

    Box(Modifier.fillMaxSize()) {
        SnackbarHost(
            snackbarHostState,
            Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }

    if (confirmRescrape) {
        AlertDialog(
            onDismissRequest = { confirmRescrape = false },
            title = { Text("强制重新刮削？") },
            text = { Text("「${work?.title ?: "该作品"}」已刮削过。重新刮削将覆盖现有元数据与封面。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.scrape()
                        confirmRescrape = false
                    },
                ) { Text("刮削") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRescrape = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun InvalidWorkState(
    work: Work,
    invalid: InvalidReason,
    refreshing: Boolean,
    onRescan: () -> Unit,
    reviewSection: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            work.title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            work.rjCodeText(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = when (invalid) {
                        InvalidReason.NotFoundInLibrary -> "该作品不在作品库中"
                        InvalidReason.MissingWork -> "作品文件夹未找到"
                        is InvalidReason.FolderUnavailable -> "作品文件夹不可访问"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when (invalid) {
                        InvalidReason.NotFoundInLibrary -> "可能已从库中移除。"
                        InvalidReason.MissingWork -> "该作品文件夹在最近一次扫描中未找到，可能已被移动、重命名或删除。你的评分与播放记录仍会保留。"
                        is InvalidReason.FolderUnavailable -> invalid.detail
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRescan, enabled = !refreshing) {
            if (refreshing) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (refreshing) "正在重新扫描…" else "重新扫描此作品")
        }
        Spacer(Modifier.height(8.dp))
        reviewSection()
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun DetailContent(
    state: WorkDetailUiState,
    coverStore: CoverStore,
    onBack: () -> Unit,
    onScrapeClick: () -> Unit,
    onToggleFolder: (String) -> Unit,
    onOpenPlayer: (String, Int) -> Unit,
    onOpenVideoPlayer: (String, Int) -> Unit,
    onOpenText: (String, String) -> Unit,
    onOpenImage: (String, String) -> Unit,
    onOpenBrowse: (String, String) -> Unit,
    onReviewRating: (Int?) -> Unit,
    onReviewProgress: (ProgressState) -> Unit,
    onReviewTextChange: (String) -> Unit,
    onReviewSaveText: () -> Unit,
    onReviewClear: () -> Unit,
    onSetAgeRating: (AgeRating?) -> Unit,
    sharedTransitionScope: SharedTransitionScope?,
    animatedContentScope: AnimatedContentScope?,
) {
    val work = state.work!!
    val treeRows = remember(state.tree) {
        (state.tree as? TrackTreeUiState.Ready)?.let { flattenTree(it.root, it.expanded) }
    }
    // 分组/树状视图状态：仅本屏 rememberSaveable（不落盘），默认分组。
    // 分组数据与 treeRows 一样在屏内从 state.tree 派生，不进 ViewModel。
    var groupedTrackView by rememberSaveable { mutableStateOf(true) }
    var selectedTrackGroup by rememberSaveable { mutableStateOf<String?>(null) }
    val trackGroups = remember(state.tree) {
        (state.tree as? TrackTreeUiState.Ready)?.let { TrackGrouper.group(it.root) }.orEmpty()
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") {
            WorkHeader(
                state = state,
                coverStore = coverStore,
                onScrapeClick = onScrapeClick,
                onBack = onBack,
                sharedTransitionScope = sharedTransitionScope,
                animatedContentScope = animatedContentScope,
            )
        }
        item(key = "metadata") {
            MetadataRow(work)
        }
        item(key = "age_rating") {
            AgeRatingEditor(work = work, onSelect = onSetAgeRating)
        }
        item(key = "chips") {
            CircleAndVaChips(work = work, state = state, onOpenBrowse = onOpenBrowse)
            TagChips(tags = state.tags, onOpenBrowse = onOpenBrowse)
        }
        item(key = "review_slot") {
            ReviewEditorSection(
                state = state,
                onRating = onReviewRating,
                onProgress = onReviewProgress,
                onTextChange = onReviewTextChange,
                onSaveText = onReviewSaveText,
                onClear = onReviewClear,
            )
        }
        item(key = "tree_header") {
            TreeHeader(
                state = state,
                groupedView = groupedTrackView,
                onViewModeChange = { groupedTrackView = it },
            )
        }
        when (val tree = state.tree) {
            is TrackTreeUiState.Loading -> item(key = "tree_loading") {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("正在读取音轨…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            is TrackTreeUiState.Error -> item(key = "tree_error") {
                Text(
                    tree.message,
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            is TrackTreeUiState.Ready -> {
                if (groupedTrackView) {
                    // 分组模式：筹码行 + 选中组的平铺文件行。选中组持久化的是
                    // 组键；失效（重扫后该组消失）时回落到第一个音频组，
                    // 没有音频组则取第一组。
                    val selected = trackGroups.firstOrNull { it.group.name == selectedTrackGroup }
                        ?: trackGroups.firstOrNull { it.group.isAudio }
                        ?: trackGroups.firstOrNull()
                    if (selected != null) {
                        item(key = "track_group_chips") {
                            TrackGroupChipRow(
                                groups = trackGroups,
                                selected = selected.group,
                                onSelect = { selectedTrackGroup = it.name },
                            )
                        }
                        val files = selected.files
                        items(
                            count = files.size,
                            key = { "group:${files[it].relativePath}" },
                        ) { index ->
                            val node = files[index]
                            TreeRowItem(
                                row = TreeRow(node, depth = 0, expanded = false),
                                workId = work.id,
                                progress = if (node.type == TrackNodeType.AUDIO) {
                                    node.trackIndex?.let { state.trackProgress[it] }
                                } else {
                                    null
                                },
                                onToggleFolder = onToggleFolder,
                                onOpenPlayer = onOpenPlayer,
                                onOpenVideoPlayer = onOpenVideoPlayer,
                                onOpenText = onOpenText,
                                onOpenImage = onOpenImage,
                                parentPath = node.relativePath
                                    .substringBeforeLast('/', "")
                                    .takeIf { it.isNotEmpty() },
                            )
                        }
                    }
                } else {
                val rows = treeRows.orEmpty()
                items(count = rows.size, key = { rows[it].node.relativePath }) { index ->
                    val row = rows[index]
                    TreeRowItem(
                        row = row,
                        workId = work.id,
                        progress = if (row.node.type == TrackNodeType.AUDIO) {
                            row.node.trackIndex?.let { state.trackProgress[it] }
                        } else {
                            null
                        },
                        onToggleFolder = onToggleFolder,
                        onOpenPlayer = onOpenPlayer,
                        onOpenVideoPlayer = onOpenVideoPlayer,
                        onOpenText = onOpenText,
                        onOpenImage = onOpenImage,
                    )
                }
                }
            }
            TrackTreeUiState.Idle -> Unit
        }
    }
}

/**
 * Wave C immersive header: the work's own cover doubles as a full-bleed
 * backdrop. Modifier.blur is RenderEffect-backed and only exists on API 31+,
 * so below 31 the scrim alone carries the melt-into-the-page effect; the
 * scrim runs background @ 0.3 (top) → background @ 1.0 (bottom) so backdrop
 * and page share one surface. The hero cover floats over the backdrop on a
 * 24dp elevation shadow. The backdrop height is derived from the cover size
 * (72dp top gap + cover + 24dp melt band) rather than a fixed constant, so
 * cover and backdrop never drift apart across screen widths — a fixed height
 * against a fractional-width cover left a variable blank gap above the title.
 * Both images share one resolved model (the same
 * local-first [CoverStore.coverModelFor] resolution [CoverImage] uses — Coil
 * caches the second decode), because CoverImage does not expose its model.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun WorkHeader(
    state: WorkDetailUiState,
    coverStore: CoverStore,
    onScrapeClick: () -> Unit,
    onBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope?,
    animatedContentScope: AnimatedContentScope?,
) {
    val work = state.work!!
    val rjCode = work.rjCodeText()
    var scrapeMenuExpanded by remember { mutableStateOf(false) }
    var coverModel by remember(rjCode, work.rootFolderUri, work.relativeDir) {
        mutableStateOf<Any?>(null)
    }
    LaunchedEffect(rjCode, work.rootFolderUri, work.relativeDir) {
        coverModel = coverStore.coverModelFor(
            rjCode, CoverType.MAIN, work.rootFolderUri, work.relativeDir,
        )
    }
    // 封面边长显式取屏宽 0.62 份，背景高度随之推导（见 KDoc），
    // 不再用固定 420dp 去凑按宽度比例变化的封面。
    val coverSize = (LocalConfiguration.current.screenWidthDp * 0.62f).dp
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(72.dp + coverSize + 24.dp),
        ) {
            val backdropModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Modifier.fillMaxSize().blur(28.dp)
            } else {
                Modifier.fillMaxSize()
            }
            AsyncImage(
                model = coverModel,
                contentDescription = null,
                modifier = backdropModifier,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
            )
            // Legibility scrim (see KDoc): backdrop melts into the page.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.background.copy(alpha = 0.3f),
                                MaterialTheme.colorScheme.background,
                            ),
                        ),
                    ),
            )
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(72.dp))
                AsyncImage(
                    model = coverModel,
                    contentDescription = null,
                    modifier = Modifier
                        .sharedWorkCover(sharedTransitionScope, animatedContentScope, work.id)
                        .size(coverSize)
                        .shadow(24.dp, MaterialTheme.shapes.large)
                        .clip(MaterialTheme.shapes.large),
                    contentScale = ContentScale.Crop,
                    placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                    error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
            // Back affordance over the backdrop: the NavHost provides no top
            // bar on this route, so without this the only way back was the
            // system gesture.
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.3f), CircleShape),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                )
            }
            // 刮削动作收进右上角溢出菜单（与左上角返回键同款半透明圆底样式），
            // 避免在标题下方摆一行醒目的 Chip + 按钮。点击语义不变：
            // 已刮削先弹确认对话框，否则直接触发刮削（见 onScrapeClick）。
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp),
            ) {
                IconButton(
                    onClick = { scrapeMenuExpanded = true },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.3f), CircleShape),
                ) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = "更多操作",
                        tint = Color.White,
                    )
                }
                DropdownMenu(
                    expanded = scrapeMenuExpanded,
                    onDismissRequest = { scrapeMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                when {
                                    state.scraping -> "刮削中…"
                                    work.scrapeStatus == ScrapeStatus.OK -> "重新刮削"
                                    else -> "刮削"
                                },
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (work.scrapeStatus == ScrapeStatus.OK) {
                                    Icons.Outlined.Refresh
                                } else {
                                    Icons.Outlined.CloudDownload
                                },
                                contentDescription = null,
                            )
                        },
                        enabled = !state.scraping,
                        onClick = {
                            scrapeMenuExpanded = false
                            onScrapeClick()
                        },
                    )
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                work.title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                rjCode,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            work.seriesName?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    "系列：$it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
            }
            ScrapeStatusHint(status = work.scrapeStatus, scraping = state.scraping)
        }
    }
}

/**
 * 刮削状态提示：已刮削时不显示任何内容（元数据本身即说明），仅在
 * 未刮削 / 失败 / 刮削中 时以一行小字低强调呈现，实际动作入口在右上角菜单。
 */
@Composable
private fun ScrapeStatusHint(status: ScrapeStatus, scraping: Boolean) {
    val tint: Color
    val text: String
    when {
        scraping -> {
            tint = MaterialTheme.colorScheme.onSurfaceVariant
            text = "刮削中…"
        }
        status == ScrapeStatus.FAILED -> {
            tint = MaterialTheme.colorScheme.error
            text = "刮削失败，可在菜单中重试"
        }
        status == ScrapeStatus.NOT_SCRAPED -> {
            tint = MaterialTheme.colorScheme.onSurfaceVariant
            text = "未刮削，可从右上角菜单刮削元数据"
        }
        else -> return
    }
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (scraping) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = tint)
        } else {
            Icon(
                Icons.Outlined.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = tint,
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetadataRow(work: Work) {
    val entries = listOfNotNull(
        work.releaseDate?.let { MetaEntry("发售日", it, Icons.Outlined.CalendarMonth) },
        work.dlCount?.let { MetaEntry("DL数", formatCount(it), Icons.Outlined.Download) },
        work.price?.let { MetaEntry("价格", "¥${formatCount(it)}", Icons.Outlined.Sell) },
        work.rateAverage2dp?.let { MetaEntry("评分", "%.2f".format(it), Icons.Outlined.Star) },
        work.reviewCount?.let { MetaEntry("评论数", formatCount(it), Icons.Outlined.Reviews) },
        work.rateCount?.let { MetaEntry("评价数", formatCount(it), Icons.Outlined.RateReview) },
    )
    if (entries.isEmpty()) return
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.forEach { entry ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = entry.icon,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${entry.label}: ${entry.value}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

private data class MetaEntry(val label: String, val value: String, val icon: ImageVector)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgeRatingEditor(work: Work, onSelect: (AgeRating?) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        ChipSectionLabel("年龄分级")
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AgeRating.entries.forEach { rating ->
                FilterChip(
                    selected = work.ageRating == rating,
                    onClick = {
                        // 点已选中项 = 清除(回到未设置);点其他项 = 设置。
                        onSelect(if (work.ageRating == rating) null else rating)
                    },
                    label = { Text(rating.label) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CircleAndVaChips(
    work: Work,
    state: WorkDetailUiState,
    onOpenBrowse: (String, String) -> Unit,
) {
    val circle = state.circleName?.takeIf { work.circleId != null }
    if (circle == null && state.vas.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        circle?.let {
            ChipSectionLabel("社团")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DimensionChip(label = it, onClick = { onOpenBrowse("circle", work.circleId!!) })
            }
        }
        if (state.vas.isNotEmpty()) {
            ChipSectionLabel("声优")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.vas.forEach { va ->
                    DimensionChip(label = va.name, onClick = { onOpenBrowse("va", va.id) })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChips(tags: List<Tag>, onOpenBrowse: (String, String) -> Unit) {
    if (tags.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        ChipSectionLabel("标签")
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tags.forEach { tag ->
                DimensionChip(label = "#${tag.name}", onClick = { onOpenBrowse("tag", tag.id) })
            }
        }
    }
}

@Composable
private fun ChipSectionLabel(label: String) {
    Text(
        label,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DimensionChip(label: String, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun TreeHeader(
    state: WorkDetailUiState,
    groupedView: Boolean,
    onViewModeChange: (Boolean) -> Unit,
) {
    val count = (state.tree as? TrackTreeUiState.Ready)?.let { tree -> countFiles(tree.root) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("音轨", style = MaterialTheme.typography.titleMedium)
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                "$count 个文件",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        // 分组/树状切换只在树就绪后渲染（加载/失败/Idle 态无可切内容）。
        if (state.tree is TrackTreeUiState.Ready) {
            ViewModeToggle(grouped = groupedView, onChange = onViewModeChange)
        }
    }
}

// M3 SegmentedButton 过高且带勾选图标，标题行里视觉过重，改用双色小胶囊。
@Composable
private fun ViewModeToggle(grouped: Boolean, onChange: (Boolean) -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(3.dp)) {
            ViewModeOption("分组", selected = grouped) { onChange(true) }
            ViewModeOption("树状", selected = !grouped) { onChange(false) }
        }
    }
}

@Composable
private fun ViewModeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val container by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        label = "viewModeOptionBg",
    )
    Text(
        label,
        modifier = Modifier
            .clip(CircleShape)
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun TreeRowItem(
    row: TreeRow,
    workId: String,
    progress: TrackProgress?,
    onToggleFolder: (String) -> Unit,
    onOpenPlayer: (String, Int) -> Unit,
    onOpenVideoPlayer: (String, Int) -> Unit,
    onOpenText: (String, String) -> Unit,
    onOpenImage: (String, String) -> Unit,
    // 分组平铺模式才传：父目录路径（树状模式靠缩进层级表达 SEなし 等
    // 变体信息，平铺后必须显式给出，否则不同目录的同名音轨无法区分）。
    parentPath: String? = null,
) {
    val node = row.node
    val onClick: (() -> Unit)? = when (node.type) {
        TrackNodeType.FOLDER -> ({ onToggleFolder(node.relativePath) })
        TrackNodeType.AUDIO -> ({ onOpenPlayer(workId, node.trackIndex!!) })
        TrackNodeType.VIDEO -> ({ onOpenVideoPlayer(workId, node.trackIndex!!) })
        TrackNodeType.TEXT -> ({ onOpenText(workId, node.documentUri) })
        TrackNodeType.IMAGE -> ({ onOpenImage(workId, node.documentUri) })
        TrackNodeType.OTHER -> null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .padding(start = (row.depth * 16).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.isFolder) {
            Icon(
                imageVector = if (row.expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                contentDescription = if (row.expanded) "折叠" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(4.dp))
        } else {
            Icon(
                imageVector = trackTypeIcon(node.type),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Spacer(Modifier.width(2.dp))
        Column(Modifier.weight(1f)) {
            Text(
                node.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (!node.isFolder) {
                Text(
                    "#${node.trackIndex} · ${formatBytes(node.size)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!parentPath.isNullOrEmpty()) {
                    Text(
                        parentPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Task 19: remembered-position progress bar on audio rows
            // (playback_state-driven; live via Room invalidation). The
            // contentDescription is the uiautomator-visible evidence handle.
            val fraction = progress?.fraction
            if (fraction != null) {
                Spacer(Modifier.height(3.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "已播 ${(fraction * 100).toInt()}%" },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }
}

/**
 * 分组筹码行：横向滚动，只渲染非空组（顺序即 [TrackGroup] 声明序，
 * 特典靠后）。默认只展示选中组的文件，其余组折叠在筹码后面——避免
 * 拍平全部文件导致多结局作品被剧透（见 TrackGrouper KDoc）。
 */
@Composable
private fun TrackGroupChipRow(
    groups: List<TrackGroupResult>,
    selected: TrackGroup,
    onSelect: (TrackGroup) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        groups.forEach { result ->
            FilterChip(
                selected = result.group == selected,
                onClick = { onSelect(result.group) },
                label = { Text("${result.group.label} · ${result.files.size}") },
            )
        }
    }
}

private fun trackTypeIcon(type: TrackNodeType): ImageVector = when (type) {
    TrackNodeType.AUDIO -> Icons.Outlined.AudioFile
    TrackNodeType.VIDEO -> Icons.Outlined.Videocam
    TrackNodeType.TEXT -> Icons.Outlined.Description
    TrackNodeType.IMAGE -> Icons.Outlined.Image
    TrackNodeType.FOLDER, TrackNodeType.OTHER -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

/** One rendered row of the expanded tree. */
internal data class TreeRow(val node: TrackNode, val depth: Int, val expanded: Boolean)

/** Depth-first flatten of the tree honoring [expanded] folder paths. */
internal fun flattenTree(root: TrackNode, expanded: Set<String>): List<TreeRow> {
    val rows = mutableListOf<TreeRow>()
    fun walk(node: TrackNode, depth: Int) {
        val isOpen = node.isFolder && node.relativePath in expanded
        rows += TreeRow(node, depth, isOpen)
        if (isOpen) node.children.forEach { walk(it, depth + 1) }
    }
    walk(root, 0)
    return rows
}

internal fun countFiles(root: TrackNode): Int {
    var count = 0
    fun walk(node: TrackNode) {
        if (!node.isFolder) count += 1
        node.children.forEach { walk(it) }
    }
    walk(root)
    return count
}

internal fun formatBytes(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
    else -> "%.1f MB".format(size / (1024.0 * 1024.0))
}

private fun formatCount(value: Int): String = String.format("%,d", value)

private fun Work.rjCodeText(): String = KeySpec.parseWorkId(id)?.rjCode ?: id
