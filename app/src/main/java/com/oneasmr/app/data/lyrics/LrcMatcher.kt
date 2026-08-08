package com.oneasmr.app.data.lyrics

import com.oneasmr.app.data.scanner.TrackNode
import com.oneasmr.app.data.scanner.TrackNodeType

/**
 * LRC auto-match for an audio track (plan Task 20, kikoeru `check-lrc`
 * semantics: same directory, same base filename, `.lrc` extension —
 * case-insensitive). When several candidates match, the FIRST in tree order
 * (deterministic DFS over the naturally sorted children) wins.
 */
object LrcMatcher {

    fun findLrc(root: TrackNode, audioRelativePath: String): TrackNode? {
        val dir = audioRelativePath.substringBeforeLast('/', "")
        val base = audioRelativePath.substringAfterLast('/').substringBeforeLast('.', "")
        if (base.isEmpty()) return null
        val wantedName = "${base.lowercase()}.lrc"
        var found: TrackNode? = null

        fun walk(node: TrackNode) {
            if (found != null) return
            if (node.isFolder) {
                node.children.forEach { walk(it) }
                return
            }
            if (node.type != TrackNodeType.TEXT) return
            if (node.relativePath.substringBeforeLast('/', "") == dir &&
                node.name.lowercase() == wantedName
            ) {
                found = node
            }
        }
        walk(root)
        return found
    }
}
