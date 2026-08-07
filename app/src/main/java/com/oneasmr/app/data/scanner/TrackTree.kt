package com.oneasmr.app.data.scanner

/**
 * Track tree model (plan Task 6). Tracks are NEVER persisted — the tree is a
 * live scan of the work folder performed at request time by
 * [TrackTreeBuilder] (same design as kikoeru's getTrackList).
 */

/** Node categories mirroring [com.oneasmr.app.domain.media.MediaType] plus FOLDER. */
enum class TrackNodeType {
    FOLDER,
    AUDIO,
    VIDEO,
    TEXT,
    IMAGE,
    OTHER,
}

/**
 * One node of a work's track tree.
 *
 * [trackIndex] is the stable 1-based index of a FILE node (folders are null),
 * assigned in deterministic depth-first pre-order over the naturally sorted
 * children — kikoeru's hash={workId}/{index} semantics. [relativePath] is the
 * path relative to the work folder, "/"-separated, no leading slash.
 */
data class TrackNode(
    val type: TrackNodeType,
    /** Display name of the entry (the work folder name for the root). */
    val name: String,
    /** Path relative to the work folder; "" for the root folder node. */
    val relativePath: String,
    /** Stable 1-based file index; null for FOLDER nodes. */
    val trackIndex: Int?,
    /** content:// document URI (see [FsEntry.documentUri]). */
    val documentUri: String,
    val size: Long,
    val lastModified: Long,
    val children: List<TrackNode>,
) {
    val isFolder: Boolean get() = type == TrackNodeType.FOLDER
}

/** Result of a track-tree build: the root folder node plus diagnostics. */
data class TrackTreeResult(
    /** FOLDER node for the work folder itself (name = work folder display name). */
    val root: TrackNode,
    /** Number of FILE nodes (audio+video+text+image+other) in the tree. */
    val fileCount: Int,
    /**
     * Non-fatal problems encountered while walking (e.g. unreadable
     * directories, skipped with a warning). The scan continues past these.
     */
    val warnings: List<String>,
)
