package com.oneasmr.app.data.scanner

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Production [SingleFileProbe]:ContentResolver 读边车,
 * [MediaMetadataRetriever] 读容器内嵌元数据与封面。
 *
 * 每个方法整体 try/catch:探针失败(损坏文件、provider 抖动、不支持的
 * 编码)一律降级为 null/空结果并记日志 —— 单文件入库最差退化到
 * 纯文件名元数据,扫描永不因单个文件中断(同 DocumentReadException 的
 * skip-and-warn 哲学)。
 *
 * 边车大小上限 [MAX_SIDECAR_BYTES]:info.json 正常几十 KB,超限视为异常
 * 文件直接放弃,防止把整个大文件读进内存。
 */
class AndroidSingleFileProbe(context: Context) : SingleFileProbe {

    private val appContext = context.applicationContext

    override fun readSidecarText(documentUri: String): String? =
        readSidecarBytes(documentUri)?.toString(Charsets.UTF_8)

    override fun readSidecarBytes(documentUri: String): ByteArray? = try {
        appContext.contentResolver.openInputStream(Uri.parse(documentUri))?.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > MAX_SIDECAR_BYTES) {
                Log.w(TAG, "sidecar too large (${bytes.size}B), ignored: $documentUri")
                null
            } else {
                bytes
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "sidecar read failed: $documentUri (${e.message})")
        null
    }

    override fun probeMedia(documentUri: String, extractCover: Boolean): MediaProbeResult {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, Uri.parse(documentUri))
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
            val cover = if (extractCover) extractCover(retriever, durationMs) else null
            MediaProbeResult(
                durationMs = durationMs,
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.takeIf { it.isNotBlank() },
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.takeIf { it.isNotBlank() },
                coverJpeg = cover,
            )
        } catch (e: Exception) {
            Log.w(TAG, "media probe failed: $documentUri (${e.message})")
            MediaProbeResult(durationMs = null, title = null, artist = null, coverJpeg = null)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 封面:内嵌图(音频专辑图/--embed-thumbnail)优先,否则视频取 10% 处
     * 关键帧(片头黑场/logo 概率比首帧低)。统一转 JPEG 字节。
     */
    private fun extractCover(retriever: MediaMetadataRetriever, durationMs: Long?): ByteArray? {
        retriever.embeddedPicture?.let { return it }
        val frameAtUs = (durationMs ?: 0L) * 1000L / 10L
        val frame = retriever.getFrameAtTime(frameAtUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: return null
        return try {
            ByteArrayOutputStream().use { out ->
                // 列表缩略图用途:限宽压缩,避免 4K 帧原样落盘。
                val scaled = scaleDown(frame, maxDim = 640)
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                if (scaled !== frame) scaled.recycle()
                out.toByteArray()
            }
        } finally {
            frame.recycle()
        }
    }

    private fun scaleDown(src: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxDim) return src
        val scale = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private companion object {
        const val TAG = "OneAsmrSingleProbe"
        const val MAX_SIDECAR_BYTES = 4 * 1024 * 1024
    }
}
