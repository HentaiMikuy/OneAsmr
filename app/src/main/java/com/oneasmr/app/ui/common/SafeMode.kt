package com.oneasmr.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 全局 NSFW 模式开关(MainActivity 从 SettingsStore 提供)。 */
val LocalNsfwEnabled = staticCompositionLocalOf { true }

/**
 * 和谐封面占位:surfaceVariant 底 + 居中 24dp 锁图标。刻意不用模糊——
 * 网格里一排 RenderEffect 会掉帧,纯色占位完全盖住封面(安全模式无需
 * 任何"隐约可见"效果)。
 */
@Composable
fun CensoredCoverPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Lock,
            contentDescription = "封面已隐藏",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}
