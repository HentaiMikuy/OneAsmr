package com.oneasmr.app.worker

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.oneasmr.app.data.repository.RootGrantStatus
import com.oneasmr.app.data.repository.ScanRootRepository
import com.oneasmr.app.data.scanner.ScanPhase
import com.oneasmr.app.data.scanner.ScanProgressStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * UI-facing scan control surface (Task 8): start/cancel library scans.
 * Implemented by [ScanScheduler]; ViewModels depend on the interface so unit
 * tests inject a fake instead of a WorkManager-backed instance.
 */
interface ScanController {
    /** @return number of roots enqueued (0 = nothing to scan). */
    fun startScan(): Int

    /** Cancels the running scan chain (committed works remain in the database). */
    fun cancelScan()
}

/**
 * Starts/cancels library scans (plan Task 6; Task 8's scan UI is the caller).
 *
 * A fresh scan enqueues ONE [ScanLibraryWorker] with the authorized roots in
 * its inputData and REPLACE policy (kills any previous chain); the worker
 * chains its own continuation chunks with APPEND_OR_REPLACE. Cancellation
 * cancels the whole unique chain: committed works stay, no half rows.
 */
@Singleton
class ScanScheduler @Inject constructor(
    private val scanRootRepository: ScanRootRepository,
    @ApplicationContext private val context: Context,
) : ScanController {

    /**
     * Enqueues a fresh full scan of all currently-authorized roots.
     * @return number of roots enqueued (0 = nothing to scan).
     */
    override fun startScan(): Int {
        val roots = scanRootRepository.entries.value
            .filter { it.status == RootGrantStatus.AUTHORIZED }
            .map { RootRef(it.root.treeUri, it.root.displayName, it.root.kind) }
        if (roots.isEmpty()) {
            Log.w(TAG, "startScan: no authorized roots")
            return 0
        }
        val request = OneTimeWorkRequestBuilder<ScanLibraryWorker>()
            .setInputData(workDataOf(ScanLibraryWorker.KEY_ROOTS to json.encodeToString(roots)))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ScanLibraryWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        Log.i(TAG, "scan enqueued for ${roots.size} roots: ${roots.map { it.displayName }}")
        return roots.size
    }

    /** Cancels the running scan chain (committed works remain in the database). */
    override fun cancelScan() {
        WorkManager.getInstance(context).cancelUniqueWork(ScanLibraryWorker.UNIQUE_WORK_NAME)
        ScanProgressStore.reset()
        Log.i(TAG, "scan cancelled")
    }

    /** Live scan state for the UI. */
    fun isScanning(): Boolean = ScanProgressStore.state.value.phase == ScanPhase.SCANNING

    companion object {
        private const val TAG = "OneAsmrScanScheduler"
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** Hilt binding: ViewModels consume [ScanController], the implementation is [ScanScheduler]. */
@Module
@InstallIn(SingletonComponent::class)
object ScanControllerModule {
    @Provides
    @Singleton
    fun provideScanController(scheduler: ScanScheduler): ScanController = scheduler
}
