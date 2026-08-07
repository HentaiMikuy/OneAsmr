package com.oneasmr.app.data.local

import android.util.Log
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * Supported work ordering modes. The DAO layer never inlines these column
 * expressions anywhere else — paging queries are built from this enum.
 *
 * `rating` maps to `rateAverage2dp` (kikoeru "rating" == rate_average_2dp).
 */
enum class WorkOrder(val sql: String) {
    ID("work.id"),
    RELEASE_DATE("work.releaseDate"),
    RATING("work.rateAverage2dp"),
    DL_COUNT("work.dlCount"),
    REVIEW_COUNT("work.reviewCount"),
    PRICE("work.price"),
    RATE_AVERAGE_2DP("work.rateAverage2dp"),
    TITLE_SORT_KEY("work.titleSortKey"),
    /** Nondeterministic per page; task 13 layers a seeded session-stable order on top. */
    RANDOM("RANDOM()"),
}

/** One search hit: a work id plus the dimension (title/circle/tag/va) it matched in. */
data class WorkSearchHit(val workId: String, val matchedIn: String)

/**
 * FTS5 trigram search across the four indexed dimensions, UNIONed like the
 * LIKE fallback so both paths return the same shape.
 */
private const val FTS_SEARCH_SQL = """
    SELECT w.id AS workId, 'title' AS matchedIn
    FROM work_fts f JOIN work w ON w.rowid = f.rowid
    WHERE work_fts MATCH :query
    UNION
    SELECT w.id AS workId, 'circle' AS matchedIn
    FROM circle_fts f JOIN circle c ON c.rowid = f.rowid JOIN work w ON w.circleId = c.id
    WHERE circle_fts MATCH :query
    UNION
    SELECT w.id AS workId, 'tag' AS matchedIn
    FROM tag_fts f JOIN tag t ON t.rowid = f.rowid
    JOIN work_tag wt ON wt.tagId = t.id JOIN work w ON w.id = wt.workId
    WHERE tag_fts MATCH :query
    UNION
    SELECT w.id AS workId, 'va' AS matchedIn
    FROM va_fts f JOIN va v ON v.rowid = f.rowid
    JOIN work_va wv ON wv.vaId = v.id JOIN work w ON w.id = wv.workId
    WHERE va_fts MATCH :query
    ORDER BY workId
"""

/** LIKE fallback with the same shape; query is pre-escaped by [WorkDao.search]. */
private const val LIKE_SEARCH_SQL = """
    SELECT w.id AS workId, 'title' AS matchedIn
    FROM work w
    WHERE w.title LIKE '%' || :query || '%' ESCAPE '\'
    UNION
    SELECT w.id AS workId, 'circle' AS matchedIn
    FROM work w JOIN circle c ON w.circleId = c.id
    WHERE c.name LIKE '%' || :query || '%' ESCAPE '\'
    UNION
    SELECT w.id AS workId, 'tag' AS matchedIn
    FROM work w JOIN work_tag wt ON w.id = wt.workId JOIN tag t ON t.id = wt.tagId
    WHERE t.name LIKE '%' || :query || '%' ESCAPE '\'
    UNION
    SELECT w.id AS workId, 'va' AS matchedIn
    FROM work w JOIN work_va wv ON w.id = wv.workId JOIN va v ON v.id = wv.vaId
    WHERE v.name LIKE '%' || :query || '%' ESCAPE '\'
    ORDER BY workId
"""

private const val SEARCH_LOG_TAG = "OneAsmrSearch"

/**
 * Minimal [SupportSQLiteQuery] for dynamic SQL (paging order / FTS MATCH).
 * Positional args are bound 1-based like native SQLite.
 */
private class SimpleSupportQuery(
    private val statement: String,
    private val args: List<Any?> = emptyList(),
) : SupportSQLiteQuery {
    override val sql: String get() = statement
    override val argCount: Int get() = args.size

    override fun bindTo(program: SupportSQLiteProgram) {
        args.forEachIndexed { index, value ->
            val bindIndex = index + 1
            when (value) {
                null -> program.bindNull(bindIndex)
                is Long -> program.bindLong(bindIndex, value)
                is Int -> program.bindLong(bindIndex, value.toLong())
                is Double -> program.bindDouble(bindIndex, value)
                is Float -> program.bindDouble(bindIndex, value.toDouble())
                is Boolean -> program.bindLong(bindIndex, if (value) 1 else 0)
                is ByteArray -> program.bindBlob(bindIndex, value)
                else -> program.bindString(bindIndex, value.toString())
            }
        }
    }
}

/**
 * Work queries: paged listing with [WorkOrder], dual-path search
 * (FTS5 trigram when available, LIKE fallback otherwise), and reverse
 * lookups by circle/tag/va.
 */
@Dao
interface WorkDao {

    @Upsert
    suspend fun upsertAll(works: List<Work>)

    @Upsert
    suspend fun upsert(work: Work)

    @Query("SELECT * FROM work WHERE id = :id")
    suspend fun getById(id: String): Work?

    @Query("DELETE FROM work WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM work")
    suspend fun count(): Int

    @RawQuery
    suspend fun getPageRaw(query: SupportSQLiteQuery): List<Work>

    /**
     * One page of works ordered by [order], with an id tiebreaker so paging is
     * deterministic (except [WorkOrder.RANDOM], which is nondeterministic per
     * page by design). `descending` applies to all deterministic orders.
     */
    suspend fun getPage(
        order: WorkOrder,
        limit: Int,
        offset: Int,
        descending: Boolean = false,
    ): List<Work> {
        require(limit >= 0) { "limit must be >= 0, got $limit" }
        require(offset >= 0) { "offset must be >= 0, got $offset" }
        val direction = if (descending) " DESC" else " ASC"
        val orderBy = if (order == WorkOrder.RANDOM) order.sql else order.sql + direction
        val sql = "SELECT * FROM work ORDER BY $orderBy LIMIT $limit OFFSET $offset"
        return getPageRaw(SimpleSupportQuery(sql))
    }

    @RawQuery
    suspend fun searchRaw(query: SupportSQLiteQuery): List<WorkSearchHit>

    /**
     * Keyword search across work.title / circle.name / tag.name / va.name.
     * Primary path: FTS5 trigram MATCH when [FtsStatus.available]; on any FTS
     * failure (init never succeeded, or the query trips FTS5 syntax) it
     * degrades to LIKE with a logcat marker. Blank input returns empty.
     *
     * Implemented via [androidx.room.RawQuery] because Room's compile-time
     * verifier cannot resolve FTS5 virtual tables (they are created at
     * runtime by [FtsCallback], not part of the exported schema).
     */
    suspend fun search(query: String): List<WorkSearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        if (FtsStatus.available) {
            return try {
                searchRaw(SimpleSupportQuery(FTS_SEARCH_SQL, listOf(q)))
            } catch (e: Exception) {
                Log.w(SEARCH_LOG_TAG, "FTS MATCH failed (${e.message}); degrading to LIKE", e)
                likeSearch(escapeLike(q))
            }
        }
        return likeSearch(escapeLike(q))
    }

    private suspend fun likeSearch(escaped: String): List<WorkSearchHit> =
        searchRaw(SimpleSupportQuery(LIKE_SEARCH_SQL, listOf(escaped)))

    private fun escapeLike(input: String): String =
        input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    @Query("SELECT w.* FROM work w WHERE w.circleId = :circleId ORDER BY w.id")
    suspend fun getWorksByCircle(circleId: String): List<Work>

    @Query(
        "SELECT w.* FROM work w JOIN work_tag wt ON w.id = wt.workId " +
            "WHERE wt.tagId = :tagId ORDER BY w.id",
    )
    suspend fun getWorksByTag(tagId: String): List<Work>

    @Query(
        "SELECT w.* FROM work w JOIN work_va wv ON w.id = wv.workId " +
            "WHERE wv.vaId = :vaId ORDER BY w.id",
    )
    suspend fun getWorksByVa(vaId: String): List<Work>
}

@Dao
interface CircleDao {
    @Upsert
    suspend fun upsertAll(circles: List<Circle>)

    @Query("SELECT * FROM circle WHERE id = :id")
    suspend fun getById(id: String): Circle?

    @Query("SELECT * FROM circle ORDER BY nameSortKey")
    suspend fun getAll(): List<Circle>
}

@Dao
interface TagDao {
    @Upsert
    suspend fun upsertAll(tags: List<Tag>)

    @Query("SELECT * FROM tag WHERE id = :id")
    suspend fun getById(id: String): Tag?

    @Query("SELECT * FROM tag ORDER BY name")
    suspend fun getAll(): List<Tag>
}

@Dao
interface VaDao {
    @Upsert
    suspend fun upsertAll(vas: List<Va>)

    @Query("SELECT * FROM va WHERE id = :id")
    suspend fun getById(id: String): Va?

    @Query("SELECT * FROM va ORDER BY nameSortKey")
    suspend fun getAll(): List<Va>
}

@Dao
interface WorkTagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(links: List<WorkTag>)

    @Query("SELECT workId FROM work_tag WHERE tagId = :tagId ORDER BY workId")
    suspend fun getWorkIdsByTag(tagId: String): List<String>

    @Query("SELECT tagId FROM work_tag WHERE workId = :workId ORDER BY tagId")
    suspend fun getTagIdsByWork(workId: String): List<String>

    @Query("DELETE FROM work_tag WHERE workId = :workId")
    suspend fun deleteByWorkId(workId: String)
}

@Dao
interface WorkVaDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(links: List<WorkVa>)

    @Query("SELECT workId FROM work_va WHERE vaId = :vaId ORDER BY workId")
    suspend fun getWorkIdsByVa(vaId: String): List<String>

    @Query("SELECT vaId FROM work_va WHERE workId = :workId ORDER BY vaId")
    suspend fun getVaIdsByWork(workId: String): List<String>

    @Query("DELETE FROM work_va WHERE workId = :workId")
    suspend fun deleteByWorkId(workId: String)
}

/**
 * Reviews (rating/progress/review text). Ratings are validated here:
 * null or 1..5 only — 0 and 6 are rejected with [IllegalArgumentException]
 * (plan QA failure path: "rating 插入 0 或 6 时 DAO 层校验拒绝并抛预期异常").
 */
@Dao
interface ReviewDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInternal(review: Review)

    /** Validated upsert (REPLACE on workId conflict). */
    suspend fun upsert(review: Review) {
        val rating = review.rating
        require(rating == null || rating in 1..5) {
            "rating must be null or in 1..5, got $rating (workId=${review.workId})"
        }
        insertInternal(review)
    }

    @Query("SELECT * FROM review WHERE workId = :workId")
    suspend fun getByWorkId(workId: String): Review?

    @Query("SELECT * FROM review ORDER BY updatedAt DESC")
    suspend fun getAll(): List<Review>

    @Query("DELETE FROM review WHERE workId = :workId")
    suspend fun deleteByWorkId(workId: String)
}

@Dao
interface PlaybackStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: PlaybackState)

    @Query("SELECT * FROM playback_state WHERE trackKey = :trackKey")
    suspend fun get(trackKey: String): PlaybackState?

    @Query("DELETE FROM playback_state WHERE trackKey = :trackKey")
    suspend fun delete(trackKey: String)
}
