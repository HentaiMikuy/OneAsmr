package com.oneasmr.app.data.remote.dlsite

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses the dynamic-fields JSON from
 * `https://www.dlsite.com/maniax-touch/product/info/ajax?product_id={RJ}`.
 *
 * Response shape (live-verified 2026-08-07): a single-key object keyed by the
 * product id, e.g. `{"RJ263959": { "dl_count": 71, "price": 220,
 * "review_count": 2, "rate_count": 42, "rate_average_2dp": 4.57,
 * "rate_count_detail": [{"review_point":1,"count":1,"ratio":2}, ...] }}`.
 *
 * These fields are NOT present in the work page HTML — kikoeru's dlsite.js
 * also fetches this endpoint for them (scrapeDynamicWorkMetadataFromDLsite).
 */
object DlsiteAjaxParser {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parses [body] for [rjCode]. Throws [DlsiteScrapeException] with
     * [DlsiteScrapeException.Kind.PARSE_ERROR] on malformed JSON or a missing
     * work key. Missing optional keys yield nulls; the empty object `{}`
     * (free/taken-down works) yields nulls without crashing.
     */
    fun parseDynamicFields(body: String, rjCode: String): AjaxFields {
        val root = try {
            json.parseToJsonElement(body)
        } catch (e: Exception) {
            throw DlsiteScrapeException(DlsiteScrapeException.Kind.PARSE_ERROR, "ajax JSON malformed for $rjCode", e)
        }
        val work = (root as? JsonObject)?.get(rjCode) as? JsonObject
            ?: throw DlsiteScrapeException(DlsiteScrapeException.Kind.PARSE_ERROR, "ajax JSON missing key $rjCode")
        return AjaxFields(
            dlCount = work.intOrNull("dl_count"),
            price = work.intOrNull("price"),
            reviewCount = work.intOrNull("review_count"),
            rateCount = work.intOrNull("rate_count"),
            rateAverage2dp = work.doubleOrNull("rate_average_2dp"),
            rateCountDetail = work.rateCountDetail(),
        )
    }

    private fun JsonObject.intOrNull(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.doubleOrNull(key: String): Double? =
        this[key]?.jsonPrimitive?.let { p -> p.contentOrNull?.toDoubleOrNull() }

    private fun JsonObject.rateCountDetail(): List<AjaxFields.RateCountDetail> {
        val arr = this["rate_count_detail"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el.jsonObject
            o.intOrNull("review_point")?.let { point ->
                AjaxFields.RateCountDetail(
                    reviewPoint = point,
                    count = o.intOrNull("count") ?: 0,
                    ratio = o.intOrNull("ratio") ?: 0,
                )
            }
        }
    }
}
