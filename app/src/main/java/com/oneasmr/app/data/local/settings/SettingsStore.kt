package com.oneasmr.app.data.local.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Light/dark theme mode, persisted in [SettingsStore]. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    companion object {
        /** Read the persisted value; unknown/missing values fall back to [SYSTEM]. */
        fun fromStored(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

/**
 * Resolve a [ThemeMode] against the current system setting. Pure function so
 * the mapping is unit-testable without any Android runtime.
 */
fun ThemeMode.resolveDarkTheme(systemInDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemInDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * DLsite scraping language preference (Task 9 sends the [code] as a
 * cookie/URL parameter). Defaults to Simplified Chinese, the app's primary
 * audience language; "ja" is the alternative.
 */
enum class ScrapingLanguage(val code: String) {
    ZH("zh-CN"),
    JA("ja"),
    ;

    companion object {
        fun fromStored(value: String?): ScrapingLanguage =
            entries.firstOrNull { it.code == value } ?: ZH
    }
}

/**
 * App-wide settings persisted in a DataStore Preferences file ("settings").
 *
 * Exposed as typed [Flow]s plus suspend setters; the Hilt module
 * ([com.oneasmr.app.data.local.OneAsmrSettingsModule]) provides the singleton
 * over the process-wide DataStore, while unit tests construct instances over
 * a temp-file [PreferenceDataStoreFactory] store to prove defaults and
 * persistence across "restarts" (new instance, same file).
 *
 * Task 5 scope: theme mode, server address, scraping language, cache cap.
 * Task 27 adds more entries; keep this class the single source of truth.
 */
@Singleton
class SettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val themeMode: Flow<ThemeMode> =
        dataStore.data.map { ThemeMode.fromStored(it[KEY_THEME_MODE]) }

    val serverAddress: Flow<String> =
        dataStore.data.map { it[KEY_SERVER_ADDRESS] ?: "" }

    val scrapingLanguage: Flow<ScrapingLanguage> =
        dataStore.data.map { ScrapingLanguage.fromStored(it[KEY_SCRAPING_LANGUAGE]) }

    val cacheSizeCapMb: Flow<Int> =
        dataStore.data.map { it[KEY_CACHE_CAP_MB] ?: DEFAULT_CACHE_CAP_MB }

    /**
     * Debug-only DLsite base-url override (Task 11 device QA: the emulator
     * reaches the host proxy through 10.0.2.2). Blank = production
     * [com.oneasmr.app.data.remote.dlsite.DlsiteScraper.DEFAULT_BASE_URL].
     * The UI that writes it is gated behind FLAG_DEBUGGABLE; release builds
     * can never set it (no UI surface, no secret intent).
     */
    val scraperBaseUrlOverride: Flow<String> =
        dataStore.data.map { it[KEY_SCRAPER_BASE_URL_OVERRIDE] ?: "" }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    suspend fun setServerAddress(address: String) {
        dataStore.edit { it[KEY_SERVER_ADDRESS] = address.trim() }
    }

    suspend fun setScrapingLanguage(language: ScrapingLanguage) {
        dataStore.edit { it[KEY_SCRAPING_LANGUAGE] = language.code }
    }

    /** Sets the debug scraper base-url override; blank restores production. */
    suspend fun setScraperBaseUrlOverride(url: String) {
        dataStore.edit { it[KEY_SCRAPER_BASE_URL_OVERRIDE] = url.trim() }
    }

    /** Cache size cap in MB, clamped to [1, 100_000] (Task 10 CoverStore enforces it). */
    suspend fun setCacheSizeCapMb(mb: Int) {
        dataStore.edit { it[KEY_CACHE_CAP_MB] = mb.coerceIn(1, 100_000) }
    }

    companion object {
        const val DEFAULT_CACHE_CAP_MB = 500

        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_SERVER_ADDRESS = stringPreferencesKey("server_address")
        private val KEY_SCRAPING_LANGUAGE = stringPreferencesKey("scraping_language")
        private val KEY_SCRAPER_BASE_URL_OVERRIDE = stringPreferencesKey("scraper_base_url_override")
        private val KEY_CACHE_CAP_MB = intPreferencesKey("cache_cap_mb")
    }
}
