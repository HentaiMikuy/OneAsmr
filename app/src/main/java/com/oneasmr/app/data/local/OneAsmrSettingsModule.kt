package com.oneasmr.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.oneasmr.app.data.local.keystore.KeystoreDataStore
import com.oneasmr.app.data.local.keystore.KeystoreKeyCipher
import com.oneasmr.app.data.local.keystore.KeyCipher
import com.oneasmr.app.data.local.settings.SettingsStore
import com.oneasmr.app.data.repository.AndroidRootDisplayNameResolver
import com.oneasmr.app.data.repository.AndroidScanRootPermissionStore
import com.oneasmr.app.data.repository.RootDisplayNameResolver
import com.oneasmr.app.data.repository.ScanRootPermissionStore
import com.oneasmr.app.data.repository.ScanRootRepository
import com.oneasmr.app.data.repository.ScanRootsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/**
 * Hilt wiring for Task 5 local persistence: the three DataStore files
 * (settings / scan_roots / secure), the settings + scan-root stores, the
 * Keystore-backed secret store, and the SAF permission gate.
 *
 * Each DataStore is built via [PreferenceDataStoreFactory] over a distinct
 * file so the classes stay JVM-constructible for unit tests (tests build
 * their own store over a temp file and inject fakes for the Android bits).
 */
@Module
@InstallIn(SingletonComponent::class)
object OneAsmrSettingsModule {

    @Provides
    @Singleton
    @Named("settings")
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("settings") })

    @Provides
    @Singleton
    @Named("scan_roots")
    fun provideScanRootsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("scan_roots") })

    @Provides
    @Singleton
    @Named("secure")
    fun provideSecureDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("secure") })

    @Provides
    @Singleton
    fun provideSettingsStore(@Named("settings") dataStore: DataStore<Preferences>): SettingsStore =
        SettingsStore(dataStore)

    @Provides
    @Singleton
    fun provideScanRootsStore(@Named("scan_roots") dataStore: DataStore<Preferences>): ScanRootsStore =
        ScanRootsStore(dataStore)

    @Provides
    @Singleton
    fun provideKeyCipher(): KeyCipher = KeystoreKeyCipher()

    @Provides
    @Singleton
    fun provideKeystoreDataStore(
        @Named("secure") dataStore: DataStore<Preferences>,
        cipher: KeyCipher,
    ): KeystoreDataStore = KeystoreDataStore(dataStore, cipher)

    @Provides
    @Singleton
    fun provideScanRootPermissionStore(@ApplicationContext context: Context): ScanRootPermissionStore =
        AndroidScanRootPermissionStore(context)

    @Provides
    @Singleton
    fun provideRootDisplayNameResolver(@ApplicationContext context: Context): RootDisplayNameResolver =
        AndroidRootDisplayNameResolver(context)

    @Provides
    @Singleton
    fun provideScanRootRepository(
        permissionStore: ScanRootPermissionStore,
        rootsStore: ScanRootsStore,
        displayNameResolver: RootDisplayNameResolver,
    ): ScanRootRepository = ScanRootRepository(permissionStore, rootsStore, displayNameResolver)
}
