package com.oneasmr.app.ui.singles

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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
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
import com.oneasmr.app.navigation.LocalBottomClusterHeight

/**
 * 单档库根标签页:收藏夹区(系统「收藏」置顶 + 自定义夹 + 新建)+
 * 全部文件列表(缩略图/标题/频道·时长、续播进度线、爱心快捷收藏、
 * 加入收藏夹)。与作品库分体系 —— 无进度状态,组织方式即收藏夹。
 */
@Composable
fun SinglesScreen(
    onOpenFile: (fileId: Long) -> Unit,
    onOpenCollection: (collectionId: Long) -> Unit,
    onOpenScanRoots: () -> Unit,
    viewModel: SinglesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var createDialog by remember { mutableStateOf(false) }
    var membershipTarget by remember { mutableStateOf<SingleFileRow?>(null) }
    var removeTarget by remember { mutableStateOf<SingleFileRow?>(null) }
    var bindTarget by remember { mutableStateOf<SingleFileRow?>(null) }
    var sortMenu by remember { mutableStateOf(false) }

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
            Column(Modifier.weight(1f)) {
                Text("单档", style = MaterialTheme.typography.displaySmall)
                Text(
                    "${state.files.size} 个文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { sortMenu = true }) {
                    Icon(Icons.Filled.Sort, contentDescription = "排序")
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
                            onClick = {
                                viewModel.setOrder(order)
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

        if (state.files.isEmpty()) {
            SinglesEmptyState(
                hasSingleRoot = state.hasSingleRoot,
                onOpenScanRoots = onOpenScanRoots,
            )
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalBottomClusterHeight.current),
        ) {
            item(key = "collections_header") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
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
            }
            item(key = "collections_row") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.collections, key = { it.collection.id }) { entry ->
                        CollectionCard(entry = entry, onClick = { onOpenCollection(entry.collection.id) })
                    }
                }
            }
            item(key = "files_header") {
                Text(
                    "全部文件",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(state.files, key = { it.file.id }) { row ->
                SingleFileListRow(
                    row = row,
                    onClick = { onOpenFile(row.file.id) },
                    onToggleFavorite = { viewModel.toggleFavorite(row) },
                    overflowActions = buildList {
                        add(RowAction("加入收藏夹…") { membershipTarget = row })
                        // 有 ID 一键补全;无 ID 先手动关联链接(裸文件名无从
                        // 可靠搜索,见 YouTubeLinkParser KDoc)。
                        if (row.file.youtubeId != null) {
                            add(RowAction("在线补全信息") { viewModel.enrich(row.file.id) })
                        } else {
                            add(RowAction("关联视频链接…") { bindTarget = row })
                        }
                        add(RowAction("从库中移除") { removeTarget = row })
                    },
                )
                HorizontalDivider()
            }
        }
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
) {
    val file = row.file
    val greyed = file.missing
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !greyed, onClick = onClick)
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
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                overflowActions.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.label) },
                        onClick = {
                            menu = false
                            item.action()
                        },
                    )
                }
            }
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

/** 3661000ms -> "1:01:01";61000ms -> "1:01"。 */
internal fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
