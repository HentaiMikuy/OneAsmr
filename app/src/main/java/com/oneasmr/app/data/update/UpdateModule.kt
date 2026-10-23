package com.oneasmr.app.data.update

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt wiring for the update feature: binds the GitHub-backed repository
 * (reusing the app-wide [okhttp3.OkHttpClient] for release metadata only).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {

    @Binds
    @Singleton
    abstract fun bindUpdateRepository(impl: GithubUpdateRepository): UpdateRepository

    @Binds
    @Singleton
    abstract fun bindUpdateDownloader(impl: SystemUpdateDownloader): UpdateDownloader
}
