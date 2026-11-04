package com.oneasmr.app.ui.singles

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oneasmr.app.data.local.SingleFileKind
import com.oneasmr.app.data.local.settings.LibraryViewMode
import com.oneasmr.app.navigation.LocalBottomClusterHeight
import com.oneasmr.app.ui.common.SingleFileThumb
import com.oneasmr.app.ui.common.rememberPressScale

@Composable
internal fun SinglesViewModeToggle(mode: LibraryViewMode, onSelect: (LibraryViewMode) -> Unit) {
    Row {
        IconToggleButton(
            checked = mode == LibraryViewMode.GRID,
            onCheckedChange = { onSelect(LibraryViewMode.GRID) },
        ) {
            Icon(Icons.Filled.GridView, contentDescription = "卡片视图")
        }
        IconToggleButton(
            checked = mode == LibraryViewMode.LIST,
            onCheckedChange = { onSelect(LibraryViewMode.LIST) },
        ) {
            Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = "列表视图")
        }
    }
}

@Composable
internal fun SingleFileLongPressHint(modifier: Modifier = Modifier) {
    Text(
        "长按卡片查看更多操作",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** 首页和收藏夹共用浏览布局；页头占满整行，避免嵌套纵向滚动。 */
@Composable
internal fun SingleFileBrowser(
    files: List<SingleFileRow>,
    viewMode: LibraryViewMode,
    onOpenFile: (Long) -> Unit,
    onToggleFavorite: (SingleFileRow) -> Unit,
    overflowActions: (SingleFileRow) -> List<RowAction>,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selectedFileIds: Set<Long> = emptySet(),
    onToggleSelected: (SingleFileRow) -> Unit = {},
    headerContent: (@Composable () -> Unit)? = null,
) {
    val grid = viewMode == LibraryViewMode.GRID
    val gridState = rememberLazyGridState()
    val listState = rememberLazyGridState()
    LazyVerticalGrid(
        columns = if (grid) GridCells.Adaptive(136.dp) else GridCells.Fixed(1),
        state = if (grid) gridState else listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = if (grid) 12.dp else 0.dp,
            end = if (grid) 12.dp else 0.dp,
            top = if (grid) 4.dp else 0.dp,
            bottom = LocalBottomClusterHeight.current + 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(if (grid) 12.dp else 0.dp),
    ) {
        if (headerContent != null) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                headerContent()
            }
        } else if (grid) {
            item(key = "long_press_hint", span = { GridItemSpan(maxLineSpan) }, contentType = "hint") {
                SingleFileLongPressHint(Modifier.fillMaxWidth().padding(horizontal = 4.dp))
            }
        }
        items(files, key = { it.file.id }, contentType = { if (grid) "card" else "row" }) { row ->
            if (grid) {
                SingleFileGridCard(
                    row = row,
                    onClick = { onOpenFile(row.file.id) },
                    onToggleFavorite = { onToggleFavorite(row) },
                    overflowActions = overflowActions(row),
                    selectionMode = selectionMode,
                    selected = row.file.id in selectedFileIds,
                    onToggleSelected = { onToggleSelected(row) },
                )
            } else {
                Column {
                    SingleFileListRow(
                        row = row,
                        onClick = { onOpenFile(row.file.id) },
                        onToggleFavorite = { onToggleFavorite(row) },
                        overflowActions = overflowActions(row),
                        selectionMode = selectionMode,
                        selected = row.file.id in selectedFileIds,
                        onToggleSelected = { onToggleSelected(row) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** 16:9 缩略图卡片，保留列表里的续播、收藏、失效状态与全部菜单操作。 */
@Composable
private fun SingleFileGridCard(
    row: SingleFileRow,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    overflowActions: List<RowAction>,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelected: () -> Unit,
) {
    val file = row.file
    val kindLabel = if (file.kind == SingleFileKind.VIDEO) "视频" else "音频"
    var menuExpanded by remember(file.id) { mutableStateOf(false) }
    val (interactionSource, pressScale) = rememberPressScale(0.97f)
    Surface(
        modifier = Modifier.graphicsLayer { scaleX = pressScale; scaleY = pressScale },
        shape = MaterialTheme.shapes.medium,
        color = if (selectionMode && selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = if (selectionMode && selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    role = if (selectionMode) Role.Checkbox else Role.Button,
                    onClickLabel = when {
                        selectionMode -> if (selected) "取消选择" else "选择文件"
                        file.missing -> "文件已失效"
                        else -> "播放$kindLabel"
                    },
                    onClick = {
                        when {
                            selectionMode -> onToggleSelected()
                            !file.missing -> onClick()
                        }
                    },
                    onLongClickLabel = if (selectionMode) null else "更多操作",
                    onLongClick = if (selectionMode) {
                        null
                    } else {
                        { menuExpanded = true }
                    },
                    hapticFeedbackEnabled = true,
                ),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                SingleFileThumb(
                    thumbSource = file.thumbSource,
                    kind = file.kind,
                    modifier = Modifier.fillMaxSize().alpha(if (file.missing) 0.45f else 1f),
                )
                Surface(
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f),
                    contentColor = Color.White,
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                ) {
                    Text(
                        if (file.missing) "已失效" else kindLabel,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
                if (selectionMode) {
                    Icon(
                        if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f), CircleShape),
                    )
                } else {
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f), CircleShape),
                    ) {
                        Icon(
                            if (row.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (row.isFavorite) "取消收藏" else "收藏",
                            tint = if (row.isFavorite) MaterialTheme.colorScheme.primary else Color.White,
                        )
                    }
                }
                file.durationMs?.let { duration ->
                    Surface(
                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f),
                        contentColor = Color.White,
                        shape = MaterialTheme.shapes.extraSmall,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 6.dp),
                    ) {
                        Text(
                            formatDuration(duration),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                    }
                }
                row.progressFraction?.let { fraction ->
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    )
                }
                SingleFileActionMenu(
                    actions = overflowActions,
                    expanded = menuExpanded,
                    onDismiss = { menuExpanded = false },
                )
            }
            Text(
                file.displayTitle,
                style = MaterialTheme.typography.titleSmall,
                color = if (file.missing) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp),
            )
            Text(
                file.channel?.takeIf { it.isNotBlank() } ?: kindLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 10.dp),
            )
        }
    }
}

@Composable
internal fun SingleFileOverflowMenu(actions: List<RowAction>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
        }
        SingleFileActionMenu(actions, expanded, onDismiss = { expanded = false })
    }
}

@Composable
private fun SingleFileActionMenu(actions: List<RowAction>, expanded: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        actions.forEach { item ->
            DropdownMenuItem(
                text = { Text(item.label) },
                onClick = {
                    onDismiss()
                    item.action()
                },
            )
        }
    }
}
