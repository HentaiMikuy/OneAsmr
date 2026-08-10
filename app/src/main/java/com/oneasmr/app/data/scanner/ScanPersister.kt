package com.oneasmr.app.data.scanner

import com.oneasmr.app.data.local.KeySpec
import com.oneasmr.app.data.local.OneAsmrDatabase
import com.oneasmr.app.data.local.ScrapeStatus
import com.oneasmr.app.data.local.SortKeyGenerator
import com.oneasmr.app.data.local.Work

/**
 * Result of one [ScanPersister.commitWork]: the row-level outcome, used by the
 * rescan diff (Task 7) to build the added/updated/unchanged summary without
 * re-reading the database.
 */
enum class CommitKind { INSERTED, UPDATED, UNCHANGED }

/**
 * Commits discovered works into the database. The interface keeps the worker
 * decoupled from Room; [RoomScanPersister] is the production implementation.
 */
interface ScanPersister {

    /**
     * Commits ONE work atomically (single Room transaction). A cancelled scan
     * keeps every already-committed work and never leaves half rows.
     *
     * Merge semantics ("新作品 scrapeStatus=NOT_SCRAPED" — NEW works only):
     * - Unknown id -> a fresh row with scrapeStatus=NOT_SCRAPED, missing=false
     *   and the titleSortKey generated from the folder name (FTS index is
     *   maintained by the work_fts triggers on INSERT/UPDATE).
     * - Known id (rescan) -> location refreshed and missing cleared (the
     *   folder was seen again — Task 7); title/titleSortKey refresh from the
     *   folder name ONLY while un-scraped — scraped works keep their scraped
     *   title, and ALL scrape metadata (scrapeStatus, circle, rating...) is
     *   PRESERVED so a later rescan cannot clobber scraped data. When nothing
     *   location/title/missing-wise changed, the row is left completely
     *   untouched ([CommitKind.UNCHANGED] — no updatedAt bump, no FTS trigger
     *   churn).
     */
    suspend fun commitWork(work: WorkCandidate, rootFolderUri: String, nowEpochMillis: Long): CommitKind
}

/**
 * Room-backed [ScanPersister]. Per-work atomicity comes from the DAO layer:
 * with BundledSQLiteDriver every generated write already runs in its own
 * driver-native transaction (Room's `performSuspending` wraps writes in
 * `PooledConnectionImpl.withTransaction`), so a cancelled scan keeps exactly
 * the already-committed works — never half rows. An EXPLICIT
 * `RoomDatabase.withTransaction` is deliberately NOT used: its framework
 * implementation calls `getOpenHelper()`, which throws
 * "no SupportSQLiteOpenHelper" when a SQLiteDriver is configured (caught by
 * the Task 8 on-device probe).
 */
class RoomScanPersister(
    private val db: OneAsmrDatabase,
    private val sortKeyGenerator: (String) -> String = SortKeyGenerator::generate,
) : ScanPersister {

    override suspend fun commitWork(
        work: WorkCandidate,
        rootFolderUri: String,
        nowEpochMillis: Long,
    ): CommitKind {
        val id = KeySpec.workId(KeySpec.LOCAL_SOURCE, work.rjCode)
        val dao = db.workDao()
        val existing = dao.getById(id)
        return if (existing == null) {
            dao.upsert(
                Work(
                    id = id,
                    rootFolderUri = rootFolderUri,
                    relativeDir = work.relativeDir,
                    title = work.displayName,
                    titleSortKey = sortKeyGenerator(work.displayName),
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
                    addedAt = nowEpochMillis,
                    updatedAt = nowEpochMillis,
                ),
            )
            CommitKind.INSERTED
        } else {
            // 已刮削(OK)的行保留刮削标题:文件夹名(RJ号)只是未刮削时的
            // 占位显示,重扫刷新位置绝不能把 DLsite/asmr.one 拿到的真标题
            // 打回 RJ 号(否则每次重扫都因"标题≠文件夹名"误判 UPDATED
            // 并覆写 —— 用户实测踩中)。
            val keepScrapedTitle = existing.scrapeStatus == ScrapeStatus.OK
            val newTitle = if (keepScrapedTitle) existing.title else work.displayName
            val newSortKey = if (keepScrapedTitle) existing.titleSortKey else sortKeyGenerator(work.displayName)
            val changed = existing.missing ||
                existing.rootFolderUri != rootFolderUri ||
                existing.relativeDir != work.relativeDir ||
                existing.title != newTitle ||
                existing.titleSortKey != newSortKey
            if (!changed) {
                CommitKind.UNCHANGED
            } else {
                dao.upsert(
                    existing.copy(
                        rootFolderUri = rootFolderUri,
                        relativeDir = work.relativeDir,
                        title = newTitle,
                        titleSortKey = newSortKey,
                        missing = false,
                        updatedAt = nowEpochMillis,
                    ),
                )
                CommitKind.UPDATED
            }
        }
    }
}
