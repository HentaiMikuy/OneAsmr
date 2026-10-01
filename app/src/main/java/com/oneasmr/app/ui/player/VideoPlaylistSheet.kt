package com.oneasmr.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 弹层列表最大高度:长目录可滚动,不把弹层顶到全屏。 */
private val SHEET_LIST_MAX_HEIGHT = 420.dp

/**
 * 视频播放列表底部弹层(Material3 ModalBottomSheet,与 [SpeedPickerSheet]
 * 共用同一套弹层/关闭行为)。
 *
 * 行数据来自 [VideoUiState.playlist](Todo 3):单档路由为同目录兄弟条目,
 * 作品路由为全部 VIDEO 轨。当前条目以主题 primary 容器色高亮(等价于倍速
 * 弹层「选中 chip」的视觉强调),每行语义 contentDescription 形如
 * `video playlist item index=N current=true|false`(QA 断言通道)。
 *
 * 点击任意行回调 [onSelect] 并关闭弹层;切集是会话内 seekTo,由 ViewModel
 * 负责 —— 本弹层只做展示与选择,不触碰播放器、不导航。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlaylistSheet(
    entries: List<VideoPlaylistEntry>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "播放列表",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }

            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = SHEET_LIST_MAX_HEIGHT),
            ) {
                itemsIndexed(entries, key = { _, entry -> entry.index }) { _, entry ->
                    PlaylistRow(
                        entry = entry,
                        onClick = {
                            onSelect(entry.index)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

/** 单行:标题 + 副标题两行;当前条目用 primary 容器色底 + 主题字重强调。 */
@Composable
private fun PlaylistRow(
    entry: VideoPlaylistEntry,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val background = if (entry.isCurrent) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    val titleColor = if (entry.isCurrent) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val subTitleColor = if (entry.isCurrent) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(shape)
            .background(background)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription =
                    "video playlist item index=${entry.index} current=${entry.isCurrent}"
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            entry.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (entry.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            entry.subTitle,
            style = MaterialTheme.typography.bodySmall,
            color = subTitleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
