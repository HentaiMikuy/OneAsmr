package com.oneasmr.app.data.remote.dlsite

import com.oneasmr.app.domain.rjcode.RjCode
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * DLsite metadata scraper (plan Task 9).
 *
 * TWO requests per work, verified against kikoeru-express scraper/dlsite.js
 * @ dd030f3 and the live DLsite structure (2026-08-07):
 *  1. Work page HTML (static fields: title/circle/nsfw/release/series/tags/vas/covers)
 *  2. Dynamic-fields JSON from the `{site}-touch/product/info/ajax` endpoint
 *     (dl_count/price/review_count/rate_count/rate_average_2dp/rate_count_detail)
 *
 * Site routing per work-code prefix (current DLsite URL structure, observed
 * 2026-08-07 in the maniax page's route table and kikoeru's dlsite.js):
 *  - RJ -> maniax:        https://www.dlsite.com/maniax/work/=/product_id/{RJ}.html
 *         ajax:          https://www.dlsite.com/maniax-touch/product/info/ajax?product_id={RJ}
 *  - BJ -> books:         https://www.dlsite.com/books/work/=/product_id/{BJ}.html
 *         ajax:          https://www.dlsite.com/books-touch/product/info/ajax?product_id={BJ}
 *  - VJ -> pro:           https://www.dlsite.com/pro/work/=/product_id/{VJ}.html
 *         ajax:          https://www.dlsite.com/pro-touch/product/info/ajax?product_id={VJ}
 *
 * SCOPE LIMITATION: only RJ is guaranteed parseable. BJ/VJ pages use the same
 * table layout today, but their field labels/layout differ and may change;
 * BJ/VJ scraping is best-effort — a parse failure surfaces as
 * [DlsiteScrapeException.Kind.PARSE_ERROR] and the caller marks the work
 * FAILED (non-blocking, retryable). There is deliberately NO HVDB fallback.
 *
 * Anti-bot posture (DLsite sits behind Cloudflare):
 * - every request carries a realistic browser User-Agent + locale cookie
 * - connect/read/call timeouts are mandatory
 * - transient failures (IO, 5xx) retry with exponential backoff
 * - Cloudflare challenges (403 / challenge HTML) map to
 *   [DlsiteScrapeException.Kind.BLOCKED], never to a fake "not found"
 * - BATCH CALLERS MUST RATE-LIMIT: Task 11 enforces >=1s spacing between
 *   works and max 2 concurrent requests; this scraper itself is single-work.
 *
 * Manual-entry only: nothing in this class schedules or auto-triggers
 * scraping; callers invoke [scrape] explicitly.
 *
 * @param baseUrl injectable endpoint root for tests (MockWebServer);
 *        defaults to https://www.dlsite.com
 * @param language locale preference; zh-cn is the kikoeru default
 * @param maxAttempts per-request attempt budget (>=1); 1 disables retry
 * @param backoffMillis base backoff; doubles per attempt
 */
class DlsiteScraper(
    baseUrl: String = "https://www.dlsite.com",
    private val language: DlsiteLanguage = DlsiteLanguage.ZH_CN,
    private val maxAttempts: Int = 3,
    private val backoffMillis: Long = 500L,
    client: OkHttpClient = defaultClient(),
) {
    private val baseUrl = baseUrl.trimEnd('/')
    private val client = client

    /** Site path prefix per code prefix; source: live DLsite routing 2026-08-07 + kikoeru dlsite.js. */
    private fun siteFor(prefix: String): SitePath = when (prefix) {
        "BJ" -> SitePath("books", "books-touch")
        "VJ" -> SitePath("pro", "pro-touch")
        else -> SitePath("maniax", "maniax-touch") // RJ (and any future prefix) -> maniax
    }

    private data class SitePath(val page: String, val touch: String)

    private fun pageUrl(code: RjCode): String {
        val site = siteFor(code.prefix)
        return "$baseUrl/${site.page}/work/=/product_id/${code.canonical}.html"
    }

    private fun ajaxUrl(code: RjCode): String {
        val site = siteFor(code.prefix)
        return "$baseUrl/${site.touch}/product/info/ajax?product_id=${code.canonical}"
    }

    private fun headersFor(code: RjCode, referer: Boolean): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
            "Accept-Language" to language.acceptLanguageHeader,
            "Cookie" to language.localeCookie,
        )
        if (referer) headers["Referer"] = pageUrl(code)
        return headers
    }

    /**
     * Scrapes [code]'s metadata. Throws [DlsiteScrapeException] with a
     * structured kind on any failure (see [DlsiteScrapeException.Kind]).
     */
    suspend fun scrape(code: RjCode): ScrapedWork {
        val pageBody = fetchWithBackoff(pageUrl(code), headersFor(code, referer = false))
        val static = DlsitePageParser.parseWorkPage(pageBody, language)
        val ajaxBody = fetchWithBackoff(ajaxUrl(code), headersFor(code, referer = true))
        val dynamic = DlsiteAjaxParser.parseDynamicFields(ajaxBody, code.canonical)
        return ScrapedWork(
            rjCode = code.canonical,
            title = static.title,
            circle = static.circle,
            nsfw = static.nsfw,
            releaseDate = static.releaseDate,
            seriesName = static.seriesName,
            tags = static.tags,
            vas = static.vas,
            covers = static.covers,
            dlCount = dynamic.dlCount,
            price = dynamic.price,
            reviewCount = dynamic.reviewCount,
            rateCount = dynamic.rateCount,
            rateAverage2dp = dynamic.rateAverage2dp,
            rateCountDetail = dynamic.rateCountDetail,
        )
    }

    /**
     * GETs [url] with per-attempt backoff. Maps HTTP status + body to
     * structured errors: 404 -> NOT_FOUND, 403/Cloudflare challenge -> BLOCKED,
     * 5xx -> retried then NETWORK, IO -> retried then NETWORK.
     */
    private suspend fun fetchWithBackoff(url: String, headers: Map<String, String>): String {
        var lastError: DlsiteScrapeException? = null
        for (attempt in 0 until maxAttempts) {
            if (attempt > 0) delay(backoffMillis * (1L shl (attempt - 1)))
            try {
                return execute(url, headers)
            } catch (e: DlsiteScrapeException) {
                if (e.kind == DlsiteScrapeException.Kind.BLOCKED || e.kind == DlsiteScrapeException.Kind.NOT_FOUND) {
                    throw e // non-retryable
                }
                lastError = e // NETWORK -> retry with backoff
            }
        }
        throw lastError ?: DlsiteScrapeException(DlsiteScrapeException.Kind.NETWORK, "request failed: $url")
    }

    private fun execute(url: String, headers: Map<String, String>): String {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw DlsiteScrapeException(DlsiteScrapeException.Kind.NETWORK, "IO failure: $url", e)
        }
        response.use { resp ->
            if (isCloudflareChallenge(resp)) {
                throw DlsiteScrapeException(DlsiteScrapeException.Kind.BLOCKED, "cloudflare challenge from $url")
            }
            if (resp.code == 404) {
                throw DlsiteScrapeException(DlsiteScrapeException.Kind.NOT_FOUND, "404 from $url")
            }
            if (resp.code == 403 || resp.code == 429) {
                throw DlsiteScrapeException(DlsiteScrapeException.Kind.BLOCKED, "anti-bot block (${resp.code}) from $url")
            }
            if (!resp.isSuccessful) {
                throw DlsiteScrapeException(DlsiteScrapeException.Kind.NETWORK, "HTTP ${resp.code} from $url")
            }
            return resp.body?.string() ?: ""
        }
    }

    /**
     * Cloudflare interstitial pages ("Just a moment...", "cf-chl-*" DOM ids)
     * are served with 200/503; treat them as BLOCKED, not as page content.
     */
    private fun isCloudflareChallenge(resp: Response): Boolean {
        if (resp.code != 200 && resp.code != 503) return false
        val body = resp.peekBody(4096).string().lowercase()
        return body.contains("just a moment") || body.contains("cf-chl") ||
            body.contains("checking your browser")
    }

    private companion object {
        /** Realistic desktop Chrome UA: DLsite rejects default OkHttp UA (no scheme/host). */
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false) // our own backoff loop handles retries
            .build()
    }
}

/** Locale cookie + Accept-Language for the preference (zh-cn default, kikoeru semantics). */
private val DlsiteLanguage.acceptLanguageHeader: String
    get() = when (this) {
        DlsiteLanguage.JA_JP -> "ja-JP,ja;q=0.9"
        DlsiteLanguage.ZH_TW -> "zh-TW,zh;q=0.9"
        DlsiteLanguage.ZH_CN -> "zh-CN,zh;q=0.9"
    }
