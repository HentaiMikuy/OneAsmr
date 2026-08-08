package com.oneasmr.app.data.remote.asmrone

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * asmr.one API DTOs (asmr.one metadata fallback source).
 *
 * Schema cross-validated across five independent typed clients
 * (kmou424/asmrone-api, fireinrain/asmr-downloader, rebelonion/echo-asmr-one,
 * KikoFlu, RSSHub) plus the kikoeru-express server asmr.one is forked from.
 *
 * All decoding goes through a `Json { ignoreUnknownKeys = true }` instance:
 * the work-info payload carries many fields this app does not consume
 * (translation_info, rank, playlistStatus, progress, userRating,
 * age_category_string, work_attributes, original_workno,
 * other_language_editions_in_db, source_type, source_url, circle_id,
 * create_date, updated_at, ...). Modelling them would couple us to server
 * churn for zero value.
 *
 * `language_editions` is deliberately NOT modelled: it is polymorphic (an
 * array on some works, an object keyed by language on others) and would need
 * a transforming serializer — ignored via ignoreUnknownKeys instead.
 */

/** POST body for /api/auth/me. asmr.one's guest account is a public, documented login. */
@Serializable
data class AsmrOneAuthRequest(
    val name: String,
    val password: String,
)

/**
 * 200 response of /api/auth/me. Newer server versions may add an optional
 * `user` object next to `token` — ignoreUnknownKeys covers that.
 */
@Serializable
data class AsmrOneAuthResponse(
    val token: String,
)

/** One entry of a tag's i18n map; `name` itself can be null/empty per locale. */
@Serializable
data class AsmrOneTagI18nEntry(
    val name: String? = null,
)

/**
 * Per-locale tag names. The JSON keys contain hyphens ("en-us", "ja-jp",
 * "zh-cn"), which are not valid Kotlin identifiers — hence @SerialName.
 */
@Serializable
data class AsmrOneTagI18n(
    @SerialName("en-us") val enUs: AsmrOneTagI18nEntry? = null,
    @SerialName("ja-jp") val jaJp: AsmrOneTagI18nEntry? = null,
    @SerialName("zh-cn") val zhCn: AsmrOneTagI18nEntry? = null,
)

/**
 * Work-info tag. `upvote`/`downvote`/`voteRank`/`voteStatus` may also appear;
 * they are vote bookkeeping this app never displays — ignored, not modelled.
 */
@Serializable
data class AsmrOneTag(
    val id: Int? = null,
    val name: String,
    val i18n: AsmrOneTagI18n? = null,
)

/** Voice-actor entry; `id` is a UUID string server-side (unlike the Int tag ids). */
@Serializable
data class AsmrOneVa(
    val id: String? = null,
    val name: String,
)

/** One star-rating histogram bucket of `rate_count_detail`. */
@Serializable
data class AsmrOneRateCountDetail(
    @SerialName("review_point") val reviewPoint: Int,
    val count: Int,
    val ratio: Int,
)

/**
 * GET /api/workInfo/{numericId} response (the numeric id is the digits ONLY,
 * no "RJ" prefix).
 *
 * Only the fields consumed by the scrape mapping are modelled; everything
 * else is dropped by ignoreUnknownKeys (see file KDoc). `title` is the one
 * mandatory field: a payload without it is treated as malformed
 * ([com.oneasmr.app.data.remote.dlsite.DlsiteScrapeException.Kind.PARSE_ERROR]).
 *
 * Note the dual id scheme: [id] is the numeric code used in the API path,
 * while [sourceId] is the canonical "RJxxxxxxxx" display code. [name] is the
 * circle name at the top level (the nested `circle` object repeats it with an
 * id we do not need).
 */
@Serializable
data class AsmrOneWorkInfo(
    val id: Int,
    @SerialName("source_id") val sourceId: String? = null,
    val title: String,
    /** Circle name (top-level `name` in the API payload). */
    val name: String? = null,
    val nsfw: Boolean = false,
    /** ISO yyyy-MM-dd release date. */
    val release: String? = null,
    @SerialName("dl_count") val dlCount: Int? = null,
    val price: Int? = null,
    @SerialName("review_count") val reviewCount: Int? = null,
    @SerialName("rate_count") val rateCount: Int? = null,
    @SerialName("rate_average_2dp") val rateAverage2dp: Double? = null,
    @SerialName("rate_count_detail") val rateCountDetail: List<AsmrOneRateCountDetail> = emptyList(),
    val vas: List<AsmrOneVa> = emptyList(),
    /** Nullable server-side (works with no tags omit or null it). */
    val tags: List<AsmrOneTag>? = null,
    val mainCoverUrl: String? = null,
    val samCoverUrl: String? = null,
    val thumbnailCoverUrl: String? = null,
)
