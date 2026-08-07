package com.oneasmr.app.data.remote.dlsite

/**
 * Structured scrape failure (plan Task 9 item 5).
 *
 * - [Kind.NETWORK]: connection/timeout/IO failure — retryable
 * - [Kind.NOT_FOUND]: HTTP 404 (work does not exist / region-blocked) — non-retryable
 * - [Kind.BLOCKED]: anti-bot (403/Cloudflare challenge) — non-retryable, batch callers
 *   must rate-limit (Task 11: >=1s interval, concurrency <=2)
 * - [Kind.PARSE_ERROR]: page structure changed / malformed payload — retryable later
 */
class DlsiteScrapeException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind { NETWORK, NOT_FOUND, BLOCKED, PARSE_ERROR }
}
