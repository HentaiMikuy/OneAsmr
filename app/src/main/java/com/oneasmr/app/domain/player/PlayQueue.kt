package com.oneasmr.app.domain.player

import kotlinx.serialization.Serializable

/**
 * Playback queue model — single source of truth for everything the player
 * builds its MediaItems from (plan Task 17). Task 18 builds the queue
 * operations (insert/remove/reorder/repeat/shuffle/speed) ON TOP of this
 * model; it stays a plain immutable data holder here.
 *
 * Key format notes (KeySpec, plan Task 4):
 * - [PlayQueueItem.sourceScope] = "local" or "srv{n}" (Task 26).
 * - [PlayQueueItem.rjCode] = bare normalized code, e.g. "RJ123456".
 * - The mediaId of the constructed MediaItem is the trackKey
 *   "{sourceScope}:{rjCode}:{trackIndex}" (built in MediaItemMapper via
 *   [com.oneasmr.app.data.local.KeySpec] — never inlined here).
 * - [PlayQueueItem.uri] supports BOTH the local SAF content:// document URI
 *   and a remote http(s) streaming URL; only the local path is exercised
 *   until Task 26 wires remote streaming.
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
    /** SAF content:// document uri OR a remote http(s) stream url (Task 26). */
    val uri: String,
    /** Total duration in ms when known (null until a playback pass). */
    val durationMs: Long? = null,
) {
    /**
     * Whether this item plays from a remote URL. The MediaItem mapper routes
     * both through the same setUri; remote support is exercised in Task 26.
     */
    val isRemote: Boolean get() = uri.startsWith("http://") || uri.startsWith("https://")
}

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
