package com.oneasmr.app.data.remote.dlsite

import kotlinx.serialization.Serializable

/**
 * DLsite scrape result models (plan Task 9).
 *
 * Field set mirrors the local `work` table (data/local/Entities.kt) plus the
 * cross tables: tags and vas are name lists; rateCountDetail is kept as a
 * typed list and serialized to JSON by the caller (Task 11 writes
 * `rateCountDetailJson`).
 */

/** Language preference for the DLsite page scrape; zh-cn is the default (kikoeru default). */
enum class DlsiteLanguage(val localeCookie: String) {
    ZH_CN("locale=zh-cn"),
    JA_JP("locale=ja-jp"),
    ZH_TW("locale=zh-tw"),
}

/** Cover image URLs in the four sizes Task 10 consumes (kikoeru RJ{id}_img_{type}.jpg naming). */
data class DlsiteCovers(
    val main: String?,
    val sam: String?,
    val thumb240: String?,
    val thumb360: String?,
)

/** Static fields parsed from the work page HTML. */
data class WorkPageFields(
    val title: String,
    val circle: String?,
    val nsfw: Boolean,
    /** ISO-8601 yyyy-MM-dd; null when the page has no 販売日/发售日 row. */
    val releaseDate: String?,
    val seriesName: String?,
    val tags: List<String>,
    val vas: List<String>,
    val covers: DlsiteCovers,
)

/** Dynamic fields parsed from the maniax-touch ajax JSON (NOT present in the HTML). */
data class AjaxFields(
    val dlCount: Int?,
    val price: Int?,
    val reviewCount: Int?,
    val rateCount: Int?,
    val rateAverage2dp: Double?,
    val rateCountDetail: List<RateCountDetail>,
) {
    @Serializable
    data class RateCountDetail(
        val reviewPoint: Int,
        val count: Int,
        val ratio: Int,
    )
}

/** Complete merged scrape result for one work. */
data class ScrapedWork(
    val rjCode: String,
    val title: String,
    val circle: String?,
    val nsfw: Boolean,
    val releaseDate: String?,
    val seriesName: String?,
    val tags: List<String>,
    val vas: List<String>,
    val covers: DlsiteCovers,
    val dlCount: Int?,
    val price: Int?,
    val reviewCount: Int?,
    val rateCount: Int?,
    val rateAverage2dp: Double?,
    val rateCountDetail: List<AjaxFields.RateCountDetail>,
)
