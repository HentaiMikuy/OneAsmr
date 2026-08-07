package com.oneasmr.app.data.scanner

import com.oneasmr.app.data.local.KeySpec
import com.oneasmr.app.data.local.OneAsmrDatabase
import com.oneasmr.app.data.local.WorkDao
import kotlinx.serialization.Serializable

/** A configured scan root (SAF tree uri + display name for messages/progress). */
data class ScanRoot(val treeUri: String, val displayName: String)

/**
 * Row-level outcome of one full rescan (plan Task 7; the Task 8 completion
 * summary "新增/更新/失效计数" consumes this). [warnings] surfaces every
 * non-fatal problem (unreadable root/directory) so the caller can present it.
 *
 * @Serializable so the completion summary survives process death: the worker
 * persists it in the scan_bookkeeping DataStore (Task 8) and the UI shows the
 * counts of the most recent scan even after a force-stop.
 */
@Serializable
data class RescanSummary(
    val added: Int,
    val updated: Int,
    val missing: Int,
    val unchanged: Int,
    val warnings: List<String>,
)

/** Result of a full [IncrementalRescanner.rescan] run. */
sealed class RescanOutcome {
    /** Every configured root was fully enumerated; missing-marking is complete. */
    data class Completed(val summary: RescanSummary) : RescanOutcome()

    /**
     * One or more roots could NOT be fully enumerated (unreadable root or
     * unreadable subdirectory). The summary's missing count covers only the
     * fully-enumerated roots — works under an unreadable root are left
     * exactly as they were (never marked missing: their folder set is
     * unknown). Existing data is never wiped on IO failure.
     */
    data class Incomplete(val summary: RescanSummary) : RescanOutcome()
}

/** Result of a single-work refresh ([IncrementalRescanner.refreshWork]). */
sealed class RefreshResult {
    /** Found: location/title refreshed and the missing flag cleared. */
    data object Refreshed : RefreshResult()

    /** Found and nothing changed (location/title identical, already present). */
    data object Unchanged : RefreshResult()

    /** Not found anywhere: the work row is now marked missing. */
    data object MarkedMissing : RefreshResult()

    /** Could not verify (root unreadable, work not in library): nothing touched. */
    data class Failed(val message: String) : RefreshResult()
}

/** Minimal stored-row view the missing diff needs (avoids dragging the full entity). */
data class StoredWorkRef(val id: String, val rootFolderUri: String, val missing: Boolean)

/**
 * Pure diff of one rescan run (Task 7): this run's discovered work ids against
 * the stored rows. Everything here is deterministic and Room/fs-free so the
 * diff rules are unit-testable in plain JUnit.
 */
object RescanDiffComputer {

    /**
     * @param discovered ids (KeySpec.workId) found by this run.
     * @param stored all stored local work rows.
     * @param completeRootUris tree uris of roots that were FULLY enumerated.
     * @return ids to mark missing: stored rows under a complete root that were
     *   NOT discovered this run and are not already marked missing.
     */
    fun computeMissing(
        discovered: Set<String>,
        stored: List<StoredWorkRef>,
        completeRootUris: Set<String>,
    ): List<String> = stored.asSequence()
        .filter { it.rootFolderUri in completeRootUris }
        .filterNot { it.id in discovered }
        .filterNot { it.missing }
        .map { it.id }
        .toList()
}

/**
 * Orchestrates incremental rescans (plan Task 7): discover works per root,
 * commit new/updated works through [ScanPersister] (per-work atomic), then
 * mark disappeared works missing — WITHOUT deleting anything (review and
 * playback_state rows survive by design; the UI greys missing works out and
 * the user removes them manually via [removeWork]).
 *
 * Safety rules (locked by IncrementalRescannerTest):
 * - An unreadable root is skipped with a warning and its works are never
 *   touched; a root that was not fully enumerated (unreadable root OR any
 *   unreadable subdirectory) is excluded from missing-marking entirely.
 * - Cancellation ([isActive]) aborts with [ScanAbortedException]; already
 *   committed works stay, never half rows (Task 6 per-work atomicity).
 * - No auto-deletion of files or rows, ever.
 */
class IncrementalRescanner(
    private val fsFactory: (treeUri: String) -> DocumentFs,
    private val persister: ScanPersister,
    private val workDao: WorkDao,
    private val onWarning: (String) -> Unit = {},
    private val onProgress: (currentDir: String, worksFound: Int) -> Unit = { _, _ -> },
    private val isActive: () -> Boolean = { true },
) {

    /**
     * Full incremental rescan of [roots] (Task 7):
     * 1. Discover works per root with [LibraryScanner]; every candidate is
     *    committed immediately and atomically via [ScanPersister] (insert new /
     *    update location / clear missing — Task 6 merge semantics).
     * 2. Diff the discovered set against the stored rows
     *    ([RescanDiffComputer.computeMissing]) and mark disappeared works
     *    missing — nothing is ever deleted; review / playback_state rows
     *    survive by design.
     *
     * IO-failure safety: an unreadable root is skipped with a warning and its
     * works stay untouched; a root with ANY unreadable subdirectory is
     * excluded from missing-marking too (its folder set is unknown, so
     * nothing under it may be declared missing). The run then completes as
     * [RescanOutcome.Incomplete] — existing data intact, failure surfaced via
     * summary.warnings. Cancellation ([isActive]) throws [ScanAbortedException]
     * at directory boundaries; committed works stay, never half rows.
     */
    suspend fun rescan(roots: List<ScanRoot>, nowEpochMillis: Long): RescanOutcome {
        checkActive()
        if (roots.isEmpty()) {
            return RescanOutcome.Completed(
                RescanSummary(added = 0, updated = 0, missing = 0, unchanged = 0, warnings = emptyList()),
            )
        }
        val discovered = mutableSetOf<String>()
        val failedRootUris = mutableSetOf<String>()
        var added = 0
        var updated = 0
        var unchanged = 0
        val warnings = mutableListOf<String>()
        for (root in roots) {
            checkActive()
            val scanner = LibraryScanner(fsFactory(root.treeUri))
            val topLevel = try {
                scanner.topLevelChunks()
            } catch (e: DocumentReadException) {
                failedRootUris += root.treeUri
                warnings += "root '${root.displayName}' unreadable: ${e.message}"
                onWarning(warnings.last())
                continue
            }
            for (chunk in topLevel) {
                scanner.scanChunk(
                    chunk,
                    object : ScanCallback {
                        override suspend fun onWorkFound(work: WorkCandidate) {
                            discovered += KeySpec.workId(KeySpec.LOCAL_SOURCE, work.rjCode)
                            when (persister.commitWork(work, root.treeUri, nowEpochMillis)) {
                                CommitKind.INSERTED -> added++
                                CommitKind.UPDATED -> updated++
                                CommitKind.UNCHANGED -> unchanged++
                            }
                        }

                        override fun onProgress(currentDir: String, worksFound: Int) {
                            this@IncrementalRescanner.onProgress(currentDir, worksFound)
                        }

                        override fun onWarning(message: String) {
                            failedRootUris += root.treeUri
                            warnings += message
                            this@IncrementalRescanner.onWarning(message)
                        }

                        override fun isActive(): Boolean = this@IncrementalRescanner.isActive()
                    },
                )
            }
        }
        val completeRoots = roots.map { it.treeUri }.toSet() - failedRootUris
        val toMarkMissing = if (completeRoots.isEmpty()) {
            emptyList()
        } else {
            val stored = workDao.getAll()
            RescanDiffComputer.computeMissing(
                discovered,
                stored.map { StoredWorkRef(it.id, it.rootFolderUri, it.missing) },
                completeRoots,
            )
        }
        if (toMarkMissing.isNotEmpty()) {
            workDao.markMissing(toMarkMissing, nowEpochMillis)
        }
        val summary = RescanSummary(added, updated, toMarkMissing.size, unchanged, warnings)
        return if (failedRootUris.isEmpty()) {
            RescanOutcome.Completed(summary)
        } else {
            RescanOutcome.Incomplete(summary)
        }
    }

    /**
     * Single-work re-scan (Task 14 detail-page pull-to-refresh entry point).
     *
     * Scans every root until the work's folder is found (short-circuit at
     * chunk boundaries); then commits it — refreshing relativeDir/title and
     * clearing the missing flag. Not found anywhere after all roots were fully
     * enumerated → the row is marked missing. Conservative failure: any
     * unreadable root/directory makes the refresh fail WITHOUT touching the
     * work (it might live under the unreadable part), and the failure is
     * surfaced.
     */
    suspend fun refreshWork(rjCode: String, roots: List<ScanRoot>, nowEpochMillis: Long): RefreshResult {
        checkActive()
        val id = KeySpec.workId(KeySpec.LOCAL_SOURCE, rjCode)
        if (workDao.getById(id) == null) {
            return RefreshResult.Failed("work '$id' is not in the library")
        }
        for (root in roots) {
            checkActive()
            val scanner = LibraryScanner(fsFactory(root.treeUri))
            val topLevel = try {
                scanner.topLevelChunks()
            } catch (e: DocumentReadException) {
                return RefreshResult.Failed("root '${root.displayName}' unreadable: ${e.message}")
            }
            var found = false
            var commitKind: CommitKind? = null
            var unreadableDir = false
            for (chunk in topLevel) {
                scanner.scanChunk(
                    chunk,
                    object : ScanCallback {
                        override suspend fun onWorkFound(work: WorkCandidate) {
                            if (work.rjCode == rjCode) {
                                commitKind = persister.commitWork(work, root.treeUri, nowEpochMillis)
                                found = true
                            }
                        }

                        override fun onProgress(currentDir: String, worksFound: Int) {
                            this@IncrementalRescanner.onProgress(currentDir, worksFound)
                        }

                        override fun onWarning(message: String) {
                            unreadableDir = true
                            this@IncrementalRescanner.onWarning(message)
                        }

                        override fun isActive(): Boolean = this@IncrementalRescanner.isActive()
                    },
                )
                if (found || unreadableDir) break
            }
            if (found) {
                return if (commitKind == CommitKind.UNCHANGED) RefreshResult.Unchanged else RefreshResult.Refreshed
            }
            if (unreadableDir) {
                return RefreshResult.Failed("cannot verify work '$rjCode': unreadable folder during refresh")
            }
        }
        workDao.markMissing(listOf(id), nowEpochMillis)
        return RefreshResult.MarkedMissing
    }

    private fun checkActive() {
        if (!isActive()) throw ScanAbortedException("scan cancelled")
    }
}

/**
 * Manual removal of a work and its user data (the "user can manually remove"
 * API of plan Task 7, consumed by the Task 8 scan UI / Task 12 greyed-out
 * entries): deletes the work row (work_tag/work_va links cascade via FK),
 * its review and its playback_state entries.
 *
 * No explicit `RoomDatabase.withTransaction` (its framework path throws with
 * BundledSQLiteDriver — see RoomScanPersister); each delete is its own
 * driver-native atomic statement, and the deletes are idempotent so a
 * re-run after an interruption completes the removal.
 *
 * NEVER invoked automatically: missing-marking alone never deletes anything.
 */
suspend fun removeWork(db: OneAsmrDatabase, workId: String) {
    db.workDao().deleteById(workId)
    db.reviewDao().deleteByWorkId(workId)
    db.playbackStateDao().deleteForWorkPrefix("$workId:")
}
