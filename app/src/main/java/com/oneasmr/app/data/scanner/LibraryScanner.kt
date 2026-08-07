package com.oneasmr.app.data.scanner

import com.oneasmr.app.domain.rjcode.RjCodeParser

/**
 * A work folder discovered by [LibraryScanner]: its path relative to the scan
 * root (no leading slash, e.g. "AudioBooks/RJ123456") plus the parsed RJ code.
 * The caller (worker/persister) turns this into a database row.
 */
data class WorkCandidate(
    /** Work folder path relative to the scan root, no leading slash. */
    val relativeDir: String,
    /** Canonical code prefix+digits, e.g. "RJ123456" (see RjCodeParser). */
    val rjCode: String,
    /** Display name of the work folder (title + titleSortKey source). */
    val displayName: String,
)

/** Thrown when [ScanCallback.isActive] turns false mid-scan: everything stops. */
class ScanAbortedException(message: String) : RuntimeException(message)

/**
 * Live callback of a [LibraryScanner] run. The core never touches Android:
 * persistence and progress are the caller's job.
 */
interface ScanCallback {
    /**
     * A work folder was found. MUST be handled atomically by the caller
     * (each work = one Room transaction) so a cancelled scan never leaves
     * half rows — already-committed works stay, uncommitted ones vanish.
     */
    suspend fun onWorkFound(work: WorkCandidate)

    /** Progress heartbeat: [currentDir] relative to the root, [worksFound] so far. */
    fun onProgress(currentDir: String, worksFound: Int)

    /** Non-fatal problem (unreadable dir skipped); scan continues. */
    fun onWarning(message: String)

    /**
     * Cancellation probe, checked at every directory boundary. Returning
     * false aborts the scan with [ScanAbortedException] (worker maps it to
     * WorkManager cancellation; committed works are untouched).
     */
    fun isActive(): Boolean
}

/**
 * Recursive work-folder discovery over a [DocumentFs] (plan Task 6).
 *
 * Traversal rules (all locked by LibraryScannerTest):
 * - Children are listed in natural order ([NaturalOrderComparator]) so
 *   discovery is deterministic.
 * - A DIRECTORY whose display name matches [RjCodeParser] registers a
 *   [WorkCandidate] (rootFolderUri + relativeDir) and is NOT recursed into —
 *   everything inside that folder belongs to the work, and a second RJ code
 *   inside it is deliberately ignored.
 * - Non-RJ directories are recursed into (works may nest at any depth).
 * - Files are ignored during work discovery.
 * - An unreadable directory is skipped with [ScanCallback.onWarning]; the
 *   scan continues.
 *
 * One scanner instance should be used per scan run: it tracks the running
 * works-found count for progress. The WorkManager worker chunks the work per
 * ROOT / per TOP-LEVEL SUBDIRECTORY ([topLevelChunks]); the resumable design
 * re-runs [scanChunk] per chunk with checkpoints in WorkManager inputData.
 */
class LibraryScanner(private val fs: DocumentFs) {

    /** Works found so far in this run (progress source). One scanner per scan run. */
    private var _worksFound = 0

    /** Running works-found count within this scanner instance. */
    val worksFound: Int get() = _worksFound

    /**
     * Lists the tree root's direct children, naturally sorted — these are the
     * chunk boundaries of the resumable worker. Throws [DocumentReadException]
     * when the root itself is unreadable (caller warns + skips the root).
     */
    fun topLevelChunks(rootPath: FsPath = FsPath(emptyList())): List<FsEntry> =
        fs.listChildren(rootPath).sortedWith(entryComparator)

    /**
     * Recursively scans ONE top-level chunk for work folders (suspend because
     * the callback commits works via Room).
     *
     * @param chunk a top-level entry from [topLevelChunks] (directories are
     *   scanned; top-level files are ignored).
     */
    suspend fun scanChunk(chunk: FsEntry, callback: ScanCallback) {
        checkActive(callback)
        if (!chunk.isDirectory) return
        val parsed = RjCodeParser.parse(chunk.name)
        if (parsed != null) {
            registerWork(WorkCandidate(chunk.name, parsed.canonical, chunk.name), callback)
        } else {
            scanDir(FsPath(listOf(chunk.documentId)), chunk.name, callback)
        }
    }

    private suspend fun scanDir(
        path: FsPath,
        relativeDir: String,
        callback: ScanCallback,
    ) {
        checkActive(callback)
        callback.onProgress(relativeDir, worksFound)
        val children = try {
            fs.listChildren(path)
        } catch (e: DocumentReadException) {
            callback.onWarning("unreadable directory '$relativeDir': ${e.message}")
            return
        }
        for (entry in children.sortedWith(entryComparator)) {
            if (!entry.isDirectory) continue
            val parsed = RjCodeParser.parse(entry.name)
            if (parsed != null) {
                registerWork(
                    WorkCandidate(joinPath(relativeDir, entry.name), parsed.canonical, entry.name),
                    callback,
                )
                // Deliberately NOT recursing: everything inside a matched work
                // folder belongs to that work (a second RJ code inside is
                // ignored — locked by the nested-RJ test).
            } else {
                scanDir(path + entry.documentId, joinPath(relativeDir, entry.name), callback)
            }
        }
    }

    private suspend fun registerWork(work: WorkCandidate, callback: ScanCallback) {
        _worksFound += 1
        callback.onProgress(work.relativeDir, _worksFound)
        callback.onWorkFound(work)
    }

    private fun checkActive(callback: ScanCallback) {
        if (!callback.isActive()) throw ScanAbortedException("scan cancelled")
    }

    private fun joinPath(parent: String, name: String): String =
        if (parent.isEmpty()) name else "$parent/$name"

    private companion object {
        val entryComparator = Comparator<FsEntry> { a, b -> NaturalOrderComparator.compare(a.name, b.name) }
    }
}
