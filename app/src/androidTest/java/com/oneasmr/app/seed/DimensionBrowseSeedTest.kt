package com.oneasmr.app.seed

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oneasmr.app.data.local.Circle
import com.oneasmr.app.data.local.ScrapeStatus
import com.oneasmr.app.data.local.Tag
import com.oneasmr.app.data.local.Va
import com.oneasmr.app.data.local.Work
import com.oneasmr.app.data.local.WorkTag
import com.oneasmr.app.data.local.WorkVa
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TASK 16 DEVICE SEED (browse dimensions).
 *
 * Writes the PRODUCTION database through the real DAOs (OneAsmrDatabase.build
 * — BundledSQLiteDriver) plus a root in the production scan_roots DataStore,
 * so the app launched right after pages through the dimension browse flows.
 *
 * Layout (counts are distinct per dimension so the work-count-DESC ordering
 * is assertable from uiautomator dumps):
 *   社团乙 x3 (RJ101001..003), 社团丙 x2 (RJ101004/5), 社团甲 x1 (RJ101006),
 *   社团空 x0  ->  empty-dimension failure path
 *   标签一 x4, 标签二 x3 ; CV一 x4, CV二 x3
 *
 * RJ101001 additionally points at a REAL emulator folder
 * (/sdcard/AsmrLib/RJ101001, created via adb before the run) so its detail
 * page renders the circle/CV/tag chips for the chip-navigation QA.
 *
 * Run: install app + test APK manually, then
 *   adb shell am instrument -w -e class com.oneasmr.app.seed.DimensionBrowseSeedTest \
 *     com.oneasmr.app.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class DimensionBrowseSeedTest {

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
    fun seedBrowseDimensions() {
        runBlocking {
            val dao = db.workDao()
            db.circleDao().upsertAll(
                listOf(
                    Circle("社团甲", "社团甲", "shetuanjia"),
                    Circle("社团乙", "社团乙", "shetuanyi"),
                    Circle("社团丙", "社团丙", "shetuanbing"),
                    Circle("社团空", "社团空", "shetuankong"),
                ),
            )
            db.tagDao().upsertAll(listOf(Tag("标签一", "标签一"), Tag("标签二", "标签二")))
            db.vaDao().upsertAll(listOf(Va("CV一", "CV一", "cv1"), Va("CV二", "CV二", "cv2")))

            val now = System.currentTimeMillis()
            fun work(i: Int, circle: String): Work {
                val rj = "RJ101${"%03d".format(i)}"
                return Work(
                    id = "local:$rj",
                    rootFolderUri = if (i == 1) {
                        "content://tree/primary%3AAsmrLib"
                    } else {
                        "content://tree/fabricated%3Anone"
                    },
                    relativeDir = rj,
                    title = "浏览测试 $rj",
                    titleSortKey = rj,
                    circleId = circle,
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
                    missing = false,
                    addedAt = now,
                    updatedAt = now,
                )
            }
            val works = listOf(
                Triple(work(1, "社团乙"), listOf("标签一", "标签二"), listOf("CV一", "CV二")),
                Triple(work(2, "社团乙"), listOf("标签一", "标签二"), listOf("CV一")),
                Triple(work(3, "社团乙"), listOf("标签一"), listOf("CV一")),
                Triple(work(4, "社团丙"), listOf("标签二"), listOf("CV二")),
                Triple(work(5, "社团丙"), emptyList<String>(), listOf("CV二")),
                Triple(work(6, "社团甲"), listOf("标签一"), listOf("CV一")),
            )
            dao.upsertAll(works.map { it.first })

            val tagLinks = works.flatMap { (w, tags, _) -> tags.map { WorkTag(w.id, it) } }
            val vaLinks = works.flatMap { (w, _, vas) -> vas.map { WorkVa(w.id, it) } }
            db.workTagDao().insertAll(tagLinks)
            db.workVaDao().insertAll(vaLinks)

            seedRoot(now)

            val circleCounts = db.circleDao().getAllWithCountsFlow().let { f ->
                kotlinx.coroutines.runBlocking { f.first() }
            }.map { it.name to it.workCount }
            val tagCounts = db.tagDao().getAllWithCountsFlow().let { f ->
                kotlinx.coroutines.runBlocking { f.first() }
            }.map { it.name to it.workCount }
            val vaCounts = db.vaDao().getAllWithCountsFlow().let { f ->
                kotlinx.coroutines.runBlocking { f.first() }
            }.map { it.name to it.workCount }
            Log.i(TAG, "SEEDED circles=$circleCounts tags=$tagCounts vas=$vaCounts")
            assertEquals(6, dao.count())
            // Ordering is part of the seed contract (count DESC).
            assertEquals(listOf("社团乙" to 3, "社团丙" to 2, "社团甲" to 1, "社团空" to 0), circleCounts)
            assertEquals(listOf("标签一" to 4, "标签二" to 3), tagCounts)
            assertEquals(listOf("CV一" to 4, "CV二" to 3), vaCounts)
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
            runBlocking { repo.addRoot("content://tree/primary%3AAsmrLib", null) }
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val TAG = "OneAsmrSeed16"
    }
}
