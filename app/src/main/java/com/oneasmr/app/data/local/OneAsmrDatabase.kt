package com.oneasmr.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * Local Room database. Migration baseline version 1 (committed schema
 * app/schemas/.../1.json); version 2 adds `work.missing` (Task 7 incremental
 * rescan + missing-work detection).
 *
 * Opened with [BundledSQLiteDriver] in production — the bundled SQLite
 * (>= 3.42) is the only reliable source of FTS5 + the trigram tokenizer; the
 * Android framework SQLite has no FTS5 module compiled in. The [FtsCallback]
 * creates the FTS index; on failure the database still opens and search
 * degrades to LIKE (see [FtsStatus]).
 */
@Database(
    entities = [
        Work::class,
        Circle::class,
        Tag::class,
        Va::class,
        WorkTag::class,
        WorkVa::class,
        Review::class,
        PlaybackState::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class OneAsmrDatabase : RoomDatabase() {

    abstract fun workDao(): WorkDao
    abstract fun circleDao(): CircleDao
    abstract fun tagDao(): TagDao
    abstract fun vaDao(): VaDao
    abstract fun workTagDao(): WorkTagDao
    abstract fun workVaDao(): WorkVaDao
    abstract fun reviewDao(): ReviewDao
    abstract fun playbackStateDao(): PlaybackStateDao

    companion object {
        const val NAME = "oneasmr.db"

        /**
         * v1 → v2: `work.missing` column (Task 7). NOT NULL needs a DEFAULT
         * for ALTER on a populated table; the entity declares the same
         * defaultValue "0" so Room's post-migration schema validation matches
         * the exported app/schemas/.../2.json. The FTS5 triggers are scoped to
         * `work.title` (see FtsIndex) — adding a column touches neither them
         * nor the FTS virtual tables, so no FTS migration is required.
         *
         * Non-destructive: keeps every row; review / playback_state tables are
         * untouched by design (missing works are never auto-deleted).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE work ADD COLUMN missing INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Production builder: BundledSQLiteDriver (FTS5) + FTS index callback.
         * Tests build their own in-memory instances without the bundled driver
         * so the degraded fallback path is exercised on the JVM.
         */
        fun build(context: Context): OneAsmrDatabase =
            Room.databaseBuilder(context, OneAsmrDatabase::class.java, NAME)
                .setDriver(BundledSQLiteDriver())
                .addMigrations(MIGRATION_1_2)
                .addCallback(FtsCallback())
                .build()
    }
}
