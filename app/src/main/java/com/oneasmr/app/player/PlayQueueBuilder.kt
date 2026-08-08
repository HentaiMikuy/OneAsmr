package com.oneasmr.app.player

import com.oneasmr.app.data.local.KeySpec
import com.oneasmr.app.data.scanner.TrackNode
import com.oneasmr.app.data.scanner.TrackNodeType
import com.oneasmr.app.domain.player.PlayQueue
import com.oneasmr.app.domain.player.PlayQueueItem

/**
 * Builds the [PlayQueue] for one work from its live track tree (plan Task 17).
 *
 * - Only AUDIO nodes enter the queue (video nodes go to the Task 22 page).
 * - Depth-first pre-order over the naturally-sorted tree — identical walk to
 *   [com.oneasmr.app.data.scanner.TrackTreeBuilder]'s index assignment, so
 *   queue order == the detail page's "#N" order (kikoeru hash semantics).
 * - The queue carries ALL audio tracks of the work; playback starts at the
 *   item whose trackIndex matches the tapped node ([startTrackIndex]).
 * - [uri] passthrough: local SAF content:// document uris only (the app is
 *   local-only; there are no remote streaming sources).
 *
 * Pure JVM — no Android types, unit-tested with synthetic [TrackNode] trees.
 */
class PlayQueueBuilder {

    /**
     * @param workId "{sourceScope}:{rjCode}" database key of the work.
     * @param workTitle display title of the work (artist line in MediaItem).
     * @param root root folder node of the work's track tree.
     * @param startTrackIndex 1-based trackIndex of the tapped audio node.
     * @return the play queue; [PlayQueue.isPlayable] is false when the work
     *   has no audio nodes (caller must surface a "no audio" message).
     */
    fun build(
        workId: String,
        workTitle: String,
        root: TrackNode,
        startTrackIndex: Int,
    ): PlayQueue {
        val parsed = KeySpec.parseWorkId(workId)
        val sourceScope = parsed?.sourceScope ?: KeySpec.LOCAL_SOURCE
        val rjCode = parsed?.rjCode ?: workId
        val items = mutableListOf<PlayQueueItem>()
        var startIndex = 0
        var indexSeen = false

        fun walk(node: TrackNode) {
            if (node.isFolder) {
                node.children.forEach { walk(it) }
                return
            }
            if (node.type != TrackNodeType.AUDIO) return
            val trackIndex = checkNotNull(node.trackIndex) {
                "audio file node without trackIndex: ${node.relativePath}"
            }
            if (!indexSeen && trackIndex == startTrackIndex) {
                startIndex = items.size
                indexSeen = true
            }
            items += PlayQueueItem(
                sourceScope = sourceScope,
                rjCode = rjCode,
                trackIndex = trackIndex,
                trackTitle = node.name,
                workTitle = workTitle,
                uri = node.documentUri,
            )
        }
        walk(root)
        return PlayQueue(workId = workId, items = items, startIndex = startIndex)
    }
}
