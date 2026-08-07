package com.oneasmr.app.data.scanner

/**
 * File-abstraction layer for the local library scanner (plan Task 6).
 *
 * The scanner/tree-builder cores ([LibraryScanner], [TrackTreeBuilder]) are
 * pure JVM: they only ever talk to a [DocumentFs]. Production wires
 * [SafDocumentFs] (a thin DocumentsContract adapter), unit tests wire an
 * in-memory fake — so traversal, work detection, natural sorting and track
 * indexing are fully unit-testable without an Android runtime.
 */
interface DocumentFs {

    /**
     * Lists the children of directory [path].
     *
     * @throws DocumentReadException when the directory cannot be read
     *   (provider error, lost permission, transient IO failure). Callers MUST
     *   catch this and continue: an unreadable directory is skipped with a
     *   warning, never an abort.
     */
    fun listChildren(path: FsPath): List<FsEntry>
}

/**
 * A path inside a tree root, expressed as the sequence of provider document
 * ids from the tree root down to the entry. The tree root itself is the empty
 * path. Document ids are opaque provider-specific strings (SAF cannot address
 * children by display name), so this path — NOT the display name — is what a
 * [DocumentFs] navigates by.
 */
data class FsPath(val segments: List<String>) {
    init {
        require(segments.none { it.isEmpty() }) { "document id segments must be non-empty" }
    }

    val isEmpty: Boolean get() = segments.isEmpty()

    val last: String get() = segments.last()

    val parent: FsPath get() = FsPath(segments.dropLast(1))

    operator fun plus(segment: String): FsPath = FsPath(segments + segment)

    override fun toString(): String = segments.joinToString("/", prefix = "/")
}

/** One directory entry as returned by a [DocumentFs] listing. */
data class FsEntry(
    /** Display name (used for RJ matching, titles, sorting). */
    val name: String,
    /** Provider document id — the child's addressable handle. */
    val documentId: String,
    /**
     * Content:// document URI (built by the adapter from the tree uri +
     * document id). Later tasks (14 detail page, 17 player) resolve playback
     * from this URI; the core never constructs URIs itself.
     */
    val documentUri: String,
    val isDirectory: Boolean,
    /** Epoch millis of last modification (0 when unknown). */
    val lastModified: Long,
    /** Size in bytes (0 for directories / unknown). */
    val size: Long,
)

/**
 * Thrown by [DocumentFs.listChildren] when a directory is unreadable.
 * Distinguishable from [ScanAbortedException]: this one means "skip this
 * directory, warn, keep going"; the abort one means "stop the whole scan".
 */
class DocumentReadException(message: String, cause: Throwable? = null) : Exception(message, cause)
