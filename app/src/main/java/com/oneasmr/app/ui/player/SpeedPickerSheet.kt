package com.oneasmr.app.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oneasmr.app.domain.player.PlaybackSpeed

/** 快捷倍速预设（均为 SUPPORTED 内的 0.1 步进值，不会吸附偏移）。 */
private val PRESETS = listOf(0.5f, 0.8f, 1.0f, 1.2f, 1.5f, 2.0f)

/** 一位小数显示，避免滑块浮点累计误差（如 1.09999999x）。 */
private fun formatSpeed(v: Float): String = "%.1f".format(v)

/**
 * 倍速选择底部弹层（Material3 ModalBottomSheet）。
 *
 * 滑动条以 0.1 步进覆盖 0.5x-2.0x（steps=14，与播放器的 16 档一致），
 * 拖动过程中实时预览数值；选中（chip 点击 / 滑块松手）即生效但不自动
 * 关闭，由用户点关闭按钮或外部区域关闭。onSelect 回传的已是归一化档位值。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SpeedPickerSheet(
    current: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var dragValue by remember { mutableFloatStateOf(current) }
    var dragging by remember { mutableStateOf(false) }
    val displayed = if (dragging) dragValue else current

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
                    "播放倍速",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }

            Text(
                "${formatSpeed(displayed)}x",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Slider(
                value = if (dragging) dragValue else current.coerceIn(0.5f, 2.0f),
                onValueChange = {
                    dragging = true
                    // 立即归一化到 0.1 档位:预览数值与回传值都不会带浮点累计误差。
                    dragValue = PlaybackSpeed.normalize(it)
                },
                onValueChangeFinished = {
                    onSelect(dragValue)
                    dragging = false
                },
                valueRange = 0.5f..2.0f,
                steps = 14,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PRESETS.forEach { speed ->
                    FilterChip(
                        selected = speed == current,
                        onClick = { onSelect(speed) },
                        label = { Text("${speed}x") },
                    )
                }
            }
        }
    }
}
