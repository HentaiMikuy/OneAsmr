package com.oneasmr.app.data.lyrics

import com.oneasmr.app.data.scanner.TrackNode
import com.oneasmr.app.data.scanner.TrackNodeType

/**
 * Lyrics/subtitle auto-match for an audio track. Extends the original
 * kikoeru `check-lrc` semantics (same directory, same base filename, `.lrc`
 * extension, case-insensitive) to the other subtitle formats shipped inside
 * DLsite work folders.
 *
 * Candidate names for audio `dir/track01.mp3`, in priority order:
 *  1. `track01.lrc`      (replaced extension — the original semantics)
 *  2. `track01.mp3.lrc`  (appended extension)
 *  3. `track01.vtt` / `track01.mp3.vtt` (DLsite official subtitles use the
 *     appended form)
 *  4. `track01.srt` / `track01.mp3.srt`
 *  5. `track01.txt` / `track01.mp3.txt` (only useful when the content is
 *     actually LRC-formatted — the loader hides the entry otherwise)
 *
 * Format priority outranks naming form (a `.lrc` in either naming beats any
 * `.vtt`). For one candidate NAME matched by several files (case variants),
 * the FIRST in tree order (deterministic DFS over the naturally sorted
 * children) wins, as before.
 */
object LyricsMatcher {

    /** 格式优先级:专用歌词 > 字幕 > 纯文本兜底。 */
    private val EXTENSION_PRIORITY = listOf("lrc", "vtt", "srt", "txt")

    fun findLyrics(root: TrackNode, audioRelativePath: String): TrackNode? {
        val dir = audioRelativePath.substringBeforeLast('/', "")
        val fullName = audioRelativePath.substringAfterLast('/').lowercase()
        val base = fullName.substringBeforeLast('.', "")
        if (base.isEmpty()) return null
        val wantedNames = EXTENSION_PRIORITY.flatMap { ext ->
            listOf("$base.$ext", "$fullName.$ext")
        }

        // Same-directory TEXT nodes, keyed by lowercase name; first tree-order
        // occurrence wins for a given name.
        val byName = HashMap<String, TrackNode>()
        fun walk(node: TrackNode) {
            if (node.isFolder) {
                node.children.forEach { walk(it) }
                return
            }
            if (node.type != TrackNodeType.TEXT) return
            if (node.relativePath.substringBeforeLast('/', "") != dir) return
            byName.putIfAbsent(node.name.lowercase(), node)
        }
        walk(root)

        return wantedNames.firstNotNullOfOrNull { byName[it] }
    }
}
