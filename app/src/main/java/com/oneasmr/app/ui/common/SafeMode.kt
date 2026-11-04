package com.oneasmr.app.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Precision
import coil3.size.Size
import coil3.transform.Transformation
import com.oneasmr.app.data.local.settings.ContentSafetySettings

/** MainActivity 提供的全局显示规则；UI 与播放服务使用同一 shouldCensor 判定。 */
val LocalContentSafety = staticCompositionLocalOf { ContentSafetySettings.LOADING }

/**
 * 和谐封面「毛玻璃」效果:把真实封面以 [FROSTED_DECODE_PX] 像素的极小尺寸
 * 解码,再在这张缩略图上做几轮盒式模糊([frostedBlur]),最后放大铺满并
 * 叠一层半透明高光/罩层,看起来像隔着磨砂玻璃。
 *
 * 为什么不用 Modifier.blur / RenderEffect:网格里一排 RenderEffect 会掉帧,
 * 且 API 31 以下是 no-op(会直接露出清晰封面)。这里模糊发生在一张几十像素
 * 的位图上,成本可以忽略,且全版本一致;原图从不以可辨认的分辨率解码,
 * 安全模式"看不清内容"的语义不变。
 *
 * [model] 为 null 或加载失败时退化为 surfaceVariant 纯色(同普通封面占位)。
 */
@Composable
fun FrostedCensoredCover(
    model: Any?,
    modifier: Modifier = Modifier,
    colorFilter: ColorFilter? = null,
    alignment: Alignment = Alignment.Center,
) {
    val context = LocalPlatformContext.current
    val request = remember(model) {
        ImageRequest.Builder(context)
            .data(model)
            .size(FROSTED_DECODE_PX)
            .precision(Precision.EXACT)
            .transformations(FrostedBlurTransformation)
            // 独立缓存键:绝不命中同一封面的清晰大图缓存。
            .memoryCacheKey("censored:$model")
            .build()
    }
    val fallback = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
    val scrim = MaterialTheme.colorScheme.surface
    Box(modifier = modifier) {
        AsyncImage(
            model = request,
            contentDescription = "封面已模糊",
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
            alignment = alignment,
            placeholder = fallback,
            error = fallback,
            fallback = fallback,
            colorFilter = colorFilter,
        )
        // 玻璃质感:顶部一抹柔光 + 整体轻微罩层,压低对比度。
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(scrim.copy(alpha = 0.18f))
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.22f),
                        0.5f to Color.White.copy(alpha = 0.06f),
                        1f to Color.Transparent,
                    ),
                ),
        )
    }
}

/** Coil 变换:对解码出的极小位图套用共享的 [frostedBlur]。 */
private object FrostedBlurTransformation : Transformation() {
    override val cacheKey: String = FROSTED_BLUR_KEY

    override suspend fun transform(input: Bitmap, size: Size): Bitmap = frostedBlur(input)
}
