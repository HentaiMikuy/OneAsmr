package com.oneasmr.app.seed

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oneasmr.app.data.local.Circle
import com.oneasmr.app.data.local.ProgressState
import com.oneasmr.app.data.local.Review
import com.oneasmr.app.data.local.ScrapeStatus
import com.oneasmr.app.data.local.Work
import com.oneasmr.app.data.repository.RootDisplayNameResolver
import com.oneasmr.app.data.repository.ScanRootPermissionStore
import com.oneasmr.app.data.repository.ScanRootRepository
import com.oneasmr.app.data.repository.ScanRootsStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TASK 12 DEVICE BULK SEED (plan QA: "seed ~1000 works").
 *
 * Writes the PRODUCTION database file (oneasmr.db via [OneAsmrDatabase.build],
 * the app's real builder: BundledSQLiteDriver + FTS callback) through the real
 * DAOs, plus a root folder in the production "scan_roots" DataStore so the
 * library home shows the works instead of onboarding. The rows persist after
 * the test run, so the app process (launched right after) pages through them.
 *
 * Seed layout (all deterministic, id-ordered so the FIRST grid screen shows
 * every QA case at once):
 *   RJ100001        missing=1, NOT_SCRAPED   -> greyed + 已失效 + no cover
 *   RJ100002        missing=1, NOT_SCRAPED   -> greyed
 *   RJ100003/4/5    OK, circle, rating, covers pushed via run-as -> covers+rating
 *   RJ100006/7/8    progress listened/listening/marked badges
 *   RJ100009        FAILED                   -> 刮削失败 badge
 *   RJ100010..RJ101000  NOT_SCRAPED (991 rows) -> placeholder covers, no crash
 *
 * Evidence of the seed itself: logcat under tag "OneAsmrSeed" + the final
 * COUNT/status assertions below (the DB is read back, not trusted by write).
 *
 * Run: ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.oneasmr.app.seed.BulkSeedTest
 */
@RunWith(AndroidJUnit4::class)
class BulkSeedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: com.oneasmr.app.data.local.OneAsmrDatabase

    @Before
    fun setUp() {
        // Stale-state hygiene: every run starts from an empty library DB.
        for (name in listOf("oneasmr.db", "oneasmr.db-wal", "oneasmr.db-shm")) {
            context.deleteDatabase(name)
        }
        db = com.oneasmr.app.data.local.OneAsmrDatabase.build(context)
        Log.i(TAG, "database file: ${context.getDatabasePath("oneasmr.db")}")
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun seedThousandWorks() {
        runBlocking {
            val dao = db.workDao()
            val reviewDao = db.reviewDao()
            val circleDao = db.circleDao()

            circleDao.upsertAll(
                listOf(
                    Circle("社团甲", "社团甲", "shetuanjia"),
                    Circle("社团乙", "社团乙", "shetuaneryi"),
                    Circle("社团丙", "社团丙", "shetuanbing"),
                ),
            )

            val now = System.currentTimeMillis()
            val works = (1..1000).map { i ->
                val rj = "RJ${100000 + i}"
                val id = "local:$rj"
                Work(
                    id = id,
                    rootFolderUri = "content://tree/primary%3AAsmrLib",
                    relativeDir = rj,
                    title = "测试作品 $rj",
                    titleSortKey = "cesizuopin $rj",
                    circleId = when (i % 3) {
                        0 -> "社团甲"
                        1 -> "社团乙"
                        else -> "社团丙"
                    },
                    nsfw = false,
                    releaseDate = null,
                    dlCount = null,
                    price = null,
                    reviewCount = null,
                    rateCount = null,
                    rateAverage2dp = null,
                    rateCountDetailJson = null,
                    seriesName = null,
                    scrapeStatus = when (i) {
                        in 3..5 -> ScrapeStatus.OK
                        9 -> ScrapeStatus.FAILED
                        else -> ScrapeStatus.NOT_SCRAPED
                    },
                    missing = i <= 2,
                    addedAt = now,
                    updatedAt = now,
                )
            }
            dao.upsertAll(works)

            // Ratings for the scraped trio (grid shows ★ 4.xx).
            for ((i, rating) in listOf(3 to 4.52, 4 to 4.85, 5 to 3.90)) {
                dao.upsert(dao.getById("local:RJ${100000 + i}")!!.copy(rateAverage2dp = rating))
            }

            // Progress badges: RJ100006 listened, 100007 listening, 100008 marked.
            reviewDao.upsert(Review("local:RJ100006", rating = 5, reviewText = null, progress = ProgressState.listened, updatedAt = now))
            reviewDao.upsert(Review("local:RJ100007", rating = 4, reviewText = null, progress = ProgressState.listening, updatedAt = now))
            reviewDao.upsert(Review("local:RJ100008", rating = null, reviewText = null, progress = ProgressState.marked, updatedAt = now))

            // A root folder so the library treats the seed as a real library.
            seedRoot(now)

            // Read back (never trust the write path).
            val total = dao.count()
            val notScraped = dao.getAll().count { it.scrapeStatus == ScrapeStatus.NOT_SCRAPED }
            val missing = dao.getAll().count { it.missing }
            val badges = db.reviewDao().getAll().size
            Log.i(TAG, "SEEDED total=$total notScraped=$notScraped missing=$missing reviews=$badges")
            assertEquals(1000, total)
            assertEquals(996, notScraped)
            assertEquals(2, missing)
            assertEquals(3, badges)
            assertTrue(dao.getById("local:RJ100003")!!.rateAverage2dp!! > 0.0)
        }
    }

    private fun seedRoot(now: Long) {
        val file = context.preferencesDataStoreFile("scan_roots")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            val permissionStore = object : ScanRootPermissionStore {
                override fun takePersistable(treeUri: String): Boolean = true
                override fun releasePersistable(treeUri: String) = Unit
                override fun persistedTreeUris(): Set<String> = emptySet()
            }
            val resolver = object : RootDisplayNameResolver {
                override fun resolve(treeUri: String, fallback: String): String =
                    treeUri.substringAfterLast('/').ifEmpty { fallback }
            }
            val repo = ScanRootRepository(
                permissionStore = permissionStore,
                rootsStore = ScanRootsStore(dataStore),
                displayNameResolver = resolver,
            )
            kotlinx.coroutines.runBlocking { repo.addRoot("content://tree/primary%3AAsmrLib", null) }
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val TAG = "OneAsmrSeed"
    }
}
