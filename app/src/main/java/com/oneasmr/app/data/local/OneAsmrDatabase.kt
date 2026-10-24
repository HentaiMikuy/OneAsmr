package com.oneasmr.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * Local Room database. Migration baseline version 1 (committed schema
 * app/schemas/.../1.json); version 2 adds `work.missing` (Task 7 incremental
 * rescan + missing-work detection); version 3 adds the single-file library
 * (single_file / collection / collection_item — 单文件库与收藏夹体系);
 * version 4 adds `work.ageRating` (手动年龄分级, 用户标记非刮削);
 * version 5 adds `listening_session` (本地实际播放时长与收听统计).
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
        ListeningSession::class,
        SingleFile::class,
        Collection::class,
        CollectionItem::class,
    ],
    version = 5,
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
    abstract fun listeningSessionDao(): ListeningSessionDao
    abstract fun singleFileDao(): SingleFileDao
    abstract fun collectionDao(): CollectionDao

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
        val MIGRATION_1_2 = sqlMigration(
            1,
            2,
            "ALTER TABLE work ADD COLUMN missing INTEGER NOT NULL DEFAULT 0",
        )

        /**
         * v2 → v3: 单文件库三张新表(single_file / collection /
         * collection_item)。纯增量 —— 不触碰任何既有表/索引/FTS,老数据
         * 零风险。CREATE 语句逐字对齐 Room 导出的 app/schemas/.../3.json
         * createSql(Room 迁移后校验含索引,见 OneAsmrDatabaseMigrationTest
         * 的教训),内置「收藏」夹由 [CollectionDao.ensureFavorites] 在仓库
         * 层幂等播种,不在迁移里插行(新装依赖 onCreate 路径,两边逻辑会
         * 分叉)。
         */
        val MIGRATION_2_3 = sqlMigration(
            2,
            3,
            "CREATE TABLE IF NOT EXISTS `single_file` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`rootFolderUri` TEXT NOT NULL, `relativePath` TEXT NOT NULL, " +
                "`fileName` TEXT NOT NULL, `displayTitle` TEXT NOT NULL, " +
                "`titleSortKey` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`youtubeId` TEXT, `channel` TEXT, `uploadDate` TEXT, " +
                "`durationMs` INTEGER, `sizeBytes` INTEGER NOT NULL, " +
                "`lastModified` INTEGER NOT NULL, `thumbSource` TEXT, " +
                "`sourceUrl` TEXT, `scrapeStatus` TEXT NOT NULL, " +
                "`missing` INTEGER NOT NULL DEFAULT 0, " +
                "`addedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_single_file_rootFolderUri_relativePath` " +
                "ON `single_file` (`rootFolderUri`, `relativePath`)",
            "CREATE INDEX IF NOT EXISTS `index_single_file_youtubeId` ON `single_file` (`youtubeId`)",
            "CREATE INDEX IF NOT EXISTS `index_single_file_titleSortKey` ON `single_file` (`titleSortKey`)",
            "CREATE INDEX IF NOT EXISTS `index_single_file_addedAt` ON `single_file` (`addedAt`)",
            "CREATE TABLE IF NOT EXISTS `collection` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `isSystem` INTEGER NOT NULL DEFAULT 0, " +
                "`sortIndex` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `collection_item` (" +
                "`collectionId` INTEGER NOT NULL, `fileId` INTEGER NOT NULL, " +
                "`sortIndex` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`collectionId`, `fileId`), " +
                "FOREIGN KEY(`collectionId`) REFERENCES `collection`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(`fileId`) REFERENCES `single_file`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX IF NOT EXISTS `index_collection_item_fileId` ON `collection_item` (`fileId`)",
        )

        /**
         * v3 → v4: `work.ageRating` column (手动年龄分级, nullable TEXT). 纯增量:
         * 不加 NOT NULL 就不需要 DEFAULT,老行自动为 NULL(未设置)。不触碰 FTS
         * 触发器和虚拟表。
         */
        val MIGRATION_3_4 = sqlMigration(
            3,
            4,
            "ALTER TABLE work ADD COLUMN ageRating TEXT",
        )

        /**
         * v4 → v5: append-only local listening history. Existing playback
         * positions remain untouched; historical positions are NOT converted
         * into fake listening durations, so statistics start with real plays.
         */
        val MIGRATION_4_5 = sqlMigration(
            4,
            5,
            "CREATE TABLE IF NOT EXISTS `listening_session` (" +
                "`id` TEXT NOT NULL, `trackKey` TEXT NOT NULL, `workId` TEXT NOT NULL, " +
                "`trackTitle` TEXT NOT NULL, `workTitle` TEXT NOT NULL, " +
                "`startedAt` INTEGER NOT NULL, `endedAt` INTEGER NOT NULL, " +
                "`durationMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_listening_session_startedAt` " +
                "ON `listening_session` (`startedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_listening_session_workId` " +
                "ON `listening_session` (`workId`)",
        )

        /**
         * Defines the same migration for Room's legacy and SQLiteDriver APIs.
         *
         * With a driver configured, Room 2.8 dispatches to
         * [Migration.migrate(SQLiteConnection)]. Its default implementation only
         * delegates to the legacy overload for a SupportSQLiteConnection; a real
         * BundledSQLiteDriver connection otherwise throws NotImplementedError.
         * Keeping both paths on the same SQL prevents upgrade-only crashes while
         * the Robolectric tests continue to exercise the legacy API.
         */
        private fun sqlMigration(
            startVersion: Int,
            endVersion: Int,
            vararg statements: String,
        ): Migration = object : Migration(startVersion, endVersion) {
            override fun migrate(db: SupportSQLiteDatabase) {
                statements.forEach { sql -> db.execSQL(sql) }
            }

            override fun migrate(connection: SQLiteConnection) {
                statements.forEach { sql ->
                    connection.prepare(sql).use { statement -> statement.step() }
                }
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .addCallback(FtsCallback())
                .build()
    }
}
