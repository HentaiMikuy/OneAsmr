package com.oneasmr.app.data.scanner

import androidx.room.withTransaction
import com.oneasmr.app.data.local.KeySpec
import com.oneasmr.app.data.local.OneAsmrDatabase
import com.oneasmr.app.data.local.ScrapeStatus
import com.oneasmr.app.data.local.SortKeyGenerator
import com.oneasmr.app.data.local.Work

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
     * - Unknown id -> a fresh row with scrapeStatus=NOT_SCRAPED and the
     *   titleSortKey generated from the folder name (FTS index is maintained
     *   by the work_fts triggers on INSERT/UPDATE).
     * - Known id (rescan) -> location/title/titleSortKey/updatedAt refreshed,
     *   scrape metadata (scrapeStatus, circle, rating...) PRESERVED so a
     *   later rescan cannot clobber scraped data.
     */
    suspend fun commitWork(work: WorkCandidate, rootFolderUri: String, nowEpochMillis: Long)
}

/**
 * Room-backed [ScanPersister]. Every work is one `withTransaction` — atomic
 * by construction. (Upsert of a single row would be atomic on its own; the
 * transaction also makes the read-modify-write of the merge atomic.)
 */
class RoomScanPersister(
    private val db: OneAsmrDatabase,
    private val sortKeyGenerator: (String) -> String = SortKeyGenerator::generate,
) : ScanPersister {

    override suspend fun commitWork(work: WorkCandidate, rootFolderUri: String, nowEpochMillis: Long) {
        db.withTransaction {
            val id = KeySpec.workId(KeySpec.LOCAL_SOURCE, work.rjCode)
            val dao = db.workDao()
            val existing = dao.getById(id)
            val merged = if (existing == null) {
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
                    addedAt = nowEpochMillis,
                    updatedAt = nowEpochMillis,
                )
            } else {
                existing.copy(
                    rootFolderUri = rootFolderUri,
                    relativeDir = work.relativeDir,
                    title = work.displayName,
                    titleSortKey = sortKeyGenerator(work.displayName),
                    updatedAt = nowEpochMillis,
                )
            }
            dao.upsert(merged)
        }
    }
}
