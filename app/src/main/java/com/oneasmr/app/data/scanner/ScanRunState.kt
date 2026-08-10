package com.oneasmr.app.data.scanner

import kotlinx.serialization.Serializable

/**
 * Cross-execution state of ONE chunked rescan run (Task 7), persisted by
 * [ScanBookkeepingStore] in DataStore.
 *
 * The chunked worker (ScanLibraryWorker) processes one top-level chunk per
 * WorkManager execution and must know, at the FINAL execution, every work id
 * this run discovered plus which roots were fully enumerated — otherwise the
 * missing-work diff would wrongly mark works under an unreadable root as
 * missing. WorkManager inputData is capped at 10KB, so this accumulates in
 * DataStore instead (large libraries are fine: a few KB per work id).
 *
 * [runId] (epoch millis, stamped by the first execution of the run) guards
 * against a stale state left by a crashed run: the first execution of a new
 * run overwrites the state, and a final execution only trusts state whose
 * runId matches its own checkpoint-carried runId.
 */
@Serializable
data class ScanRunState(
    val runId: Long,
    /** Work ids (KeySpec.workId, e.g. "local:RJ123456") discovered so far. */
    val discoveredIds: List<String> = emptyList(),
    /**
     * Tree uris of roots that were NOT fully enumerated (top-level read failed
     * or an unreadable subdirectory was hit): their works must be excluded
     * from missing-marking — the folder set is unknown, so nothing may be
     * declared missing there.
     */
    val failedRootUris: List<String> = emptyList(),
    /** Row-level outcomes accumulated so far (Task 8 completion summary). */
    val added: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
    /**
     * single_file row ids discovered so far(单文件根流水线;与作品的
     * discoveredIds 平行,分开存因主键类型不同)。默认空:老版本持久化
     * 的运行状态缺该字段也能解码(ignoreUnknownKeys + 默认值)。
     */
    val singleDiscoveredIds: List<Long> = emptyList(),
) {
    val discoveredSet: Set<String> get() = discoveredIds.toSet()

    val singleDiscoveredSet: Set<Long> get() = singleDiscoveredIds.toSet()
}
