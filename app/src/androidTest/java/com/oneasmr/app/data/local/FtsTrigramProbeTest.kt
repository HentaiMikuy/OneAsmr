package com.oneasmr.app.data.local

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DEVICE-SIDE FTS5 TRIGRAM PROBE (plan Task 4 acceptance).
 *
 * JVM/Robolectric unit tests cannot load the BundledSQLiteDriver native lib
 * and the framework SQLite has no FTS5 module — so the trigram MATCH assertion
 * lives HERE, on a real Android runtime (Pixel_9 emulator):
 *
 *  1. opens the real OneAsmrDatabase with BundledSQLiteDriver
 *     (this exercises the production FtsCallback path),
 *  2. asserts FtsStatus.available == true (bundled driver actually active),
 *  3. inserts a CJK title via WorkDao (triggers index it),
 *  4. runs one trigram MATCH query and asserts the row comes back,
 *  5. logs the result to logcat under tag "OneAsmrFtsProbe".
 *
 * Run: ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.oneasmr.app.data.local.FtsTrigramProbeTest
 */
@RunWith(AndroidJUnit4::class)
class FtsTrigramProbeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: OneAsmrDatabase

    @Before
    fun setUp() {
        // Fresh DB per run (stale-state hygiene); delete both the -wal/-shm too.
        context.deleteDatabase("fts_probe.db")
        db = Room.databaseBuilder(context, OneAsmrDatabase::class.java, "fts_probe.db")
            .setDriver(BundledSQLiteDriver())
            .addCallback(FtsCallback())
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("fts_probe.db")
    }

    @Test
    fun trigramMatchRunsWithoutExceptionOnBundledDriver() {
        runBlocking {
            // Room opens lazily and there is no SupportSQLiteOpenHelper on the
            // new driver path: useConnection forces the open (FtsCallback runs).
            db.useConnection(false) { }

            // 1-2. Bundled driver must be active, otherwise this test is meaningless.
            assertTrue(
                "BundledSQLiteDriver did not bring up FTS5 (framework SQLite has no FTS5); " +
                    "check the OneAsmrFts logcat marker for the fallback reason",
                FtsStatus.available,
            )

            // 3. CJK title -> indexed by the work_fts trigger with trigram tokenizer.
            val title = "催眠音声でおやすみ"
            db.workDao().upsert(
                Work(
                    id = KeySpec.workId(KeySpec.LOCAL_SOURCE, "RJ999999"),
                    rootFolderUri = "content://tree/probe",
                    relativeDir = "RJ999999",
                    title = title,
                    titleSortKey = SortKeyGenerator.generate(title),
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
                    missing = false,
                    addedAt = 1L,
                    updatedAt = 1L,
                ),
            )

            // 4-5. One trigram MATCH query ("催眠音声" is a 4-char CJK substring of
            // the title; trigram requires >= 3 chars). No exception + row found.
            val hits = db.workDao().search("催眠音声")
            assertEquals(1, hits.size)
            assertEquals("local:RJ999999", hits.single().workId)
            assertEquals("title", hits.single().matchedIn)

            Log.i(
                "OneAsmrFtsProbe",
                "trigram MATCH ok: query='催眠音声' hits=${hits.size} " +
                    "workId=${hits.single().workId} matchedIn=${hits.single().matchedIn} " +
                    "FtsStatus.available=true",
            )

            // Also verify trigram substring semantics on a 3-char substring that is
            // not a word boundary: "おやす" must match (trigram tokenizer finds
            // any 3-char substring, unlike unicode61 word tokens).
            val substringHits = db.workDao().search("おやす")
            assertTrue(substringHits.isNotEmpty())
            Log.i("OneAsmrFtsProbe", "trigram substring ok: query='おやす' hits=${substringHits.size}")

            // repeated_interruptions: FTS tables and triggers live in the DB
            // file, so a "restart" (close + reopen, no onCreate callback) must
            // still serve trigram MATCH.
            db.close()
            db = Room.databaseBuilder(context, OneAsmrDatabase::class.java, "fts_probe.db")
                .setDriver(BundledSQLiteDriver())
                .addCallback(FtsCallback())
                .build()
            db.useConnection(false) { }
            val afterRestart = db.workDao().search("催眠音声")
            assertEquals(1, afterRestart.size)
            Log.i(
                "OneAsmrFtsProbe",
                "restart ok: reopened DB serves trigram MATCH hits=${afterRestart.size}",
            )
        }
    }
}
