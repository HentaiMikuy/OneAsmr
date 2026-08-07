package com.oneasmr.app.data.repository

/**
 * Single-work scrape entry (plan Task 11) narrowed to what the detail page
 * needs — one work, never throws, status written to the DB by the impl.
 *
 * An interface (rather than the concrete [ScrapeRepository]) keeps
 * WorkDetailViewModel unit-testable: tests inject a fake that returns a fixed
 * [ScrapeOutcome] without spinning up the real scraper/cover stack.
 */
fun interface SingleWorkScraper {
    suspend fun scrapeOne(workId: String): ScrapeOutcome
}
