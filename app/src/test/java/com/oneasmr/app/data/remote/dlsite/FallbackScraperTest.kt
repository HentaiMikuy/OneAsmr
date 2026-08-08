package com.oneasmr.app.data.remote.dlsite

import com.oneasmr.app.domain.rjcode.RjCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * FallbackScraper unit tests with recording fakes (no network, no Android).
 *
 * Covers:
 * - primary success: fallback provider lambda is never even invoked
 * - every DlsiteScrapeException.Kind (NETWORK/NOT_FOUND/BLOCKED/PARSE_ERROR)
 *   from the primary triggers the fallback
 * - fallback() == null (source disabled): primary exception propagates unchanged
 * - BJ/VJ codes are ineligible: primary exception propagates, fallback untouched
 * - fallback failure: the FALLBACK's exception propagates (kind + message)
 * - CancellationException from either call propagates without falling back
 */
class FallbackScraperTest {

    private class FakeScraper(
        private val result: ScrapedWork? = null,
        private val error: Throwable? = null,
    ) : DlsiteScraperApi {
        val calls = mutableListOf<RjCode>()

        override suspend fun scrape(code: RjCode): ScrapedWork {
            calls += code
            error?.let { throw it }
            return checkNotNull(result) { "fake has neither result nor error" }
        }
    }

    private fun scrapedWork(code: RjCode, title: String = "work") = ScrapedWork(
        rjCode = code.canonical,
        title = title,
        circle = null,
        nsfw = false,
        releaseDate = null,
        seriesName = null,
        tags = emptyList(),
        vas = emptyList(),
        covers = DlsiteCovers(null, null, null, null),
        dlCount = null,
        price = null,
        reviewCount = null,
        rateCount = null,
        rateAverage2dp = null,
        rateCountDetail = emptyList(),
    )

    @Test
    fun `primary success returns primary result and never invokes fallback provider`() = runTest {
        val code = RjCode("RJ", "263959")
        val expected = scrapedWork(code, title = "from primary")
        val primary = FakeScraper(result = expected)
        val fallback = FakeScraper(result = scrapedWork(code, title = "from fallback"))
        var providerCalls = 0
        val scraper = FallbackScraper(primary, { providerCalls++; fallback })

        val work = scraper.scrape(code)

        assertSame(expected, work)
        assertEquals(listOf(code), primary.calls)
        assertEquals(0, providerCalls)
        assertTrue(fallback.calls.isEmpty())
    }

    @Test
    fun `every failure kind from primary triggers the fallback`() = runTest {
        val code = RjCode("RJ", "263959")
        for (kind in DlsiteScrapeException.Kind.entries) {
            val primary = FakeScraper(error = DlsiteScrapeException(kind, "primary $kind"))
            val expected = scrapedWork(code, title = "from fallback")
            val fallback = FakeScraper(result = expected)
            val scraper = FallbackScraper(primary, { fallback })

            val work = scraper.scrape(code)

            assertSame("kind $kind", expected, work)
            assertEquals("kind $kind", listOf(code), primary.calls)
            assertEquals("kind $kind", listOf(code), fallback.calls)
        }
    }

    @Test
    fun `null fallback source rethrows primary exception unchanged`() = runTest {
        val code = RjCode("RJ", "263959")
        val primaryError = DlsiteScrapeException(DlsiteScrapeException.Kind.NETWORK, "primary down")
        val primary = FakeScraper(error = primaryError)
        var providerCalls = 0
        val scraper = FallbackScraper(primary, { providerCalls++; null })

        val thrown = try {
            scraper.scrape(code)
            fail("expected DlsiteScrapeException")
        } catch (e: DlsiteScrapeException) {
            e
        }

        assertSame(primaryError, thrown)
        assertEquals(1, providerCalls)
    }

    @Test
    fun `BJ and VJ codes are ineligible - primary exception propagates, fallback not invoked`() = runTest {
        for (prefix in listOf("BJ", "VJ")) {
            val code = RjCode(prefix, "012345")
            val primaryError = DlsiteScrapeException(DlsiteScrapeException.Kind.NOT_FOUND, "primary 404 $prefix")
            val primary = FakeScraper(error = primaryError)
            val fallback = FakeScraper(result = scrapedWork(code))
            var providerCalls = 0
            val scraper = FallbackScraper(primary, { providerCalls++; fallback })

            val thrown = try {
                scraper.scrape(code)
                fail("expected DlsiteScrapeException for $prefix")
            } catch (e: DlsiteScrapeException) {
                e
            }

            assertSame("$prefix", primaryError, thrown)
            assertEquals("$prefix", 0, providerCalls)
            assertTrue("$prefix", fallback.calls.isEmpty())
        }
    }

    @Test
    fun `fallback failure propagates the fallback exception with kind and message`() = runTest {
        val code = RjCode("RJ", "263959")
        val primaryError = DlsiteScrapeException(DlsiteScrapeException.Kind.BLOCKED, "primary blocked")
        val fallbackError = DlsiteScrapeException(DlsiteScrapeException.Kind.PARSE_ERROR, "fallback page changed")
        val primary = FakeScraper(error = primaryError)
        val fallback = FakeScraper(error = fallbackError)
        val scraper = FallbackScraper(primary, { fallback })

        val thrown = runCatching { scraper.scrape(code) }.exceptionOrNull()

        assertTrue(thrown is DlsiteScrapeException)
        thrown as DlsiteScrapeException
        assertSame(fallbackError, thrown)
        assertEquals(DlsiteScrapeException.Kind.PARSE_ERROR, thrown.kind)
        assertEquals("fallback page changed", thrown.message)
    }

    @Test
    fun `cancellation during primary propagates immediately without fallback`() = runTest {
        val code = RjCode("RJ", "263959")
        val primary = FakeScraper(error = CancellationException("cancelled"))
        val fallback = FakeScraper(result = scrapedWork(code))
        var providerCalls = 0
        val scraper = FallbackScraper(primary, { providerCalls++; fallback })

        try {
            scraper.scrape(code)
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }

        assertEquals(0, providerCalls)
        assertTrue(fallback.calls.isEmpty())
    }

    @Test
    fun `cancellation during fallback propagates without being swallowed`() = runTest {
        val code = RjCode("RJ", "263959")
        val primary = FakeScraper(error = DlsiteScrapeException(DlsiteScrapeException.Kind.NETWORK, "primary down"))
        val fallback = FakeScraper(error = CancellationException("cancelled in fallback"))
        val scraper = FallbackScraper(primary, { fallback })

        try {
            scraper.scrape(code)
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("cancelled in fallback", e.message)
        }

        assertEquals(listOf(code), fallback.calls)
    }
}
