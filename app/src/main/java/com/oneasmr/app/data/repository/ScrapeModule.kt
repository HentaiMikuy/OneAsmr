package com.oneasmr.app.data.repository

import com.oneasmr.app.data.local.CircleDao
import com.oneasmr.app.data.local.TagDao
import com.oneasmr.app.data.local.VaDao
import com.oneasmr.app.data.local.WorkDao
import com.oneasmr.app.data.local.WorkTagDao
import com.oneasmr.app.data.local.WorkVaDao
import com.oneasmr.app.data.local.settings.SettingsStore
import com.oneasmr.app.data.remote.dlsite.DlsiteScraper
import com.oneasmr.app.data.remote.dlsite.DlsiteScraperApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient

/**
 * Task 11 Hilt wiring: [ScrapeRepository] with a scraper factory that honors
 * the debug base-url override ([SettingsStore.scraperBaseUrlOverride] — blank
 * = production DLsite). The override is read per scrape entry point, so the
 * debug setting takes effect without a process restart. The UI that writes it
 * is FLAG_DEBUGGABLE-gated (ScrapeViewModel); release builds never see it.
 */
@Module
@InstallIn(SingletonComponent::class)
object ScrapeModule {

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
            DlsiteScraper(
                baseUrl = if (override.isBlank()) DlsiteScraperApi.DEFAULT_BASE_URL else override.trimEnd('/'),
                client = client,
            )
        },
    )
}
