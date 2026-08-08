package com.oneasmr.app.data.repository

import android.content.Context
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.oneasmr.app.data.local.settings.SettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Hilt wiring for Task 10: the singleton Coil [ImageLoader] (coil-network-okhttp),
 * the [CoverStore] and its collaborators, and the cache-cap flow from
 * [SettingsStore].
 *
 * One ImageLoader for the whole app, wired via Hilt and registered as Coil's
 * process singleton in [com.oneasmr.app.OneAsmrApp.onCreate] — every
 * AsyncImage (including the library grid's, Task 12) resolves to it.
 *
 * The cache cap (SettingsStore.cacheSizeCapMb, default 500MB) is surfaced as
 * a [StateFlow] of BYTES; it applies to CoverStore's own covers/ directory
 * (Coil's internal memory/disk caches never manage externally written files).
 */
@Module
@InstallIn(SingletonComponent::class)
object CoverModule {

    @Provides
    @Singleton
    fun provideCoversDir(@ApplicationContext context: Context): File =
        File(context.filesDir, "covers")

    @Provides
    @Singleton
    fun provideAppCoroutineScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideCacheCapBytes(
        settingsStore: SettingsStore,
        scope: CoroutineScope,
    ): StateFlow<Long> =
        settingsStore.cacheSizeCapMb
            .map { it.toLong() * 1024L * 1024L }
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                SettingsStore.DEFAULT_CACHE_CAP_MB.toLong() * 1024L * 1024L,
            )

    /**
     * Shared OkHttp client for Coil + the cover downloader. A browser UA is
     * stamped on every request: DLsite cover URLs 403 without it (hotlink
     * protection, Task 9 learning). No referer here — that is per-request in
     * [OkHttpCoverDownloader] (the work page URL); Coil loads DLsite covers
     * that need no referer at all.
     */
    @Provides
    @Singleton
    fun provideCoilOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request: Request = chain.request().newBuilder()
                .header("User-Agent", BROWSER_UA)
                .build()
            chain.proceed(request)
        }
        .build()

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        client: OkHttpClient,
    ): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(client)) }
        .build()

    @Provides
    @Singleton
    fun provideCoverDownloader(client: OkHttpClient): CoverDownloader =
        OkHttpCoverDownloader(client)

    @Provides
    @Singleton
    fun provideBundledCoverLocator(@ApplicationContext context: Context): BundledCoverLocator =
        SafBundledCoverLocator(context)

    @Provides
    @Singleton
    fun provideCoverStore(
        coversDir: File,
        downloader: CoverDownloader,
        bundledLocator: BundledCoverLocator,
        cacheCapBytes: StateFlow<Long>,
    ): CoverStore = CoverStore(
        coversDir = coversDir,
        downloader = downloader,
        bundledLocator = bundledLocator,
        cacheCapBytes = { cacheCapBytes.value },
    )

    private const val BROWSER_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
}
