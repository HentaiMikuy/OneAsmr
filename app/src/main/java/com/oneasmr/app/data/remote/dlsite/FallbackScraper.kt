package com.oneasmr.app.data.remote.dlsite

import com.oneasmr.app.domain.rjcode.RjCode
import kotlinx.coroutines.CancellationException

/**
 * [DlsiteScraperApi] decorator that retries a failed scrape against an
 * alternate source (e.g. asmr.one) before giving up.
 *
 * Behavior contract of [scrape]:
 * - Primary success short-circuits: the [fallback] provider is never even
 *   invoked, so a disabled/lazily-configured fallback costs nothing on the
 *   happy path.
 * - Fallback fires on ANY [DlsiteScrapeException.Kind] — including NOT_FOUND.
 *   This is a locked product decision: a 404 on DLsite may mean the work is
 *   region-locked or delisted rather than nonexistent, and the alternate
 *   source is the only way to distinguish the two. Fallback failure kinds are
 *   therefore not filtered here; callers that care inspect the final kind.
 * - Eligibility defaults to RJ-only (`prefix == "RJ"`) because asmr.one
 *   indexes voice works exclusively and carries no BJ/VJ entries — a fallback
 *   request for those prefixes is a guaranteed miss and wasted traffic.
 * - When the source is disabled ([fallback] returns null) or the code is
 *   ineligible, the primary's exception is rethrown unchanged so callers see
 *   the exact failure they would have seen without this decorator.
 * - On double failure the FALLBACK's exception wins: it is the last attempt
 *   and reflects the current best knowledge of reality, while the primary
 *   failure is already implied by the fact that the fallback ran at all.
 * - [CancellationException] is always rethrown immediately from either call.
 *   Cancellation is never a scrape failure, must never trigger the fallback
 *   (a cancelled batch must stop, not fan out to a second source), and is
 *   never swallowed.
 *
 * JVM-pure decorator: no Android or network types; both scrapers are faked in
 * unit tests.
 *
 * @param primary the real DLsite scraper (first attempt)
 * @param fallback provider for the alternate scraper; invoked lazily, only
 *        after a primary failure on an eligible code; null = source disabled
 * @param isEligible gate checked before invoking [fallback]; default RJ-only
 */
class FallbackScraper(
    private val primary: DlsiteScraperApi,
    private val fallback: () -> DlsiteScraperApi?,
    private val isEligible: (RjCode) -> Boolean = { it.prefix == "RJ" },
) : DlsiteScraperApi {

    override suspend fun scrape(code: RjCode): ScrapedWork {
        val primaryError = try {
            return primary.scrape(code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DlsiteScrapeException) {
            e
        }
        val alternate = (if (isEligible(code)) fallback() else null) ?: throw primaryError
        return alternate.scrape(code)
    }
}
