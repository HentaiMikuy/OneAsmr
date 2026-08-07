package com.oneasmr.app.data.scanner

import com.oneasmr.app.data.local.TestDataStoreFile
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * lastScanAt bookkeeping (plan Task 6 "Persist scan bookkeeping ... lastScanAt
 * at least") over a temp-file DataStore. DataStore on the JVM needs a real
 * clock (Task 5 learning: runTest virtual time does not advance on real IO),
 * so these use runBlocking + withTimeout.
 */
class ScanBookkeepingStoreTest {

    private fun tempFile(): File =
        File.createTempFile("scan_bookkeeping_test", ".preferences_pb").apply { deleteOnExit() }

    @Test
    fun `lastScanAt starts null and roundtrips a written value`() = runBlocking {
        val testFile = TestDataStoreFile(tempFile())
        val store = ScanBookkeepingStore(testFile.open())

        withTimeout(5_000) { assertNull(store.lastScanAt.first()) }

        withTimeout(5_000) { store.setLastScanAt(1_712_000_000_000L) }
        withTimeout(5_000) { assertEquals(1_712_000_000_000L, store.lastScanAt.first()) }
    }

    @Test
    fun `lastScanAt survives a store restart (same file, new instance)`() = runBlocking {
        val testFile = TestDataStoreFile(tempFile())
        ScanBookkeepingStore(testFile.open()).let { store ->
            withTimeout(5_000) { store.setLastScanAt(1_722_000_000_000L) }
        }

        // Simulated process restart: cancel the old scope, open the file again.
        val reopened = ScanBookkeepingStore(testFile.restart())
        withTimeout(5_000) { assertEquals(1_722_000_000_000L, reopened.lastScanAt.first()) }
    }

    @Test
    fun `overwriting lastScanAt replaces the previous value`() = runBlocking {
        val testFile = TestDataStoreFile(tempFile())
        val store = ScanBookkeepingStore(testFile.open())

        withTimeout(5_000) { store.setLastScanAt(111L) }
        withTimeout(5_000) { store.setLastScanAt(222L) }
        withTimeout(5_000) { assertEquals(222L, store.lastScanAt.first()) }
    }

    @Test
    fun `two stores on distinct files do not interfere`() = runBlocking {
        val a = ScanBookkeepingStore(TestDataStoreFile(tempFile()).open())
        val b = ScanBookkeepingStore(TestDataStoreFile(tempFile()).open())

        withTimeout(5_000) { a.setLastScanAt(1L) }
        withTimeout(5_000) { assertNull(b.lastScanAt.first()) }
    }
}
