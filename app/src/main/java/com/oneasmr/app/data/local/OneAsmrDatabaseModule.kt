package com.oneasmr.app.data.local

import android.content.Context
import androidx.paging.PagingSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt wiring for the local database. DAO providers are added as later tasks
 * (scanner, library UI) start consuming them.
 */
@Module
@InstallIn(SingletonComponent::class)
object OneAsmrDatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OneAsmrDatabase =
        OneAsmrDatabase.build(context)

    @Provides
    fun provideWorkDao(db: OneAsmrDatabase): WorkDao = db.workDao()

    /**
     * Task 12/13 library paging: a fresh [PagingSource] per Pager generation
     * carrying the full sort + search parameters (order field, direction,
     * keyword, session random seed). Factory injection keeps
     * LibraryViewModel/SearchViewModel unit-testable with a fake source while
     * production always pages through Room's LimitOffsetPagingSource.
     */
    @Provides
    fun provideWorkPagingSourceFactory(workDao: WorkDao): WorkPagingSourceFactory =
        WorkPagingSourceFactory { order, descending, keyword, randomSeed ->
            workDao.pagingSource(order, descending, keyword, randomSeed)
        }

    @Provides
    fun provideCircleDao(db: OneAsmrDatabase): CircleDao = db.circleDao()

    @Provides
    fun provideTagDao(db: OneAsmrDatabase): TagDao = db.tagDao()

    @Provides
    fun provideVaDao(db: OneAsmrDatabase): VaDao = db.vaDao()

    @Provides
    fun provideWorkTagDao(db: OneAsmrDatabase): WorkTagDao = db.workTagDao()

    @Provides
    fun provideWorkVaDao(db: OneAsmrDatabase): WorkVaDao = db.workVaDao()

    @Provides
    fun provideReviewDao(db: OneAsmrDatabase): ReviewDao = db.reviewDao()

    @Provides
    fun providePlaybackStateDao(db: OneAsmrDatabase): PlaybackStateDao = db.playbackStateDao()
}
