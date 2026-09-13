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

/** 手动年龄分级(用户标记,非刮削);null = 未设置。 */
enum class AgeRating(val label: String) {
    ALL_AGES("全年龄"),
    R15("R15"),
    R18("R18"),
}

/**
 * 和谐规则:未分级(null)/R15/R18 均为敏感;仅 ALL_AGES 豁免。UI(安全模式
 * 封面/曲名)与播放服务(通知栏封面)共用这一条判定,故与 [AgeRating] 同住
 * 数据层,供 ui 与 player 两侧引用。
 */
fun AgeRating?.isCensored(): Boolean = this != AgeRating.ALL_AGES

/**
 * 单文件的媒体类别。刻意不用 domain 的 MediaType:后者含 TEXT/IMAGE/OTHER,
 * 对 single_file 行是非法值——扫描白名单只放行音视频。
 */
enum class SingleFileKind { AUDIO, VIDEO }

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

    @TypeConverter
    fun singleFileKindToString(value: SingleFileKind): String = value.name

    @TypeConverter
    fun stringToSingleFileKind(value: String): SingleFileKind = SingleFileKind.valueOf(value)

    @TypeConverter
    fun ageRatingToString(value: AgeRating): String = value.name

    @TypeConverter
    fun stringToAgeRating(value: String): AgeRating = AgeRating.valueOf(value)
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
    /** 手动年龄分级(全年龄/R15/R18);null = 未设置。由用户在详情页标记,刮削/重扫绝不覆盖。 */
    val ageRating: AgeRating? = null,
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

/**
 * 单文件库条目(流媒体下载的散音视频,如 YouTube ASMR mp4)。与 [Work]
 * 平行的独立体系:无 RJ 码、无社团/声优/进度状态,组织方式是收藏夹
 * ([Collection])。身份 = (rootFolderUri, relativePath) 唯一索引;重扫按
 * 与作品相同的 missing 语义(标记不删除,用户手动移除)。
 *
 * 播放键为 KeySpec 三段形态 "single:{id}:1"([KeySpec.singleFileTrackKey]),
 * 复用 playback_state 及整套写入/续播机制。
 */
@Entity(
    tableName = "single_file",
    indices = [
        Index(value = ["rootFolderUri", "relativePath"], unique = true),
        Index("youtubeId"),
        Index("titleSortKey"),
        Index("addedAt"),
    ],
)
data class SingleFile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** SAF tree uri of the single-file scan root this file lives under. */
    val rootFolderUri: String,
    /** File path relative to the root (display names, no leading slash), incl. file name. */
    val relativePath: String,
    /** File display name (with extension) — sidecar pairing + re-parse source. */
    val fileName: String,
    val displayTitle: String,
    /** Pinyin/romaji key from [SortKeyGenerator] over [displayTitle]. */
    val titleSortKey: String,
    val kind: SingleFileKind,
    /** YouTube 11 位视频 ID;来自边车/文件名/在线补全,未知为 null。 */
    val youtubeId: String?,
    val channel: String?,
    /** ISO-8601 上传日期 "yyyy-MM-dd";未知为 null。 */
    val uploadDate: String?,
    val durationMs: Long?,
    val sizeBytes: Long,
    val lastModified: Long,
    /**
     * 封面的 Coil model:边车图的 SAF document uri、抽帧/下载缩略图的本地
     * 文件路径,或 null(UI 画占位)。
     */
    val thumbSource: String?,
    /** 原始网页链接(info.json webpage_url 或由 youtubeId 推导)。 */
    val sourceUrl: String?,
    /** 在线补全(YouTube oEmbed)状态;语义同作品的刮削三态。 */
    val scrapeStatus: ScrapeStatus,
    /** True when absent from the last full rescan of its root (同 Work.missing). */
    @ColumnInfo(defaultValue = "0")
    val missing: Boolean,
    val addedAt: Long,
    val updatedAt: Long,
)

/**
 * 收藏夹。[FAVORITES_ID] 行是内置「收藏」夹(isSystem=1,置顶、不可删改名,
 * 由 [CollectionDao.ensureFavorites] 幂等播种);其余为用户自建。单文件与
 * 收藏夹是多对多([CollectionItem]),播放列表语义 —— 一个文件可进多夹。
 */
@Entity(tableName = "collection")
data class Collection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "0")
    val isSystem: Boolean,
    /** 用户自建夹的手动排序位;系统夹恒排最前(查询按 isSystem DESC 优先)。 */
    val sortIndex: Int,
    val createdAt: Long,
) {
    companion object {
        /** 内置「收藏」夹的保留主键。 */
        const val FAVORITES_ID = 1L
    }
}

/** collection <-> single_file 多对多成员关系;删除任一端级联清理。 */
@Entity(
    tableName = "collection_item",
    primaryKeys = ["collectionId", "fileId"],
    foreignKeys = [
        ForeignKey(
            entity = Collection::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SingleFile::class,
            parentColumns = ["id"],
            childColumns = ["fileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("fileId")],
)
data class CollectionItem(
    val collectionId: Long,
    val fileId: Long,
    /** 夹内排序位(加入顺序递增;拖拽重排预留)。 */
    val sortIndex: Int,
    val addedAt: Long,
)
