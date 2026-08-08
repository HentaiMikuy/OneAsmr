package com.oneasmr.app.data.remote.asmrone

import com.oneasmr.app.data.local.settings.ScrapingLanguage
import com.oneasmr.app.data.remote.RequestPacer
import com.oneasmr.app.data.remote.dlsite.DlsiteScrapeException
import com.oneasmr.app.domain.rjcode.RjCode
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end spec tests for [AsmrOneScraper] against MockWebServer.
 *
 * Covers:
 *  (a) first scrape: auth POST then workInfo GET; bearer header, static
 *      Origin/Referer, numeric-only path
 *  (b) token caching: second scrape reuses the token (1 auth + 2 workInfo)
 *  (c) 401 -> re-auth + retry once succeeds; double 401 -> BLOCKED
 *  (d) 404 -> NOT_FOUND; 403/429 -> BLOCKED; garbage JSON -> PARSE_ERROR;
 *      connection failure -> NETWORK
 *  (e) full field mapping incl. rate_count_detail, i18n tag selection
 *      (ZH and JA), cover URLs
 *  (f) pacer invoked before every HTTP request (auth + workInfo = 2, then 1)
 *  (g) BJ/VJ prefix -> PARSE_ERROR with ZERO requests
 */
class AsmrOneScraperTest {

    private lateinit var server: MockWebServer

    /**
     * Recording pacer: the clock never advances, so after one warm-up pace()
     * (a brand-new RequestPacer never delays on its first call) EVERY pace()
     * triggers paceDelay — which is what we count.
     */
    private var paceCalls = 0
    private lateinit var pacer: RequestPacer

    @Before
    fun setUp() = runBlocking {
        server = MockWebServer()
        server.start()
        paceCalls = 0
        pacer = RequestPacer(clock = { 0L }, paceDelay = { paceCalls++ }, minRequestIntervalMillis = 1_000L)
        pacer.pace() // warm-up: consumes the never-used-pacer free slot
        paceCalls = 0
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun scraper(language: ScrapingLanguage = ScrapingLanguage.ZH): AsmrOneScraper =
        AsmrOneScraper(
            baseUrl = server.url("/").toString(),
            pacer = pacer,
            language = language,
        )

    private fun enqueueAuth(token: String = "tok1") {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"token":"$token"}"""),
        )
    }

    private fun enqueueWorkInfo(body: String = FULL_WORK_JSON) {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    private fun kindOf(e: Throwable?): DlsiteScrapeException.Kind {
        assertTrue("expected DlsiteScrapeException, got $e", e is DlsiteScrapeException)
        return (e as DlsiteScrapeException).kind
    }

    // (a) first scrape: auth POST then workInfo GET with bearer + static headers
    @Test
    fun `first scrape authenticates then fetches workInfo with bearer and origin headers`() = runBlocking {
        enqueueAuth("tok-abc")
        enqueueWorkInfo()

        val work = scraper().scrape(RjCode("RJ", "1227734"))

        assertEquals("テスト作品", work.title)

        val authReq = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", authReq.method)
        assertEquals("/api/auth/me", authReq.path)
        assertEquals("https://asmr.one", authReq.getHeader("Origin"))
        assertEquals("https://asmr.one/", authReq.getHeader("Referer"))
        assertTrue(authReq.getHeader("User-Agent")!!.contains("Mozilla"))
        assertTrue(authReq.getHeader("Content-Type")!!.startsWith("application/json"))
        assertTrue(authReq.body.readUtf8().contains("\"guest\""))

        val infoReq = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", infoReq.method)
        assertEquals("/api/workInfo/1227734", infoReq.path) // digits only, no RJ prefix
        assertEquals("Bearer tok-abc", infoReq.getHeader("Authorization"))
        assertEquals("https://asmr.one", infoReq.getHeader("Origin"))
        assertEquals("https://asmr.one/", infoReq.getHeader("Referer"))
        assertEquals(2, server.requestCount)
    }

    // (b) second scrape reuses the cached token
    @Test
    fun `second scrape reuses cached token without re-authenticating`() = runBlocking {
        enqueueAuth("tok-abc")
        enqueueWorkInfo()
        enqueueWorkInfo()

        val s = scraper()
        s.scrape(RjCode("RJ", "1227734"))
        s.scrape(RjCode("RJ", "1227734"))

        assertEquals(3, server.requestCount) // 1 auth + 2 workInfo
        assertEquals("/api/auth/me", server.takeRequest().path)
        assertEquals("/api/workInfo/1227734", server.takeRequest().path)
        val second = server.takeRequest()
        assertEquals("/api/workInfo/1227734", second.path)
        assertEquals("Bearer tok-abc", second.getHeader("Authorization"))
    }

    // (c) 401 -> clear token, re-auth once, retry once -> success
    @Test
    fun `401 on workInfo triggers re-auth and one retry which succeeds`() = runBlocking {
        enqueueAuth("tok-old")
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid token"}"""))
        enqueueAuth("tok-new")
        enqueueWorkInfo()

        val work = scraper().scrape(RjCode("RJ", "1227734"))

        assertEquals("テスト作品", work.title)
        assertEquals(4, server.requestCount) // auth, 401, re-auth, retry
        server.takeRequest() // auth
        server.takeRequest() // 401 workInfo
        server.takeRequest() // re-auth
        val retry = server.takeRequest()
        assertEquals("Bearer tok-new", retry.getHeader("Authorization"))
    }

    // (c) double 401 -> BLOCKED, no third attempt
    @Test
    fun `double 401 yields BLOCKED without further retries`() = runBlocking {
        enqueueAuth("tok-old")
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid token"}"""))
        enqueueAuth("tok-new")
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"still invalid"}"""))

        val e = runCatching { scraper().scrape(RjCode("RJ", "1227734")) }.exceptionOrNull()
        assertEquals(DlsiteScrapeException.Kind.BLOCKED, kindOf(e))
        assertEquals(4, server.requestCount) // auth, 401, re-auth, 401 — stop
    }

    // (d) error mapping
    @Test
    fun `404 yields NOT_FOUND`() = runBlocking {
        enqueueAuth()
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"work not found"}"""))

        val e = runCatching { scraper().scrape(RjCode("RJ", "999999")) }.exceptionOrNull()
        assertEquals(DlsiteScrapeException.Kind.NOT_FOUND, kindOf(e))
    }

    @Test
    fun `403 yields BLOCKED`() = runBlocking {
        enqueueAuth()
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))

        val e = runCatching { scraper().scrape(RjCode("RJ", "1227734")) }.exceptionOrNull()
        assertEquals(DlsiteScrapeException.Kind.BLOCKED, kindOf(e))
    }

    @Test
    fun `429 rate limit yields BLOCKED`() = runBlocking {
        enqueueAuth()
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"too many requests"}"""))

        val e = runCatching { scraper().scrape(RjCode("RJ", "1227734")) }.exceptionOrNull()
        assertEquals(DlsiteScrapeException.Kind.BLOCKED, kindOf(e))
    }

    @Test
    fun `garbage workInfo json yields PARSE_ERROR`() = runBlocking {
        enqueueAuth()
        enqueueWorkInfo("{not json")

        val e = runCatching { scraper().scrape(RjCode("RJ", "1227734")) }.exceptionOrNull()
        assertEquals(DlsiteScrapeException.Kind.PARSE_ERROR, kindOf(e))
    }

    @Test
    fun `workInfo without title yields PARSE_ERROR`() = runBlocking {
        enqueueAuth()
        enqueueWorkInfo("""{"id":1227734,"name":"circle only"}""")

        val e = runCatching { scraper().scrape(RjCode("RJ", "1227734")) }.exceptionOrNull()
        assertEquals(DlsiteScrapeException.Kind.PARSE_ERROR, kindOf(e))
    }

    @Test
    fun `connection failure yields NETWORK`() = runBlocking {
        // Connection refused (no server listening) -> IOException -> NETWORK.
        val dead = MockWebServer()
        dead.start()
        val deadUrl = dead.url("/").toString()
        dead.shutdown()

        val e = runCatching {
            AsmrOneScraper(baseUrl = deadUrl, pacer = pacer).scrape(RjCode("RJ", "1227734"))
        }.exceptionOrNull()
        assertEquals(DlsiteScrapeException.Kind.NETWORK, kindOf(e))
    }

    // (e) full field mapping, ZH tag selection
    @Test
    fun `full field mapping with ZH tag i18n selection`() = runBlocking {
        enqueueAuth()
        enqueueWorkInfo()

        val work = scraper(ScrapingLanguage.ZH).scrape(RjCode("RJ", "1227734"))

        // The payload's source_id ("RJ01227734", zero-padded) deliberately
        // diverges from the input code: the LOCAL canonical form always wins,
        // or covers would be stored under a name the UI never looks up.
        assertEquals("RJ1227734", work.rjCode)
        assertEquals("テスト作品", work.title)
        assertEquals("テストサークル", work.circle)
        assertTrue(work.nsfw)
        assertEquals("2024-05-01", work.releaseDate)
        assertNull(work.seriesName) // asmr.one has no series concept
        // ZH: zh-cn -> ja-jp -> raw name
        assertEquals(listOf("标签中", "日のみ", "plain"), work.tags)
        assertEquals(listOf("声優A", "声優B"), work.vas)
        assertEquals("https://img.example/main.jpg", work.covers.main)
        assertEquals("https://img.example/sam.jpg", work.covers.sam)
        assertEquals("https://img.example/thumb.jpg", work.covers.thumb240)
        assertNull(work.covers.thumb360)
        assertEquals(1234, work.dlCount)
        assertEquals(1100, work.price)
        assertEquals(56, work.reviewCount)
        assertEquals(789, work.rateCount)
        assertEquals(4.56, work.rateAverage2dp!!, 0.001)
        assertEquals(2, work.rateCountDetail.size)
        assertEquals(5, work.rateCountDetail[0].reviewPoint)
        assertEquals(100, work.rateCountDetail[0].count)
        assertEquals(60, work.rateCountDetail[0].ratio)
        assertEquals(4, work.rateCountDetail[1].reviewPoint)
    }

    // (e) JA tag selection: ja-jp -> raw name
    @Test
    fun `JA tag i18n selection prefers ja-jp then raw name`() = runBlocking {
        enqueueAuth()
        enqueueWorkInfo()

        val work = scraper(ScrapingLanguage.JA).scrape(RjCode("RJ", "1227734"))

        assertEquals(listOf("タグ日", "日のみ", "plain"), work.tags)
    }

    // (e) rjCode falls back to the caller's canonical form without source_id
    @Test
    fun `rjCode falls back to canonical input when source_id is absent`() = runBlocking {
        enqueueAuth()
        enqueueWorkInfo(MINIMAL_WORK_JSON)

        val work = scraper().scrape(RjCode("RJ", "01234567"))

        assertEquals("RJ01234567", work.rjCode)
        assertEquals("minimal", work.title)
        assertEquals(emptyList<String>(), work.tags) // tags: null server-side
        assertEquals(emptyList<String>(), work.vas)
        assertNull(work.circle)
    }

    // (f) pacer invoked before every HTTP request
    @Test
    fun `pacer is invoked before every request including auth`() = runBlocking {
        enqueueAuth()
        enqueueWorkInfo()
        enqueueWorkInfo()

        val s = scraper()
        s.scrape(RjCode("RJ", "1227734"))
        assertEquals(2, paceCalls) // auth POST + workInfo GET
        s.scrape(RjCode("RJ", "1227734"))
        assertEquals(3, paceCalls) // cached token -> workInfo only
    }

    // (g) non-RJ prefixes rejected before any HTTP
    @Test
    fun `BJ and VJ prefixes yield PARSE_ERROR with zero requests`() = runBlocking {
        val e1 = runCatching { scraper().scrape(RjCode("BJ", "012345")) }.exceptionOrNull()
        assertEquals(DlsiteScrapeException.Kind.PARSE_ERROR, kindOf(e1))
        val e2 = runCatching { scraper().scrape(RjCode("VJ", "012345")) }.exceptionOrNull()
        assertEquals(DlsiteScrapeException.Kind.PARSE_ERROR, kindOf(e2))
        assertEquals(0, server.requestCount)
        assertEquals(0, paceCalls)
    }

    private companion object {
        /**
         * Rich work-info payload: every mapped field plus ignored extras
         * (language_editions polymorphic array, rank, translation_info, ...)
         * to prove ignoreUnknownKeys tolerance.
         */
        val FULL_WORK_JSON = """
            {
              "id": 1227734,
              "source_id": "RJ01227734",
              "title": "テスト作品",
              "name": "テストサークル",
              "circle": {"id": 42, "name": "テストサークル"},
              "nsfw": true,
              "release": "2024-05-01",
              "dl_count": 1234,
              "price": 1100,
              "review_count": 56,
              "rate_count": 789,
              "rate_average_2dp": 4.56,
              "rate_count_detail": [
                {"review_point": 5, "count": 100, "ratio": 60},
                {"review_point": 4, "count": 50, "ratio": 30}
              ],
              "has_subtitle": true,
              "duration": 3600,
              "vas": [
                {"id": "11111111-2222-3333-4444-555555555555", "name": "声優A"},
                {"id": "66666666-7777-8888-9999-000000000000", "name": "声優B"}
              ],
              "tags": [
                {"id": 10, "name": "tag-raw", "i18n": {"zh-cn": {"name": "标签中"}, "ja-jp": {"name": "タグ日"}, "en-us": {"name": "tag-en"}}},
                {"id": 11, "name": "fallback-raw", "i18n": {"ja-jp": {"name": "日のみ"}}},
                {"id": 12, "name": "plain", "i18n": null}
              ],
              "mainCoverUrl": "https://img.example/main.jpg",
              "samCoverUrl": "https://img.example/sam.jpg",
              "thumbnailCoverUrl": "https://img.example/thumb.jpg",
              "language_editions": [],
              "rank": null,
              "translation_info": {},
              "original_workno": null,
              "other_language_editions_in_db": [],
              "userRating": null,
              "progress": null,
              "create_date": "2024-05-01T00:00:00Z",
              "updated_at": "2024-05-02T00:00:00Z",
              "age_category_string": "adult",
              "work_attributes": "VTuber",
              "source_type": "DLSITE",
              "source_url": "https://www.dlsite.com/maniax/work/=/product_id/RJ1227734.html",
              "circle_id": 42
            }
        """.trimIndent()

        /** No source_id, no tags/vas/circle — exercises fallbacks and nullables. */
        val MINIMAL_WORK_JSON = """
            {
              "id": 1234567,
              "title": "minimal",
              "nsfw": false,
              "tags": null,
              "vas": [],
              "rate_count_detail": []
            }
        """.trimIndent()
    }
}
