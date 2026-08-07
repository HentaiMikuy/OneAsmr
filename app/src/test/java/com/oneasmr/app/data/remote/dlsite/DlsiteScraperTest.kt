package com.oneasmr.app.data.remote.dlsite

import com.oneasmr.app.domain.rjcode.RjCode
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end spec tests for [DlsiteScraper] against MockWebServer (plan Task 9).
 *
 * Covers:
 * - two-request flow: work page HTML (static fields) + ajax JSON (dynamic
 *   fields); both must be fetched or the scrape FAILS — HTML-only would leave
 *   the dynamic assertions below unsatisfied (that is the point: dynamic
 *   fields live only in the ajax response).
 * - HTTP 404 -> NOT_FOUND (structured error, not a crash)
 * - HTTP 403 -> BLOCKED (anti-bot)
 * - corrupted HTML with 200 -> PARSE_ERROR (no crash)
 * - corrupted ajax JSON -> PARSE_ERROR
 */
class DlsiteScraperTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader.getResource("dlsite/$name")) { "missing fixture $name" }
            .readText()

    private fun scraper(): DlsiteScraper =
        DlsiteScraper(baseUrl = server.url("/").toString(), maxAttempts = 1, backoffMillis = 1)

    @Test
    fun `happy path fetches both page and ajax and merges all fields`() = runBlocking {
        val html = fixture("RJ263959.html")
        val ajax = fixture("RJ263959.ajax.json")
        val pagePath = "/maniax/work/=/product_id/RJ263959.html"
        val ajaxPath = "/maniax-touch/product/info/ajax?product_id=RJ263959"
        server.enqueue(MockResponse().setResponseCode(200).setBody(html).setHeader("Content-Type", "text/html; charset=utf-8"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(ajax).setHeader("Content-Type", "application/json"))

        val work = scraper().scrape(RjCode("RJ", "263959"))

        // static fields from the work page HTML
        assertEquals("マギレコ全員H 第四弾", work.title)
        assertEquals("芋焼酎", work.circle)
        assertTrue(work.nsfw)
        assertEquals("2019-09-06", work.releaseDate)
        assertEquals("マギレコ全員H", work.seriesName)
        assertEquals(listOf("内射/中出", "外射", "母乳"), work.tags)
        // dynamic fields from the ajax JSON (NOT in the HTML fixture)
        assertEquals(71, work.dlCount)
        assertEquals(220, work.price)
        assertEquals(2, work.reviewCount)
        assertEquals(42, work.rateCount)
        assertEquals(4.57, work.rateAverage2dp!!, 0.001)
        assertEquals(5, work.rateCountDetail.size)

        val req1 = server.takeRequest()
        assertEquals(pagePath, req1.path)
        assertTrue(req1.getHeader("User-Agent")!!.contains("Mozilla"))
        assertEquals(2, server.requestCount)
        val req2 = server.takeRequest()
        assertEquals(ajaxPath, req2.path)
        assertTrue(req2.getHeader("Referer")!!.contains("RJ263959"))
    }

    @Test
    fun `404 on work page yields NOT_FOUND structured error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("<html>404</html>"))

        val e = runCatching { scraper().scrape(RjCode("RJ", "263959")) }.exceptionOrNull()
        assertTrue(e is DlsiteScrapeException)
        assertEquals(DlsiteScrapeException.Kind.NOT_FOUND, (e as DlsiteScrapeException).kind)
    }

    @Test
    fun `403 yields BLOCKED anti-bot error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))

        val e = runCatching { scraper().scrape(RjCode("RJ", "263959")) }.exceptionOrNull()
        assertTrue(e is DlsiteScrapeException)
        assertEquals(DlsiteScrapeException.Kind.BLOCKED, (e as DlsiteScrapeException).kind)
    }

    @Test
    fun `cloudflare challenge html with 200 yields BLOCKED`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                "<html><body><h1>Just a moment...</h1><p>Checking your browser before accessing.</p></body></html>",
            ),
        )

        val e = runCatching { scraper().scrape(RjCode("RJ", "263959")) }.exceptionOrNull()
        assertTrue(e is DlsiteScrapeException)
        assertEquals(DlsiteScrapeException.Kind.BLOCKED, (e as DlsiteScrapeException).kind)
    }

    @Test
    fun `corrupted html with 200 yields PARSE_ERROR not crash`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>garbage</body></html>"))

        val e = runCatching { scraper().scrape(RjCode("RJ", "263959")) }.exceptionOrNull()
        assertTrue(e is DlsiteScrapeException)
        assertEquals(DlsiteScrapeException.Kind.PARSE_ERROR, (e as DlsiteScrapeException).kind)
    }

    @Test
    fun `corrupted ajax json yields PARSE_ERROR`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture("RJ263959.html")))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{broken json").setHeader("Content-Type", "application/json"))

        val e = runCatching { scraper().scrape(RjCode("RJ", "263959")) }.exceptionOrNull()
        assertTrue(e is DlsiteScrapeException)
        assertEquals(DlsiteScrapeException.Kind.PARSE_ERROR, (e as DlsiteScrapeException).kind)
    }

    @Test
    fun `network failure yields NETWORK error`() = runBlocking {
        // Connection refused (no server listening) -> IOException -> NETWORK.
        // (DISCONNECT_AT_START yields an empty 200 body, which is a PARSE_ERROR
        // path instead — that's a different assertion.)
        val dead = MockWebServer()
        dead.start()
        val deadUrl = dead.url("/").toString()
        dead.shutdown()

        val e = runCatching { DlsiteScraper(baseUrl = deadUrl, maxAttempts = 1).scrape(RjCode("RJ", "263959")) }.exceptionOrNull()
        assertTrue(e is DlsiteScrapeException)
        assertEquals(DlsiteScrapeException.Kind.NETWORK, (e as DlsiteScrapeException).kind)
    }

    @Test
    fun `BJ and VJ codes build books and pro site urls`() = runBlocking {
        // BJ -> books site, VJ -> pro site (current DLsite routing, see
        // DlsiteScraper siteFor); best-effort parse, only RJ guaranteed.
        // The ajax fixture is keyed by the requested code, so serve an inline
        // body for the BJ/VJ keys.
        val html = fixture("RJ263959.html")
        val bjAjax = """{"BJ012345":{"dl_count":1,"price":100,"review_count":0,"rate_count":0,"rate_average_2dp":5.0,"rate_count_detail":[]}}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(html))
        server.enqueue(MockResponse().setResponseCode(200).setBody(bjAjax))

        val bj = scraper().scrape(RjCode("BJ", "012345"))
        assertEquals("マギレコ全員H 第四弾", bj.title) // same parser on books site html
        assertEquals(2, server.requestCount)
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/books/work/=/product_id/BJ012345.html"))
        val reqAjax = server.takeRequest()
        assertTrue(reqAjax.path!!.startsWith("/books-touch/product/info/ajax?product_id=BJ012345"))

        val vjAjax = """{"VJ012345":{"dl_count":1,"price":100,"review_count":0,"rate_count":0,"rate_average_2dp":5.0,"rate_count_detail":[]}}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(html))
        server.enqueue(MockResponse().setResponseCode(200).setBody(vjAjax))
        scraper().scrape(RjCode("VJ", "012345"))
        assertEquals(4, server.requestCount)
        val req2 = server.takeRequest()
        assertTrue(req2.path!!.startsWith("/pro/work/=/product_id/VJ012345.html"))
    }
}
