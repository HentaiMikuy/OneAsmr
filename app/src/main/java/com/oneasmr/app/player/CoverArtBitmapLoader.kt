package com.oneasmr.app.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.oneasmr.app.data.repository.CoverStore
import com.oneasmr.app.data.repository.CoverType
import com.oneasmr.app.ui.common.FROSTED_DECODE_PX
import com.oneasmr.app.ui.common.frostedBlur
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 会话位图加载器(通知栏封面)。Media3 的 DefaultMediaNotificationProvider
 * 在渲染媒体通知时从 [MediaSession.getBitmapLoader] 取加载器解析
 * artworkUri —— 把封面解析收敛在这里,所有入队路径(前台启动、播放中切
 * NSFW、服务冷恢复)自动获得一致结果,时间线本身永不被改写。
 *
 * 解析规则(单一权威):
 * - `oneasmr-cover:` URI(见 [coverArtUri]):censored=1 → 毛玻璃封面
 *   (与播放页 FrostedCensoredCover 同语义):真实封面只以极小尺寸解码并
 *   模糊,绝不以可辨认的分辨率读取 —— 安全模式对通知栏的强约束;无封面
 *   时落默认封面。否则经 [CoverStore] 解析本地刮削
 *   封面(主图优先,跨尺寸回退;与播放页 hero 封面同一条链路),全缺失
 *   时同样落默认封面。
 * - 其他 URI(单文件 thumbSource 的 file:///content: 缩略图)走通用解码,
 *   失败让 future 抛错(通知回落系统小图标)。
 *
 * 线程:解析在自有 IO scope 上执行,Future 立即返回(BitmapLoader 契约)。
 * 解码用 inSampleSize 压到 ~1024px 内,通知提供方外层的
 * SizeLimitedBitmapLoader 还会再缩到 shelf 尺寸。
 */
@Singleton
@UnstableApi
class CoverArtBitmapLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coverStore: CoverStore,
) : BitmapLoader {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun supportsMimeType(mimeType: String): Boolean = true

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = start {
        BitmapFactory.decodeByteArray(data, 0, data.size)
            ?: throw IOException("embedded artwork decode failed")
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = start {
        val ref = uri.asCoverArtRef()
        if (ref == null) {
            decodeExternal(uri)
        } else if (ref.censored) {
            resolveFrosted(ref.rjCode)
        } else {
            resolveUncensored(ref.rjCode)
        }
    }

    /**
     * 和谐条目的封面:真实封面只以 [FROSTED_DECODE_PX] 级别的极小尺寸解码
     * (内容不可辨认),套用与 UI 侧同一套 [frostedBlur],再平滑放大到
     * [FROSTED_OUTPUT_SIZE] 并叠玻璃罩层;没有封面 → 默认封面。
     */
    private suspend fun resolveFrosted(rjCode: String): Bitmap {
        val model = runCatching { coverStore.coverModelFor(rjCode, CoverType.MAIN, null, null) }
            .getOrElse { return placeholder() }
        val tiny = when (model) {
            is File -> decodeScaled(model, FROSTED_DECODE_PX)
            is String -> model.toUriOrNull()?.let { decodeExternalOrNull(it, FROSTED_DECODE_PX) }
            else -> null
        } ?: return placeholder()
        return runCatching { renderFrosted(tiny) }.getOrElse { placeholder() }
    }

    private fun renderFrosted(decoded: Bitmap): Bitmap {
        // 压成正方形极小图再模糊(通知封面为方形;糊化后比例失真不可见)。
        val square = Bitmap.createScaledBitmap(decoded, FROSTED_DECODE_PX, FROSTED_DECODE_PX, true)
        val blurred = frostedBlur(square)
        val out = Bitmap.createScaledBitmap(blurred, FROSTED_OUTPUT_SIZE, FROSTED_OUTPUT_SIZE, true)
            .let { if (it.isMutable) it else it.copy(Bitmap.Config.ARGB_8888, true) }
        val canvas = Canvas(out)
        val size = FROSTED_OUTPUT_SIZE.toFloat()
        canvas.drawColor(FROSTED_SCRIM)
        val highlight = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, size,
                intArrayOf(0x38FFFFFF, 0x0FFFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, size, size, highlight)
        return out
    }

    /**
     * 未和谐条目的封面:本地刮削封面(跨尺寸回退),没有 → 默认封面。
     * 与播放页 hero 封面一致传 null 文件夹信息(不做 SAF bundled 查找)。
     */
    private suspend fun resolveUncensored(rjCode: String): Bitmap {
        val model = runCatching { coverStore.coverModelFor(rjCode, CoverType.MAIN, null, null) }
            .getOrElse { return placeholder() }
        val bitmap = when (model) {
            is File -> decodeScaled(model)
            is String -> model.toUriOrNull()?.let { decodeExternalOrNull(it) }
            else -> null
        }
        return bitmap ?: placeholder()
    }

    private fun decodeExternal(uri: Uri): Bitmap = decodeExternalOrNull(uri)
        ?: throw IOException("artwork decode failed: $uri")

    private fun decodeExternalOrNull(uri: Uri, maxSize: Int = MAX_DECODE_SIZE): Bitmap? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            decodeBytesScaled(bytes, maxSize)
        }
    }.getOrNull()

    private fun decodeScaled(file: File, maxSize: Int = MAX_DECODE_SIZE): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxSize)
            },
        )
    }.getOrNull()

    private fun decodeBytesScaled(bytes: ByteArray, maxSize: Int = MAX_DECODE_SIZE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxSize)
            },
        )
    }

    private fun sampleSizeFor(width: Int, height: Int, maxSize: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > maxSize) sample *= 2
        return sample
    }

    private fun start(block: suspend () -> Bitmap): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        scope.launch {
            try {
                future.set(block())
            } catch (t: Throwable) {
                future.setException(t)
            }
        }
        return future
    }

    /**
     * 默认封面(无封面可用时):单进程一份,首次用到时绘制。中性深灰底 +
     * 与毛玻璃封面同款的顶部柔光,不再画锁形图标。
     */
    private val placeholderBitmap: Bitmap by lazy { renderPlaceholder() }

    private fun placeholder(): Bitmap = placeholderBitmap

    private fun renderPlaceholder(): Bitmap {
        val bitmap = Bitmap.createBitmap(PLACEHOLDER_SIZE, PLACEHOLDER_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(PLACEHOLDER_BG)
        val size = PLACEHOLDER_SIZE.toFloat()
        val highlight = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, size,
                intArrayOf(0x24FFFFFF, 0x00FFFFFF),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, size, size, highlight)
        return bitmap
    }

    private fun String.toUriOrNull(): Uri? = runCatching {
        if (startsWith("content:") || startsWith("file:")) Uri.parse(this) else Uri.fromFile(File(this))
    }.getOrNull()

    private companion object {
        const val MAX_DECODE_SIZE = 1024
        const val PLACEHOLDER_SIZE = 512

        /** 毛玻璃通知封面的输出边长(由极小模糊图平滑放大而来)。 */
        const val FROSTED_OUTPUT_SIZE = 256

        /** 毛玻璃罩层:约 18% 深色,压低对比度,与 UI 侧罩层观感接近。 */
        const val FROSTED_SCRIM = 0x2E1C1C22

        /** 中性深灰底:明暗通知栏上都不刺眼,不借鉴任何真实封面色调。 */
        const val PLACEHOLDER_BG = 0xFF2E2E36.toInt()
    }
}
