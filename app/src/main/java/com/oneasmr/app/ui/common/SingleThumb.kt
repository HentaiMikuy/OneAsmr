package com.oneasmr.app.ui.common

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.oneasmr.app.data.local.SingleFileKind
import java.io.File

/**
 * 单档封面([com.oneasmr.app.data.local.SingleFile.thumbSource] → Coil/Media3
 * 可用的 model)的单一解析规则:content: uri 原样,其余按本地绝对路径补
 * file://。单档缩略图不进 [com.oneasmr.app.data.repository.CoverStore]
 * (covers/ 是刮削封面目录),所以这条规则必须与单档列表、视频页通知栏封面
 * 完全一致 —— 三处共用本函数。
 */
fun singleThumbModel(thumbSource: String): Uri =
    if (thumbSource.startsWith("content:")) Uri.parse(thumbSource) else Uri.fromFile(File(thumbSource))

/**
 * 单档缩略图。未抽帧/无在线封面(thumbSource 为 null)时按类别画占位图标,
 * 与单档列表行同款。尺寸与形状由调用方通过 [modifier] 提供(clip/大小)。
 */
@Composable
fun SingleFileThumb(
    thumbSource: String?,
    kind: SingleFileKind?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = thumbSource?.let(::singleThumbModel),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
            error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
        )
        if (thumbSource == null) {
            Icon(
                if (kind == SingleFileKind.VIDEO) Icons.Outlined.Videocam else Icons.Outlined.AudioFile,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
