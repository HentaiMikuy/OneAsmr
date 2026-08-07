package com.oneasmr.app.data.scanner

import com.oneasmr.app.domain.media.MediaClassifier
import com.oneasmr.app.domain.media.MediaType

/**
 * Builds a work folder's track tree at request time (plan Task 6).
 *
 * - Walks the work folder recursively through the injected [DocumentFs]
 *   (never DocumentFile).
 * - Children of every directory are sorted with [NaturalOrderComparator]
 *   (numeric-aware: track1 < track2 < track10).
 * - Each FILE is classified by extension via [MediaClassifier] into
 *   audio/video/text/image/other.
 * - [TrackNode.trackIndex] is a stable 1-based depth-first index over file
 *   nodes only (folders carry null), matching kikoeru hash={workId}/{index}.
 * - An unreadable directory is recorded as a warning and skipped; the rest of
 *   the tree still builds.
 *
 * Tracks are never stored in the database — this tree is built on demand
 * (detail page, player queue) and discarded.
 */
class TrackTreeBuilder(private val fs: DocumentFs) {

    /**
     * Builds the tree for the work folder at [workPath].
     *
     * @param workPath path of the work folder (never the tree root itself).
     * @param workName display name of the work folder (title source for the
     *   root node — [FsPath] carries document ids, not display names).
     * @param workDocumentUri content:// document URI of the work folder
     *   (from the entry that registered the work; used for the root node).
     * @param isActive cancellation probe; when it returns false the build
     *   aborts with [ScanAbortedException] at the next directory boundary.
     */
    fun build(
        workPath: FsPath,
        workName: String,
        workDocumentUri: String,
        isActive: () -> Boolean = { true },
    ): TrackTreeResult {
        val counter = FileIndexCounter()
        val warnings = mutableListOf<String>()
        val root = buildDirNode(
            path = workPath,
            relativePath = "",
            nodeName = workName,
            nodeDocumentUri = workDocumentUri,
            isActive = isActive,
            warnings = warnings,
            counter = counter,
        )
        return TrackTreeResult(root = root, fileCount = counter.value, warnings = warnings)
    }

    private fun buildDirNode(
        path: FsPath,
        relativePath: String,
        nodeName: String,
        nodeDocumentUri: String,
        isActive: () -> Boolean,
        warnings: MutableList<String>,
        counter: FileIndexCounter,
    ): TrackNode {
        if (!isActive()) throw ScanAbortedException("track tree build cancelled at '$relativePath'")
        val children = try {
            fs.listChildren(path)
        } catch (e: DocumentReadException) {
            warnings += "unreadable directory '$relativePath': ${e.message}"
            emptyList()
        }
        val sorted = children.sortedWith { a, b -> NaturalOrderComparator.compare(a.name, b.name) }
        val nodes = sorted.map { entry ->
            if (entry.isDirectory) {
                buildDirNode(
                    path = path + entry.documentId,
                    relativePath = joinPath(relativePath, entry.name),
                    nodeName = entry.name,
                    nodeDocumentUri = entry.documentUri,
                    isActive = isActive,
                    warnings = warnings,
                    counter = counter,
                )
            } else {
                counter.value += 1
                TrackNode(
                    type = classifyToNodeType(entry.name),
                    name = entry.name,
                    relativePath = joinPath(relativePath, entry.name),
                    trackIndex = counter.value,
                    documentUri = entry.documentUri,
                    size = entry.size,
                    lastModified = entry.lastModified,
                    children = emptyList(),
                )
            }
        }
        return TrackNode(
            type = TrackNodeType.FOLDER,
            name = nodeName,
            relativePath = relativePath,
            trackIndex = null,
            documentUri = nodeDocumentUri,
            size = 0L,
            lastModified = 0L,
            children = nodes,
        )
    }

    private fun joinPath(parent: String, name: String): String =
        if (parent.isEmpty()) name else "$parent/$name"

    private fun classifyToNodeType(fileName: String): TrackNodeType = when (MediaClassifier.classify(fileName)) {
        MediaType.AUDIO -> TrackNodeType.AUDIO
        MediaType.VIDEO -> TrackNodeType.VIDEO
        MediaType.TEXT -> TrackNodeType.TEXT
        MediaType.IMAGE -> TrackNodeType.IMAGE
        MediaType.OTHER -> TrackNodeType.OTHER
    }

    /** Mutable counter holder so the DFS can thread the running index through recursion. */
    private class FileIndexCounter(var value: Int = 0)
}
