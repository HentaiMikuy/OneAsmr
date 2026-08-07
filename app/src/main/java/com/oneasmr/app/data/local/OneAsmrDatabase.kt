package com.oneasmr.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * Local Room database (migration baseline version 1).
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
    version = 1,
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
         * Production builder: BundledSQLiteDriver (FTS5) + FTS index callback.
         * Tests build their own in-memory instances without the bundled driver
         * so the degraded fallback path is exercised on the JVM.
         */
        fun build(context: Context): OneAsmrDatabase =
            Room.databaseBuilder(context, OneAsmrDatabase::class.java, NAME)
                .setDriver(BundledSQLiteDriver())
                .addCallback(FtsCallback())
                .build()
    }
}
