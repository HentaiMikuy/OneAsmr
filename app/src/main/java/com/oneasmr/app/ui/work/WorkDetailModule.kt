package com.oneasmr.app.ui.work

import android.content.Context
import com.oneasmr.app.data.repository.ScrapeRepository
import com.oneasmr.app.data.repository.SingleWorkScraper
import com.oneasmr.app.data.scanner.DocumentFs
import com.oneasmr.app.data.scanner.SafDocumentFs
import com.oneasmr.app.data.text.SafTextFileReader
import com.oneasmr.app.data.text.TextFileReader
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Creates a [DocumentFs] bound to one SAF tree root (plan Task 6/14 pattern). */
fun interface DocumentFsFactory {
    fun create(treeUri: String): DocumentFs
}

/**
 * Hilt wiring for the work detail page (plan Task 14). Everything the
 * ViewModels need is injected here so their constructors stay free of Android
 * types and unit tests can substitute fakes for every collaborator.
 */
@Module
@InstallIn(SingletonComponent::class)
object WorkDetailModule {

    @Provides
    @Singleton
    fun provideDocumentFsFactory(@ApplicationContext context: Context): DocumentFsFactory =
        DocumentFsFactory { treeUri -> SafDocumentFs(context, treeUri) }

    @Provides
    @Singleton
    fun provideTextFileReader(@ApplicationContext context: Context): TextFileReader =
        SafTextFileReader(context)
}

/** Binds the full scrape pipeline behind the narrow single-work interface. */
@Module
@InstallIn(SingletonComponent::class)
abstract class WorkDetailBindingsModule {
    @Binds
    abstract fun bindSingleWorkScraper(impl: ScrapeRepository): SingleWorkScraper
}
