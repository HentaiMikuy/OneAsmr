package com.oneasmr.app.data.repository

import com.oneasmr.app.data.local.CircleDao
import com.oneasmr.app.data.local.TagDao
import com.oneasmr.app.data.local.VaDao
import com.oneasmr.app.data.local.WorkDao
import com.oneasmr.app.data.local.WorkTagDao
import com.oneasmr.app.data.local.WorkVaDao
import com.oneasmr.app.data.local.settings.SettingsStore
import com.oneasmr.app.data.remote.RequestPacer
import com.oneasmr.app.data.remote.asmrone.AsmrOneScraper
import com.oneasmr.app.data.remote.dlsite.DlsiteScraper
import com.oneasmr.app.data.remote.dlsite.DlsiteScraperApi
import com.oneasmr.app.data.remote.dlsite.FallbackScraper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient

/**
 * Task 11 Hilt wiring: [ScrapeRepository] with a scraper factory that builds
 * the asmr.one fallback chain.
 *
 * The factory runs per scrape entry point (every scrapeOne / batchScrape
 * call) and ALL settings are read at that moment — none are cached in this
 * singleton — so toggling the fallback or editing the mirror takes effect
 * without a process restart. This mirrors the existing debug-override
 * convention below. (The fallback SETTINGS are snapshotted eagerly in the
 * suspend factory because [FallbackScraper]'s provider is non-suspend; the
 * provider itself stays lazy — the [AsmrOneScraper] is only constructed
 * after an actual primary failure.)
 *
 * Chain shape ([FallbackScraper]):
 *  - Primary: [DlsiteScraper], honoring the debug base-url override
 *    ([SettingsStore.scraperBaseUrlOverride] — blank = production DLsite).
 *    The UI that writes it is FLAG_DEBUGGABLE-gated (ScrapeViewModel);
 *    release builds never see it.
 *  - Fallback: [AsmrOneScraper], provided lazily and only when
 *    [SettingsStore.asmrOneFallbackEnabled] is on; a blank
 *    [SettingsStore.asmrOneBaseUrl] means [SettingsStore.DEFAULT_ASMR_ONE_BASE_URL].
 *    Fallback eligibility (RJ-only — asmr.one indexes no BJ/VJ works) lives
 *    INSIDE [FallbackScraper]; this wiring does not re-check it.
 *
 * The debug DLsite override and the user-facing asmr.one mirror are
 * INDEPENDENT settings: the override retargets the primary only (QA proxy),
 * the mirror retargets the fallback only, and neither implies the other.
 *
 * [provideRequestPacer]'s singleton is handed BOTH to the repository
 * (batch-loop pacing) and to [AsmrOneScraper] (its auth + workInfo HTTP
 * calls), so DLsite and asmr.one requests count toward the same global 1s
 * courtesy gate.
 */
@Module
@InstallIn(SingletonComponent::class)
object ScrapeModule {

    /**
     * The app-wide request gate: ONE singleton so every scrape source (today
     * DLsite via [ScrapeRepository]; the upcoming asmr.one fallback) paces
     * its HTTP requests against the same global 1s interval. Provided here
     * rather than left to the @Inject constructor — Dagger cannot bind the
     * constructor's function types (Function0 wildcard mismatch, repo
     * learning), the same reason CoverStore is provided in CoverModule.
     */
    @Provides
    @Singleton
    fun provideRequestPacer(): RequestPacer = RequestPacer()

    @Provides
    @Singleton
    fun provideScrapeRepository(
        workDao: WorkDao,
        circleDao: CircleDao,
        tagDao: TagDao,
        vaDao: VaDao,
        workTagDao: WorkTagDao,
        workVaDao: WorkVaDao,
        coverStore: CoverStore,
        settingsStore: SettingsStore,
        client: OkHttpClient,
        requestPacer: RequestPacer,
    ): ScrapeRepository = ScrapeRepository(
        workDao = workDao,
        circleDao = circleDao,
        tagDao = tagDao,
        vaDao = vaDao,
        workTagDao = workTagDao,
        workVaDao = workVaDao,
        coverStore = coverStore,
        scraperFactory = {
            val override = settingsStore.scraperBaseUrlOverride.first()
            val fallbackEnabled = settingsStore.asmrOneFallbackEnabled.first()
            val asmrOneBaseUrl = settingsStore.asmrOneBaseUrl.first()
            val language = settingsStore.scrapingLanguage.first()
            FallbackScraper(
                primary = DlsiteScraper(
                    baseUrl = if (override.isBlank()) DlsiteScraperApi.DEFAULT_BASE_URL else override.trimEnd('/'),
                    client = client,
                ),
                fallback = {
                    if (!fallbackEnabled) {
                        null
                    } else {
                        AsmrOneScraper(
                            baseUrl = asmrOneBaseUrl.ifBlank { SettingsStore.DEFAULT_ASMR_ONE_BASE_URL },
                            client = client,
                            pacer = requestPacer,
                            language = language,
                        )
                    }
                },
            )
        },
        requestPacer = requestPacer,
    )
}
