package com.oneasmr.app.data.scanner

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Scan bookkeeping (plan Task 6): per-root checkpoints live in WorkManager
 * inputData (see ScanLibraryWorker), and [lastScanAt] — the epoch millis the
 * most recent scan run completed — lives here.
 *
 * Deliberately DataStore, NOT Room: the Room schema is locked at version 1
 * (no migration bumps this project), and Task 7's incremental rescan reads
 * this value to decide what changed since the last run.
 */
@Singleton
class ScanBookkeepingStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    /** Last completed scan time; null when no scan ever finished. */
    val lastScanAt: Flow<Long?> = dataStore.data.map { it[KEY_LAST_SCAN_AT] }

    suspend fun setLastScanAt(epochMillis: Long) {
        dataStore.edit { it[KEY_LAST_SCAN_AT] = epochMillis }
    }

    companion object {
        private val KEY_LAST_SCAN_AT = longPreferencesKey("last_scan_at")
    }
}
