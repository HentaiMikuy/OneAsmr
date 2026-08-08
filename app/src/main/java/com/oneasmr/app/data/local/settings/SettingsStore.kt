package com.oneasmr.app.data.local.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.oneasmr.app.data.local.WorkOrder
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

/** Library home layout (Task 12): cover grid vs list rows, persisted. */
enum class LibraryViewMode {
    GRID,
    LIST,
    ;

    companion object {
        /** Read the persisted value; unknown/missing values fall back to [GRID]. */
        fun fromStored(value: String?): LibraryViewMode =
            entries.firstOrNull { it.name == value } ?: GRID
    }
}

/**
 * Playback-resume policy (plan Task 19): [AUTO] resumes from the remembered
 * position per [com.oneasmr.app.player.ResumePolicy] boundaries; [ALWAYS_ASK]
 * prompts the user on every resume (the ask dialog ships with the Task 21
 * player screen — until then the mode auto-resumes, see
 * ResumePositionResolver). Default: [AUTO].
 */
enum class ResumeMode {
    AUTO,
    ALWAYS_ASK,
    ;

    companion object {
        /** Read the persisted value; unknown/missing values fall back to [AUTO]. */
        fun fromStored(value: String?): ResumeMode =
            entries.firstOrNull { it.name == value } ?: AUTO
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
 * Task 5 scope: theme mode, scraping language, cache cap.
 * Task 27 adds more entries; keep this class the single source of truth.
 */
@Singleton
class SettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val themeMode: Flow<ThemeMode> =
        dataStore.data.map { ThemeMode.fromStored(it[KEY_THEME_MODE]) }

    val scrapingLanguage: Flow<ScrapingLanguage> =
        dataStore.data.map { ScrapingLanguage.fromStored(it[KEY_SCRAPING_LANGUAGE]) }

    val cacheSizeCapMb: Flow<Int> =
        dataStore.data.map { it[KEY_CACHE_CAP_MB] ?: DEFAULT_CACHE_CAP_MB }

    /** Library home layout preference (Task 12), defaults to the cover grid. */
    val libraryViewMode: Flow<LibraryViewMode> =
        dataStore.data.map { LibraryViewMode.fromStored(it[KEY_LIBRARY_VIEW_MODE]) }

    /** Library sort order (Task 13); unknown/missing stored values fall back to [WorkOrder.ID]. */
    val librarySortOrder: Flow<WorkOrder> =
        dataStore.data.map { WorkOrder.fromStored(it[KEY_LIBRARY_SORT_ORDER]) }

    /** Library sort direction (Task 13); ascending by default. Irrelevant for [WorkOrder.RANDOM]. */
    val librarySortDescending: Flow<Boolean> =
        dataStore.data.map { it[KEY_LIBRARY_SORT_DESCENDING] ?: false }

    /**
     * Task 13 recent search terms, newest first, deduplicated, capped at
     * [MAX_RECENT_SEARCHES]. Terms are joined into one preference string with
     * the unit separator (U+001F) — a control character no search box can
     * type — so a single key round-trips the list.
     */
    val recentSearches: Flow<List<String>> =
        dataStore.data.map { decodeRecentSearches(it[KEY_RECENT_SEARCHES]) }

    /**
     * Playback-resume policy (Task 19); default [ResumeMode.AUTO] (auto
     * resume per the >95% / <3% boundaries). "总是询问" ships its ask UI with
     * Task 21; the persisted value is honored by ResumePositionResolver.
     */
    val resumeMode: Flow<ResumeMode> =
        dataStore.data.map { ResumeMode.fromStored(it[KEY_RESUME_MODE]) }

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

    suspend fun setLibraryViewMode(mode: LibraryViewMode) {
        dataStore.edit { it[KEY_LIBRARY_VIEW_MODE] = mode.name }
    }

    /** Persists the library sort selection (order + direction) in one atomic edit. */
    suspend fun setLibrarySort(order: WorkOrder, descending: Boolean) {
        dataStore.edit {
            it[KEY_LIBRARY_SORT_ORDER] = order.name
            it[KEY_LIBRARY_SORT_DESCENDING] = descending
        }
    }

    /**
     * Records a searched term: moves it to the front, deduplicates, strips the
     * list separator (defense in depth — the UI never types control chars) and
     * caps at [MAX_RECENT_SEARCHES]. Blank terms are ignored.
     */
    suspend fun addRecentSearch(term: String) {
        val cleaned = term.replace(SEPARATOR, "").trim()
        if (cleaned.isEmpty()) return
        dataStore.edit { prefs ->
            val existing = decodeRecentSearches(prefs[KEY_RECENT_SEARCHES])
            prefs[KEY_RECENT_SEARCHES] =
                (listOf(cleaned) + existing.filterNot { it == cleaned })
                    .take(MAX_RECENT_SEARCHES)
                    .joinToString(SEPARATOR)
        }
    }

    suspend fun clearRecentSearches() {
        dataStore.edit { it.remove(KEY_RECENT_SEARCHES) }
    }

    suspend fun setResumeMode(mode: ResumeMode) {
        dataStore.edit { it[KEY_RESUME_MODE] = mode.name }
    }

    companion object {
        const val DEFAULT_CACHE_CAP_MB = 500
        const val MAX_RECENT_SEARCHES = 10

        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_SCRAPING_LANGUAGE = stringPreferencesKey("scraping_language")
        private val KEY_SCRAPER_BASE_URL_OVERRIDE = stringPreferencesKey("scraper_base_url_override")
        private val KEY_CACHE_CAP_MB = intPreferencesKey("cache_cap_mb")
        private val KEY_LIBRARY_VIEW_MODE = stringPreferencesKey("library_view_mode")
        private val KEY_LIBRARY_SORT_ORDER = stringPreferencesKey("library_sort_order")
        private val KEY_LIBRARY_SORT_DESCENDING = booleanPreferencesKey("library_sort_descending")
        private val KEY_RECENT_SEARCHES = stringPreferencesKey("recent_searches")
        private val KEY_RESUME_MODE = stringPreferencesKey("resume_mode")

        /** Unit separator: the only forbidden character in a search term. */
        private const val SEPARATOR = "\u001F"

        private fun decodeRecentSearches(stored: String?): List<String> =
            stored?.split(SEPARATOR)?.filter { it.isNotEmpty() } ?: emptyList()
    }
}
