package com.oneasmr.app.data.scanner

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lifecycle of a library scan run, exposed to the UI (Task 8 consumes it). */
enum class ScanPhase { IDLE, SCANNING, DONE }

/**
 * Snapshot of scan progress (plan Task 6: "扫描进度以 StateFlow 暴露（当前目录/
 * 已发现作品数）；可取消"). [currentDir] is relative to the root currently being
 * scanned; [worksFound] accumulates across chunks/roots within one run.
 */
data class ScanProgress(
    val phase: ScanPhase,
    /** Number of roots enqueued in this run. */
    val rootsTotal: Int,
    /** Display name of the root currently being scanned. */
    val rootDisplayName: String?,
    /** Current directory relative to the root ("" when idle). */
    val currentDir: String,
    /** Works found so far in this run. */
    val worksFound: Int,
    /** Non-fatal warnings so far (unreadable dirs skipped). */
    val warningCount: Int,
) {
    companion object {
        val IDLE = ScanProgress(
            phase = ScanPhase.IDLE,
            rootsTotal = 0,
            rootDisplayName = null,
            currentDir = "",
            worksFound = 0,
            warningCount = 0,
        )
    }
}

/**
 * Live scan progress bus.
 *
 * Deliberately a plain object (not Hilt): the WorkManager worker cannot use
 * @HiltWorker (the hilt-work artifact is NOT in the locked version catalog,
 * and the catalog cannot be modified this task), so the worker updates this
 * singleton directly and the UI reads [state]. One scan chain runs at a time
 * (the scheduler enqueues with REPLACE, the worker chains chunks with
 * APPEND_OR_REPLACE), so a single global state is unambiguous.
 *
 * Progress is process-lifetime only: a killed process restarts from the
 * WORKER CHECKPOINT (inputData), not from this state.
 */
object ScanProgressStore {
    private val _state = MutableStateFlow(ScanProgress.IDLE)
    val state: StateFlow<ScanProgress> = _state.asStateFlow()

    fun begin(rootsTotal: Int) {
        _state.value = ScanProgress.IDLE.copy(phase = ScanPhase.SCANNING, rootsTotal = rootsTotal)
    }

    fun update(rootDisplayName: String, currentDir: String, worksFound: Int) {
        _state.value = _state.value.copy(
            phase = ScanPhase.SCANNING,
            rootDisplayName = rootDisplayName,
            currentDir = currentDir,
            worksFound = worksFound,
        )
    }

    /** Records a non-fatal warning (unreadable directory skipped). */
    fun warn(message: String) {
        _state.value = _state.value.copy(warningCount = _state.value.warningCount + 1)
    }

    fun finish(worksFound: Int) {
        _state.value = _state.value.copy(phase = ScanPhase.DONE, worksFound = worksFound, currentDir = "")
    }

    fun reset() {
        _state.value = ScanProgress.IDLE
    }
}
