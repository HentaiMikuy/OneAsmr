package com.oneasmr.app.data.scanner

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Scan bookkeeping (plan Task 6/7): per-root checkpoints live in WorkManager
 * inputData (see ScanLibraryWorker), [lastScanAt] — the epoch millis the most
 * recent scan run completed — and the [ScanRunState] of the in-flight run
 * (Task 7's cross-execution discovered-set accumulation) live here.
 *
 * Deliberately DataStore, NOT Room: the Room schema only grows by migration
 * (v1→v2 adds `work.missing`), and rescan bookkeeping must not drag the
 * schema along.
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

    /** Current in-flight run state; null when no run is in progress. */
    val runState: Flow<ScanRunState?> = dataStore.data.map { prefs ->
        prefs[KEY_RUN_STATE]?.let { raw ->
            runCatching { json.decodeFromString<ScanRunState>(raw) }.getOrNull()
        }
    }

    suspend fun getRunState(): ScanRunState? = runState.first()

    suspend fun setRunState(state: ScanRunState) {
        dataStore.edit { it[KEY_RUN_STATE] = json.encodeToString(ScanRunState.serializer(), state) }
    }

    suspend fun clearRunState() {
        dataStore.edit { it.remove(KEY_RUN_STATE) }
    }

    companion object {
        private val KEY_LAST_SCAN_AT = longPreferencesKey("last_scan_at")
        private val KEY_RUN_STATE = stringPreferencesKey("scan_run_state")
        private val json = Json { ignoreUnknownKeys = true }
    }
}
