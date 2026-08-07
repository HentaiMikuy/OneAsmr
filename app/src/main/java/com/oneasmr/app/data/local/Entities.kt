package com.oneasmr.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/** Scrape status of a work's metadata (Task 9 writes OK/FAILED). */
enum class ScrapeStatus { NOT_SCRAPED, OK, FAILED }

/** Six-state listening progress (aligned with kikoeru t_review semantics). */
enum class ProgressState { none, marked, listening, listened, replay, postponed }

/**
 * Room type converters: enums are stored as TEXT with their exact plan-spelled
 * names ("NOT_SCRAPED", "none", ...) so the storage format is stable for the
 * version-1 migration baseline.
 */
class Converters {
    @TypeConverter
    fun scrapeStatusToString(value: ScrapeStatus): String = value.name

    @TypeConverter
    fun stringToScrapeStatus(value: String): ScrapeStatus = ScrapeStatus.valueOf(value)

    @TypeConverter
    fun progressToString(value: ProgressState): String = value.name

    @TypeConverter
    fun stringToProgress(value: String): ProgressState = ProgressState.valueOf(value)
}

/**
 * A library work. Primary key follows [KeySpec]: "{sourceScope}:{rjCode}", e.g.
 * "local:RJ123456". Remote works never enter this table (Task 25/26 keep them
 * in memory/server-side only).
 */
@Entity(
    tableName = "work",
    foreignKeys = [
        ForeignKey(
            entity = Circle::class,
            parentColumns = ["id"],
            childColumns = ["circleId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("circleId"),
        Index("releaseDate"),
        Index("dlCount"),
        Index("reviewCount"),
        Index("price"),
        Index("rateAverage2dp"),
        Index("titleSortKey"),
    ],
)
data class Work(
    @PrimaryKey val id: String,
    /** SAF tree uri of the scanned root folder this work lives under. */
    val rootFolderUri: String,
    /** Work folder path relative to the root folder (no leading slash). */
    val relativeDir: String,
    val title: String,
    /** Pinyin/romaji key from [SortKeyGenerator]; Chinese sorts phonetically. */
    val titleSortKey: String,
    val circleId: String?,
    val nsfw: Boolean,
    /** ISO-8601 date, e.g. "2024-03-15"; null until scraped. */
    val releaseDate: String?,
    val dlCount: Int?,
    val price: Int?,
    val reviewCount: Int?,
    val rateCount: Int?,
    /** Average rating with 2 decimal places, e.g. 4.52; null until scraped. */
    val rateAverage2dp: Double?,
    /** Raw JSON of DLsite rate_count_detail; opaque to the database layer. */
    val rateCountDetailJson: String?,
    val seriesName: String?,
    val scrapeStatus: ScrapeStatus,
    /**
     * True when the work folder was ABSENT from the last full rescan (Task 7
     * "增量重扫与失效作品检测"). Missing works keep their row AND their review /
     * playback_state rows (nothing is auto-deleted); the UI greys them out and
     * offers a manual remove (Task 8/12, [com.oneasmr.app.data.scanner.IncrementalRescanner.removeWork]).
     * Default "0" matches the v1→v2 migration's ALTER TABLE ADD COLUMN
     * (SQLite requires a DEFAULT when adding a NOT NULL column to a
     * populated table — see [com.oneasmr.app.data.local.OneAsmrDatabase.MIGRATION_1_2]).
     */
    @ColumnInfo(defaultValue = "0")
    val missing: Boolean,
    /** Epoch millis when the row was inserted. */
    val addedAt: Long,
    /** Epoch millis of the last metadata update. */
    val updatedAt: Long,
)

/** Circle (社团). Id equals the circle name; deduplicated by name. */
@Entity(
    tableName = "circle",
    indices = [Index("nameSortKey")],
)
data class Circle(
    @PrimaryKey val id: String,
    val name: String,
    val nameSortKey: String,
)

/** Tag. Id equals the tag name; deduplicated by name. */
@Entity(tableName = "tag")
data class Tag(
    @PrimaryKey val id: String,
    val name: String,
)

/** Voice actor (CV/声优). Id equals the va name; deduplicated by name. */
@Entity(
    tableName = "va",
    indices = [Index("nameSortKey")],
)
data class Va(
    @PrimaryKey val id: String,
    val name: String,
    val nameSortKey: String,
)

/** work <-> tag cross table. */
@Entity(
    tableName = "work_tag",
    primaryKeys = ["workId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = Work::class,
            parentColumns = ["id"],
            childColumns = ["workId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Tag::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tagId")],
)
data class WorkTag(
    val workId: String,
    val tagId: String,
)

/** work <-> va cross table. */
@Entity(
    tableName = "work_va",
    primaryKeys = ["workId", "vaId"],
    foreignKeys = [
        ForeignKey(
            entity = Work::class,
            parentColumns = ["id"],
            childColumns = ["workId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Va::class,
            parentColumns = ["id"],
            childColumns = ["vaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("vaId")],
)
data class WorkVa(
    val workId: String,
    val vaId: String,
)

/**
 * User review / progress. Primary key follows [KeySpec] work-id rule
 * ("{sourceScope}:{rjCode}"); remote reviews are written to the server only
 * and never land in this table (Task 25/26).
 */
@Entity(tableName = "review")
data class Review(
    @PrimaryKey val workId: String,
    /** 1..5 star rating; null = not rated. Out-of-range values are rejected by [ReviewDao]. */
    val rating: Int?,
    val reviewText: String?,
    val progress: ProgressState,
    val updatedAt: Long,
)

/**
 * Playback position memory. Primary key follows [KeySpec] track-key rule
 * ("{sourceScope}:{rjCode}:{trackIndex}", e.g. "local:RJ123456:3"); the key
 * embeds the bare rjCode — never work.id — so local and remote playbacks of
 * the same RJ never collide.
 */
@Entity(tableName = "playback_state")
data class PlaybackState(
    @PrimaryKey val trackKey: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)
