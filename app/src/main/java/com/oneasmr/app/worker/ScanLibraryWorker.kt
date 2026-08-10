package com.oneasmr.app.worker

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.oneasmr.app.data.local.KeySpec
import com.oneasmr.app.data.local.OneAsmrDatabase
import com.oneasmr.app.data.repository.ScanRootKind
import com.oneasmr.app.data.scanner.AndroidSingleFileProbe
import com.oneasmr.app.data.scanner.CommitKind
import com.oneasmr.app.data.scanner.DocumentReadException
import com.oneasmr.app.data.scanner.LibraryScanner
import com.oneasmr.app.data.scanner.RescanDiffComputer
import com.oneasmr.app.data.scanner.RescanSummary
import com.oneasmr.app.data.scanner.RoomScanPersister
import com.oneasmr.app.data.scanner.RoomSingleFilePersister
import com.oneasmr.app.data.scanner.SafDocumentFs
import com.oneasmr.app.data.scanner.ScanAbortedException
import com.oneasmr.app.data.scanner.ScanBookkeepingStore
import com.oneasmr.app.data.scanner.ScanCallback
import com.oneasmr.app.data.scanner.ScanProgressStore
import com.oneasmr.app.data.scanner.ScanRunState
import com.oneasmr.app.data.scanner.SingleFileCandidate
import com.oneasmr.app.data.scanner.SingleFileRescanDiff
import com.oneasmr.app.data.scanner.SingleFileScanner
import com.oneasmr.app.data.scanner.SingleThumbStore
import com.oneasmr.app.data.scanner.StoredWorkRef
import com.oneasmr.app.data.scanner.WorkCandidate
import java.io.File
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A scan root as carried in the worker input: tree URI + display name for
 * progress. [kind] 默认 WORKS,老 checkpoint 链里的 JSON 缺字段也能解码。
 */
@Serializable
data class RootRef(
    val treeUri: String,
    val displayName: String,
    val kind: ScanRootKind = ScanRootKind.WORKS,
)

/**
 * Resumption cursor persisted in the WORKER INPUT of the next chunk:
 * `(rootIndex, chunkDocumentId)` = "continue inside root `rootIndex`, after
 * the top-level entry whose document id is `chunkDocumentId`". Durable in
 * WorkManager storage, so a killed process resumes where it stopped.
 * [runId] stamps the whole chunk chain (first execution generates it, the
 * final execution matches its accumulated [ScanRunState] against it) — a
 * stale run state left by a crashed run is never trusted by a new run.
 */
@Serializable
data class ScanCheckpoint(val rootIndex: Int, val chunkDocumentId: String?, val runId: Long? = null)

/**
 * Hilt entry point for the worker. The worker CANNOT use @HiltWorker: that
 * requires the androidx.hilt:hilt-work artifact, and the version catalog is
 * locked for this task (no new dependencies, no catalog edits). The standard
 * workaround is [EntryPointAccessors] — full access to the app's singleton
 * graph (database, bookkeeping) without a new dependency.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ScanWorkerEntryPoint {
    fun database(): OneAsmrDatabase

    fun bookkeeping(): ScanBookkeepingStore
}

/**
 * Chunked, resumable library scan worker (plan Task 6).
 *
 * LONG-TASK DESIGN CHOICE — CHUNKED/RESUMABLE (documented per plan):
 * WorkManager kills regular workers at the ~10-minute execution cap, so a
 * full-tree scan must not be one execution. This worker processes ONE
 * GRANULARITY UNIT per execution — one root at a time, one top-level
 * subdirectory ("chunk") at a time — and:
 * - persists a [ScanCheckpoint] into the NEXT chunk's inputData
 *   (beginUniqueWork + APPEND_OR_REPLACE self-enqueue), so progress survives
 *   the 10-min cap AND process death;
 * - re-checks a time budget ([CHUNK_TIME_BUDGET_MS], below the cap) after
 *   every chunk and enqueues the continuation when exhausted;
 * - commits every work in its own Room transaction (see
 *   [RoomScanPersister.commitWork]), so a cancelled/interrupted scan keeps
 *   exactly the already-committed works — never half rows; the re-run of an
 *   interrupted chunk is safe because commits are idempotent upserts.
 *
 * The alternative — setForeground foreground work — was REJECTED: it requires
 * the FOREGROUND_SERVICE_DATA_SYNC permission and a manifest
 * `foregroundServiceType="dataSync"` for targetSdk 34+, and the manifest is
 * out of scope for this task (no manifest edits), which would make
 * foreground work crash on API 34+.
 *
 * Cancellation (WorkManager.cancelUniqueWork) stops the worker via
 * [CoroutineWorker.isStopped]; the scanner core checks it at every directory
 * boundary and aborts with [ScanAbortedException]. Committed works remain.
 * NOTE: WorkManager 2.11 removed Result.cancelled(); the result of a stopped
 * worker is discarded anyway, so cancellation paths return Result.failure().
 */
class ScanLibraryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val roots = decodeRoots(inputData.getString(KEY_ROOTS))
        if (roots == null) {
            Log.e(TAG, "missing/invalid roots input")
            return@withContext Result.failure()
        }
        if (roots.isEmpty()) {
            Log.w(TAG, "no roots to scan")
            return@withContext Result.success()
        }
        var checkpoint = decodeCheckpoint(inputData.getString(KEY_CHECKPOINT))

        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, ScanWorkerEntryPoint::class.java)
        val db = entryPoint.database()
        val bookkeeping = entryPoint.bookkeeping()

        ScanProgressStore.begin(roots.size)
        val runId = checkpoint?.runId ?: System.currentTimeMillis()
        // Task 7: cross-execution diff state. Trust the persisted state only
        // when its runId matches THIS run's chain; anything else is a stale
        // leftover of a crashed run and is replaced by a fresh state.
        var runState = if (checkpoint?.runId != null) {
            bookkeeping.getRunState()?.takeIf { it.runId == runId } ?: ScanRunState(runId)
        } else {
            ScanRunState(runId)
        }
        var rootIndex = checkpoint?.rootIndex ?: 0
        var completedWorks = 0
        var warnings = 0
        val warningMessages = mutableListOf<String>()
        val budgetDeadline = SystemClock.elapsedRealtime() + CHUNK_TIME_BUDGET_MS

        try {
            while (rootIndex < roots.size && !isStopped) {
                val root = roots[rootIndex]
                // 恢复游标指向本根的某个已处理 chunk 时,说明单文件遍
                // (若有)在上一次执行里已完成 —— 只有全新进入根才跑。
                val resumingMidRoot =
                    checkpoint?.rootIndex == rootIndex && checkpoint?.chunkDocumentId != null
                if (root.kind != ScanRootKind.WORKS && !resumingMidRoot) {
                    // 单文件遍(SINGLE_FILES / MIXED):整根为一个粒度单元
                    // (下载目录的量级远小于作品库树);提交是幂等 upsert,
                    // 重跑半途根安全。RJ 作品文件夹由扫描器整棵跳过。
                    val result = scanSingleFileRoot(db, root) { current, found ->
                        ScanProgressStore.update(root.displayName, current, completedWorks + found)
                    }
                    warnings += result.warnings.size
                    warningMessages += result.warnings
                    if (result.warnings.isNotEmpty()) {
                        runState = runState.copy(
                            failedRootUris = (runState.failedRootUris + root.treeUri).distinct(),
                        )
                    }
                    runState = runState.copy(
                        singleDiscoveredIds = (runState.singleDiscoveredIds + result.discoveredIds).distinct(),
                        added = runState.added + result.added,
                        updated = runState.updated + result.updated,
                        unchanged = runState.unchanged + result.unchanged,
                    )
                    bookkeeping.setRunState(runState)
                    completedWorks += result.discoveredIds.size
                }
                if (root.kind == ScanRootKind.SINGLE_FILES) {
                    // 纯单文件根到此完成;混合根继续落入下方作品分块遍
                    // (不在两遍之间做预算检查:作品遍的每个 chunk 自带
                    // 检查点,能正确恢复且恢复时跳过单文件遍)。
                    rootIndex++
                    checkpoint = null
                    if (rootIndex < roots.size && SystemClock.elapsedRealtime() > budgetDeadline) {
                        enqueueContinuation(roots, rootIndex, chunkDocumentId = null, runId = runId)
                        return@withContext Result.success()
                    }
                    continue
                }
                val fs = SafDocumentFs(applicationContext, root.treeUri)
                val scanner = LibraryScanner(fs)
                val persister = RoomScanPersister(db)

                val topLevel = try {
                    scanner.topLevelChunks()
                } catch (e: DocumentReadException) {
                    // Root unreadable (grant revoked mid-scan): warn and skip it.
                    // Its works are excluded from missing-marking (folder set
                    // unknown — never mark them missing on an IO failure).
                    Log.w(TAG, "skipping unreadable root '${root.displayName}': ${e.message}")
                    warnings += 1
                    warningMessages += "root '${root.displayName}' unreadable: ${e.message}"
                    runState = runState.copy(failedRootUris = (runState.failedRootUris + root.treeUri).distinct())
                    bookkeeping.setRunState(runState)
                    rootIndex++
                    continue
                }

                // Resume cursor: skip entries up to and including the
                // checkpointed document id, then process the rest.
                val resumeId = checkpoint?.takeIf { it.rootIndex == rootIndex }?.chunkDocumentId
                var skipping = resumeId != null
                var lastProcessedChunkId: String? = null

                for (chunk in topLevel) {
                    if (skipping) {
                        if (chunk.documentId == resumeId) skipping = false
                        continue
                    }
                    if (isStopped) {
                        ScanProgressStore.reset()
                        return@withContext Result.failure()
                    }
                    var chunkDiscovered = mutableListOf<String>()
                    var chunkAdded = 0
                    var chunkUpdated = 0
                    var chunkUnchanged = 0
                    scanner.scanChunk(
                        chunk,
                        object : ScanCallback {
                            override suspend fun onWorkFound(work: WorkCandidate) {
                                when (persister.commitWork(work, root.treeUri, System.currentTimeMillis())) {
                                    CommitKind.INSERTED -> chunkAdded++
                                    CommitKind.UPDATED -> chunkUpdated++
                                    CommitKind.UNCHANGED -> chunkUnchanged++
                                }
                                chunkDiscovered += KeySpec.workId(KeySpec.LOCAL_SOURCE, work.rjCode)
                            }

                            override fun onProgress(currentDir: String, worksFound: Int) {
                                ScanProgressStore.update(root.displayName, currentDir, completedWorks + worksFound)
                            }

                            override fun onWarning(message: String) {
                                warnings += 1
                                warningMessages += message
                                // This root was not fully enumerated (unreadable
                                // subdirectory): exclude it from missing-marking.
                                runState = runState.copy(failedRootUris = (runState.failedRootUris + root.treeUri).distinct())
                                ScanProgressStore.warn(message)
                                Log.w(TAG, message)
                            }

                            override fun isActive(): Boolean = !isStopped
                        },
                    )
                    // Persist the accumulated diff state after EVERY chunk so
                    // every exit path (budget continuation, process death,
                    // final diff) sees exactly the works committed so far.
                    runState = runState.copy(
                        discoveredIds = (runState.discoveredIds + chunkDiscovered).distinct(),
                        added = runState.added + chunkAdded,
                        updated = runState.updated + chunkUpdated,
                        unchanged = runState.unchanged + chunkUnchanged,
                    )
                    bookkeeping.setRunState(runState)
                    lastProcessedChunkId = chunk.documentId
                    // Budget check AFTER the chunk: the checkpoint cursor is
                    // always a PROCESSED chunk, so no chunk is ever skipped.
                    if (SystemClock.elapsedRealtime() > budgetDeadline) {
                        enqueueContinuation(roots, rootIndex, lastProcessedChunkId!!, runId)
                        return@withContext Result.success()
                    }
                }
                completedWorks += scanner.worksFound
                rootIndex++
                checkpoint = null
            }
        } catch (e: ScanAbortedException) {
            Log.i(TAG, "scan aborted at directory boundary; committed works stay in the db")
            ScanProgressStore.reset()
            return@withContext Result.failure()
        }

        // ---- final execution of the run: Task 7 missing-work diff ----
        // isStopped guard: a scan cancelled after the last chunk must not
        // mark anything missing (the user stopped it on purpose).
        if (isStopped) {
            ScanProgressStore.reset()
            return@withContext Result.failure()
        }
        bookkeeping.setRunState(runState)
        val now = System.currentTimeMillis()
        val completeRoots = roots.map { it.treeUri }.toSet() - runState.failedRootUris.toSet()
        val stored = db.workDao().getAll()
        val toMarkMissing = RescanDiffComputer.computeMissing(
            runState.discoveredSet,
            stored.map { StoredWorkRef(it.id, it.rootFolderUri, it.missing) },
            completeRoots,
        )
        if (toMarkMissing.isNotEmpty()) {
            db.workDao().markMissing(toMarkMissing, now)
        }
        // 单文件差集与作品同规则同保护:根 uri 天然分属两类流水线,
        // completeRoots 共用不串扰。
        val singlesToMark = SingleFileRescanDiff.computeMissing(
            runState.singleDiscoveredSet,
            db.singleFileDao().getAll().map {
                SingleFileRescanDiff.StoredRef(it.id, it.rootFolderUri, it.missing)
            },
            completeRoots,
        )
        if (singlesToMark.isNotEmpty()) {
            db.singleFileDao().markMissing(singlesToMark, now)
        }
        if (runState.failedRootUris.isNotEmpty()) {
            Log.w(TAG, "rescan incomplete: ${runState.failedRootUris.size} root(s) not fully enumerated; " +
                "their works were NOT marked missing (existing data kept)")
        }
        // Task 8 completion summary: the real run counters (RunState) + the
        // missing diff result — never fabricated. Persisted so the UI can show
        // 新增/更新/失效 counts even after a process restart.
        bookkeeping.setLastSummary(
            RescanSummary(
                added = runState.added,
                updated = runState.updated,
                missing = toMarkMissing.size + singlesToMark.size,
                unchanged = runState.unchanged,
                warnings = warningMessages,
            ),
        )
        bookkeeping.setLastScanAt(now)
        bookkeeping.clearRunState()
        ScanProgressStore.finish(completedWorks)
        Log.i(
            TAG,
            "scan complete: roots=${roots.size} works=$completedWorks added=${runState.added} " +
                "updated=${runState.updated} unchanged=${runState.unchanged} " +
                "missing=${toMarkMissing.size}+${singlesToMark.size} " +
                "warnings=$warnings lastScanAt=$now",
        )
        Result.success()
    }

    /** [scanSingleFileRoot] 的根级结果(worker 归并进 RunState)。 */
    private data class SingleRootResult(
        val discoveredIds: List<Long>,
        val added: Int,
        val updated: Int,
        val unchanged: Int,
        val warnings: List<String>,
    )

    /** 扫一个单文件根:发现 -> 逐文件原子提交,聚合行级结果。 */
    private suspend fun scanSingleFileRoot(
        db: OneAsmrDatabase,
        root: RootRef,
        onProgress: (currentDir: String, filesFound: Int) -> Unit,
    ): SingleRootResult {
        val persister = RoomSingleFilePersister(
            db = db,
            probe = AndroidSingleFileProbe(applicationContext),
            thumbStore = SingleThumbStore(File(applicationContext.filesDir, SINGLE_THUMBS_DIR)),
        )
        val discovered = mutableListOf<Long>()
        val warnings = mutableListOf<String>()
        var added = 0
        var updated = 0
        var unchanged = 0
        SingleFileScanner(SafDocumentFs(applicationContext, root.treeUri)).scanRoot(
            object : SingleFileScanner.Callback {
                override suspend fun onFileFound(candidate: SingleFileCandidate) {
                    val commit = persister.commitFile(candidate, root.treeUri, System.currentTimeMillis())
                    when (commit.kind) {
                        CommitKind.INSERTED -> added++
                        CommitKind.UPDATED -> updated++
                        CommitKind.UNCHANGED -> unchanged++
                    }
                    if (commit.fileId > 0) discovered += commit.fileId
                }

                override fun onProgress(currentDir: String, filesFound: Int) {
                    onProgress(currentDir, filesFound)
                }

                override fun onWarning(message: String) {
                    warnings += message
                    ScanProgressStore.warn(message)
                    Log.w(TAG, message)
                }

                override fun isActive(): Boolean = !isStopped
            },
        )
        return SingleRootResult(discovered, added, updated, unchanged, warnings)
    }

    /** Enqueues the next chunk chained after this one (APPEND_OR_REPLACE). */
    private fun enqueueContinuation(roots: List<RootRef>, rootIndex: Int, chunkDocumentId: String?, runId: Long) {
        val checkpoint = ScanCheckpoint(rootIndex, chunkDocumentId, runId)
        val request = OneTimeWorkRequestBuilder<ScanLibraryWorker>()
            .setInputData(workDataOf(KEY_ROOTS to json.encodeToString(roots), KEY_CHECKPOINT to json.encodeToString(checkpoint)))
            .build()
        WorkManager.getInstance(applicationContext)
            .beginUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            .enqueue()
        Log.i(TAG, "continuation enqueued: rootIndex=$rootIndex after chunk=$chunkDocumentId")
    }

    companion object {
        const val TAG = "OneAsmrScanWorker"
        const val UNIQUE_WORK_NAME = "oneasmr-library-scan"
        const val KEY_ROOTS = "roots_json"
        const val KEY_CHECKPOINT = "checkpoint_json"

        /** filesDir 下的单文件缩略图目录([SingleThumbStore] 的存储位)。 */
        const val SINGLE_THUMBS_DIR = "single_thumbs"

        /**
         * Per-execution time budget, safely below WorkManager's ~10-minute
         * execution cap: when exhausted mid-root, the worker stops and chains
         * a continuation carrying the checkpoint.
         */
        const val CHUNK_TIME_BUDGET_MS = 9 * 60 * 1000L

        private val json = Json { ignoreUnknownKeys = true }

        private fun decodeRoots(raw: String?): List<RootRef>? =
            raw?.let { runCatching { json.decodeFromString<List<RootRef>>(it) }.getOrNull() }

        private fun decodeCheckpoint(raw: String?): ScanCheckpoint? =
            raw?.let { runCatching { json.decodeFromString<ScanCheckpoint>(it) }.getOrNull() }
    }
}
