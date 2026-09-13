package com.oneasmr.app.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.oneasmr.app.data.repository.CoverStore
import com.oneasmr.app.data.repository.CoverType
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
 * - `oneasmr-cover:` URI(见 [coverArtUri]):censored=1 → 直接画默认封面
 *   (锁形占位,与播放页 CensoredCoverPlaceholder 同语义),绝不读取真实
 *   封面 —— 安全模式对通知栏的强约束。否则经 [CoverStore] 解析本地刮削
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
            placeholder()
        } else {
            resolveUncensored(ref.rjCode)
        }
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

    private fun decodeExternalOrNull(uri: Uri): Bitmap? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            decodeBytesScaled(bytes)
        }
    }.getOrNull()

    private fun decodeScaled(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            },
        )
    }.getOrNull()

    private fun decodeBytesScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            },
        )
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > MAX_DECODE_SIZE) sample *= 2
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

    /** 默认封面:单进程一份,首次用到时绘制(锁形占位,CensoredCoverPlaceholder 同语义)。 */
    private val placeholderBitmap: Bitmap by lazy { renderPlaceholder() }

    private fun placeholder(): Bitmap = placeholderBitmap

    private fun renderPlaceholder(): Bitmap {
        val bitmap = Bitmap.createBitmap(PLACEHOLDER_SIZE, PLACEHOLDER_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(PLACEHOLDER_BG)
        val cx = PLACEHOLDER_SIZE / 2f
        val bodyTop = PLACEHOLDER_SIZE * 0.46f
        val bodyWidth = PLACEHOLDER_SIZE * 0.36f
        val bodyHeight = PLACEHOLDER_SIZE * 0.26f
        val shackleRadius = PLACEHOLDER_SIZE * 0.16f

        val lock = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PLACEHOLDER_FG }
        lock.style = Paint.Style.STROKE
        lock.strokeWidth = PLACEHOLDER_SIZE * 0.065f
        canvas.drawArc(
            RectF(cx - shackleRadius, bodyTop - shackleRadius, cx + shackleRadius, bodyTop + shackleRadius),
            180f,
            180f,
            false,
            lock,
        )
        lock.style = Paint.Style.FILL
        canvas.drawRoundRect(
            RectF(cx - bodyWidth / 2, bodyTop, cx + bodyWidth / 2, bodyTop + bodyHeight),
            PLACEHOLDER_SIZE * 0.03f,
            PLACEHOLDER_SIZE * 0.03f,
            lock,
        )
        // Keyhole cut back in the background color: circle + short stem.
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PLACEHOLDER_BG }
        val holeRadius = PLACEHOLDER_SIZE * 0.035f
        val holeCy = bodyTop + bodyHeight * 0.38f
        canvas.drawCircle(cx, holeCy, holeRadius, hole)
        canvas.drawRect(
            cx - holeRadius * 0.4f,
            holeCy,
            cx + holeRadius * 0.4f,
            holeCy + holeRadius * 1.6f,
            hole,
        )
        return bitmap
    }

    private fun String.toUriOrNull(): Uri? = runCatching {
        if (startsWith("content:") || startsWith("file:")) Uri.parse(this) else Uri.fromFile(File(this))
    }.getOrNull()

    private companion object {
        const val MAX_DECODE_SIZE = 1024
        const val PLACEHOLDER_SIZE = 512

        /** 中性深灰底 + 浅灰锁形:明暗通知栏上都不刺眼,不借鉴任何真实封面色调。 */
        const val PLACEHOLDER_BG = 0xFF2E2E36.toInt()
        const val PLACEHOLDER_FG = 0xFFC7C7D2.toInt()
    }
}
