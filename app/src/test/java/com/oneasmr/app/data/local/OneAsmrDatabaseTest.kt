package com.oneasmr.app.data.local

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * DAO integration tests on an in-memory Room database under Robolectric.
 *
 * NOTE: these tests cover the NON-FTS paths only. Robolectric's framework
 * SQLite has no FTS5 module (and the BundledSQLiteDriver native lib cannot be
 * loaded on the JVM), so the FTS callback degrades to LIKE search — which is
 * exactly what [WorkDao.search] falls back to (degraded-fallback path, marker
 * logged). The FTS5 trigram MATCH assertion lives in the device probe:
 * androidTest/com/oneasmr/app/data/local/FtsTrigramProbeTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OneAsmrDatabaseTest {

    private lateinit var db: OneAsmrDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            OneAsmrDatabase::class.java,
        )
            .addCallback(FtsCallback())
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun work(id: String, title: String, titleSortKey: String = SortKeyGenerator.generate(title)) =
        Work(
            id = id,
            rootFolderUri = "content://tree/root",
            relativeDir = title,
            title = title,
            titleSortKey = titleSortKey,
            circleId = null,
            nsfw = false,
            releaseDate = null,
            dlCount = null,
            price = null,
            reviewCount = null,
            rateCount = null,
            rateAverage2dp = null,
            rateCountDetailJson = null,
            seriesName = null,
            scrapeStatus = ScrapeStatus.NOT_SCRAPED,
            addedAt = 1_000L,
            updatedAt = 1_000L,
        )

    // ---------- insert + paged query ----------

    @Test
    fun `insert then paged query ordered by id`() = runBlocking {
        db.workDao().upsertAll(listOf(work("local:RJ300", "third"), work("local:RJ100", "first"), work("local:RJ200", "second")))

        val page1 = db.workDao().getPage(WorkOrder.ID, limit = 2, offset = 0)
        assertEquals(listOf("local:RJ100", "local:RJ200"), page1.map { it.id })

        val page2 = db.workDao().getPage(WorkOrder.ID, limit = 2, offset = 2)
        assertEquals(listOf("local:RJ300"), page2.map { it.id })

        assertEquals(3, db.workDao().count())
    }

    @Test
    fun `paged query orders by title sort key`() = runBlocking {
        // 阿 (a) < 中 (zhong) by pinyin key.
        db.workDao().upsertAll(
            listOf(
                work("local:RJ200", "中", titleSortKey = "zhong"),
                work("local:RJ100", "阿", titleSortKey = "a"),
            ),
        )
        val page = db.workDao().getPage(WorkOrder.TITLE_SORT_KEY, limit = 10, offset = 0)
        assertEquals(listOf("local:RJ100", "local:RJ200"), page.map { it.id })
    }

    @Test
    fun `paged query descending release date`() = runBlocking {
        db.workDao().upsertAll(
            listOf(
                work("local:RJ100", "old").copy(releaseDate = "2020-01-01"),
                work("local:RJ200", "new").copy(releaseDate = "2024-06-01"),
            ),
        )
        val page = db.workDao().getPage(WorkOrder.RELEASE_DATE, limit = 10, offset = 0, descending = true)
        assertEquals(listOf("local:RJ200", "local:RJ100"), page.map { it.id })
    }

    @Test
    fun `insert then get by id roundtrips all fields`() = runBlocking {
        db.circleDao().upsertAll(listOf(Circle("circle-1", "シロクマ工房", "shirokumakoubou")))
        val w = work("local:RJ123456", "催眠音声でおやすみ").copy(
            circleId = "circle-1",
            nsfw = true,
            releaseDate = "2024-03-15",
            dlCount = 1234,
            price = 1650,
            reviewCount = 42,
            rateCount = 500,
            rateAverage2dp = 4.52,
            rateCountDetailJson = """{"1":10,"2":5,"3":30,"4":150,"5":305}""",
            seriesName = "シリーズ",
            scrapeStatus = ScrapeStatus.OK,
        )
        db.workDao().upsert(w)

        val read = db.workDao().getById("local:RJ123456")!!
        assertEquals(w, read)
        assertEquals(ScrapeStatus.OK, read.scrapeStatus)
        assertTrue(read.nsfw)
        assertEquals(4.52, read.rateAverage2dp!!, 0.001)
    }

    // ---------- review upsert + rating validation ----------

    @Test
    fun `review upsert inserts then updates in place`() = runBlocking {
        db.workDao().upsert(work("local:RJ123456", "title"))

        db.reviewDao().upsert(Review("local:RJ123456", rating = 4, reviewText = "good", progress = ProgressState.listened, updatedAt = 1L))
        assertEquals(1, db.reviewDao().getAll().size)

        db.reviewDao().upsert(Review("local:RJ123456", rating = 5, reviewText = "great", progress = ProgressState.replay, updatedAt = 2L))
        val reviews = db.reviewDao().getAll()
        assertEquals(1, reviews.size) // upsert, not insert
        assertEquals(5, reviews[0].rating)
        assertEquals("great", reviews[0].reviewText)
        assertEquals(ProgressState.replay, reviews[0].progress)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rating 0 is rejected`() = runBlocking {
        db.reviewDao().upsert(Review("local:RJ123456", rating = 0, reviewText = null, progress = ProgressState.none, updatedAt = 1L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rating 6 is rejected`() = runBlocking {
        db.reviewDao().upsert(Review("local:RJ123456", rating = 6, reviewText = null, progress = ProgressState.none, updatedAt = 1L))
    }

    @Test
    fun `null rating and null text are allowed`() = runBlocking {
        db.reviewDao().upsert(Review("local:RJ123456", rating = null, reviewText = null, progress = ProgressState.marked, updatedAt = 1L))
        val review = db.reviewDao().getByWorkId("local:RJ123456")!!
        assertNull(review.rating)
        assertNull(review.reviewText)
        assertEquals(ProgressState.marked, review.progress)
    }

    @Test
    fun `review delete removes row`() = runBlocking {
        db.reviewDao().upsert(Review("local:RJ123456", rating = 3, reviewText = null, progress = ProgressState.listening, updatedAt = 1L))
        db.reviewDao().deleteByWorkId("local:RJ123456")
        assertNull(db.reviewDao().getByWorkId("local:RJ123456"))
    }

    // ---------- reverse lookups by tag / va / circle ----------

    @Test
    fun `works by tag reverse lookup`() = runBlocking {
        db.workDao().upsertAll(
            listOf(
                work("local:RJ100", "A"),
                work("local:RJ200", "B"),
                work("local:RJ300", "C"),
            ),
        )
        db.tagDao().upsertAll(listOf(Tag("asmr", "asmr"), Tag("kuuki", "kuuki")))
        db.workTagDao().insertAll(
            listOf(
                WorkTag("local:RJ100", "asmr"),
                WorkTag("local:RJ200", "asmr"),
                WorkTag("local:RJ200", "kuuki"),
            ),
        )

        assertEquals(listOf("local:RJ100", "local:RJ200"), db.workTagDao().getWorkIdsByTag("asmr"))
        assertEquals(listOf("local:RJ200"), db.workTagDao().getWorkIdsByTag("kuuki"))
        assertEquals(setOf("asmr", "kuuki"), db.workTagDao().getTagIdsByWork("local:RJ200").toSet())
        assertEquals(listOf("local:RJ100", "local:RJ200"), db.workDao().getWorksByTag("asmr").map { it.id })
    }

    @Test
    fun `works by va reverse lookup`() = runBlocking {
        db.workDao().upsertAll(
            listOf(
                work("local:RJ100", "A"),
                work("local:RJ200", "B"),
            ),
        )
        db.vaDao().upsertAll(listOf(Va("白鍵たま", "白鍵たま", "shirakitatama"), Va("天知遥", "天知遥", "amachiharu")))
        db.workVaDao().insertAll(
            listOf(
                WorkVa("local:RJ100", "白鍵たま"),
                WorkVa("local:RJ200", "白鍵たま"),
                WorkVa("local:RJ200", "天知遥"),
            ),
        )

        assertEquals(listOf("local:RJ100", "local:RJ200"), db.workVaDao().getWorkIdsByVa("白鍵たま"))
        assertEquals(listOf("local:RJ200"), db.workVaDao().getWorkIdsByVa("天知遥"))
        assertEquals(setOf("白鍵たま", "天知遥"), db.workVaDao().getVaIdsByWork("local:RJ200").toSet())
        assertEquals(listOf("local:RJ100", "local:RJ200"), db.workDao().getWorksByVa("白鍵たま").map { it.id })
    }

    @Test
    fun `works by circle reverse lookup`() = runBlocking {
        db.circleDao().upsertAll(
            listOf(Circle("circle-1", "one", "one"), Circle("circle-2", "two", "two")),
        )
        db.workDao().upsertAll(
            listOf(
                work("local:RJ100", "A").copy(circleId = "circle-1"),
                work("local:RJ200", "B").copy(circleId = "circle-1"),
                work("local:RJ300", "C").copy(circleId = "circle-2"),
            ),
        )
        assertEquals(listOf("local:RJ100", "local:RJ200"), db.workDao().getWorksByCircle("circle-1").map { it.id })
        assertEquals(listOf("local:RJ300"), db.workDao().getWorksByCircle("circle-2").map { it.id })
    }

    // ---------- playback state ----------

    @Test
    fun `playback state write read and overwrite`() = runBlocking {
        db.playbackStateDao().upsert(PlaybackState("local:RJ123456:3", positionMs = 1_000, durationMs = 600_000, updatedAt = 1L))
        val read = db.playbackStateDao().get("local:RJ123456:3")!!
        assertEquals(1_000, read.positionMs)
        assertEquals(600_000, read.durationMs)

        db.playbackStateDao().upsert(PlaybackState("local:RJ123456:3", positionMs = 250_000, durationMs = 600_000, updatedAt = 2L))
        assertEquals(250_000, db.playbackStateDao().get("local:RJ123456:3")!!.positionMs)
    }

    @Test
    fun `playback state missing key returns null and delete works`() = runBlocking {
        assertNull(db.playbackStateDao().get("local:RJ123456:9"))
        db.playbackStateDao().upsert(PlaybackState("srv1:RJ123456:0", positionMs = 5, durationMs = 10, updatedAt = 1L))
        db.playbackStateDao().delete("srv1:RJ123456:0")
        assertNull(db.playbackStateDao().get("srv1:RJ123456:0"))
    }

    // ---------- search (degraded LIKE path on JVM) ----------

    @Test
    fun `search fallback like finds cjk title`() = runBlocking {
        FtsStatus.available = false // degraded path is the only JVM path
        db.workDao().upsert(work("local:RJ123456", "催眠音声でおやすみ"))

        val hits = db.workDao().search("催眠")
        assertEquals(listOf(WorkSearchHit("local:RJ123456", "title")), hits)
    }

    @Test
    fun `search fallback like matches japanese kana substring`() = runBlocking {
        FtsStatus.available = false
        db.workDao().upsert(work("local:RJ123456", "催眠音声でおやすみ"))
        assertTrue(db.workDao().search("おやすみ").any { it.workId == "local:RJ123456" })
    }

    @Test
    fun `search matches circle name via fallback`() = runBlocking {
        FtsStatus.available = false
        db.circleDao().upsertAll(listOf(Circle("circle-1", "シロクマ工房", "shirokumakoubou")))
        db.workDao().upsert(work("local:RJ100", "A").copy(circleId = "circle-1"))

        val hits = db.workDao().search("シロクマ")
        assertEquals(WorkSearchHit("local:RJ100", "circle"), hits.single())
    }

    @Test
    fun `blank search returns empty`() = runBlocking {
        FtsStatus.available = false
        db.workDao().upsert(work("local:RJ123456", "催眠音声"))
        assertTrue(db.workDao().search("").isEmpty())
        assertTrue(db.workDao().search("   ").isEmpty())
    }

    @Test
    fun `like fallback escapes wildcard characters`() = runBlocking {
        FtsStatus.available = false
        db.workDao().upsert(work("local:RJ100", "100%純粋"))
        db.workDao().upsert(work("local:RJ200", "other"))

        // A literal '%' in the query must match only the literal percent row.
        assertEquals(listOf("local:RJ100"), db.workDao().search("100%").map { it.workId })
    }

    // ---------- key-spec consistency across tables ----------

    @Test
    fun `key spec consistency across work review and playback tables`() = runBlocking {
        val rjCode = "RJ123456"
        val workId = KeySpec.workId(KeySpec.LOCAL_SOURCE, rjCode)
        val trackKey = KeySpec.trackKey(KeySpec.LOCAL_SOURCE, rjCode, 3)

        db.workDao().upsert(work(workId, "title"))
        db.reviewDao().upsert(Review(workId, rating = 5, reviewText = "x", progress = ProgressState.listened, updatedAt = 1L))
        db.playbackStateDao().upsert(PlaybackState(trackKey, positionMs = 100, durationMs = 200, updatedAt = 1L))

        assertEquals("local:RJ123456", workId)
        assertEquals("local:RJ123456:3", trackKey)
        assertEquals(workId, db.workDao().getById(workId)!!.id)
        assertEquals(workId, db.reviewDao().getByWorkId(workId)!!.workId)
        assertEquals(trackKey, db.playbackStateDao().get(trackKey)!!.trackKey)
    }

    // ---------- FK cascade ----------

    @Test
    fun `deleting a work cascades to tag links`() = runBlocking {
        db.workDao().upsert(work("local:RJ100", "A"))
        db.tagDao().upsertAll(listOf(Tag("asmr", "asmr")))
        db.workTagDao().insertAll(listOf(WorkTag("local:RJ100", "asmr")))
        assertEquals(1, db.workTagDao().getWorkIdsByTag("asmr").size)

        db.workDao().deleteById("local:RJ100")

        assertNull(db.workDao().getById("local:RJ100"))
        assertTrue(db.workTagDao().getWorkIdsByTag("asmr").isEmpty())
    }
}
