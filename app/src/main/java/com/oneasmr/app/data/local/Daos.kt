package com.oneasmr.app.data.local

import android.util.Log
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * Supported work ordering modes. The DAO layer never inlines these column
 * expressions anywhere else — paging queries are built from this enum.
 *
 * `rating` maps to `rateAverage2dp` (kikoeru "rating" == rate_average_2dp);
 * the library menu exposes both "评分" and "均价" as separate entries over the
 * same column (kikoeru order-parameter parity).
 *
 * Every deterministic order gets a `work.id` tiebreaker appended by the DAO:
 * rows whose sort field is NULL (e.g. unscraped works have no releaseDate)
 * group deterministically and fall back to id order — the plan's "排序字段
 * 不存在时回退编号排序", guaranteed at the SQL level, never a crash.
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
    ;

    companion object {
        /**
         * Reads a persisted order name; unknown/missing values fall back to
         * [ID] — a corrupt or stale preference must never crash or mis-sort
         * the library (plan failure path: "排序字段不存在时回退编号排序").
         */
        fun fromStored(value: String?): WorkOrder =
            entries.firstOrNull { it.name == value } ?: ID
    }
}

/** One search hit: a work id plus the dimension (title/circle/tag/va) it matched in. */
data class WorkSearchHit(val workId: String, val matchedIn: String)

/** One library card/row (Task 12): the work joined with its circle name and
 * user progress so the grid/list renders everything from a single paged query
 * — no per-item lookups during scrolling (the "no IO in item recomposition"
 * rule).
 */
data class WorkListItem(
    val id: String,
    val title: String,
    val circleName: String?,
    val rateAverage2dp: Double?,
    val missing: Boolean,
    val scrapeStatus: ScrapeStatus,
    /** null when the user has no review row yet (LEFT JOIN). */
    val progress: ProgressState?,
    val rootFolderUri: String,
    val relativeDir: String,
) {
    /** Bare RJ/BJ/VJ code (KeySpec); falls back to the raw id for safety. */
    val rjCode: String get() = KeySpec.parseWorkId(id)?.rjCode ?: id
}

/**
 * Task 15 library filter dimension, enforced at the DAO level (extended into
 * the paging query — never in-memory filtering of a loaded page). Mirrors the
 * kikoeru `GET /api/review?filter=` semantics: a review-joined predicate.
 *
 * - [Rated]: works the user rated (review row with a 1-5 rating).
 * - [Progress]: works whose progress state equals [state]. `none` matches
 *   BOTH explicit `progress = 'none'` rows and works without any review row
 *   (a no-review work is trivially "no progress yet" — kikoeru's default).
 */
sealed interface WorkFilter {
    data object Rated : WorkFilter
    data class Progress(val state: ProgressState) : WorkFilter
}

/**
 * Task 12/13/15: creates a fresh [PagingSource] per Pager generation with the
 * full sort + search + filter parameters (order field, direction, keyword,
 * random seed, filter). Hilt provides the Room-backed instance; unit tests
 * inject a fake source over a plain list so Pager runs on the test's virtual
 * scheduler (no real IO threads).
 */
fun interface WorkPagingSourceFactory {
    fun create(
        order: WorkOrder,
        descending: Boolean,
        keyword: String?,
        randomSeed: Long,
        filter: WorkFilter?,
    ): PagingSource<Int, WorkListItem>
}

/**
 * One dimension-list row (Task 16 browse): a circle / tag / VA with the count
 * of library works referencing it. Counts come from a DAO-level GROUP BY —
 * never computed in memory.
 */
data class DimensionListItem(
    val id: String,
    val name: String,
    val workCount: Int,
)

/**
 * Task 16 dimension-works paging: creates a fresh [PagingSource] per Pager
 * generation for `browse/{dimension}/{id}`. Hilt provides the Room-backed
 * instance (unknown dimensions degrade to [EmptyDimensionPagingSource] — the
 * failure path, never a crash); unit tests inject a fake source over a plain
 * list so the Pager runs on the test's virtual scheduler (repo flake
 * convention — no real IO threads, no real-time waits).
 */
fun interface DimensionWorksPagingSourceFactory {
    fun create(dimension: String, id: String): PagingSource<Int, WorkListItem>
}

/** Always-empty source for unknown browse dimensions (Task 16 failure path). */
object EmptyDimensionPagingSource : PagingSource<Int, WorkListItem>() {
    override fun getRefreshKey(state: PagingState<Int, WorkListItem>): Int? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, WorkListItem> =
        LoadResult.Page(emptyList(), prevKey = null, nextKey = null)
}

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
 * Deterministic, seed-dependent pseudo-random key per work id — the "random"
 * order's session-stable core (Task 13). SQLite's RANDOM() is unseedable, so
 * the key is a seeded hash of the id: sum over characters of
 * (position * salt mod 97) * code point, reduced mod 2^31-1. Same seed -> the
 * SAME total order across every page of a session (paging stays coherent);
 * different seeds -> different orders. Ties fall to the id tiebreaker.
 *
 * The per-position weight is taken mod 97 (a prime above the 64 supported
 * positions): a bare `position * salt` factor only SCALES the hash linearly
 * (key = salt * K(id)), so ids with narrow K ranges rank identically for
 * every seed — the mod introduces seed-dependent wrap points that genuinely
 * re-rank the works (this exact bug was caught by the DAO test asserting
 * seed 42 != seed 43). Salt is clamped to (0, 100003) so a zero salt can
 * never degenerate the hash; all arithmetic stays within int64 (max ~6.9e9,
 * no float promotion, deterministic on every SQLite build).
 *
 * The number table is a recursive CTE because `FROM (VALUES ...)` is a syntax
 * error on the Android framework SQLite used by Robolectric (verified
 * on-device-JVM, Task 13). The CTE prefixes the whole statement (see
 * [WorkDao.buildLibrarySql]) and is visible inside the correlated ORDER BY
 * subquery.
 */
private const val MAX_HASH_CHARS = 64
private const val HASH_WEIGHT_MOD = 97

private const val NUMBERS_CTE =
    "WITH RECURSIVE nums(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM nums WHERE n < $MAX_HASH_CHARS)"

private fun seededRandomKey(seed: Long): String {
    val salt = (((seed % 100003L) + 100003L) % 100003L).let { if (it == 0L) 1L else it }
    return "(SELECT (sum( ((t.n * $salt) % $HASH_WEIGHT_MOD) * unicode(substr(w.id, t.n, 1)) ) % 2147483647) " +
        "FROM nums t WHERE t.n <= length(w.id))"
}

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

    /** Live single-work row (Task 14 detail page: metadata refreshes after scrape/rescan). */
    @Query("SELECT * FROM work WHERE id = :id")
    fun getByIdFlow(id: String): Flow<Work?>

    @Query("DELETE FROM work WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM work")
    suspend fun count(): Int

    /** Every local work row (the work table only ever holds local works — KeySpec). */
    @Query("SELECT * FROM work")
    suspend fun getAll(): List<Work>

    /** Live stream of every local work row (Task 8 library list + empty-state decision). */
    @Query("SELECT * FROM work")
    fun getAllFlow(): Flow<List<Work>>

    /** Live total work count (Task 12 empty-state + batch-menu gates). */
    @Query("SELECT COUNT(*) FROM work")
    fun countFlow(): Flow<Int>

    /**
     * Works of one scrape status that are still present on disk (Task 27
     * settings batch-scrape status row; same predicate as the batch targets).
     */
    @Query("SELECT COUNT(*) FROM work WHERE NOT missing AND scrapeStatus = :status")
    suspend fun countByScrapeStatus(status: ScrapeStatus): Int

    /**
     * Paged library source (Task 12/13): work + circle name + user progress in
     * ONE indexed query. Room's built-in PagingSource (LimitOffsetPagingSource)
     * translates this into `SELECT ... LIMIT ? OFFSET ?` — a single statement
     * per page, never the whole library (plan Must NOT). Invalidates
     * automatically on work/circle/review/tag/va table changes via Room's
     * invalidation tracker.
     */
    @Query(
        "SELECT w.id AS id, w.title AS title, c.name AS circleName, " +
            "w.rateAverage2dp AS rateAverage2dp, w.missing AS missing, " +
            "w.scrapeStatus AS scrapeStatus, r.progress AS progress, " +
            "w.rootFolderUri AS rootFolderUri, w.relativeDir AS relativeDir " +
            "FROM work w " +
            "LEFT JOIN circle c ON c.id = w.circleId " +
            "LEFT JOIN review r ON r.workId = w.id " +
            "ORDER BY w.id",
    )
    fun pagingSourceById(): PagingSource<Int, WorkListItem>

    @RawQuery(observedEntities = [Work::class, Circle::class, Review::class, Tag::class, Va::class])
    fun pagingSourceRaw(query: SupportSQLiteQuery): PagingSource<Int, WorkListItem>

    /**
     * Task 13 sort + search composition, implemented as DAO-level query
     * parameters (order field + direction + keyword) — never in-memory
     * filtering of a loaded list. The keyword (when non-null) restricts rows
     * to the FTS5-trigram search hits (LIKE fallback when the FTS index is
     * unavailable), UNIONed across the four dimensions exactly like
     * [search]; the order is applied by the database. Task 15 adds the
     * review-join [filter] predicate (progress state / rated-only) as a
     * third composition axis — all three combine in one SQL statement.
     *
     * Ordering rules:
     * - every deterministic order appends `work.id` as tiebreaker, so works
     *   whose sort field is NULL (unscraped metadata) deterministically fall
     *   back to id order — "排序字段不存在时回退编号排序" without a crash.
     * - [WorkOrder.RANDOM] ignores [descending] and uses the seeded key from
     *   [seededRandomKey] — stable within a session for a fixed [randomSeed].
     *
     * FTS MATCH can throw at load time on user-typed syntax (punctuation,
     * operators); the returned source then degrades to the LIKE variant once,
     * mirroring [search]'s degrade-on-exception contract.
     */
    fun pagingSource(
        order: WorkOrder,
        descending: Boolean,
        keyword: String?,
        randomSeed: Long,
        filter: WorkFilter? = null,
    ): PagingSource<Int, WorkListItem> {
        val (sql, args) = buildLibrarySql(order, descending, keyword, randomSeed, filter)
        val primary = pagingSourceRaw(SimpleSupportQuery(sql, args))
        val degraded = if (keyword != null && FtsStatus.available) {
            val (likeSql, likeArgs) = buildLibrarySql(order, descending, keyword, randomSeed, filter, forceLike = true)
            pagingSourceRaw(SimpleSupportQuery(likeSql, likeArgs))
        } else {
            null
        }
        return if (degraded == null) primary else DegradingPagingSource(primary, degraded)
    }

    /** Single row of the library list shape by id (search direct-lookup hit). */
    @Query(
        "SELECT w.id AS id, w.title AS title, c.name AS circleName, " +
            "w.rateAverage2dp AS rateAverage2dp, w.missing AS missing, " +
            "w.scrapeStatus AS scrapeStatus, r.progress AS progress, " +
            "w.rootFolderUri AS rootFolderUri, w.relativeDir AS relativeDir " +
            "FROM work w " +
            "LEFT JOIN circle c ON c.id = w.circleId " +
            "LEFT JOIN review r ON r.workId = w.id " +
            "WHERE w.id = :id",
    )
    suspend fun getListItemById(id: String): WorkListItem?

    /**
     * Work-list SQL for the library and search pages: the Task 12 join shape
     * plus an optional search-hit restriction and the requested ordering.
     * Returns (sql, bindArgs): the only bindable parameter is the keyword's
     * `:query` (the search UNIONs already use that exact name), the order
     * expression is inlined (enum-owned columns + a clamped numeric seed —
     * never user input), and Room's LimitOffsetPagingSource appends its own
     * `LIMIT ? OFFSET ?` positionally after it.
     */
    private fun buildLibrarySql(
        order: WorkOrder,
        descending: Boolean,
        keyword: String?,
        randomSeed: Long,
        filter: WorkFilter?,
        forceLike: Boolean = false,
    ): Pair<String, List<Any?>> {
        val searchSql = when {
            keyword == null -> null
            forceLike || !FtsStatus.available -> LIKE_SEARCH_SQL
            else -> FTS_SEARCH_SQL
        }
        val args = mutableListOf<Any?>()
        val join = if (searchSql != null) {
            // The LIKE variant is wildcard-sensitive: % and _ in the keyword
            // must be escaped exactly like [search] does (escapeLike), so a
            // literal "%" in the keyword cannot match every row — the FTS
            // MATCH variant binds the raw query.
            args += if (searchSql == LIKE_SEARCH_SQL && keyword != null) escapeLike(keyword) else keyword
            " JOIN (SELECT DISTINCT workId FROM ($searchSql)) search_hits ON search_hits.workId = w.id"
        } else {
            ""
        }
        // Task 15 review filter over the already-LEFT-JOINed `r` alias.
        // `none` also matches review-less works (r.progress IS NULL).
        // Bind order: :stateName lands textually AFTER the search
        // subquery's :query, matching the args appended below.
        val whereClause = when (filter) {
            null -> ""
            WorkFilter.Rated -> " WHERE r.rating IS NOT NULL"
            is WorkFilter.Progress ->
                if (filter.state == ProgressState.none) {
                    " WHERE (r.progress IS NULL OR r.progress = 'none')"
                } else {
                    args += filter.state.name
                    " WHERE r.progress = :stateName"
                }
        }
        val orderBy = when (order) {
            WorkOrder.RANDOM -> "${seededRandomKey(randomSeed)} ASC, w.id ASC"
            else -> {
                // The FROM alias is `w`; SQLite hides the real table name for
                // qualified references once aliased (work.id would fail).
                val column = order.sql.substringAfter('.')
                val direction = if (descending) " DESC" else " ASC"
                "w.$column$direction, w.id ASC"
            }
        }
        val ctePrefix = if (order == WorkOrder.RANDOM) "$NUMBERS_CTE " else ""
        val sql = ctePrefix +
            "SELECT w.id AS id, w.title AS title, c.name AS circleName, " +
            "w.rateAverage2dp AS rateAverage2dp, w.missing AS missing, " +
            "w.scrapeStatus AS scrapeStatus, r.progress AS progress, " +
            "w.rootFolderUri AS rootFolderUri, w.relativeDir AS relativeDir " +
            "FROM work w " +
            "LEFT JOIN circle c ON c.id = w.circleId " +
            "LEFT JOIN review r ON r.workId = w.id" +
            join +
            whereClause +
            " ORDER BY $orderBy"
        return sql to args
    }

    /**
     * Marks the given works as missing (Task 7 diff). Empty [ids] is a no-op —
     * Room's `IN (:ids)` would otherwise render the invalid `IN ()`.
     */
    suspend fun markMissing(ids: List<String>, now: Long) {
        if (ids.isEmpty()) return
        markMissingInternal(ids, now)
    }

    @Query("UPDATE work SET missing = 1, updatedAt = :now WHERE id IN (:ids)")
    suspend fun markMissingInternal(ids: List<String>, now: Long)

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
            } catch (e: CancellationException) {
                throw e
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

    /**
     * Task 16 circle-works paging: the Task 12 [WorkListItem] join shape
     * restricted to one circle, ordered by id (kikoeru circle-works
     * semantics). Room's LimitOffsetPagingSource pages it; invalidates on
     * work/circle/review changes.
     */
    @Query(
        "SELECT w.id AS id, w.title AS title, c.name AS circleName, " +
            "w.rateAverage2dp AS rateAverage2dp, w.missing AS missing, " +
            "w.scrapeStatus AS scrapeStatus, r.progress AS progress, " +
            "w.rootFolderUri AS rootFolderUri, w.relativeDir AS relativeDir " +
            "FROM work w " +
            "LEFT JOIN circle c ON c.id = w.circleId " +
            "LEFT JOIN review r ON r.workId = w.id " +
            "WHERE w.circleId = :circleId ORDER BY w.id",
    )
    fun pagingSourceByCircle(circleId: String): PagingSource<Int, WorkListItem>

    @Query(
        "SELECT w.* FROM work w JOIN work_tag wt ON w.id = wt.workId " +
            "WHERE wt.tagId = :tagId ORDER BY w.id",
    )
    suspend fun getWorksByTag(tagId: String): List<Work>

    /** Task 16 tag-works paging (same shape as [pagingSourceByCircle]). */
    @Query(
        "SELECT w.id AS id, w.title AS title, c.name AS circleName, " +
            "w.rateAverage2dp AS rateAverage2dp, w.missing AS missing, " +
            "w.scrapeStatus AS scrapeStatus, r.progress AS progress, " +
            "w.rootFolderUri AS rootFolderUri, w.relativeDir AS relativeDir " +
            "FROM work w " +
            "LEFT JOIN circle c ON c.id = w.circleId " +
            "LEFT JOIN review r ON r.workId = w.id " +
            "JOIN work_tag wt ON w.id = wt.workId " +
            "WHERE wt.tagId = :tagId ORDER BY w.id",
    )
    fun pagingSourceByTag(tagId: String): PagingSource<Int, WorkListItem>

    @Query(
        "SELECT w.* FROM work w JOIN work_va wv ON w.id = wv.workId " +
            "WHERE wv.vaId = :vaId ORDER BY w.id",
    )
    suspend fun getWorksByVa(vaId: String): List<Work>

    /** Task 16 VA-works paging (same shape as [pagingSourceByCircle]). */
    @Query(
        "SELECT w.id AS id, w.title AS title, c.name AS circleName, " +
            "w.rateAverage2dp AS rateAverage2dp, w.missing AS missing, " +
            "w.scrapeStatus AS scrapeStatus, r.progress AS progress, " +
            "w.rootFolderUri AS rootFolderUri, w.relativeDir AS relativeDir " +
            "FROM work w " +
            "LEFT JOIN circle c ON c.id = w.circleId " +
            "LEFT JOIN review r ON r.workId = w.id " +
            "JOIN work_va wv ON w.id = wv.workId " +
            "WHERE wv.vaId = :vaId ORDER BY w.id",
    )
    fun pagingSourceByVa(vaId: String): PagingSource<Int, WorkListItem>
}

@Dao
interface CircleDao {
    @Upsert
    suspend fun upsertAll(circles: List<Circle>)

    @Query("SELECT * FROM circle WHERE id = :id")
    suspend fun getById(id: String): Circle?

    @Query("SELECT * FROM circle ORDER BY nameSortKey")
    suspend fun getAll(): List<Circle>

    /**
     * Task 16 circle browse list: every circle with its library work count,
     * work count DESC (plan: "社团列表（作品数排序）"). The LEFT JOIN keeps
     * zero-work circles visible at count 0; the count is a GROUP BY aggregate
     * — never computed in memory.
     */
    @Query(
        "SELECT c.id AS id, c.name AS name, COUNT(w.id) AS workCount " +
            "FROM circle c LEFT JOIN work w ON w.circleId = c.id " +
            "GROUP BY c.id, c.name " +
            "ORDER BY workCount DESC, c.name",
    )
    fun getAllWithCountsFlow(): Flow<List<DimensionListItem>>
}

@Dao
interface TagDao {
    @Upsert
    suspend fun upsertAll(tags: List<Tag>)

    @Query("SELECT * FROM tag WHERE id = :id")
    suspend fun getById(id: String): Tag?

    @Query("SELECT * FROM tag ORDER BY name")
    suspend fun getAll(): List<Tag>

    /** Task 16 tag browse list: work count DESC (see [CircleDao.getAllWithCountsFlow]). */
    @Query(
        "SELECT t.id AS id, t.name AS name, COUNT(w.id) AS workCount " +
            "FROM tag t LEFT JOIN work_tag wt ON wt.tagId = t.id " +
            "LEFT JOIN work w ON w.id = wt.workId " +
            "GROUP BY t.id, t.name " +
            "ORDER BY workCount DESC, t.name",
    )
    fun getAllWithCountsFlow(): Flow<List<DimensionListItem>>
}

@Dao
interface VaDao {
    @Upsert
    suspend fun upsertAll(vas: List<Va>)

    @Query("SELECT * FROM va WHERE id = :id")
    suspend fun getById(id: String): Va?

    @Query("SELECT * FROM va ORDER BY nameSortKey")
    suspend fun getAll(): List<Va>

    /** Task 16 CV browse list: work count DESC (see [CircleDao.getAllWithCountsFlow]). */
    @Query(
        "SELECT v.id AS id, v.name AS name, COUNT(w.id) AS workCount " +
            "FROM va v LEFT JOIN work_va wv ON wv.vaId = v.id " +
            "LEFT JOIN work w ON w.id = wv.workId " +
            "GROUP BY v.id, v.name " +
            "ORDER BY workCount DESC, v.name",
    )
    fun getAllWithCountsFlow(): Flow<List<DimensionListItem>>
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
 * One review-list row (Task 15 "我标记的作品"): the review joined with its
 * work's display data. The work join is LEFT so a review survives even when
 * the work row is gone (or missing-flagged) — the list can always show the
 * code and navigate to the detail page's review section.
 */
data class ReviewListItem(
    val workId: String,
    val rating: Int?,
    val reviewText: String?,
    val progress: ProgressState,
    val updatedAt: Long,
    /** Null when the work row is absent (review-only orphan). */
    val title: String?,
    val missing: Boolean,
    val rootFolderUri: String?,
    val relativeDir: String?,
) {
    /** Bare RJ/BJ/VJ code (KeySpec); falls back to the raw id for safety. */
    val rjCode: String get() = KeySpec.parseWorkId(workId)?.rjCode ?: workId
}

/**
 * Reviews (rating/progress/review text). Ratings are validated here:
 * null or 1..5 only — 0 and 6 are rejected with [IllegalArgumentException]
 * (plan QA failure path: "rating 插入 0 或 6 时 DAO 层校验拒绝并抛预期异常").
 * This DAO is the SINGLE enforcement point for the rating range; the UI
 * only ever offers 1-5 stars.
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

    /** Live single-review row (Task 15 detail page review section). */
    @Query("SELECT * FROM review WHERE workId = :workId")
    fun getByWorkIdFlow(workId: String): Flow<Review?>

    @Query("SELECT * FROM review ORDER BY updatedAt DESC")
    suspend fun getAll(): List<Review>

    /**
     * Live "我标记的作品" list, newest update first (kikoeru review-list
     * semantics). LEFT JOIN keeps reviews of missing/removed works visible.
     */
    @Query(
        "SELECT r.workId AS workId, r.rating AS rating, r.reviewText AS reviewText, " +
            "r.progress AS progress, r.updatedAt AS updatedAt, " +
            "w.title AS title, w.missing AS missing, " +
            "w.rootFolderUri AS rootFolderUri, w.relativeDir AS relativeDir " +
            "FROM review r LEFT JOIN work w ON w.id = r.workId " +
            "ORDER BY r.updatedAt DESC",
    )
    fun getAllJoinedFlow(): Flow<List<ReviewListItem>>

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

    /**
     * Every playback entry of one work (Task 19 detail-page progress bars).
     * [prefix] is "{workId}:" e.g. "local:RJ123456:" — the trailing colon
     * anchors the match so "local:RJ123456:3" is returned but a hypothetical
     * "local:RJ1234567:3" is NOT (same anchoring rule as
     * [deleteForWorkPrefix]).
     */
    @Query("SELECT * FROM playback_state WHERE trackKey LIKE :prefix || '%'")
    suspend fun getAllForWork(prefix: String): List<PlaybackState>

    /** Live variant: detail-page rows re-render when the writer upserts. */
    @Query("SELECT * FROM playback_state WHERE trackKey LIKE :prefix || '%'")
    fun getAllForWorkFlow(prefix: String): Flow<List<PlaybackState>>

    /**
     * Deletes every playback entry of one work (manual-remove API, Task 7).
     * [prefix] is "{workId}:" e.g. "local:RJ123456:" — the trailing colon
     * anchors the match so "local:RJ123456:3" is removed but a hypothetical
     * "local:RJ1234567:3" is NOT. Prefixes are alphanumeric + ':' (KeySpec
     * shape) so LIKE wildcards cannot appear in them.
     */
    @Query("DELETE FROM playback_state WHERE trackKey LIKE :prefix || '%'")
    suspend fun deleteForWorkPrefix(prefix: String)

    /**
     * Task 27 optional "同时清除播放进度": wipes EVERY playback position.
     * Only ever invoked from the Settings confirm dialog — never implicitly.
     */
    @Query("DELETE FROM playback_state")
    suspend fun clearAll()
}

/**
 * Task 13: wraps the FTS-backed paging source so a MATCH syntax error at load
 * time (user-typed punctuation/operators trip FTS5 syntax) retries the LIKE
 * variant once — the paging equivalent of [WorkDao.search]'s
 * degrade-on-exception contract (documented degraded fallback, Task 4).
 * The LIKE source is pre-built (not per page) so the degrade is a single
 * catch + one extra load. Cancellation is never swallowed by the degrade:
 * a cancelled load rethrows [CancellationException] instead of falling back
 * to LIKE (final-wave finding F2).
 */
internal class DegradingPagingSource(
    private val fts: PagingSource<Int, WorkListItem>,
    private val like: PagingSource<Int, WorkListItem>,
) : PagingSource<Int, WorkListItem>() {
    override fun getRefreshKey(state: PagingState<Int, WorkListItem>): Int? =
        fts.getRefreshKey(state)

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, WorkListItem> =
        try {
            fts.load(params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(SEARCH_LOG_TAG, "FTS paging MATCH failed (${e.message}); degraded to LIKE", e)
            like.load(params)
        }
}
