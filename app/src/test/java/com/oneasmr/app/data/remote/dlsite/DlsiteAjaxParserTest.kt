package com.oneasmr.app.data.remote.dlsite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fixture-driven spec tests for [DlsiteAjaxParser] (plan Task 9, dynamic fields).
 *
 * The dynamic fields (dl_count, price, review_count, rate_count,
 * rate_average_2dp, rate_count_detail) are NOT present in the work page HTML —
 * they come exclusively from the maniax-touch ajax endpoint
 * (https://www.dlsite.com/maniax-touch/product/info/ajax?product_id={RJ}).
 * A parser fed only the HTML fixture therefore has no values for these fields;
 * the fixtures below are the live ajax responses captured 2026-08-07.
 */
class DlsiteAjaxParserTest {

    private fun fixtureJson(rj: String): String =
        checkNotNull(javaClass.classLoader.getResource("dlsite/$rj.ajax.json")) { "missing fixture $rj.ajax.json" }
            .readText()

    @Test
    fun `RJ263959 dynamic fields`() {
        val d = DlsiteAjaxParser.parseDynamicFields(fixtureJson("RJ263959"), "RJ263959")
        assertEquals(71, d.dlCount)
        assertEquals(220, d.price)
        assertEquals(2, d.reviewCount)
        assertEquals(42, d.rateCount)
        assertEquals(4.57, d.rateAverage2dp!!, 0.001)
        assertEquals(
            listOf(1, 2, 3, 4, 5),
            d.rateCountDetail.map { it.reviewPoint },
        )
        assertEquals(
            listOf(1, 0, 3, 8, 30),
            d.rateCountDetail.map { it.count },
        )
    }

    @Test
    fun `RJ283598 dynamic fields`() {
        val d = DlsiteAjaxParser.parseDynamicFields(fixtureJson("RJ283598"), "RJ283598")
        assertEquals(3367, d.dlCount)
        assertEquals(1320, d.price)
        assertEquals(7, d.reviewCount)
        assertEquals(1043, d.rateCount)
        assertEquals(4.85, d.rateAverage2dp!!, 0.001)
        assertEquals(5, d.rateCountDetail.size)
    }

    @Test
    fun `RJ327447 dynamic fields`() {
        val d = DlsiteAjaxParser.parseDynamicFields(fixtureJson("RJ327447"), "RJ327447")
        assertEquals(39527, d.dlCount)
        assertEquals(495, d.price)
        assertEquals(76, d.reviewCount)
        assertEquals(11332, d.rateCount)
        assertEquals(4.84, d.rateAverage2dp!!, 0.001)
        assertEquals(
            listOf(21, 34, 295, 1049, 9933),
            d.rateCountDetail.map { it.count },
        )
    }

    @Test
    fun `missing optional fields default to null`() {
        val json = """{"RJ123456":{"dl_count":5,"price":100,"review_count":1,"rate_count":2,"rate_average_2dp":4.5,"rate_count_detail":[{"review_point":5,"count":2,"ratio":100}]}}"""
        val d = DlsiteAjaxParser.parseDynamicFields(json, "RJ123456")
        assertEquals(5, d.dlCount)
        assertEquals(100, d.price)
        assertEquals(1, d.reviewCount)
        assertEquals(2, d.rateCount)
        assertEquals(4.5, d.rateAverage2dp!!, 0.001)
        assertEquals(1, d.rateCountDetail.size)
    }

    @Test(expected = DlsiteScrapeException::class)
    fun `corrupted json throws parse error`() {
        DlsiteAjaxParser.parseDynamicFields("{not json", "RJ123456")
    }

    @Test(expected = DlsiteScrapeException::class)
    fun `json missing the work key throws parse error`() {
        DlsiteAjaxParser.parseDynamicFields("""{"RJ000000":{"dl_count":1}}""", "RJ123456")
    }

    @Test
    fun `empty ajax object yields null fields`() {
        // Free/taken-down works return an object with null dynamic fields;
        // parse must not crash and yields nulls for missing keys.
        val d = DlsiteAjaxParser.parseDynamicFields("""{"RJ123456":{}}""", "RJ123456")
        assertNull(d.dlCount)
        assertNull(d.price)
        assertNull(d.reviewCount)
        assertNull(d.rateCount)
        assertNull(d.rateAverage2dp)
        assertEquals(emptyList<AjaxFields.RateCountDetail>(), d.rateCountDetail)
    }
}
