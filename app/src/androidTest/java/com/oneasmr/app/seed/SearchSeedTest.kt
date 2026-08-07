package com.oneasmr.app.seed

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oneasmr.app.data.local.Circle
import com.oneasmr.app.data.local.OneAsmrDatabase
import com.oneasmr.app.data.local.ScrapeStatus
import com.oneasmr.app.data.local.SortKeyGenerator
import com.oneasmr.app.data.local.Tag
import com.oneasmr.app.data.local.Va
import com.oneasmr.app.data.local.Work
import com.oneasmr.app.data.local.WorkTag
import com.oneasmr.app.data.local.WorkVa
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TASK 13 DEVICE SEED: a small multi-language library (Chinese / Japanese /
 * English titles, circles, tags and VAs) so the FTS5 trigram index has
 * content across all four search dimensions, plus works with DISTINCT
 * sortable fields (releaseDate / dlCount / price / reviewCount /
 * rateAverage2dp) for the multi-field sort QA, a direct-lookup work
 * (RJ260001), and a work whose TITLE contains a 7-digit code text
 * (RJ2600001) — the direct-probe-miss → FTS-fallback case.
 *
 * Writes the PRODUCTION database (OneAsmrDatabase.build — BundledSQLiteDriver
 * + FTS callback) through the real DAOs; rows persist after the run.
 *
 * Sort-QA ground truth (all values distinct so top-N order is unambiguous):
 *   releaseDate asc : RJ260005(2020) < RJ260001(2021) < RJ260002(2022) < RJ260004(2023) < RJ260003(2024) < RJ260006(2025)
 *   dlCount asc    : RJ260005(100) < RJ260001(500) < RJ260006(1000) < RJ260002(3000) < RJ260004(8000) < RJ260003(20000)
 *   price asc      : RJ260005(600) < RJ260001(1100) < RJ260003(1650) < RJ260002(2200) < RJ260006(2750) < RJ260004(3300)
 *   rating desc    : RJ260003(4.9) > RJ260004(4.5) > RJ260006(4.2) > RJ260001(4.0) > RJ260002(3.8) > RJ260005(3.5)
 *   reviewCount asc: RJ260001(20) < RJ260002(50) < RJ260003(90) < RJ260004(150) < RJ260005(300) < RJ260006(700)
 *
 * Run: adb shell am instrument -w -e class com.oneasmr.app.seed.SearchSeedTest \
 *   com.oneasmr.app.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class SearchSeedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: OneAsmrDatabase

    @Before
    fun setUp() {
        // Stale-state hygiene: fresh library DB every run.
        for (name in listOf("oneasmr.db", "oneasmr.db-wal", "oneasmr.db-shm")) {
            context.deleteDatabase(name)
        }
        db = OneAsmrDatabase.build(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun work(
        id: String,
        title: String,
        circleId: String? = null,
        releaseDate: String? = null,
        dlCount: Int? = null,
        price: Int? = null,
        reviewCount: Int? = null,
        rateAverage2dp: Double? = null,
    ) = Work(
        id = id,
        rootFolderUri = "content://tree/primary%3AAsmrLib",
        relativeDir = id.substringAfter(':'),
        title = title,
        titleSortKey = SortKeyGenerator.generate(title),
        circleId = circleId,
        nsfw = false,
        releaseDate = releaseDate,
        dlCount = dlCount,
        price = price,
        reviewCount = reviewCount,
        rateCount = null,
        rateAverage2dp = rateAverage2dp,
        rateCountDetailJson = null,
        seriesName = null,
        scrapeStatus = ScrapeStatus.OK,
        missing = false,
        addedAt = 1_000L,
        updatedAt = 1_000L,
    )

    @Test
    fun seedSearchLibrary() {
        runBlocking {
            val dao = db.workDao()
            val circleDao = db.circleDao()
            val tagDao = db.tagDao()
            val vaDao = db.vaDao()

            circleDao.upsertAll(
                listOf(
                    Circle("月亮社团", "月亮社团", SortKeyGenerator.generate("月亮社团")),
                    Circle("サマーサークル", "サマーサークル", SortKeyGenerator.generate("サマーサークル")),
                ),
            )
            tagDao.upsertAll(
                listOf(Tag("治愈系", "治愈系"), Tag("安眠誘導", "安眠誘導"), Tag("子守唄", "子守唄"), Tag("白噪音", "白噪音")),
            )
            vaDao.upsertAll(
                listOf(
                    Va("秋月爱莉", "秋月爱莉", SortKeyGenerator.generate("秋月爱莉")),
                    Va("初音ミク", "初音ミク", SortKeyGenerator.generate("初音ミク")),
                ),
            )

            dao.upsertAll(
                listOf(
                    work(
                        "local:RJ260001", "夜晚助眠陪伴音声", circleId = "月亮社团",
                        releaseDate = "2021-03-15", dlCount = 500, price = 1100, reviewCount = 20, rateAverage2dp = 4.0,
                    ),
                    work(
                        "local:RJ260002", "おやすみなさい安眠ボイス", circleId = "月亮社团",
                        releaseDate = "2022-06-01", dlCount = 3000, price = 2200, reviewCount = 50, rateAverage2dp = 3.8,
                    ),
                    work(
                        "local:RJ260003", "Gentle Sleep Hypnosis",
                        releaseDate = "2024-11-20", dlCount = 20000, price = 1650, reviewCount = 90, rateAverage2dp = 4.9,
                    ),
                    work(
                        "local:RJ260004", "初音ミクの子守唄", circleId = "サマーサークル",
                        releaseDate = "2023-09-09", dlCount = 8000, price = 3300, reviewCount = 150, rateAverage2dp = 4.5,
                    ),
                    work(
                        "local:RJ260005", "雨声与白噪音",
                        releaseDate = "2020-01-05", dlCount = 100, price = 600, reviewCount = 300, rateAverage2dp = 3.5,
                    ),
                    work(
                        "local:RJ260006", "SUMMER VACATION 2024", circleId = "サマーサークル",
                        releaseDate = "2025-07-07", dlCount = 1000, price = 2750, reviewCount = 700, rateAverage2dp = 4.2,
                    ),
                    work("local:RJ260007", "RJ2600001番外編"),
                    work("local:BJ260008", "BJ 漫画特典"),
                ),
            )

            // Tags: 安眠誘導 on the Japanese title work; 治愈系/子守唄/白噪音 across the CJK works.
            db.workTagDao().insertAll(
                listOf(
                    WorkTag("local:RJ260002", "安眠誘導"),
                    WorkTag("local:RJ260001", "治愈系"),
                    WorkTag("local:RJ260004", "子守唄"),
                    WorkTag("local:RJ260005", "白噪音"),
                ),
            )
            db.workVaDao().insertAll(
                listOf(
                    WorkVa("local:RJ260001", "秋月爱莉"),
                    WorkVa("local:RJ260004", "初音ミク"),
                ),
            )

            val total = dao.count()
            val rj260001 = dao.getById("local:RJ260001")
            val sevenDigitTitle = dao.getById("local:RJ260007")
            Log.i(TAG, "SEARCH SEEDED total=$total title1=${rj260001!!.title} circle=${dao.getListItemById("local:RJ260001")?.circleName} seven=${sevenDigitTitle!!.title}")
            assertEquals(8, total)
            assertEquals("夜晚助眠陪伴音声", rj260001.title)
            assertEquals("月亮社团", dao.getListItemById("local:RJ260001")!!.circleName)
            assertTrue(sevenDigitTitle.title.contains("2600001"))
            // FTS index must be live on the bundled driver: a 3+ char trigram
            // probe over the seeded titles (device-only assertion).
            assertTrue(db.workDao().search("助眠陪伴").isNotEmpty())
        }
    }

    private companion object {
        const val TAG = "OneAsmrSearchSeed"
    }
}
