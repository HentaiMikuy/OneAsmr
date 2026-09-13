package com.oneasmr.app.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.oneasmr.app.data.local.KeySpec
import com.oneasmr.app.domain.player.PlayQueue
import com.oneasmr.app.domain.player.PlayQueueItem

/**
 * Maps [PlayQueueItem]s to Media3 [MediaItem]s (plan Task 17).
 *
 * The mediaId is the NORMATIVE trackKey "{sourceScope}:{rjCode}:{trackIndex}"
 * built exclusively through [KeySpec] — the single source of truth for keys
 * (plan Task 4; Task 19 reads position memory back through the same key).
 * The uri is passed through verbatim — the local SAF content:// document uri
 * of the track file (the app is local-only; no remote streaming sources).
 *
 * 封面(通知栏):每个条目都带 [coverArtUri] 形态的 artworkUri,由会话的
 * [CoverArtBitmapLoader] 在通知渲染时解析。和谐决策(NSFW 关 + 敏感评级 →
 * 默认封面)在**构建时**编码进 URI 的 censored 参数 —— Media3 的
 * CacheBitmapLoader 按 URI 缓存,状态进 URI 才能保证开关切换后旧图不复活。
 * 参数无默认值:新增调用点必须显式做一次和谐决策。
 */
fun PlayQueueItem.toMediaItem(censored: Boolean): MediaItem = MediaItem.Builder()
    .setMediaId(KeySpec.trackKey(sourceScope, rjCode, trackIndex))
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(trackTitle)
            .setArtist(workTitle)
            .setArtworkUri(coverArtUri(sourceScope, rjCode, censored))
            .build(),
    )
    .build()

/** Convenience: the whole queue as MediaItems (used by service + controller). */
fun PlayQueue.toMediaItems(censored: Boolean): List<MediaItem> = items.map { it.toMediaItem(censored) }

/**
 * 通知栏封面 artworkUri:"oneasmr-cover://{sourceScope}/{rjCode}?censored=0|1"。
 * censored=1 时 [CoverArtBitmapLoader] 直接画默认封面(锁形占位),绝不解析
 * 真实封面 —— 这是安全模式对通知栏的强约束;其余情况解析本地刮削封面,
 * 全缺失时同样落默认封面(与播放页占位行为一致)。
 */
fun coverArtUri(sourceScope: String, rjCode: String, censored: Boolean): Uri = Uri.Builder()
    .scheme(COVER_ART_SCHEME)
    .authority(sourceScope)
    .appendPath(rjCode)
    .appendQueryParameter(COVER_ART_CENSORED_PARAM, if (censored) "1" else "0")
    .build()

/** Parsed form of a [coverArtUri]; null for foreign URIs or malformed parts. */
data class CoverArtRef(val sourceScope: String, val rjCode: String, val censored: Boolean)

fun Uri.asCoverArtRef(): CoverArtRef? {
    if (scheme != COVER_ART_SCHEME) return null
    val scope = authority ?: return null
    val code = lastPathSegment ?: return null
    if (scope.isEmpty() || code.isEmpty()) return null
    val censored = getQueryParameter(COVER_ART_CENSORED_PARAM) == "1"
    return CoverArtRef(scope, code, censored)
}

const val COVER_ART_SCHEME = "oneasmr-cover"
private const val COVER_ART_CENSORED_PARAM = "censored"
