package com.oneasmr.app.domain.player

import kotlinx.serialization.Serializable

/**
 * Playback queue model — single source of truth for everything the player
 * builds its MediaItems from (plan Task 17). Task 18 builds the queue
 * operations (insert/remove/reorder/repeat/shuffle/speed) ON TOP of this
 * model; it stays a plain immutable data holder here.
 *
 * Key format notes (KeySpec, plan Task 4):
 * - [PlayQueueItem.sourceScope] = "local" (the app is local-only).
 * - [PlayQueueItem.rjCode] = bare normalized code, e.g. "RJ123456".
 * - The mediaId of the constructed MediaItem is the trackKey
 *   "{sourceScope}:{rjCode}:{trackIndex}" (built in MediaItemMapper via
 *   [com.oneasmr.app.data.local.KeySpec] — never inlined here).
 * - [PlayQueueItem.uri] is the local SAF content:// document URI of the
 *   track file (the app is local-only; no remote streaming sources).
 */
@Serializable
data class PlayQueueItem(
    val sourceScope: String,
    val rjCode: String,
    /** 1-based stable file index within the work (TrackNode.trackIndex). */
    val trackIndex: Int,
    /** Track file display name (e.g. "track1.mp3"). */
    val trackTitle: String,
    /** Work title — shown as the artist line in the media notification. */
    val workTitle: String,
    /** Local SAF content:// document uri of the track file. */
    val uri: String,
    /** Total duration in ms when known (null until a playback pass). */
    val durationMs: Long? = null,
)

/**
 * Immutable playback queue: all [items] of a work plus the index playback
 * should start at. [workId] is the "{sourceScope}:{rjCode}" key so the queue
 * can be correlated with the review/progress rows (Task 19).
 */
data class PlayQueue(
    val workId: String,
    val items: List<PlayQueueItem>,
    /** Index into [items] to start playback from (first matching track). */
    val startIndex: Int,
) {
    /** Queue is playable when it contains at least one item and a valid start. */
    val isPlayable: Boolean get() = items.isNotEmpty() && startIndex in items.indices
}

/**
 * 安全模式:音轨标题和谐为「音频 N」(N = 1-based trackIndex)。
 * 纯函数 — 只改 trackTitle,workId/startIndex/uri/trackIndex 全部原样保留,
 * 由 PlayerViewModel 在队列交给会话前调用;PlayQueueBuilder 保持纯净。
 */
fun PlayQueue.harmonizedTitles(): PlayQueue =
    copy(items = items.map { it.copy(trackTitle = "音频 ${it.trackIndex}") })

/**
 * 安全模式单曲曲名决策:需要和谐([harmonize] = NSFW 关 + 敏感评级)时
 * 固定为「音频 N」;还原时取真实曲名,真实曲名缺失则保持现状
 * ([currentTitle],避免把已和谐的标题改成空串)。播放中切换 NSFW 开关时,
 * PlayerViewModel 用它重算会话队列曲名(harmonizedTitles 只管启动时刻)。
 */
fun safeModeTitle(
    trackIndex: Int?,
    realTitle: String?,
    currentTitle: String?,
    harmonize: Boolean,
): String? {
    if (harmonize) return trackIndex?.let { "音频 $it" }
    return realTitle ?: currentTitle
}
