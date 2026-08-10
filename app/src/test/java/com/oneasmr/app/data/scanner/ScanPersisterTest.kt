package com.oneasmr.app.data.scanner

import androidx.room.Room
import com.oneasmr.app.data.local.Circle
import com.oneasmr.app.data.local.OneAsmrDatabase
import com.oneasmr.app.data.local.ScrapeStatus
import com.oneasmr.app.data.local.SortKeyGenerator
import com.oneasmr.app.data.local.Work
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Commit semantics of [RoomScanPersister] on an in-memory Room database:
 * one transaction per work (atomic — a cancelled scan keeps committed works,
 * never half rows), NOT_SCRAPED for new works, metadata preserved on rescan.
 *
 * NOTE (inherited from Task 4): Robolectric's framework SQLite has no FTS5,
 * so the FTS triggers degrade — FTS index maintenance on upsert is verified
 * by the device probe (androidTest/FtsTrigramProbeTest), not here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanPersisterTest {

    private lateinit var db: OneAsmrDatabase
    private lateinit var persister: RoomScanPersister

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            OneAsmrDatabase::class.java,
        ).build()
        persister = RoomScanPersister(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `new work is committed as NOT_SCRAPED with sort key and location`() = runBlocking {
        val work = WorkCandidate(
            relativeDir = "AudioBooks/RJ123456",
            rjCode = "RJ123456",
            displayName = "RJ123456",
        )
        persister.commitWork(work, rootFolderUri = "content://tree/root", nowEpochMillis = 1_000L)

        val row = db.workDao().getById("local:RJ123456")
        assertNotNull(row)
        assertEquals("AudioBooks/RJ123456", row!!.relativeDir)
        assertEquals("content://tree/root", row.rootFolderUri)
        assertEquals("RJ123456", row.title)
        assertEquals("rj123456", row.titleSortKey)
        assertEquals(ScrapeStatus.NOT_SCRAPED, row.scrapeStatus)
        assertEquals(1_000L, row.addedAt)
        assertEquals(1_000L, row.updatedAt)
    }

    @Test
    fun `cjK folder name produces a pinyin sort key`() = runBlocking {
        val work = WorkCandidate(relativeDir = "中文音声/RJ123456", rjCode = "RJ123456", displayName = "中文音声")
        persister.commitWork(work, rootFolderUri = "content://tree/root", nowEpochMillis = 1_000L)

        val row = db.workDao().getById("local:RJ123456")
        assertEquals(SortKeyGenerator.generate("中文音声"), row!!.titleSortKey)
        assertTrue(row.titleSortKey!!.isNotBlank())
    }

    @Test
    fun `rescan refreshes location but preserves scraped title and metadata`() = runBlocking {
        persister.commitWork(
            WorkCandidate(relativeDir = "RJ123456", rjCode = "RJ123456", displayName = "RJ123456"),
            rootFolderUri = "content://tree/root",
            nowEpochMillis = 1_000L,
        )
        // Simulate a scraped row (Task 9 would write this). The circle parent
        // must exist first (Robolectric enforces FKs — Task 4 learning).
        db.circleDao().upsertAll(listOf(Circle(id = "circle-1", name = "circle-1", nameSortKey = "circle-1")))
        val before = db.workDao().getById("local:RJ123456")!!
        db.workDao().upsert(
            before.copy(
                title = "【ASMR】刮削到的真标题",
                titleSortKey = SortKeyGenerator.generate("【ASMR】刮削到的真标题"),
                scrapeStatus = ScrapeStatus.OK,
                circleId = "circle-1",
                rateAverage2dp = 4.52,
                releaseDate = "2024-03-15",
            ),
        )

        persister.commitWork(
            WorkCandidate(relativeDir = "Moved/RJ123456", rjCode = "RJ123456", displayName = "RJ123456"),
            rootFolderUri = "content://tree/root2",
            nowEpochMillis = 2_000L,
        )

        val after = db.workDao().getById("local:RJ123456")!!
        assertEquals("Moved/RJ123456", after.relativeDir)
        assertEquals("content://tree/root2", after.rootFolderUri)
        // 已刮削行:标题绝不被文件夹名(RJ号)打回(用户实测踩中的回归)。
        assertEquals("【ASMR】刮削到的真标题", after.title)
        assertEquals(SortKeyGenerator.generate("【ASMR】刮削到的真标题"), after.titleSortKey)
        assertEquals(2_000L, after.updatedAt)
        // Scraped metadata and insertion time survive the rescan.
        assertEquals(ScrapeStatus.OK, after.scrapeStatus)
        assertEquals("circle-1", after.circleId)
        assertEquals(4.52, after.rateAverage2dp!!, 0.001)
        assertEquals("2024-03-15", after.releaseDate)
        assertEquals(1_000L, after.addedAt)
    }

    @Test
    fun `rescan of scraped row at same location is UNCHANGED despite folder-name mismatch`() = runBlocking {
        persister.commitWork(
            WorkCandidate(relativeDir = "RJ123456", rjCode = "RJ123456", displayName = "RJ123456"),
            rootFolderUri = "content://tree/root",
            nowEpochMillis = 1_000L,
        )
        val before = db.workDao().getById("local:RJ123456")!!
        db.workDao().upsert(
            before.copy(
                title = "刮削标题",
                titleSortKey = SortKeyGenerator.generate("刮削标题"),
                scrapeStatus = ScrapeStatus.OK,
            ),
        )

        // 位置没变:不能因"刮削标题≠文件夹名"抖 UPDATED/updatedAt。
        val kind = persister.commitWork(
            WorkCandidate(relativeDir = "RJ123456", rjCode = "RJ123456", displayName = "RJ123456"),
            rootFolderUri = "content://tree/root",
            nowEpochMillis = 9_000L,
        )
        assertEquals(CommitKind.UNCHANGED, kind)
        val after = db.workDao().getById("local:RJ123456")!!
        assertEquals("刮削标题", after.title)
        assertTrue(after.updatedAt < 9_000L)
    }

    @Test
    fun `distinct works commit independently and no half rows appear`() = runBlocking {
        persister.commitWork(
            WorkCandidate(relativeDir = "RJ111111", rjCode = "RJ111111", displayName = "RJ111111"),
            rootFolderUri = "content://tree/root",
            nowEpochMillis = 1_000L,
        )
        persister.commitWork(
            WorkCandidate(relativeDir = "BJ012345", rjCode = "BJ012345", displayName = "BJ012345"),
            rootFolderUri = "content://tree/root",
            nowEpochMillis = 1_000L,
        )

        assertEquals(2, db.workDao().count())
        // Re-committing one work must not duplicate it (upsert semantics).
        persister.commitWork(
            WorkCandidate(relativeDir = "BJ012345", rjCode = "BJ012345", displayName = "BJ012345"),
            rootFolderUri = "content://tree/root",
            nowEpochMillis = 1_000L,
        )
        assertEquals(2, db.workDao().count())
    }

    @Test
    fun `commit of an identical work returns UNCHANGED and rewrites nothing`() = runBlocking {
        persister.commitWork(
            WorkCandidate(relativeDir = "RJ123456", rjCode = "RJ123456", displayName = "RJ123456"),
            rootFolderUri = "content://tree/root",
            nowEpochMillis = 1_000L,
        )

        val kind = persister.commitWork(
            WorkCandidate(relativeDir = "RJ123456", rjCode = "RJ123456", displayName = "RJ123456"),
            rootFolderUri = "content://tree/root",
            nowEpochMillis = 2_000L,
        )

        assertEquals(CommitKind.UNCHANGED, kind)
        val row = db.workDao().getById("local:RJ123456")!!
        // No-op commit must not bump updatedAt (no misleading "updated" counts).
        assertEquals(1_000L, row.updatedAt)
    }

    @Test
    fun `commit of a changed work returns UPDATED and bumps updatedAt`() = runBlocking {
        persister.commitWork(
            WorkCandidate(relativeDir = "RJ123456", rjCode = "RJ123456", displayName = "RJ123456"),
            rootFolderUri = "content://tree/root",
            nowEpochMillis = 1_000L,
        )

        val kind = persister.commitWork(
            WorkCandidate(relativeDir = "Moved/RJ123456", rjCode = "RJ123456", displayName = "RJ123456"),
            rootFolderUri = "content://tree/root",
            nowEpochMillis = 2_000L,
        )

        assertEquals(CommitKind.UPDATED, kind)
        assertEquals("Moved/RJ123456", db.workDao().getById("local:RJ123456")!!.relativeDir)
        assertEquals(2_000L, db.workDao().getById("local:RJ123456")!!.updatedAt)
    }

    @Test
    fun `commit of a previously missing work clears missing and returns UPDATED`() = runBlocking {
        persister.commitWork(
            WorkCandidate(relativeDir = "RJ123456", rjCode = "RJ123456", displayName = "RJ123456"),
            rootFolderUri = "content://tree/root",
            nowEpochMillis = 1_000L,
        )
        val before = db.workDao().getById("local:RJ123456")!!
        db.workDao().upsert(before.copy(missing = true))

        val kind = persister.commitWork(
            WorkCandidate(relativeDir = "RJ123456", rjCode = "RJ123456", displayName = "RJ123456"),
            rootFolderUri = "content://tree/root",
            nowEpochMillis = 2_000L,
        )

        assertEquals(CommitKind.UPDATED, kind)
        assertFalse(db.workDao().getById("local:RJ123456")!!.missing)
    }

    @Test
    fun `new work returns INSERTED`() = runBlocking {
        val kind = persister.commitWork(
            WorkCandidate(relativeDir = "RJ123456", rjCode = "RJ123456", displayName = "RJ123456"),
            rootFolderUri = "content://tree/root",
            nowEpochMillis = 1_000L,
        )
        assertEquals(CommitKind.INSERTED, kind)
        assertFalse(db.workDao().getById("local:RJ123456")!!.missing)
    }
}
