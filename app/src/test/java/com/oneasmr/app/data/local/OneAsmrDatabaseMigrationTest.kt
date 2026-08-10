package com.oneasmr.app.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1 → v2 migration test (Task 7 adds `work.missing`): a REAL v1 database
 * file is built with the v1 DDL transcribed verbatim from the committed
 * baseline app/schemas/com.oneasmr.app.data.local.OneAsmrDatabase/1.json,
 * seeded with a work + review + playback row, then opened through Room v2
 * with [OneAsmrDatabase.MIGRATION_1_2]. Room itself runs the migration and
 * validates the migrated schema against the exported 2.json — a wrong ALTER
 * (or a missing one) makes this test throw IllegalStateException.
 *
 * MigrationTestHelper was deliberately NOT used: it loads schema JSONs from
 * the instrumentation APK assets, which would require wiring app/schemas into
 * the unit-test assets (a build-file change). Transcribing the v1 DDL into
 * this test gives the same coverage for a single-ALTER migration without
 * touching the locked build config. NOTE: Room 2.8.4 validates INDICES too
 * (not just tables/columns/keys), so every CREATE INDEX from 1.json is
 * transcribed as well — omitting them fails with "Migration didn't properly
 * handle: work".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OneAsmrDatabaseMigrationTest {

    private fun tempDbFile(): File =
        File.createTempFile("oneasmr_migration_test", ".db").apply { deleteOnExit() }

    @Test
    fun `migrating v1 to v2 preserves every row and defaults missing to false`() {
        val file = tempDbFile()
        createV1Database(file)

        // 数据库类已是 v4:注册全链迁移(Room 需要 1→当前版本的完整路径),
        // 本测试仍聚焦 1→2 加列后的数据保全断言。
        val db = Room.databaseBuilder(
            RuntimeEnvironment.getApplication(),
            OneAsmrDatabase::class.java,
            file.absolutePath,
        )
            .addMigrations(
                OneAsmrDatabase.MIGRATION_1_2,
                OneAsmrDatabase.MIGRATION_2_3,
                OneAsmrDatabase.MIGRATION_3_4,
            )
            .build()

        runBlocking {
            val work = db.workDao().getById("local:RJ123456")!!
            // All v1 data survived the ALTER.
            assertEquals("AudioBooks/RJ123456", work.relativeDir)
            assertEquals("RJ123456", work.title)
            assertEquals("rj123456", work.titleSortKey)
            assertEquals("circle-1", work.circleId)
            assertEquals(ScrapeStatus.OK, work.scrapeStatus)
            assertEquals(4.52, work.rateAverage2dp!!, 0.001)
            assertEquals("2024-03-15", work.releaseDate)
            assertEquals(1_000L, work.addedAt)
            // The new column exists and defaults to false (NOT a wiped library).
            assertFalse(work.missing)

            val review = db.reviewDao().getByWorkId("local:RJ123456")!!
            assertEquals(5, review.rating)
            assertEquals(ProgressState.listened, review.progress)

            val playback = db.playbackStateDao().get("local:RJ123456:3")!!
            assertEquals(123L, playback.positionMs)
            assertEquals(456L, playback.durationMs)

            // The migrated database is fully writable at v2.
            db.workDao().markMissing(listOf("local:RJ123456"), now = 2_000L)
            assertTrue(db.workDao().getById("local:RJ123456")!!.missing)
        }
        db.close()
    }

    /**
     * v1 → v4 全链(1→2 加列,2→3 建单文件库三表,3→4 加 ageRating):
     * 老数据原样,新表在迁移后立即可写,ageRating 老行自动 NULL。
     * Room 迁移后按 4.json 校验全部表+索引 —— 迁移 DDL 与导出 schema
     * 不符会在 build() 后首次访问时抛 IllegalStateException。
     */
    @Test
    fun `migrating v1 through v4 keeps data and creates usable single-file tables`() {
        val file = tempDbFile()
        createV1Database(file)

        val db = Room.databaseBuilder(
            RuntimeEnvironment.getApplication(),
            OneAsmrDatabase::class.java,
            file.absolutePath,
        )
            .addMigrations(
                OneAsmrDatabase.MIGRATION_1_2,
                OneAsmrDatabase.MIGRATION_2_3,
                OneAsmrDatabase.MIGRATION_3_4,
            )
            .build()

        runBlocking {
            // 老数据穿过三级迁移原样可读。
            assertEquals("RJ123456", db.workDao().getById("local:RJ123456")!!.title)
            assertEquals(123L, db.playbackStateDao().get("local:RJ123456:3")!!.positionMs)
            // 手动年龄分级列存在且老行未设置。
            assertNull(db.workDao().getById("local:RJ123456")!!.ageRating)

            // 新表可写可查:文件行 + 系统收藏夹 + 成员关系。
            val fileId = db.singleFileDao().insert(
                SingleFile(
                    rootFolderUri = "content://tree/singles",
                    relativePath = "【ASMR】耳かき [dQw4w9WgXcQ].mp4",
                    fileName = "【ASMR】耳かき [dQw4w9WgXcQ].mp4",
                    displayTitle = "【ASMR】耳かき",
                    titleSortKey = "asmrmimikaki",
                    kind = SingleFileKind.VIDEO,
                    youtubeId = "dQw4w9WgXcQ",
                    channel = null,
                    uploadDate = null,
                    durationMs = null,
                    sizeBytes = 100L,
                    lastModified = 0L,
                    thumbSource = null,
                    sourceUrl = null,
                    scrapeStatus = ScrapeStatus.NOT_SCRAPED,
                    missing = false,
                    addedAt = 3_000L,
                    updatedAt = 3_000L,
                ),
            )
            assertTrue(fileId > 0)
            db.collectionDao().ensureFavorites(now = 3_000L)
            db.collectionDao().addItem(
                CollectionItem(Collection.FAVORITES_ID, fileId, sortIndex = 1, addedAt = 3_000L),
            )
            val favorites = db.collectionDao().collectionsWithCountFlow().first().single()
            assertTrue(favorites.collection.isSystem)
            assertEquals(1, favorites.fileCount)
        }
        db.close()
    }

    /**
     * v3 → v4: `work.ageRating` nullable TEXT 加列。v3 库按 3.json 的
     * createSql 逐字转录(含全部索引 —— Room 迁移后校验含索引),老行
     * 迁移后 ageRating 自动为 NULL,新写入可设置/清除该列。
     */
    @Test
    fun `migrating v3 to v4 adds nullable ageRating and keeps existing rows`() {
        val file = tempDbFile()
        createV3Database(file)

        val db = Room.databaseBuilder(
            RuntimeEnvironment.getApplication(),
            OneAsmrDatabase::class.java,
            file.absolutePath,
        )
            .addMigrations(OneAsmrDatabase.MIGRATION_3_4)
            .build()

        runBlocking {
            // 老行原样,新增列自动为 NULL(未设置)。
            val work = db.workDao().getById("local:RJ123456")!!
            assertEquals("RJ123456", work.title)
            assertNull(work.ageRating)

            // 新列可写:设置 → 读回 → 清除。
            db.workDao().updateAgeRating("local:RJ123456", AgeRating.R18, 2_000L)
            assertEquals(AgeRating.R18, db.workDao().getById("local:RJ123456")!!.ageRating)
            assertEquals(2_000L, db.workDao().getById("local:RJ123456")!!.updatedAt)
            db.workDao().updateAgeRating("local:RJ123456", null, 3_000L)
            assertNull(db.workDao().getById("local:RJ123456")!!.ageRating)

            // 全新插入可直接带 ageRating。
            db.workDao().upsert(
                Work(
                    id = "local:RJ654321",
                    rootFolderUri = "content://tree/rootA",
                    relativeDir = "AudioBooks/RJ654321",
                    title = "RJ654321",
                    titleSortKey = "rj654321",
                    circleId = null,
                    nsfw = false,
                    ageRating = AgeRating.R15,
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
                    addedAt = 4_000L,
                    updatedAt = 4_000L,
                ),
            )
            assertEquals(AgeRating.R15, db.workDao().getById("local:RJ654321")!!.ageRating)
        }
        db.close()
    }

    // ---------- v1 fixture ----------

    /** Transcribed from app/schemas/.../1.json (createSql of each entity, ${TABLE_NAME} resolved). */
    private fun createV1Database(file: File) {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.version = 1
        V1_DDL.forEach { db.execSQL(it) }
        db.execSQL(
            "INSERT INTO circle (id, name, nameSortKey) VALUES ('circle-1', 'シロクマ工房', 'shirokumakoubou')",
        )
        db.execSQL(
            "INSERT INTO work (id, rootFolderUri, relativeDir, title, titleSortKey, circleId, nsfw, " +
                "releaseDate, dlCount, price, reviewCount, rateCount, rateAverage2dp, rateCountDetailJson, " +
                "seriesName, scrapeStatus, addedAt, updatedAt) " +
                "VALUES ('local:RJ123456', 'content://tree/rootA', 'AudioBooks/RJ123456', 'RJ123456', " +
                "'rj123456', 'circle-1', 0, '2024-03-15', 100, 1650, 10, 50, 4.52, " +
                "'{\"1\":1,\"2\":2,\"3\":3,\"4\":20,\"5\":24}', 'シリーズ', 'OK', 1000, 1000)",
        )
        db.execSQL(
            "INSERT INTO review (workId, rating, reviewText, progress, updatedAt) " +
                "VALUES ('local:RJ123456', 5, 'kept across migration', 'listened', 1000)",
        )
        db.execSQL(
            "INSERT INTO playback_state (trackKey, positionMs, durationMs, updatedAt) " +
                "VALUES ('local:RJ123456:3', 123, 456, 1000)",
        )
        db.close()
    }

    // ---------- v3 fixture ----------

    /** Transcribed from app/schemas/.../3.json (v1 schema + work.missing + the single-file tables). */
    private fun createV3Database(file: File) {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.version = 3
        V3_DDL.forEach { db.execSQL(it) }
        db.execSQL(
            "INSERT INTO circle (id, name, nameSortKey) VALUES ('circle-1', 'シロクマ工房', 'shirokumakoubou')",
        )
        db.execSQL(
            "INSERT INTO work (id, rootFolderUri, relativeDir, title, titleSortKey, circleId, nsfw, " +
                "releaseDate, dlCount, price, reviewCount, rateCount, rateAverage2dp, rateCountDetailJson, " +
                "seriesName, scrapeStatus, missing, addedAt, updatedAt) " +
                "VALUES ('local:RJ123456', 'content://tree/rootA', 'AudioBooks/RJ123456', 'RJ123456', " +
                "'rj123456', 'circle-1', 0, '2024-03-15', 100, 1650, 10, 50, 4.52, " +
                "'{\"1\":1,\"2\":2,\"3\":3,\"4\":20,\"5\":24}', 'シリーズ', 'OK', 0, 1000, 1000)",
        )
        db.close()
    }

    private companion object {
        val V1_DDL: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS `work` (`id` TEXT NOT NULL, `rootFolderUri` TEXT NOT NULL, " +
                "`relativeDir` TEXT NOT NULL, `title` TEXT NOT NULL, `titleSortKey` TEXT NOT NULL, " +
                "`circleId` TEXT, `nsfw` INTEGER NOT NULL, `releaseDate` TEXT, `dlCount` INTEGER, " +
                "`price` INTEGER, `reviewCount` INTEGER, `rateCount` INTEGER, `rateAverage2dp` REAL, " +
                "`rateCountDetailJson` TEXT, `seriesName` TEXT, `scrapeStatus` TEXT NOT NULL, " +
                "`addedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`circleId`) REFERENCES `circle`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)",
            "CREATE TABLE IF NOT EXISTS `circle` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`nameSortKey` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `tag` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `va` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`nameSortKey` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `work_tag` (`workId` TEXT NOT NULL, `tagId` TEXT NOT NULL, " +
                "PRIMARY KEY(`workId`, `tagId`), FOREIGN KEY(`workId`) REFERENCES `work`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`tagId`) REFERENCES `tag`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE TABLE IF NOT EXISTS `work_va` (`workId` TEXT NOT NULL, `vaId` TEXT NOT NULL, " +
                "PRIMARY KEY(`workId`, `vaId`), FOREIGN KEY(`workId`) REFERENCES `work`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`vaId`) REFERENCES `va`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE TABLE IF NOT EXISTS `review` (`workId` TEXT NOT NULL, `rating` INTEGER, " +
                "`reviewText` TEXT, `progress` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`workId`))",
            "CREATE TABLE IF NOT EXISTS `playback_state` (`trackKey` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, " +
                "`durationMs` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`trackKey`))",
            // Indices: Room validates them on migration, so ALL of 1.json's.
            "CREATE INDEX IF NOT EXISTS `index_work_circleId` ON `work` (`circleId`)",
            "CREATE INDEX IF NOT EXISTS `index_work_releaseDate` ON `work` (`releaseDate`)",
            "CREATE INDEX IF NOT EXISTS `index_work_dlCount` ON `work` (`dlCount`)",
            "CREATE INDEX IF NOT EXISTS `index_work_reviewCount` ON `work` (`reviewCount`)",
            "CREATE INDEX IF NOT EXISTS `index_work_price` ON `work` (`price`)",
            "CREATE INDEX IF NOT EXISTS `index_work_rateAverage2dp` ON `work` (`rateAverage2dp`)",
            "CREATE INDEX IF NOT EXISTS `index_work_titleSortKey` ON `work` (`titleSortKey`)",
            "CREATE INDEX IF NOT EXISTS `index_circle_nameSortKey` ON `circle` (`nameSortKey`)",
            "CREATE INDEX IF NOT EXISTS `index_va_nameSortKey` ON `va` (`nameSortKey`)",
            "CREATE INDEX IF NOT EXISTS `index_work_tag_tagId` ON `work_tag` (`tagId`)",
            "CREATE INDEX IF NOT EXISTS `index_work_va_vaId` ON `work_va` (`vaId`)",
        )

        /** Transcribed from app/schemas/.../3.json — v1's tables + work.missing + the three single-file tables. */
        val V3_DDL: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS `work` (`id` TEXT NOT NULL, `rootFolderUri` TEXT NOT NULL, " +
                "`relativeDir` TEXT NOT NULL, `title` TEXT NOT NULL, `titleSortKey` TEXT NOT NULL, " +
                "`circleId` TEXT, `nsfw` INTEGER NOT NULL, `releaseDate` TEXT, `dlCount` INTEGER, " +
                "`price` INTEGER, `reviewCount` INTEGER, `rateCount` INTEGER, `rateAverage2dp` REAL, " +
                "`rateCountDetailJson` TEXT, `seriesName` TEXT, `scrapeStatus` TEXT NOT NULL, " +
                "`missing` INTEGER NOT NULL DEFAULT 0, `addedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`), FOREIGN KEY(`circleId`) REFERENCES `circle`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE SET NULL )",
            "CREATE TABLE IF NOT EXISTS `circle` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`nameSortKey` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `tag` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `va` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`nameSortKey` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `work_tag` (`workId` TEXT NOT NULL, `tagId` TEXT NOT NULL, " +
                "PRIMARY KEY(`workId`, `tagId`), FOREIGN KEY(`workId`) REFERENCES `work`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`tagId`) REFERENCES `tag`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `work_va` (`workId` TEXT NOT NULL, `vaId` TEXT NOT NULL, " +
                "PRIMARY KEY(`workId`, `vaId`), FOREIGN KEY(`workId`) REFERENCES `work`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`vaId`) REFERENCES `va`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `review` (`workId` TEXT NOT NULL, `rating` INTEGER, " +
                "`reviewText` TEXT, `progress` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`workId`))",
            "CREATE TABLE IF NOT EXISTS `playback_state` (`trackKey` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, " +
                "`durationMs` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`trackKey`))",
            "CREATE TABLE IF NOT EXISTS `single_file` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`rootFolderUri` TEXT NOT NULL, `relativePath` TEXT NOT NULL, `fileName` TEXT NOT NULL, " +
                "`displayTitle` TEXT NOT NULL, `titleSortKey` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`youtubeId` TEXT, `channel` TEXT, `uploadDate` TEXT, `durationMs` INTEGER, " +
                "`sizeBytes` INTEGER NOT NULL, `lastModified` INTEGER NOT NULL, `thumbSource` TEXT, " +
                "`sourceUrl` TEXT, `scrapeStatus` TEXT NOT NULL, `missing` INTEGER NOT NULL DEFAULT 0, " +
                "`addedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `collection` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `isSystem` INTEGER NOT NULL DEFAULT 0, `sortIndex` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `collection_item` (`collectionId` INTEGER NOT NULL, `fileId` INTEGER NOT NULL, " +
                "`sortIndex` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`collectionId`, `fileId`), FOREIGN KEY(`collectionId`) REFERENCES `collection`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`fileId`) REFERENCES `single_file`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
            // Indices: Room validates them on migration, so ALL of 3.json's.
            "CREATE INDEX IF NOT EXISTS `index_work_circleId` ON `work` (`circleId`)",
            "CREATE INDEX IF NOT EXISTS `index_work_releaseDate` ON `work` (`releaseDate`)",
            "CREATE INDEX IF NOT EXISTS `index_work_dlCount` ON `work` (`dlCount`)",
            "CREATE INDEX IF NOT EXISTS `index_work_reviewCount` ON `work` (`reviewCount`)",
            "CREATE INDEX IF NOT EXISTS `index_work_price` ON `work` (`price`)",
            "CREATE INDEX IF NOT EXISTS `index_work_rateAverage2dp` ON `work` (`rateAverage2dp`)",
            "CREATE INDEX IF NOT EXISTS `index_work_titleSortKey` ON `work` (`titleSortKey`)",
            "CREATE INDEX IF NOT EXISTS `index_circle_nameSortKey` ON `circle` (`nameSortKey`)",
            "CREATE INDEX IF NOT EXISTS `index_va_nameSortKey` ON `va` (`nameSortKey`)",
            "CREATE INDEX IF NOT EXISTS `index_work_tag_tagId` ON `work_tag` (`tagId`)",
            "CREATE INDEX IF NOT EXISTS `index_work_va_vaId` ON `work_va` (`vaId`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_single_file_rootFolderUri_relativePath` " +
                "ON `single_file` (`rootFolderUri`, `relativePath`)",
            "CREATE INDEX IF NOT EXISTS `index_single_file_youtubeId` ON `single_file` (`youtubeId`)",
            "CREATE INDEX IF NOT EXISTS `index_single_file_titleSortKey` ON `single_file` (`titleSortKey`)",
            "CREATE INDEX IF NOT EXISTS `index_single_file_addedAt` ON `single_file` (`addedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_collection_item_fileId` ON `collection_item` (`fileId`)",
        )
    }
}
