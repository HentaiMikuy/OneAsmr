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
import com.oneasmr.app.data.local.AgeRating
import com.oneasmr.app.data.local.isCensored

/** 全局 NSFW 模式开关(MainActivity 从 SettingsStore 提供)。 */
val LocalNsfwEnabled = staticCompositionLocalOf { true }

/**
 * 封面和谐判定(UI 侧单一权威):安全模式(NSFW 关)且评级敏感时隐藏。
 * 评级未知(null,尚未在详情页标记或作品行查不到)按敏感处理 —— 安全第一,
 * 与 [CensoredCoverPlaceholder] 的"宁可多锁一个"立场一致。
 *
 * 入参是作品行的真实评级(列表查询/VM 已带出),不是预先算好的"是否和谐"
 * 布尔值 —— 判定只此一处,胶囊与作品库不可能各锁一半。播放服务的通知栏
 * 封面是同一规则的另一执行点:那边没有 CompositionLocal 可读,改为在构建
 * 队列时把决策编码进 artworkUri(见 CoverArtBitmapLoader)。
 */
fun safeModeHidesCover(nsfwEnabled: Boolean, ageRating: AgeRating?): Boolean =
    !nsfwEnabled && ageRating.isCensored()

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
