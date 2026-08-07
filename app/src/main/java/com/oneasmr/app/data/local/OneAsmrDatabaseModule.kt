package com.oneasmr.app.data.local

import android.content.Context
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
