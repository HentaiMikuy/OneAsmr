package com.oneasmr.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Production-driver regression for the v0.2.1 (Room v4) -> v0.2.2 (Room v5)
 * upgrade path.
 *
 * The JVM migration tests use SupportSQLiteDatabase, but production configures
 * BundledSQLiteDriver. Room 2.8 then calls Migration.migrate(SQLiteConnection),
 * whose default implementation throws NotImplementedError for a real bundled
 * connection. This test must open and migrate the fixture through that exact
 * driver path; a legacy-only migration fails here before any assertion runs.
 */
@RunWith(AndroidJUnit4::class)
class OneAsmrDatabaseDriverMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun migratingV4ToV5WithBundledDriverPreservesDataAndCreatesHistory() = runBlocking {
        // Build the current schema once, then remove exactly the v5 additions to
        // obtain a real v4 file. v4 and v5 are otherwise identical (4.json/5.json).
        val fixture = openDatabase()
        try {
            fixture.playbackStateDao().upsert(
                PlaybackState(
                    trackKey = TRACK_KEY,
                    positionMs = 123L,
                    durationMs = 456L,
                    updatedAt = 1_000L,
                ),
            )
        } finally {
            fixture.close()
        }
        downgradeCurrentSchemaToV4()

        val migrated = openDatabase()
        try {
            // Opening any DAO forces Room to run 4 -> 5 and validate the schema.
            assertEquals(123L, migrated.playbackStateDao().get(TRACK_KEY)!!.positionMs)
            assertEquals(456L, migrated.playbackStateDao().get(TRACK_KEY)!!.durationMs)

            val session = ListeningSession(
                id = "driver-migration-session",
                trackKey = TRACK_KEY,
                workId = "local:RJ123456",
                trackTitle = "track 3",
                workTitle = "RJ123456",
                startedAt = 5_000L,
                endedAt = 65_000L,
                durationMs = 60_000L,
            )
            migrated.listeningSessionDao().upsert(session)
            assertEquals(listOf(session), migrated.listeningSessionDao().getAll())
        } finally {
            migrated.close()
        }
    }

    private fun openDatabase(): OneAsmrDatabase =
        Room.databaseBuilder(context, OneAsmrDatabase::class.java, DATABASE_NAME)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(
                OneAsmrDatabase.MIGRATION_1_2,
                OneAsmrDatabase.MIGRATION_2_3,
                OneAsmrDatabase.MIGRATION_3_4,
                OneAsmrDatabase.MIGRATION_4_5,
            )
            .addCallback(FtsCallback())
            .build()

    private fun downgradeCurrentSchemaToV4() {
        BundledSQLiteDriver().open(context.getDatabasePath(DATABASE_NAME).absolutePath).use { connection ->
            connection.execSql("DROP INDEX IF EXISTS index_listening_session_startedAt")
            connection.execSql("DROP INDEX IF EXISTS index_listening_session_workId")
            connection.execSql("DROP TABLE listening_session")
            connection.execSql("UPDATE room_master_table SET identity_hash = '$V4_IDENTITY_HASH'")
            connection.execSql("PRAGMA user_version = 4")
        }
    }

    private fun SQLiteConnection.execSql(sql: String) {
        prepare(sql).use { statement -> statement.step() }
    }

    private companion object {
        const val DATABASE_NAME = "oneasmr_driver_migration_test.db"
        const val TRACK_KEY = "local:RJ123456:3"

        /** identityHash exported in app/schemas/com.oneasmr.app.data.local.OneAsmrDatabase/4.json. */
        const val V4_IDENTITY_HASH = "f552b30a6916eef769637bf0b45487cc"
    }
}
