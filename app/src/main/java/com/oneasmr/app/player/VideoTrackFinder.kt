package com.oneasmr.app.player

import com.oneasmr.app.data.local.KeySpec
import com.oneasmr.app.data.scanner.TrackNode
import com.oneasmr.app.data.scanner.TrackNodeType

/**
 * Pure JVM helpers for the attached-video page (plan Task 22) — no Android
 * types, unit-tested without a device (repo flake convention).
 *
 * The video route carries `{workId}/{trackIndex}` where trackIndex is the
 * work track tree's stable 1-based FILE index (same index space as audio
 * tracks — see [com.oneasmr.app.data.scanner.TrackTreeBuilder]).
 */
object VideoTrackFinder {

    /**
     * Finds the VIDEO file node with [trackIndex] in the work's track tree.
     * Walks the tree in the same depth-first pre-order the builder used to
     * assign indices, so a nested video file resolves exactly as the detail
     * page shows it. Returns null when the index belongs to a non-video node
     * (audio/text/image), is absent, or is out of range.
     */
    fun find(root: TrackNode, trackIndex: Int): TrackNode? {
        if (trackIndex < 1) return null
        var found: TrackNode? = null
        fun walk(node: TrackNode) {
            if (found != null) return
            if (node.isFolder) {
                node.children.forEach { walk(it) }
                return
            }
            if (node.type == TrackNodeType.VIDEO && node.trackIndex == trackIndex) {
                found = node
            }
        }
        walk(root)
        return found
    }

    /**
     * Entry decision of the video page against the current session item:
     * ATTACH when the session already plays this exact video track (re-entry
     * / deep-link re-delivery — resume in place, never re-snapshot the audio
     * context), otherwise ENTER (snapshot audio context, replace timeline).
     */
    fun decideEntry(currentMediaId: String?, workId: String, trackIndex: Int): VideoEntryDecision {
        val parts = currentMediaId?.let(KeySpec::parseTrackKey) ?: return VideoEntryDecision.ENTER
        val sameVideo = KeySpec.workId(parts.sourceScope, parts.rjCode) == workId &&
            parts.trackIndex == trackIndex
        return if (sameVideo) VideoEntryDecision.ATTACH else VideoEntryDecision.ENTER
    }
}

enum class VideoEntryDecision { ENTER, ATTACH }
