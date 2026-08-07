package com.oneasmr.app.data.scanner

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.oneasmr.app.data.local.TestDataStoreFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Task 8 addition to the scan bookkeeping store: the persisted completion
 * summary (lastSummary). Roundtrip + corrupt-JSON-degrades-to-null, matching
 * the runState test conventions (Task 7). DataStore IO is real-time by design
 * (see Task 5 learnings: no virtual-time awaits on DataStore).
 */
class ScanBookkeepingLastSummaryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `lastSummary roundtrips the rescan outcome counters`() {
        val storeFile = TestDataStoreFile(tmp.newFile("bk.preferences_pb"))
        val store = ScanBookkeepingStore(storeFile.open())

        assertNull(runBlocking { store.lastSummary.first() })

        runBlocking {
            store.setLastSummary(RescanSummary(added = 2, updated = 1, missing = 3, unchanged = 9, warnings = listOf("w1")))
        }
        val summary = runBlocking { store.lastSummary.first() }
        assertEquals(2, summary!!.added)
        assertEquals(1, summary.updated)
        assertEquals(3, summary.missing)
        assertEquals(9, summary.unchanged)
        assertEquals(listOf("w1"), summary.warnings)

        // process-restart simulation: reopen the same file (old scope cancelled)
        val restarted = ScanBookkeepingStore(storeFile.restart())
        val afterRestart = runBlocking { restarted.lastSummary.first() }
        assertEquals(2, afterRestart!!.added)
        assertEquals(3, afterRestart.missing)
    }

    @Test
    fun `corrupt lastSummary json degrades to null`() {
        val storeFile = TestDataStoreFile(tmp.newFile("bk2.preferences_pb"))
        val dataStore = storeFile.open()
        val store = ScanBookkeepingStore(dataStore)

        // mirror of ScanBookkeepingStore.KEY_LAST_SUMMARY ("last_scan_summary")
        runBlocking {
            dataStore.edit {
                it[stringPreferencesKey("last_scan_summary")] = "not json {"
            }
        }
        assertNull(runBlocking { store.lastSummary.first() })
    }
}
