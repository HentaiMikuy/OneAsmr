package com.oneasmr.app.data.local

import android.util.Log
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Runtime availability flag for the FTS5 trigram index.
 *
 * Set by [FtsCallback] when the database is opened:
 * - true  -> the FTS5 tables were created (BundledSQLiteDriver with a bundled
 *   SQLite >= 3.42 is active, trigram tokenizer present).
 * - false -> FTS creation failed (framework SQLite has no FTS5 module; this is
 *   the Robolectric/JVM-test and any misconfigured-device path) and search
 *   degrades to LIKE (see [WorkDao.search]).
 *
 * A plain object is used instead of DI because Room DAOs cannot take arbitrary
 * constructor dependencies; later tasks (13 search UI) should read this flag
 * only, never mutate it.
 */
object FtsStatus {
    @Volatile
    var available: Boolean = false
        internal set
}

/**
 * Raw SQL for the FTS5 trigram virtual tables and their maintenance triggers.
 *
 * Four separate FTS tables index work.title / circle.name / tag.name / va.name
 * and search UNIONs across them (see [WorkDao]). Each FTS row carries the
 * content-table rowid so queries join back through rowid. Triggers keep the
 * index in sync with the base tables.
 *
 * The FTS5 special 'delete' command (`INSERT INTO t(t, rowid, c1...) VALUES
 * ('delete', ...)`) is NOT used: with the trigram tokenizer it fails with
 * SQLITE_ERROR on the bundled SQLite (verified on-device, Task 8). Deletion
 * uses `DELETE FROM fts WHERE rowid = old.rowid`, which is supported on
 * contentful FTS5 tables.
 */
object FtsIndex {

    /**
     * Complete, self-contained statements. Each element is ONE statement —
     * never split on ';' at runtime: trigger bodies contain their own
     * semicolons, and the bundled driver's prepare() fails with
     * "incomplete input" on a truncated trigger.
     */
    val STATEMENTS: List<String> = listOf(
        "CREATE VIRTUAL TABLE IF NOT EXISTS work_fts USING fts5(title, tokenize='trigram')",
        "CREATE VIRTUAL TABLE IF NOT EXISTS circle_fts USING fts5(name, tokenize='trigram')",
        "CREATE VIRTUAL TABLE IF NOT EXISTS tag_fts USING fts5(name, tokenize='trigram')",
        "CREATE VIRTUAL TABLE IF NOT EXISTS va_fts USING fts5(name, tokenize='trigram')",
        """
            CREATE TRIGGER IF NOT EXISTS work_fts_ai AFTER INSERT ON work BEGIN
                INSERT INTO work_fts(rowid, title) VALUES (new.rowid, new.title);
            END
        """,
        """
            CREATE TRIGGER IF NOT EXISTS work_fts_ad AFTER DELETE ON work BEGIN
                DELETE FROM work_fts WHERE rowid = old.rowid;
            END
        """,
        """
            CREATE TRIGGER IF NOT EXISTS work_fts_au AFTER UPDATE OF title ON work BEGIN
                DELETE FROM work_fts WHERE rowid = old.rowid;
                INSERT INTO work_fts(rowid, title) VALUES (new.rowid, new.title);
            END
        """,
        """
            CREATE TRIGGER IF NOT EXISTS circle_fts_ai AFTER INSERT ON circle BEGIN
                INSERT INTO circle_fts(rowid, name) VALUES (new.rowid, new.name);
            END
        """,
        """
            CREATE TRIGGER IF NOT EXISTS circle_fts_ad AFTER DELETE ON circle BEGIN
                DELETE FROM circle_fts WHERE rowid = old.rowid;
            END
        """,
        """
            CREATE TRIGGER IF NOT EXISTS circle_fts_au AFTER UPDATE OF name ON circle BEGIN
                DELETE FROM circle_fts WHERE rowid = old.rowid;
                INSERT INTO circle_fts(rowid, name) VALUES (new.rowid, new.name);
            END
        """,
        """
            CREATE TRIGGER IF NOT EXISTS tag_fts_ai AFTER INSERT ON tag BEGIN
                INSERT INTO tag_fts(rowid, name) VALUES (new.rowid, new.name);
            END
        """,
        """
            CREATE TRIGGER IF NOT EXISTS tag_fts_ad AFTER DELETE ON tag BEGIN
                DELETE FROM tag_fts WHERE rowid = old.rowid;
            END
        """,
        """
            CREATE TRIGGER IF NOT EXISTS va_fts_ai AFTER INSERT ON va BEGIN
                INSERT INTO va_fts(rowid, name) VALUES (new.rowid, new.name);
            END
        """,
        """
            CREATE TRIGGER IF NOT EXISTS va_fts_ad AFTER DELETE ON va BEGIN
                DELETE FROM va_fts WHERE rowid = old.rowid;
            END
        """,
    )

    private const val TAG = "OneAsmrFts"

    private fun runSql(connection: SQLiteConnection, sql: String) {
        connection.prepare(sql).use { it.step() }
    }

    /** Executes the DDL on a new-API connection (BundledSQLiteDriver path). */
    fun init(connection: SQLiteConnection) {
        try {
            STATEMENTS.forEach { runSql(connection, it) }
            FtsStatus.available = true
            Log.i(TAG, "FTS5 trigram index created (bundled sqlite driver active)")
        } catch (e: Exception) {
            FtsStatus.available = false
            Log.w(TAG, "FTS5 init failed (${e.message}); falling back to LIKE search", e)
        }
    }

    /** Executes the DDL on the legacy-API database (framework driver path). */
    fun init(db: SupportSQLiteDatabase) {
        try {
            STATEMENTS.forEach { db.execSQL(it) }
            FtsStatus.available = true
            Log.i(TAG, "FTS5 trigram index created")
        } catch (e: Exception) {
            FtsStatus.available = false
            Log.w(TAG, "FTS5 init failed (${e.message}); falling back to LIKE search", e)
        }
    }
}

/**
 * Room callback that creates the FTS5 index on first database creation.
 *
 * Both onCreate overloads are overridden because the new driver API path
 * (androidx.sqlite.SQLiteConnection, used by BundledSQLiteDriver) and the
 * legacy path (framework driver, used by Robolectric/JVM tests) dispatch to
 * different overloads. Either failure is caught here so the database still
 * opens — search then degrades to LIKE (degraded-fallback marker in logcat).
 */
class FtsCallback : RoomDatabase.Callback() {

    override fun onCreate(connection: SQLiteConnection) = FtsIndex.init(connection)

    override fun onCreate(db: SupportSQLiteDatabase) = FtsIndex.init(db)
}
