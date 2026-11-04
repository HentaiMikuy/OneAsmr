package com.oneasmr.app.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oneasmr.app.domain.player.AudioRepeatMode

/** 名称常驻，避免仅靠图标和颜色猜测当前播放策略。 */
internal fun repeatModeLabel(mode: AudioRepeatMode): String = when (mode) {
    AudioRepeatMode.OFF -> "播完列表"
    AudioRepeatMode.ALL -> "列表循环"
    AudioRepeatMode.ONE -> "单曲循环"
    AudioRepeatMode.PAUSE_AT_END -> "播完暂停"
}

private fun repeatModeDescription(mode: AudioRepeatMode): String = when (mode) {
    AudioRepeatMode.OFF -> "自动播放下一曲，列表播完后停止"
    AudioRepeatMode.ALL -> "播完列表后，从头继续播放"
    AudioRepeatMode.ONE -> "反复播放当前曲目"
    AudioRepeatMode.PAUSE_AT_END -> "当前曲目播完即暂停，不进入下一曲"
}

private fun repeatModeIcon(mode: AudioRepeatMode): ImageVector = when (mode) {
    AudioRepeatMode.OFF -> Icons.AutoMirrored.Filled.PlaylistPlay
    AudioRepeatMode.ALL -> Icons.Filled.Repeat
    AudioRepeatMode.ONE -> Icons.Filled.RepeatOne
    AudioRepeatMode.PAUSE_AT_END -> Icons.Filled.PauseCircleOutline
}

/** 图标与文字共用完整触控区域，较大字体可换行。 */
@Composable
internal fun PlaybackModeButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClickLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .heightIn(min = 64.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = onClickLabel,
                onClick = onClick,
            )
            .padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
internal fun RepeatModeSelector(
    mode: AudioRepeatMode,
    enabled: Boolean,
    onSelect: (AudioRepeatMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        PlaybackModeButton(
            icon = repeatModeIcon(mode),
            label = repeatModeLabel(mode),
            active = mode != AudioRepeatMode.OFF,
            enabled = enabled,
            onClickLabel = "选择播放模式",
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(288.dp),
        ) {
            AudioRepeatMode.entries.forEach { option ->
                val selected = option == mode
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(repeatModeLabel(option), style = MaterialTheme.typography.titleSmall)
                            Text(
                                repeatModeDescription(option),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    leadingIcon = {
                        Icon(
                            repeatModeIcon(option),
                            contentDescription = null,
                            tint = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    trailingIcon = if (selected) {
                        { Icon(Icons.Filled.Check, contentDescription = "当前模式") }
                    } else {
                        null
                    },
                    enabled = enabled,
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}
