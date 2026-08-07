package com.oneasmr.app.ui.work

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneasmr.app.data.local.KeySpec
import com.oneasmr.app.data.local.ScrapeStatus
import com.oneasmr.app.data.local.Tag
import com.oneasmr.app.data.local.Va
import com.oneasmr.app.data.local.Work
import com.oneasmr.app.data.repository.CoverStore
import com.oneasmr.app.data.repository.CoverType
import com.oneasmr.app.data.scanner.TrackNode
import com.oneasmr.app.data.scanner.TrackNodeType
import com.oneasmr.app.ui.common.CoverImage
import com.oneasmr.app.ui.library.rememberCoverStore

/**
 * Task 14 work detail page: cover, title, RJ code, circle/CV/tags (tappable →
 * Task 16 browse route), dynamic metadata, series, scrape status + manual
 * scrape entry (Task 11 ScrapeRepository), the live track tree with per-type
 * viewers, a Task 15 review slot, and the invalid-state + rescan entry for
 * missing/moved works — never a white screen.
 */
@Composable
fun WorkDetailScreen(
    viewModel: WorkDetailViewModel = hiltViewModel(),
    onOpenPlayer: (workId: String, trackIndex: Int) -> Unit,
    onOpenVideoPlayer: (workId: String, trackIndex: Int) -> Unit,
    onOpenText: (workId: String, documentUri: String) -> Unit,
    onOpenImage: (workId: String, documentUri: String) -> Unit,
    onOpenBrowse: (dimension: String, id: String) -> Unit,
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
        )
        else -> DetailContent(
            state = state,
            coverStore = coverStore,
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
    }
}

@Composable
private fun DetailContent(
    state: WorkDetailUiState,
    coverStore: CoverStore,
    onScrapeClick: () -> Unit,
    onToggleFolder: (String) -> Unit,
    onOpenPlayer: (String, Int) -> Unit,
    onOpenVideoPlayer: (String, Int) -> Unit,
    onOpenText: (String, String) -> Unit,
    onOpenImage: (String, String) -> Unit,
    onOpenBrowse: (String, String) -> Unit,
) {
    val work = state.work!!
    val treeRows = remember(state.tree) {
        (state.tree as? TrackTreeUiState.Ready)?.let { flattenTree(it.root, it.expanded) }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") {
            WorkHeader(state = state, coverStore = coverStore, onScrapeClick = onScrapeClick)
        }
        item(key = "metadata") {
            MetadataRow(work)
        }
        item(key = "chips") {
            CircleAndVaChips(work = work, state = state, onOpenBrowse = onOpenBrowse)
            TagChips(tags = state.tags, onOpenBrowse = onOpenBrowse)
        }
        item(key = "review_slot") {
            ReviewSlot()
        }
        item(key = "tree_header") {
            TreeHeader(state = state)
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
                val rows = treeRows.orEmpty()
                items(count = rows.size, key = { rows[it].node.relativePath }) { index ->
                    TreeRowItem(
                        row = rows[index],
                        workId = work.id,
                        onToggleFolder = onToggleFolder,
                        onOpenPlayer = onOpenPlayer,
                        onOpenVideoPlayer = onOpenVideoPlayer,
                        onOpenText = onOpenText,
                        onOpenImage = onOpenImage,
                    )
                }
            }
            TrackTreeUiState.Idle -> Unit
        }
    }
}

@Composable
private fun WorkHeader(
    state: WorkDetailUiState,
    coverStore: CoverStore,
    onScrapeClick: () -> Unit,
) {
    val work = state.work!!
    Row(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        CoverImage(
            coverStore = coverStore,
            rjCode = work.rjCodeText(),
            type = CoverType.MAIN,
            rootFolderUri = work.rootFolderUri,
            relativeDir = work.relativeDir,
            modifier = Modifier
                .size(132.dp)
                .clip(MaterialTheme.shapes.medium),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                work.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                work.rjCodeText(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            work.seriesName?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    "系列：$it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            ScrapeStatusChip(status = work.scrapeStatus)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onScrapeClick, enabled = !state.scraping, modifier = Modifier.height(36.dp)) {
                if (state.scraping) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("刮削中…", style = MaterialTheme.typography.labelLarge)
                } else {
                    Text("刮削", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun ScrapeStatusChip(status: ScrapeStatus) {
    val (label, container, content) = when (status) {
        ScrapeStatus.OK -> Triple("已刮削", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        ScrapeStatus.FAILED -> Triple("刮削失败", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        ScrapeStatus.NOT_SCRAPED -> Triple("未刮削", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(shape = CircleShape, color = container) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetadataRow(work: Work) {
    val entries = listOfNotNull(
        work.releaseDate?.let { "发售日" to it },
        work.dlCount?.let { "DL数" to formatCount(it) },
        work.price?.let { "价格" to "¥${formatCount(it)}" },
        work.rateAverage2dp?.let { "评分" to "%.2f".format(it) },
        work.reviewCount?.let { "评论数" to formatCount(it) },
        work.rateCount?.let { "评价数" to formatCount(it) },
    )
    if (entries.isEmpty()) return
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.forEach { (label, value) ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    "$label: $value",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
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
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        circle?.let { DimensionChip(label = it, onClick = { onOpenBrowse("circle", work.circleId!!) }) }
        state.vas.forEach { va -> DimensionChip(label = va.name, onClick = { onOpenBrowse("va", va.id) }) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChips(tags: List<Tag>, onOpenBrowse: (String, String) -> Unit) {
    if (tags.isEmpty()) return
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tags.forEach { tag -> DimensionChip(label = "#${tag.name}", onClick = { onOpenBrowse("tag", tag.id) }) }
    }
}

@Composable
private fun DimensionChip(label: String, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun ReviewSlot() {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("评分与进度", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                "评分、收听进度与评语将在后续版本提供（任务 15 接入）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TreeHeader(state: WorkDetailUiState) {
    val count = (state.tree as? TrackTreeUiState.Ready)?.let { tree -> countFiles(tree.root) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("音轨", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                "$count 个文件",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TreeRowItem(
    row: TreeRow,
    workId: String,
    onToggleFolder: (String) -> Unit,
    onOpenPlayer: (String, Int) -> Unit,
    onOpenVideoPlayer: (String, Int) -> Unit,
    onOpenText: (String, String) -> Unit,
    onOpenImage: (String, String) -> Unit,
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
            )
        } else {
            Spacer(Modifier.width(24.dp))
        }
        Spacer(Modifier.width(2.dp))
        TypeBadge(node.type)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                node.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!node.isFolder) {
                Text(
                    "#${node.trackIndex} · ${formatBytes(node.size)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TypeBadge(type: TrackNodeType) {
    val (label, container, content) = when (type) {
        TrackNodeType.FOLDER -> Triple("F", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        TrackNodeType.AUDIO -> Triple("A", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        TrackNodeType.VIDEO -> Triple("V", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        TrackNodeType.TEXT -> Triple("T", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        TrackNodeType.IMAGE -> Triple("I", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        TrackNodeType.OTHER -> Triple("?", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = content)
    }
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
