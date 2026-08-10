package com.oneasmr.app.data.scanner

import androidx.room.Room
import com.oneasmr.app.data.local.Collection
import com.oneasmr.app.data.local.CollectionItem
import com.oneasmr.app.data.local.OneAsmrDatabase
import com.oneasmr.app.data.local.ScrapeStatus
import com.oneasmr.app.data.local.SingleFileKind
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 单文件入库:元数据优先级链(info.json > 内嵌 > 文件名)、封面来源
 * (边车图 > 探测封面)、重扫刷新的保守语义(元数据保留、UNCHANGED 不抖
 * updatedAt)、竞态回退。Robolectric 内存库 + 可编程 [SingleFileProbe] 假件。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SingleFilePersisterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: OneAsmrDatabase
    private lateinit var thumbStore: SingleThumbStore

    private class FakeProbe(
        var sidecarText: String? = null,
        var sidecarBytes: ByteArray? = null,
        var media: MediaProbeResult = MediaProbeResult(null, null, null, null),
    ) : SingleFileProbe {
        override fun readSidecarText(documentUri: String): String? = sidecarText

        override fun readSidecarBytes(documentUri: String): ByteArray? = sidecarBytes

        var lastExtractCover: Boolean? = null

        override fun probeMedia(documentUri: String, extractCover: Boolean): MediaProbeResult {
            lastExtractCover = extractCover
            return media
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            OneAsmrDatabase::class.java,
        ).build()
        thumbStore = SingleThumbStore(tmp.newFolder("thumbs"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun candidate(
        path: String = "【ASMR】耳かき [dQw4w9WgXcQ].mp4",
        infoJsonUri: String? = null,
        thumbUri: String? = null,
        size: Long = 100L,
        lastModified: Long = 10L,
    ) = SingleFileCandidate(
        relativePath = path,
        fileName = path.substringAfterLast('/'),
        documentUri = "content://fake/$path",
        isVideo = path.endsWith(".mp4"),
        sizeBytes = size,
        lastModified = lastModified,
        infoJsonUri = infoJsonUri,
        thumbUri = thumbUri,
    )

    private fun persister(probe: SingleFileProbe) =
        RoomSingleFilePersister(db, probe, thumbStore)

    private val root = "content://tree/singles"

    @Test
    fun `insert uses info json over embedded over filename`() = runBlocking {
        val probe = FakeProbe(
            sidecarText = """{"title":"json 标题","channel":"频道","duration":60,"id":"dQw4w9WgXcQ"}""",
            media = MediaProbeResult(999L, "embedded title", "embedded artist", null),
        )
        val commit = persister(probe).commitFile(
            candidate(infoJsonUri = "content://fake/a.info.json"),
            root,
            nowEpochMillis = 1_000L,
        )
        assertEquals(CommitKind.INSERTED, commit.kind)
        val row = db.singleFileDao().getById(commit.fileId)!!
        assertEquals("json 标题", row.displayTitle)
        assertEquals("频道", row.channel)
        assertEquals(60_000L, row.durationMs)
        assertEquals("dQw4w9WgXcQ", row.youtubeId)
        assertEquals(SingleFileKind.VIDEO, row.kind)
        assertEquals(ScrapeStatus.NOT_SCRAPED, row.scrapeStatus)
    }

    @Test
    fun `embedded metadata fills gaps when no info json`() = runBlocking {
        val probe = FakeProbe(
            media = MediaProbeResult(120_000L, null, "うp主", null),
        )
        val commit = persister(probe).commitFile(candidate(), root, 1_000L)
        val row = db.singleFileDao().getById(commit.fileId)!!
        // 标题来自文件名(剥尾部 ID),频道/时长来自内嵌。
        assertEquals("【ASMR】耳かき", row.displayTitle)
        assertEquals("dQw4w9WgXcQ", row.youtubeId)
        assertEquals("うp主", row.channel)
        assertEquals(120_000L, row.durationMs)
    }

    @Test
    fun `sidecar thumb wins and skips frame extraction`() = runBlocking {
        val probe = FakeProbe(sidecarBytes = byteArrayOf(1, 2, 3))
        val commit = persister(probe).commitFile(
            candidate(thumbUri = "content://fake/a.webp"),
            root,
            1_000L,
        )
        assertEquals(false, probe.lastExtractCover) // 有边车图就不抽帧
        val row = db.singleFileDao().getById(commit.fileId)!!
        assertNotNull(row.thumbSource)
        assertTrue(File(row.thumbSource!!).readBytes().contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `probe cover used when no sidecar thumb`() = runBlocking {
        val probe = FakeProbe(
            media = MediaProbeResult(null, null, null, byteArrayOf(9, 9)),
        )
        val commit = persister(probe).commitFile(candidate(), root, 1_000L)
        assertEquals(true, probe.lastExtractCover)
        assertNotNull(db.singleFileDao().getById(commit.fileId)!!.thumbSource)
    }

    @Test
    fun `rescan unchanged file touches nothing`() = runBlocking {
        val p = persister(FakeProbe())
        val first = p.commitFile(candidate(), root, 1_000L)
        val second = p.commitFile(candidate(), root, 2_000L)
        assertEquals(CommitKind.UNCHANGED, second.kind)
        assertEquals(first.fileId, second.fileId)
        assertEquals(1_000L, db.singleFileDao().getById(first.fileId)!!.updatedAt)
    }

    @Test
    fun `rescan changed file refreshes facts but keeps metadata and membership`() = runBlocking {
        val probe = FakeProbe(
            sidecarText = """{"title":"边车标题","channel":"频道"}""",
        )
        val p = persister(probe)
        val first = p.commitFile(candidate(infoJsonUri = "content://fake/x"), root, 1_000L)
        db.collectionDao().ensureFavorites(1_000L)
        db.collectionDao().addItem(CollectionItem(Collection.FAVORITES_ID, first.fileId, 1, 1_000L))

        // 同位置、文件重下载(大小/时间变了),且边车此时已被用户删掉。
        probe.sidecarText = null
        val second = p.commitFile(candidate(size = 999L, lastModified = 20L), root, 2_000L)

        assertEquals(CommitKind.UPDATED, second.kind)
        val row = db.singleFileDao().getById(first.fileId)!!
        assertEquals(999L, row.sizeBytes)
        assertEquals(2_000L, row.updatedAt)
        assertEquals("边车标题", row.displayTitle) // 元数据保留,不被重扫清洗
        assertEquals("频道", row.channel)
        assertEquals(1, db.collectionDao().collectionsWithCountFlow().first().single().fileCount)
    }

    @Test
    fun `rescan clears missing flag`() = runBlocking {
        val p = persister(FakeProbe())
        val first = p.commitFile(candidate(), root, 1_000L)
        db.singleFileDao().markMissing(listOf(first.fileId), 1_500L)

        val second = p.commitFile(candidate(), root, 2_000L)
        assertEquals(CommitKind.UPDATED, second.kind)
        assertFalse(db.singleFileDao().getById(first.fileId)!!.missing)
    }

    @Test
    fun `corrupt info json falls back to filename`() = runBlocking {
        val probe = FakeProbe(sidecarText = "{broken json")
        val commit = persister(probe).commitFile(
            candidate(infoJsonUri = "content://fake/broken"),
            root,
            1_000L,
        )
        assertEquals("【ASMR】耳かき", db.singleFileDao().getById(commit.fileId)!!.displayTitle)
    }

    @Test
    fun `removeSingleFile clears row membership playback and thumb`() = runBlocking {
        val probe = FakeProbe(media = MediaProbeResult(null, null, null, byteArrayOf(1)))
        val commit = persister(probe).commitFile(candidate(), root, 1_000L)
        val id = commit.fileId
        db.collectionDao().ensureFavorites(1_000L)
        db.collectionDao().addItem(CollectionItem(Collection.FAVORITES_ID, id, 1, 1_000L))
        db.playbackStateDao().upsert(
            com.oneasmr.app.data.local.PlaybackState("single:$id:1", 5L, 10L, 1_000L),
        )
        val thumbPath = db.singleFileDao().getById(id)!!.thumbSource!!

        removeSingleFile(db, thumbStore, id)

        assertNull(db.singleFileDao().getById(id))
        assertNull(db.playbackStateDao().get("single:$id:1"))
        assertEquals(0, db.collectionDao().allItemsFlow().first().size)
        assertFalse(File(thumbPath).exists())
    }
}
