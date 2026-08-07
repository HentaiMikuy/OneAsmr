package com.oneasmr.app.data.scanner

import androidx.room.Room
import com.oneasmr.app.data.local.OneAsmrDatabase
import com.oneasmr.app.data.local.PlaybackState
import com.oneasmr.app.data.local.ProgressState
import com.oneasmr.app.data.local.Review
import com.oneasmr.app.data.local.ScrapeStatus
import com.oneasmr.app.data.local.Tag
import com.oneasmr.app.data.local.WorkTag
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * End-to-end incremental rescan + missing-work detection (plan Task 7) over a
 * fake filesystem + in-memory Room: rename→relativeDir update, delete→missing
 * mark with review/playback retained, new→NOT_SCRAPED insert, IO failure→
 * existing data intact + failure surfaced, per-work refresh, manual remove.
 *
 * A fresh fake fs per test = a fresh filesystem (stale_state hygiene — never
 * share state across tests). Deterministic: no real-time waits, no scopes
 * (repo convention after the Task 6 flake fix).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncrementalRescannerTest {

    private lateinit var db: OneAsmrDatabase
    private lateinit var persister: RoomScanPersister
    private val fsByUri = mutableMapOf<String, FakeDocumentFs>()
    private val warnings = mutableListOf<String>()
    private lateinit var rescanner: IncrementalRescanner

    private val rootA = "content://tree/rootA"
    private val rootB = "content://tree/rootB"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            OneAsmrDatabase::class.java,
        ).build()
        persister = RoomScanPersister(db)
        warnings.clear()
        fsByUri.clear()
        rescanner = IncrementalRescanner(
            fsFactory = { uri -> fsByUri.getValue(uri) },
            persister = persister,
            workDao = db.workDao(),
            onWarning = { warnings += it },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------- fixtures ----------

    private fun commit(rjCode: String, relativeDir: String, rootUri: String, now: Long = 1_000L) {
        runBlocking {
            persister.commitWork(WorkCandidate(relativeDir, rjCode, rjCode), rootUri, now)
        }
    }

    private suspend fun markMissing(id: String) {
        val row = db.workDao().getById(id)!!
        db.workDao().upsert(row.copy(missing = true))
    }

    /** AudioBooks/RJ123456 + AudioBooks/RJ200000 + BJ012345 + a top-level file. */
    private fun standardRoot(): FakeDocumentFs = FakeDocumentFs().apply {
        val audioBooks = addDirectory(emptyList(), "AudioBooks")
        addDirectory(audioBooks, "RJ123456")
        addDirectory(audioBooks, "RJ200000")
        addDirectory(emptyList(), "BJ012345")
        addFile(emptyList(), "readme.txt")
    }

    private fun rescan(roots: List<ScanRoot>, now: Long = 2_000L): RescanOutcome =
        runBlocking { rescanner.rescan(roots, now) }

    // ---------- new / unchanged / no-duplicates ----------

    @Test
    fun `first scan inserts every work as NOT_SCRAPED with location`() = runBlocking {
        fsByUri[rootA] = standardRoot()

        val outcome = rescan(listOf(ScanRoot(rootA, "RootA")))

        assertTrue(outcome is RescanOutcome.Completed)
        val summary = (outcome as RescanOutcome.Completed).summary
        assertEquals(3, summary.added)
        assertEquals(0, summary.updated)
        assertEquals(0, summary.missing)
        assertEquals(0, summary.unchanged)
        assertTrue(summary.warnings.isEmpty())

        val w1 = db.workDao().getById("local:RJ123456")!!
        assertEquals("AudioBooks/RJ123456", w1.relativeDir)
        assertEquals(ScrapeStatus.NOT_SCRAPED, w1.scrapeStatus)
        assertFalse(w1.missing)
        assertEquals("local:RJ200000", db.workDao().getById("local:RJ200000")!!.id)
        assertEquals("BJ012345", db.workDao().getById("local:BJ012345")!!.title)
        assertEquals(3, db.workDao().count())
    }

    @Test
    fun `rescanning an identical tree reports unchanged and never duplicates`() = runBlocking {
        fsByUri[rootA] = standardRoot()
        rescan(listOf(ScanRoot(rootA, "RootA")))
        val before = db.workDao().getAll().associateBy { it.id }

        val summary = (rescan(listOf(ScanRoot(rootA, "RootA")), now = 3_000L) as RescanOutcome.Completed).summary

        assertEquals(0, summary.added)
        assertEquals(0, summary.updated)
        assertEquals(0, summary.missing)
        assertEquals(3, summary.unchanged)
        assertEquals(3, db.workDao().count())
        // Row-level: nothing was rewritten, updatedAt untouched by the no-op rescan.
        db.workDao().getAll().forEach { assertEquals(before.getValue(it.id).updatedAt, it.updatedAt) }
    }

    // ---------- rename -> relativeDir update ----------

    @Test
    fun `renamed folder updates relativeDir and clears missing`() = runBlocking {
        commit("RJ123456", "AudioBooks/RJ123456", rootA)
        commit("RJ200000", "AudioBooks/RJ200000", rootA)
        commit("BJ012345", "BJ012345", rootA)
        markMissing("local:RJ123456")

        val moved = FakeDocumentFs().apply {
            val movedDir = addDirectory(emptyList(), "Moved")
            addDirectory(movedDir, "RJ123456")
            val audioBooks = addDirectory(emptyList(), "AudioBooks")
            addDirectory(audioBooks, "RJ200000")
            addDirectory(emptyList(), "BJ012345")
        }
        fsByUri[rootA] = moved

        val summary = (rescan(listOf(ScanRoot(rootA, "RootA"))) as RescanOutcome.Completed).summary

        assertEquals(1, summary.updated)
        assertEquals(0, summary.added)
        assertEquals(0, summary.missing)
        assertEquals(2, summary.unchanged)

        val row = db.workDao().getById("local:RJ123456")!!
        assertEquals("Moved/RJ123456", row.relativeDir)
        assertFalse(row.missing)
    }

    // ---------- delete -> missing mark, review/playback retained ----------

    @Test
    fun `deleted folder marks missing and keeps review and playback rows`() = runBlocking {
        commit("RJ123456", "AudioBooks/RJ123456", rootA)
        commit("RJ200000", "AudioBooks/RJ200000", rootA)
        commit("BJ012345", "BJ012345", rootA)
        db.reviewDao().upsert(Review("local:RJ123456", rating = 5, reviewText = "keep me", progress = ProgressState.listened, updatedAt = 1L))
        db.playbackStateDao().upsert(PlaybackState("local:RJ123456:3", positionMs = 123, durationMs = 456, updatedAt = 1L))

        val without123456 = FakeDocumentFs().apply {
            val audioBooks = addDirectory(emptyList(), "AudioBooks")
            addDirectory(audioBooks, "RJ200000")
            addDirectory(emptyList(), "BJ012345")
        }
        fsByUri[rootA] = without123456

        val outcome = rescan(listOf(ScanRoot(rootA, "RootA")))

        assertTrue(outcome is RescanOutcome.Completed)
        val summary = (outcome as RescanOutcome.Completed).summary
        assertEquals(1, summary.missing)
        assertEquals(0, summary.added)
        assertEquals(2, summary.unchanged)

        val row = db.workDao().getById("local:RJ123456")!!
        assertTrue(row.missing)
        // Review + playback survive the missing mark; nothing was deleted.
        assertEquals(5, db.reviewDao().getByWorkId("local:RJ123456")!!.rating)
        assertEquals(123, db.playbackStateDao().get("local:RJ123456:3")!!.positionMs)
        assertEquals(3, db.workDao().count())
        assertFalse(db.workDao().getById("local:RJ200000")!!.missing)
    }

    @Test
    fun `rescanning again does not re-mark or double-count already missing works`() = runBlocking {
        commit("RJ123456", "AudioBooks/RJ123456", rootA)
        val without123456 = FakeDocumentFs().apply {
            addDirectory(emptyList(), "AudioBooks")
        }
        fsByUri[rootA] = without123456
        rescan(listOf(ScanRoot(rootA, "RootA")))
        assertTrue(db.workDao().getById("local:RJ123456")!!.missing)

        val summary = (rescan(listOf(ScanRoot(rootA, "RootA")), now = 3_000L) as RescanOutcome.Completed).summary

        assertEquals(0, summary.missing)
        assertEquals(0, summary.added)
        assertEquals(0, summary.unchanged) // nothing present to be unchanged
        assertTrue(db.workDao().getById("local:RJ123456")!!.missing)
        assertEquals(1, db.workDao().count())
    }

    // ---------- new folder -> NOT_SCRAPED insert ----------

    @Test
    fun `new folder on rescan is inserted as NOT_SCRAPED`() = runBlocking {
        commit("RJ200000", "AudioBooks/RJ200000", rootA)
        commit("BJ012345", "BJ012345", rootA)

        fsByUri[rootA] = standardRoot() // RJ123456 appears now

        val summary = (rescan(listOf(ScanRoot(rootA, "RootA"))) as RescanOutcome.Completed).summary

        assertEquals(1, summary.added)
        assertEquals(0, summary.updated)
        assertEquals(0, summary.missing)
        assertEquals(2, summary.unchanged)

        val row = db.workDao().getById("local:RJ123456")!!
        assertEquals("AudioBooks/RJ123456", row.relativeDir)
        assertEquals(ScrapeStatus.NOT_SCRAPED, row.scrapeStatus)
        assertFalse(row.missing)
    }

    // ---------- IO failure paths: existing data intact, failure surfaced ----------

    @Test
    fun `unreadable root keeps its works intact and surfaces the failure`() = runBlocking {
        commit("RJ123456", "AudioBooks/RJ123456", rootA)
        commit("RJ200000", "AudioBooks/RJ200000", rootA)
        commit("BJ012345", "BJ012345", rootA)
        commit("RJ999999", "Sub/RJ999999", rootB)
        // A work under rootA that is absent from this run's rootA tree: rootA
        // IS fully enumerated, so it must be marked missing.
        commit("RJ777777", "Gone/RJ777777", rootA)

        fsByUri[rootA] = standardRoot()
        fsByUri[rootB] = FakeDocumentFs().apply { markUnreadable(emptyList()) }

        val outcome = rescan(listOf(ScanRoot(rootA, "RootA"), ScanRoot(rootB, "RootB")))

        assertTrue(outcome is RescanOutcome.Incomplete)
        val summary = (outcome as RescanOutcome.Incomplete).summary
        assertTrue(summary.warnings.any { it.contains("RootB") })
        assertEquals(1, summary.missing) // only rootA's Gone/RJ777777
        assertEquals(0, summary.added)

        // rootB's work is completely untouched: no missing mark, same location.
        val b = db.workDao().getById("local:RJ999999")!!
        assertFalse(b.missing)
        assertEquals("Sub/RJ999999", b.relativeDir)
        // rootA's disappeared work IS marked (rootA enumerated fine).
        assertTrue(db.workDao().getById("local:RJ777777")!!.missing)
        assertEquals(5, db.workDao().count())
    }

    @Test
    fun `unreadable subdirectory excludes that root from missing marking`() = runBlocking {
        commit("RJ123456", "Locked/RJ123456", rootA)
        commit("RJ200000", "AudioBooks/RJ200000", rootA)
        commit("BJ012345", "BJ012345", rootA)

        val withLocked = FakeDocumentFs().apply {
            addDirectory(emptyList(), "Locked") // listed but unreadable below
            markUnreadable(listOf("Locked"))
            val audioBooks = addDirectory(emptyList(), "AudioBooks")
            addDirectory(audioBooks, "RJ200000")
            addDirectory(emptyList(), "BJ012345")
        }
        fsByUri[rootA] = withLocked

        val outcome = rescan(listOf(ScanRoot(rootA, "RootA")))

        assertTrue(outcome is RescanOutcome.Incomplete)
        assertTrue((outcome as RescanOutcome.Incomplete).summary.warnings.any { it.contains("Locked") })
        // The work under the unreadable dir may still exist — never marked missing.
        val row = db.workDao().getById("local:RJ123456")!!
        assertFalse(row.missing)
        assertEquals("Locked/RJ123456", row.relativeDir)
        assertEquals(0, outcome.summary.missing)
        assertFalse(db.workDao().getById("local:RJ200000")!!.missing)
    }

    @Test
    fun `cancel before the run starts aborts with committed works intact`() = runBlocking {
        commit("RJ123456", "AudioBooks/RJ123456", rootA)
        fsByUri[rootA] = standardRoot()
        val cancelled = IncrementalRescanner(
            fsFactory = { uri -> fsByUri.getValue(uri) },
            persister = persister,
            workDao = db.workDao(),
            isActive = { false },
        )

        try {
            cancelled.rescan(listOf(ScanRoot(rootA, "RootA")), 2_000L)
            fail("expected ScanAbortedException")
        } catch (e: ScanAbortedException) {
            // expected
        }
        assertEquals(1, db.workDao().count())
        assertFalse(db.workDao().getById("local:RJ123456")!!.missing)
    }

    // ---------- per-work refresh ----------

    @Test
    fun `refresh finds a moved work and clears missing`() = runBlocking {
        commit("RJ123456", "AudioBooks/RJ123456", rootA)
        markMissing("local:RJ123456")
        val moved = FakeDocumentFs().apply {
            val movedDir = addDirectory(emptyList(), "Moved")
            addDirectory(movedDir, "RJ123456")
        }
        fsByUri[rootA] = moved

        val result = rescanner.refreshWork("RJ123456", listOf(ScanRoot(rootA, "RootA")), 2_000L)

        assertEquals(RefreshResult.Refreshed, result)
        val row = db.workDao().getById("local:RJ123456")!!
        assertEquals("Moved/RJ123456", row.relativeDir)
        assertFalse(row.missing)
    }

    @Test
    fun `refresh of an unchanged work reports Unchanged and rewrites nothing`() = runBlocking {
        commit("RJ123456", "AudioBooks/RJ123456", rootA)
        fsByUri[rootA] = standardRoot()

        val result = rescanner.refreshWork("RJ123456", listOf(ScanRoot(rootA, "RootA")), 2_000L)

        assertEquals(RefreshResult.Unchanged, result)
        assertEquals(1_000L, db.workDao().getById("local:RJ123456")!!.updatedAt)
    }

    @Test
    fun `refresh marks the work missing when it is gone everywhere`() = runBlocking {
        commit("RJ123456", "AudioBooks/RJ123456", rootA)
        fsByUri[rootA] = FakeDocumentFs().apply {
            addDirectory(emptyList(), "AudioBooks") // empty
        }

        val result = rescanner.refreshWork("RJ123456", listOf(ScanRoot(rootA, "RootA")), 2_000L)

        assertEquals(RefreshResult.MarkedMissing, result)
        assertTrue(db.workDao().getById("local:RJ123456")!!.missing)
    }

    @Test
    fun `refresh fails on an unreadable root and does not touch the work`() = runBlocking {
        commit("RJ123456", "AudioBooks/RJ123456", rootA)
        fsByUri[rootB] = FakeDocumentFs().apply { markUnreadable(emptyList()) }
        fsByUri[rootA] = standardRoot()

        // The unreadable root comes FIRST: the work may live there, so the
        // refresh must fail conservatively instead of declaring it missing.
        val result = runBlocking {
            rescanner.refreshWork("RJ123456", listOf(ScanRoot(rootB, "RootB"), ScanRoot(rootA, "RootA")), 2_000L)
        }

        assertTrue(result is RefreshResult.Failed)
        val row = db.workDao().getById("local:RJ123456")!!
        assertFalse(row.missing)
        assertEquals("AudioBooks/RJ123456", row.relativeDir)
        assertEquals(1_000L, row.updatedAt)
    }

    @Test
    fun `refresh of a work not in the library fails`() = runBlocking {
        val result = rescanner.refreshWork("RJ999999", emptyList(), 2_000L)
        assertTrue(result is RefreshResult.Failed)
        assertNull(db.workDao().getById("local:RJ999999"))
    }

    // ---------- manual remove (Task 8 API) ----------

    @Test
    fun `removeWork deletes the work its review and playback in one transaction`() = runBlocking {
        commit("RJ123456", "AudioBooks/RJ123456", rootA)
        commit("RJ200000", "AudioBooks/RJ200000", rootA)
        db.reviewDao().upsert(Review("local:RJ123456", rating = 4, reviewText = "bye", progress = ProgressState.marked, updatedAt = 1L))
        db.playbackStateDao().upsert(PlaybackState("local:RJ123456:1", positionMs = 1, durationMs = 2, updatedAt = 1L))
        db.playbackStateDao().upsert(PlaybackState("local:RJ123456:3", positionMs = 3, durationMs = 4, updatedAt = 1L))
        db.tagDao().upsertAll(listOf(Tag("asmr", "asmr")))
        db.workTagDao().insertAll(listOf(WorkTag("local:RJ123456", "asmr")))

        removeWork(db, "local:RJ123456")

        assertNull(db.workDao().getById("local:RJ123456"))
        assertNull(db.reviewDao().getByWorkId("local:RJ123456"))
        assertNull(db.playbackStateDao().get("local:RJ123456:1"))
        assertNull(db.playbackStateDao().get("local:RJ123456:3"))
        assertTrue(db.workTagDao().getWorkIdsByTag("asmr").isEmpty()) // FK cascade
        assertNotNull(db.workDao().getById("local:RJ200000")) // sibling untouched
    }
}
