package com.oneasmr.app.ui.singles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.oneasmr.app.data.local.CollectionWithCount
import com.oneasmr.app.data.local.SingleFileKind
import com.oneasmr.app.data.local.settings.LibraryViewMode
import com.oneasmr.app.navigation.LocalBottomClusterHeight

/**
 * 单档库根标签页:收藏夹区(系统「收藏」置顶 + 自定义夹 + 新建)+
 * 全部文件卡片/列表(缩略图/标题/频道·时长、续播进度线、爱心快捷收藏、
 * 单个或多选批量加入收藏夹)。与作品库分体系 —— 无进度状态,组织方式即收藏夹。
 */
@Composable
fun SinglesScreen(
    onOpenFile: (fileId: Long) -> Unit,
    onOpenCollection: (collectionId: Long) -> Unit,
    onOpenScanRoots: () -> Unit,
    viewModel: SinglesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    var createDialog by remember { mutableStateOf(false) }
    var membershipTarget by remember { mutableStateOf<SingleFileRow?>(null) }
    var removeTarget by remember { mutableStateOf<SingleFileRow?>(null) }
    var bindTarget by remember { mutableStateOf<SingleFileRow?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedFileIds by remember { mutableStateOf(setOf<Long>()) }
    var batchMembershipDialog by remember { mutableStateOf(false) }

    val availableFileIds = state.files.mapTo(mutableSetOf()) { it.file.id }
    val selectedVisibleFileIds = selectedFileIds.intersect(availableFileIds)
    val allSelected = availableFileIds.isNotEmpty() && selectedVisibleFileIds.size == availableFileIds.size

    fun exitSelectionMode() {
        selectionMode = false
        selectedFileIds = emptySet()
        batchMembershipDialog = false
    }

    BackHandler(enabled = selectionMode && !batchMembershipDialog) { exitSelectionMode() }
    LaunchedEffect(availableFileIds) {
        // 重扫或手动移除后，不让已不存在的 id 留在多选计数里。
        if (selectedVisibleFileIds.size != selectedFileIds.size) {
            selectedFileIds = selectedVisibleFileIds
        }
    }

    // 一次性提示(补全结果等)用 Toast:本页无 Scaffold/SnackbarHost。
    val context = LocalContext.current
    val message by viewModel.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.message.value = null
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                IconButton(onClick = ::exitSelectionMode) {
                    Icon(Icons.Filled.Close, contentDescription = "退出多选")
                }
                Column(Modifier.weight(1f)) {
                    Text("已选 ${selectedVisibleFileIds.size} 项", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "点击文件选择或取消",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = {
                        selectedFileIds = if (allSelected) emptySet() else availableFileIds
                    },
                    enabled = availableFileIds.isNotEmpty(),
                ) {
                    Icon(
                        Icons.Filled.SelectAll,
                        contentDescription = if (allSelected) "取消全选" else "全选",
                    )
                }
                IconButton(
                    onClick = { batchMembershipDialog = true },
                    enabled = selectedVisibleFileIds.isNotEmpty(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "批量加入收藏夹")
                }
            } else {
                Column(Modifier.weight(1f)) {
                    Text("单档", style = MaterialTheme.typography.displaySmall)
                    Text(
                        "${state.files.size} 个文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { selectionMode = true }) {
                    Icon(Icons.Filled.Checklist, contentDescription = "多选")
                }
                SinglesViewModeToggle(mode = viewMode, onSelect = viewModel::setViewMode)
                Box {
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(
                            Icons.Filled.Sort,
                            contentDescription = "排序：${state.order.label}，${state.order.directionLabel(state.sortDescending)}",
                        )
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        SinglesOrder.entries.forEach { order ->
                            DropdownMenuItem(
                                text = { Text(order.label) },
                                leadingIcon = {
                                    RadioButton(
                                        selected = state.order == order,
                                        onClick = null,
                                    )
                                },
                                trailingIcon = if (state.order == order) {
                                    {
                                        Icon(
                                            if (state.sortDescending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                                            contentDescription = if (state.sortDescending) "降序" else "升序",
                                        )
                                    }
                                } else {
                                    null
                                },
                                onClick = {
                                    viewModel.setOrder(order)
                                    sortMenu = false
                                },
                            )
                        }
                        HorizontalDivider()
                        listOf(false, true).forEach { descending ->
                            DropdownMenuItem(
                                text = { Text(state.order.directionLabel(descending)) },
                                leadingIcon = {
                                    RadioButton(selected = state.sortDescending == descending, onClick = null)
                                },
                                onClick = {
                                    viewModel.setSortDescending(descending)
                                    sortMenu = false
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("移除所有失效条目") },
                            onClick = {
                                sortMenu = false
                                viewModel.removeAllMissing()
                            },
                        )
                    }
                }
            }
        }

        if (state.files.isEmpty()) {
            SinglesEmptyState(
                hasSingleRoot = state.hasSingleRoot,
                onOpenScanRoots = onOpenScanRoots,
            )
            return@Column
        }

        val headerInset = if (viewMode == LibraryViewMode.GRID) 4.dp else 16.dp
        SingleFileBrowser(
            files = state.files,
            viewMode = viewMode,
            onOpenFile = onOpenFile,
            onToggleFavorite = viewModel::toggleFavorite,
            selectionMode = selectionMode,
            selectedFileIds = selectedVisibleFileIds,
            onToggleSelected = { row ->
                selectedFileIds = if (row.file.id in selectedFileIds) {
                    selectedFileIds - row.file.id
                } else {
                    selectedFileIds + row.file.id
                }
            },
            overflowActions = { row ->
                buildList {
                    add(
                        RowAction("多选") {
                            selectionMode = true
                            selectedFileIds = selectedFileIds + row.file.id
                        },
                    )
                    add(RowAction("加入收藏夹…") { membershipTarget = row })
                    // 有 ID 一键补全；无 ID 先手动关联链接。
                    if (row.file.youtubeId != null) {
                        add(RowAction("在线补全信息") { viewModel.enrich(row.file.id) })
                    } else {
                        add(RowAction("关联视频链接…") { bindTarget = row })
                    }
                    add(RowAction("从库中移除") { removeTarget = row })
                }
            },
            headerContent = {
                Column {
                    if (!selectionMode) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = headerInset, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "收藏夹",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { createDialog = true }) {
                                Icon(Icons.Filled.Add, contentDescription = "新建收藏夹")
                            }
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = headerInset),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.collections, key = { it.collection.id }) { entry ->
                                CollectionCard(entry = entry, onClick = { onOpenCollection(entry.collection.id) })
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = headerInset, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("全部文件", style = MaterialTheme.typography.titleMedium)
                        if (!selectionMode && viewMode == LibraryViewMode.GRID) {
                            SingleFileLongPressHint(Modifier.weight(1f).padding(start = 12.dp))
                        }
                    }
                }
            },
        )
    }

    if (createDialog) {
        CreateCollectionDialog(
            onConfirm = {
                viewModel.createCollection(it)
                createDialog = false
            },
            onDismiss = { createDialog = false },
        )
    }
    membershipTarget?.let { target ->
        MembershipDialog(
            fileTitle = target.file.displayTitle,
            collections = state.collections,
            initialMembership = state.memberships[target.file.id].orEmpty(),
            onToggle = { collectionId, member ->
                viewModel.setMembership(target.file.id, collectionId, member)
            },
            onCreate = { createDialog = true },
            onDismiss = { membershipTarget = null },
        )
    }
    if (batchMembershipDialog) {
        BatchMembershipDialog(
            fileCount = selectedVisibleFileIds.size,
            collections = state.collections,
            onConfirm = { collectionIds ->
                viewModel.addFilesToCollections(selectedVisibleFileIds, collectionIds)
                exitSelectionMode()
            },
            onCreate = { createDialog = true },
            onDismiss = { batchMembershipDialog = false },
        )
    }
    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("移除该条目？") },
            text = { Text("仅从单档库移除「${target.file.displayTitle}」及其收藏记录，不删除磁盘文件。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeFile(target.file.id)
                        removeTarget = null
                    },
                ) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("取消") } },
        )
    }
    bindTarget?.let { target ->
        var link by remember(target.file.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { bindTarget = null },
            title = { Text("关联视频链接") },
            text = {
                Column {
                    Text(
                        "该文件名里没有视频 ID,无法自动补全。粘贴原视频链接" +
                            "(或 11 位视频 ID)即可关联并在线补全标题/频道/封面。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it },
                        singleLine = true,
                        label = { Text("https://youtu.be/…") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.bindLink(target.file.id, link)
                        bindTarget = null
                    },
                    enabled = link.isNotBlank(),
                ) { Text("关联并补全") }
            },
            dismissButton = { TextButton(onClick = { bindTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SinglesEmptyState(hasSingleRoot: Boolean, onOpenScanRoots: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            // 同 LibraryScreen 空态:底部悬浮簇是覆盖层,先避让再居中。
            .padding(bottom = LocalBottomClusterHeight.current)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.FolderSpecial,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (hasSingleRoot) "单文件库为空" else "尚未添加单文件库",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasSingleRoot) {
                "在根文件夹管理中重新扫描,或向已授权的单文件库目录放入音视频文件。"
            } else {
                "把 YouTube 等流媒体下载的散音视频文件夹添加为「单文件库」根目录,即可在这里管理与收藏。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onOpenScanRoots) { Text("打开根文件夹管理") }
    }
}

/** 收藏夹卡片:系统夹带实心心形,自定义夹用歌单图标;副行为成员数。 */
@Composable
private fun CollectionCard(entry: CollectionWithCount, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (entry.collection.isSystem) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (entry.collection.isSystem) Icons.Filled.Favorite else Icons.AutoMirrored.Filled.PlaylistAdd,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (entry.collection.isSystem) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(entry.collection.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${entry.fileCount} 个",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 行溢出菜单的一个操作项。 */
internal data class RowAction(val label: String, val action: () -> Unit)

/** 文件行:16:9 缩略图 + 标题/频道·时长 + 续播进度线 + 爱心 + 溢出菜单。 */
@Composable
internal fun SingleFileListRow(
    row: SingleFileRow,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    overflowActions: List<RowAction>,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelected: () -> Unit = {},
) {
    val file = row.file
    val greyed = file.missing
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selectionMode && selected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(
                enabled = selectionMode || !greyed,
                onClickLabel = if (selectionMode) {
                    if (selected) "取消选择" else "选择文件"
                } else {
                    null
                },
                onClick = { if (selectionMode) onToggleSelected() else onClick() },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(112.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = file.thumbSource,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
            )
            if (file.thumbSource == null) {
                Icon(
                    if (file.kind == SingleFileKind.VIDEO) Icons.Outlined.Videocam else Icons.Outlined.AudioFile,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            row.progressFraction?.let { fraction ->
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.BottomCenter),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (greyed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                listOfNotNull(
                    file.channel,
                    file.durationMs?.let { formatDuration(it) },
                ).joinToString(" · ").ifEmpty { if (file.kind == SingleFileKind.VIDEO) "视频" else "音频" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (greyed) {
                AssistChip(
                    onClick = {},
                    label = { Text("已失效", style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        if (selectionMode) {
            Spacer(Modifier.width(8.dp))
            Checkbox(checked = selected, onCheckedChange = null)
        } else {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (row.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (row.isFavorite) "取消收藏" else "收藏",
                    tint = if (row.isFavorite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            SingleFileOverflowMenu(overflowActions)
        }
    }
}

@Composable
private fun CreateCollectionDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建收藏夹") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("名称") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 勾选式多夹归属对话框(播放列表语义:一文件可进多夹)。 */
@Composable
private fun MembershipDialog(
    fileTitle: String,
    collections: List<CollectionWithCount>,
    initialMembership: Set<Long>,
    onToggle: (collectionId: Long, member: Boolean) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 勾选态以本地乐观状态呈现:父层的整表流回推有一拍延迟,直接读流
    // 会让勾选框「点了不动」。初始值取自当前成员关系。
    var checked by remember(fileTitle) { mutableStateOf(initialMembership) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入收藏夹") },
        text = {
            Column {
                Text(
                    fileTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                collections.forEach { entry ->
                    val id = entry.collection.id
                    val isChecked = id in checked
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                checked = if (isChecked) checked - id else checked + id
                                onToggle(id, !isChecked)
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = isChecked, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(entry.collection.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                TextButton(onClick = onCreate) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("新建收藏夹")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

/** 多选文件批量加入一个或多个收藏夹；只新增成员关系，不移除原有归属。 */
@Composable
private fun BatchMembershipDialog(
    fileCount: Int,
    collections: List<CollectionWithCount>,
    onConfirm: (Set<Long>) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    var checked by remember { mutableStateOf(setOf<Long>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量加入收藏夹") },
        text = {
            Column {
                Text(
                    "将 $fileCount 个文件加入所选收藏夹，已有条目不会重复添加。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(collections, key = { it.collection.id }) { entry ->
                        val id = entry.collection.id
                        val isChecked = id in checked
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { checked = if (isChecked) checked - id else checked + id }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = isChecked, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text(entry.collection.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    item {
                        TextButton(onClick = onCreate) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("新建收藏夹")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(checked) },
                enabled = fileCount > 0 && checked.isNotEmpty(),
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 3661000ms -> "1:01:01";61000ms -> "1:01"。 */
internal fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
