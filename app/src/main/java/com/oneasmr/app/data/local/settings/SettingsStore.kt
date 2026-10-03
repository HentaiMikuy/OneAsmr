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

    /**
     * Material You dynamic color (Android 12+). Off by default since the
     * custom brand scheme (warm-amber dark palette, OneAsmrColorSchemes) is
     * the intended out-of-box look; the toggle opts INTO wallpaper-derived
     * color for users who prefer it.
     */
    val dynamicColor: Flow<Boolean> =
        dataStore.data.map { it[KEY_DYNAMIC_COLOR] ?: false }

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

    /**
     * asmr.one fallback source toggle. On by default: when a DLsite scrape
     * fails (any kind), RJ works are retried against asmr.one's JSON API.
     * Unlike [scraperBaseUrlOverride] this is a user-facing, non-debug
     * setting — asmr.one is a legitimate secondary metadata source, not a QA
     * hook.
     */
    val asmrOneFallbackEnabled: Flow<Boolean> =
        dataStore.data.map { it[KEY_ASMR_ONE_FALLBACK_ENABLED] ?: true }

    /**
     * asmr.one API mirror base-url. Blank = [DEFAULT_ASMR_ONE_BASE_URL]
     * (same blank-means-default convention as [scraperBaseUrlOverride]);
     * non-blank values point at a mirror (e.g. api.asmr-100.com) for users
     * whose networks cannot reach the primary host.
     */
    val asmrOneBaseUrl: Flow<String> =
        dataStore.data.map { it[KEY_ASMR_ONE_BASE_URL] ?: "" }

    /**
     * 视频后台续播开关(默认开)。开:视频页切后台/关屏、以及退出单档视频页时
     * 都不暂停,只临时禁用视频轨(仅解码音频,省电接近纯音频播放),回前台/
     * 重进页面恢复画面 —— ASMR 视频关屏听声音是核心场景。关:保持旧行为,
     * 离开页面或后台即暂停。纯音频单文件不受此开关约束,无条件续播。
     */
    val videoBackgroundPlayback: Flow<Boolean> =
        dataStore.data.map { it[KEY_VIDEO_BACKGROUND_PLAYBACK] ?: true }

    /**
     * NSFW 模式开关(安全模式)。默认开 = 完整显示所有封面与曲名;关 =
     * 安全模式,未分级(null)/R15/R18 作品的封面与曲名被隐藏,适合公共场合。
     * 首次启动的对话框选定一次,之后由设置页开关随时修改。
     */
    val nsfwEnabled: Flow<Boolean> =
        dataStore.data.map { it[KEY_NSFW_ENABLED] ?: true }

    /** 首次启动 NSFW 对话框是否已应答(应答一次后永不再问)。 */
    val nsfwChoiceAsked: Flow<Boolean> =
        dataStore.data.map { it[KEY_NSFW_CHOICE_ASKED] ?: false }

    /**
     * 冷启动启动动画开关(默认开)。关 = 不播放品牌启动动画:系统启动窗口
     * 退场后直接进入主界面。
     *
     * 冷启动时这个值还没读过盘,而动画的武装必须在 onCreate 同步完成,所以
     * MainActivity 先按「开」武装、读到「关」再立刻把动画层收掉——它的第 0
     * 帧与启动窗口逐像素相同,收掉前后画面不变(见 shouldShowLaunchSplash)。
     */
    val launchAnimationEnabled: Flow<Boolean> =
        dataStore.data.map { it[KEY_LAUNCH_ANIMATION_ENABLED] ?: true }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    suspend fun setScrapingLanguage(language: ScrapingLanguage) {
        dataStore.edit { it[KEY_SCRAPING_LANGUAGE] = language.code }
    }

    /** Sets the debug scraper base-url override; blank restores production. */
    suspend fun setScraperBaseUrlOverride(url: String) {
        dataStore.edit { it[KEY_SCRAPER_BASE_URL_OVERRIDE] = url.trim() }
    }

    suspend fun setAsmrOneFallbackEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_ASMR_ONE_FALLBACK_ENABLED] = enabled }
    }

    suspend fun setVideoBackgroundPlayback(enabled: Boolean) {
        dataStore.edit { it[KEY_VIDEO_BACKGROUND_PLAYBACK] = enabled }
    }

    suspend fun setNsfwEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_NSFW_ENABLED] = enabled }
    }

    /** Records that the first-launch NSFW dialog was answered (idempotent). */
    suspend fun setNsfwChoiceAsked() {
        dataStore.edit { it[KEY_NSFW_CHOICE_ASKED] = true }
    }

    /** 「启动动画」开关(设置页);改动从下一次冷启动开始生效。 */
    suspend fun setLaunchAnimationEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_LAUNCH_ANIMATION_ENABLED] = enabled }
    }

    /** Sets the asmr.one mirror base-url; blank restores [DEFAULT_ASMR_ONE_BASE_URL]. */
    suspend fun setAsmrOneBaseUrl(url: String) {
        dataStore.edit { it[KEY_ASMR_ONE_BASE_URL] = url.trim().trimEnd('/') }
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
        const val DEFAULT_ASMR_ONE_BASE_URL = "https://api.asmr.one"

        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val KEY_SCRAPING_LANGUAGE = stringPreferencesKey("scraping_language")
        private val KEY_SCRAPER_BASE_URL_OVERRIDE = stringPreferencesKey("scraper_base_url_override")
        private val KEY_CACHE_CAP_MB = intPreferencesKey("cache_cap_mb")
        private val KEY_LIBRARY_VIEW_MODE = stringPreferencesKey("library_view_mode")
        private val KEY_LIBRARY_SORT_ORDER = stringPreferencesKey("library_sort_order")
        private val KEY_LIBRARY_SORT_DESCENDING = booleanPreferencesKey("library_sort_descending")
        private val KEY_RECENT_SEARCHES = stringPreferencesKey("recent_searches")
        private val KEY_RESUME_MODE = stringPreferencesKey("resume_mode")
        private val KEY_ASMR_ONE_FALLBACK_ENABLED = booleanPreferencesKey("asmr_one_fallback_enabled")
        private val KEY_VIDEO_BACKGROUND_PLAYBACK = booleanPreferencesKey("video_background_playback")
        private val KEY_ASMR_ONE_BASE_URL = stringPreferencesKey("asmr_one_base_url")
        private val KEY_NSFW_ENABLED = booleanPreferencesKey("nsfw_enabled")
        private val KEY_NSFW_CHOICE_ASKED = booleanPreferencesKey("nsfw_choice_asked")
        private val KEY_LAUNCH_ANIMATION_ENABLED = booleanPreferencesKey("launch_animation_enabled")

        /** Unit separator: the only forbidden character in a search term. */
        private const val SEPARATOR = "\u001F"

        private fun decodeRecentSearches(stored: String?): List<String> =
            stored?.split(SEPARATOR)?.filter { it.isNotEmpty() } ?: emptyList()
    }
}
