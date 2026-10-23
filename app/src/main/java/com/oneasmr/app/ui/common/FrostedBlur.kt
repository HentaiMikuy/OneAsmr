package com.oneasmr.app.ui.common

import android.graphics.Bitmap

/**
 * 和谐封面「毛玻璃」的共享参数与算法:UI 侧([FrostedCensoredCover])与
 * 通知栏封面(CoverArtBitmapLoader)用同一套,保证各处观感一致。
 *
 * 和谐封面只以 [FROSTED_DECODE_PX] 像素的极小尺寸解码,内容不可辨认;
 * 再在这张缩略图上做模糊,成本可以忽略,也不依赖 RenderEffect(API 31+)。
 */
internal const val FROSTED_DECODE_PX = 24

private const val BLUR_RADIUS = 2
private const val BLUR_PASSES = 3

/** 缓存键片段:模糊参数变化时让旧缓存失效。 */
internal const val FROSTED_BLUR_KEY = "frosted_blur_r${BLUR_RADIUS}_p$BLUR_PASSES"

/**
 * 对(极小)位图做 [BLUR_PASSES] 轮可分离盒式模糊(≈ 高斯),返回新位图。
 * 输入只有几十像素,纯 Kotlin 循环即可。
 */
internal fun frostedBlur(input: Bitmap): Bitmap {
    val src = if (input.config == Bitmap.Config.ARGB_8888) input
    else input.copy(Bitmap.Config.ARGB_8888, false)
    val w = src.width
    val h = src.height
    val pixels = IntArray(w * h)
    src.getPixels(pixels, 0, w, 0, 0, w, h)
    val tmp = IntArray(w * h)
    repeat(BLUR_PASSES) {
        boxBlur(pixels, tmp, w, h, horizontal = true)
        boxBlur(tmp, pixels, w, h, horizontal = false)
    }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, w, 0, 0, w, h)
    return out
}

private fun boxBlur(src: IntArray, dst: IntArray, w: Int, h: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val len = if (horizontal) w else h
    val count = BLUR_RADIUS * 2 + 1
    for (line in 0 until lines) {
        for (i in 0 until len) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (k in -BLUR_RADIUS..BLUR_RADIUS) {
                val j = (i + k).coerceIn(0, len - 1)
                val p = if (horizontal) src[line * w + j] else src[j * w + line]
                a += p ushr 24
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
            }
            val idx = if (horizontal) line * w + i else i * w + line
            dst[idx] = ((a / count) shl 24) or ((r / count) shl 16) or
                ((g / count) shl 8) or (b / count)
        }
    }
}
