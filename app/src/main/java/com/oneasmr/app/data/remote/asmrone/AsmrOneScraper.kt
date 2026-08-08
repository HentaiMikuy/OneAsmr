package com.oneasmr.app.data.remote.asmrone

import com.oneasmr.app.data.local.settings.ScrapingLanguage
import com.oneasmr.app.data.remote.RequestPacer
import com.oneasmr.app.data.remote.dlsite.AjaxFields
import com.oneasmr.app.data.remote.dlsite.DlsiteCovers
import com.oneasmr.app.data.remote.dlsite.DlsiteScrapeException
import com.oneasmr.app.data.remote.dlsite.DlsiteScraperApi
import com.oneasmr.app.data.remote.dlsite.ScrapedWork
import com.oneasmr.app.domain.rjcode.RjCode
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * asmr.one metadata scraper — JSON-API fallback source for works DLsite
 * fails on. Implements [DlsiteScraperApi] so callers (ScrapeRepository)
 * consume both sources through one surface.
 *
 * API shape (cross-validated across five typed clients + the server source,
 * see AsmrOneModels.kt):
 *  1. POST {base}/api/auth/me with {"name":"guest","password":"guest"}
 *     -> {"token": "<JWT>"}  (guest is a public, documented account)
 *  2. GET  {base}/api/workInfo/{numericId}  with `Authorization: Bearer <JWT>`
 *     (numericId is the digits ONLY, no "RJ" prefix)
 *
 * TOKEN CACHING: the JWT's TTL is ~365 days (inside the opaque token), so
 * authenticating per scrape would hammer the auth endpoint for no benefit.
 * The first scrape authenticates lazily behind [tokenMutex] (concurrent
 * scrapes share one auth round-trip); later scrapes reuse the cached token.
 * The cache self-refreshes after [TOKEN_REFRESH_AFTER_MILLIS] (300 days, a
 * safety margin under the nominal 365) using the injected [clock], and is
 * also invalidated on a 401 (see below).
 *
 * RE-AUTH-ONCE on 401: a 401 from workInfo means the cached token was
 * invalidated server-side (restart, secret rotation). The scraper clears the
 * cache, re-authenticates ONCE and retries the request ONCE. A second 401 is
 * mapped to BLOCKED — looping would hammer the auth endpoint and disguise an
 * account/IP-level block as flakiness.
 *
 * ERROR MAPPING (mirrors DlsiteScraper semantics):
 *  - IOException/timeout            -> NETWORK (retryable)
 *  - 404                            -> NOT_FOUND (work does not exist)
 *  - 403 / 429                      -> BLOCKED (rate limit / anti-bot)
 *  - 401 after the single retry     -> BLOCKED
 *  - auth POST IO/5xx failure       -> NETWORK; auth POST 401/403 -> BLOCKED
 *  - malformed JSON / missing title -> PARSE_ERROR
 *  All messages are prefixed "asmr.one: " so logs distinguish the source.
 *
 * HEADERS: every request carries `Origin: https://asmr.one`,
 * `Referer: https://asmr.one/` and a browser-like User-Agent — the server
 * rejects requests without them. These stay https://asmr.one even when
 * [baseUrl] points at a mirror (api.asmr-100/200/300.com serve identical
 * paths); the mirror is only a host swap, not a rebrand.
 *
 * PACING: [pacer] is the process-global [RequestPacer] singleton; pace() is
 * called before EVERY HTTP request (auth POST included, re-auth and retry
 * included) so this source counts toward the same 1s courtesy gate as DLsite.
 *
 * MAPPING NOTES:
 *  - rjCode: ALWAYS the caller's [RjCode.canonical], never the payload's
 *    source_id. Covers are stored and looked up under the LOCAL code
 *    (CoverStore keys files by the library's canonical form), and asmr.one
 *    sometimes stores unpadded ids (e.g. "RJ1227734" where the library has
 *    "RJ01227734") — trusting source_id could save covers under a name the
 *    UI never looks up. Matches DlsiteScraper's output shape.
 *  - seriesName: always null — asmr.one has no series concept.
 *  - covers: main/sam/thumb240 from mainCoverUrl/samCoverUrl/
 *    thumbnailCoverUrl; thumb360 is null (asmr.one ships no 360px variant).
 *  - Non-RJ prefixes (BJ/VJ): rejected with PARSE_ERROR BEFORE any HTTP —
 *    asmr.one is an RJ-only database (defense in depth).
 *
 * JVM-pure: no Android types; tests drive it against MockWebServer.
 *
 * @param baseUrl API root (injectable for tests/mirrors); trailing slash trimmed
 * @param client OkHttp client; defaults to a timeout-bounded production client
 * @param pacer shared global request pacer (ScrapeModule singleton)
 * @param language tag i18n preference (ZH: zh-cn -> ja-jp -> raw; JA: ja-jp -> raw)
 * @param clock time source for the token-cache TTL; tests inject a fake
 */
class AsmrOneScraper(
    baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = defaultClient(),
    private val pacer: RequestPacer,
    private val language: ScrapingLanguage = ScrapingLanguage.ZH,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : DlsiteScraperApi {

    private val baseUrl = baseUrl.trimEnd('/')

    private val tokenMutex = Mutex()
    private var cachedToken: String? = null
    private var tokenObtainedAt: Long = 0L

    /** Marker for "server answered 401" so scrape() can re-auth once and retry. */
    private class UnauthorizedException : Exception()

    override suspend fun scrape(code: RjCode): ScrapedWork {
        // asmr.one indexes RJ works only; reject others without any HTTP
        // (a BJ/VJ lookup would 404 anyway — this saves two paced requests).
        if (code.prefix != "RJ") {
            throw DlsiteScrapeException(
                DlsiteScrapeException.Kind.PARSE_ERROR,
                "asmr.one: unsupported prefix ${code.prefix} (RJ only)",
            )
        }

        val token = token()
        val body = try {
            fetchWorkInfo(code.digits, token)
        } catch (e: UnauthorizedException) {
            // Cached token rejected: clear, re-auth ONCE, retry ONCE (see KDoc).
            clearToken()
            try {
                fetchWorkInfo(code.digits, token())
            } catch (e2: UnauthorizedException) {
                throw DlsiteScrapeException(
                    DlsiteScrapeException.Kind.BLOCKED,
                    "asmr.one: 401 persists after re-auth (token rejected)",
                )
            }
        }
        return toScrapedWork(parseWorkInfo(body), code)
    }

    /**
     * Returns a usable bearer token: cached when fresh (under
     * [TOKEN_REFRESH_AFTER_MILLIS]), otherwise authenticates. Serialized by
     * [tokenMutex] so concurrent scrapes share one auth round-trip.
     */
    private suspend fun token(): String = tokenMutex.withLock {
        val cached = cachedToken
        if (cached != null && clock() - tokenObtainedAt < TOKEN_REFRESH_AFTER_MILLIS) {
            return@withLock cached
        }
        val fresh = authenticate()
        cachedToken = fresh
        tokenObtainedAt = clock()
        fresh
    }

    private suspend fun clearToken() = tokenMutex.withLock {
        cachedToken = null
    }

    /** POST /api/auth/me with the public guest account. */
    private suspend fun authenticate(): String {
        pacer.pace()
        val payload = json.encodeToString(AsmrOneAuthRequest.serializer(), GUEST_AUTH)
        val request = Request.Builder()
            .url("$baseUrl/api/auth/me")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .apply { commonHeaders().forEach { (k, v) -> header(k, v) } }
            .build()
        val body = try {
            execute(request)
        } catch (e: UnauthorizedException) {
            // 401 on the AUTH endpoint is not retryable (guest rejected) -> BLOCKED.
            throw DlsiteScrapeException(DlsiteScrapeException.Kind.BLOCKED, "asmr.one: guest auth rejected (401)")
        }
        val response = try {
            json.decodeFromString(AsmrOneAuthResponse.serializer(), body)
        } catch (e: SerializationException) {
            throw DlsiteScrapeException(DlsiteScrapeException.Kind.PARSE_ERROR, "asmr.one: malformed auth response", e)
        } catch (e: IllegalArgumentException) {
            throw DlsiteScrapeException(DlsiteScrapeException.Kind.PARSE_ERROR, "asmr.one: malformed auth response", e)
        }
        return response.token
    }

    /** GET /api/workInfo/{digits} (digits only, no "RJ" prefix) with the bearer token. */
    private suspend fun fetchWorkInfo(digits: String, token: String): String {
        pacer.pace()
        val request = Request.Builder()
            .url("$baseUrl/api/workInfo/$digits")
            .header("Authorization", "Bearer $token")
            .apply { commonHeaders().forEach { (k, v) -> header(k, v) } }
            .build()
        return execute(request)
    }

    /**
     * Executes [request] and maps the outcome to structured errors.
     * 401 is NOT mapped here — it throws [UnauthorizedException] so the caller
     * decides between re-auth-retry (workInfo) and BLOCKED (auth endpoint).
     */
    private fun execute(request: Request): String {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw DlsiteScrapeException(DlsiteScrapeException.Kind.NETWORK, "asmr.one: IO failure: ${request.url}", e)
        }
        response.use { resp ->
            when {
                resp.code == 401 -> throw UnauthorizedException()
                resp.code == 404 -> throw DlsiteScrapeException(
                    DlsiteScrapeException.Kind.NOT_FOUND,
                    "asmr.one: 404 from ${request.url}",
                )
                resp.code == 403 || resp.code == 429 -> throw DlsiteScrapeException(
                    DlsiteScrapeException.Kind.BLOCKED,
                    "asmr.one: rate limit / block (${resp.code}) from ${request.url}",
                )
                !resp.isSuccessful -> throw DlsiteScrapeException(
                    DlsiteScrapeException.Kind.NETWORK,
                    "asmr.one: HTTP ${resp.code} from ${request.url}",
                )
            }
            // OkHttp 5 guarantees a non-null body; an empty one fails JSON
            // decoding downstream as PARSE_ERROR (same shape as DlsiteScraper).
            return resp.body.string()
        }
    }

    private fun parseWorkInfo(body: String): AsmrOneWorkInfo = try {
        // Missing `title` -> MissingFieldException (a SerializationException),
        // which is exactly the malformed-payload case.
        json.decodeFromString(AsmrOneWorkInfo.serializer(), body)
    } catch (e: SerializationException) {
        throw DlsiteScrapeException(DlsiteScrapeException.Kind.PARSE_ERROR, "asmr.one: malformed workInfo payload", e)
    } catch (e: IllegalArgumentException) {
        throw DlsiteScrapeException(DlsiteScrapeException.Kind.PARSE_ERROR, "asmr.one: malformed workInfo payload", e)
    }

    /** Maps the DTO to the shared [ScrapedWork] result (see class KDoc for rules). */
    private fun toScrapedWork(info: AsmrOneWorkInfo, code: RjCode): ScrapedWork = ScrapedWork(
        rjCode = code.canonical, // always the local code — see class KDoc
        title = info.title,
        circle = info.name,
        nsfw = info.nsfw,
        releaseDate = info.release,
        seriesName = null, // asmr.one has no series concept
        tags = info.tags.orEmpty().map(::tagName),
        vas = info.vas.map { it.name },
        covers = DlsiteCovers(
            main = info.mainCoverUrl,
            sam = info.samCoverUrl,
            thumb240 = info.thumbnailCoverUrl,
            thumb360 = null, // asmr.one ships no 360px thumbnail variant
        ),
        dlCount = info.dlCount,
        price = info.price,
        reviewCount = info.reviewCount,
        rateCount = info.rateCount,
        rateAverage2dp = info.rateAverage2dp,
        rateCountDetail = info.rateCountDetail.map {
            AjaxFields.RateCountDetail(reviewPoint = it.reviewPoint, count = it.count, ratio = it.ratio)
        },
    )

    /**
     * Localized tag name. ZH prefers the Simplified-Chinese i18n entry, falls
     * back to the Japanese one, then to the raw (usually English) name; JA
     * prefers the Japanese entry, then the raw name.
     */
    private fun tagName(tag: AsmrOneTag): String = when (language) {
        ScrapingLanguage.ZH -> tag.i18n?.zhCn?.name ?: tag.i18n?.jaJp?.name ?: tag.name
        ScrapingLanguage.JA -> tag.i18n?.jaJp?.name ?: tag.name
    }

    /**
     * Origin/Referer stay https://asmr.one even on mirror hosts (the mirrors
     * are transparent host swaps of the same service; the server validates
     * these headers against the canonical origin).
     */
    private fun commonHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Origin" to "https://asmr.one",
        "Referer" to "https://asmr.one/",
        "Accept" to "application/json",
    )

    companion object {
        /** Production asmr.one API root; mirrors: api.asmr-100/200/300.com. */
        const val DEFAULT_BASE_URL = "https://api.asmr.one"

        /**
         * Browser-like UA: asmr.one rejects the default OkHttp UA.
         * Mirrors DlsiteScraper's desktop Chrome string.
         */
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /**
         * Re-authenticate when the cached token is older than this. The JWT
         * TTL is ~365 days (opaque); 300 days leaves a wide safety margin
         * while still making the cache effectively permanent in practice.
         */
        const val TOKEN_REFRESH_AFTER_MILLIS: Long = 300L * 24 * 60 * 60 * 1000

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val GUEST_AUTH = AsmrOneAuthRequest(name = "guest", password = "guest")

        private val json = Json { ignoreUnknownKeys = true }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false) // pacing + re-auth-once are handled above
            .build()
    }
}
