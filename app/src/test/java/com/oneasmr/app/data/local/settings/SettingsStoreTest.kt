package com.oneasmr.app.data.local.settings

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.oneasmr.app.data.local.TestDataStoreFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Locks SettingsStore defaults, round-trip writes and cross-instance
 * persistence (new store over the same file == "process restart").
 */
class SettingsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun storeIn(dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) =
        SettingsStore(dataStore)

    private fun file(name: String) = TestDataStoreFile(tmp.newFile("$name.preferences_pb"))

    @Test
    fun `defaults are system theme empty server zh-CN and 500MB cache cap`() = runTest {
        val store = storeIn(file("defaults").open())
        assertEquals(ThemeMode.SYSTEM, store.themeMode.first())
        assertEquals("", store.serverAddress.first())
        assertEquals(ScrapingLanguage.ZH, store.scrapingLanguage.first())
        assertEquals(SettingsStore.DEFAULT_CACHE_CAP_MB, store.cacheSizeCapMb.first())
        assertEquals(ResumeMode.AUTO, store.resumeMode.first())
    }

    @Test
    fun `resume mode round trip and persistence across store instances`() = runTest {
        val tf = file("resume")
        storeIn(tf.open()).setResumeMode(ResumeMode.ALWAYS_ASK)
        assertEquals(ResumeMode.ALWAYS_ASK, storeIn(tf.restart()).resumeMode.first())
    }

    @Test
    fun `corrupt persisted resume mode falls back to AUTO`() = runTest {
        val tf = file("resume_corrupt")
        val dataStore = tf.open()
        dataStore.edit { it[stringPreferencesKey("resume_mode")] = "NUCLEAR" }
        assertEquals(ResumeMode.AUTO, storeIn(dataStore).resumeMode.first())
    }

    @Test
    fun `theme round trip and persistence across store instances`() = runTest {
        val tf = file("theme")
        storeIn(tf.open()).setThemeMode(ThemeMode.DARK)
        // New instance over the same file == cold restart.
        assertEquals(ThemeMode.DARK, storeIn(tf.restart()).themeMode.first())
    }

    @Test
    fun `all settings round trip together`() = runTest {
        val tf = file("all")
        val store = storeIn(tf.open())
        store.setThemeMode(ThemeMode.LIGHT)
        store.setServerAddress("  http://192.168.1.5:8787/  ")
        store.setScrapingLanguage(ScrapingLanguage.JA)
        store.setCacheSizeCapMb(256)

        val restarted = storeIn(tf.restart())
        assertEquals(ThemeMode.LIGHT, restarted.themeMode.first())
        // Server address is trimmed on write.
        assertEquals("http://192.168.1.5:8787/", restarted.serverAddress.first())
        assertEquals(ScrapingLanguage.JA, restarted.scrapingLanguage.first())
        assertEquals(256, restarted.cacheSizeCapMb.first())
    }

    @Test
    fun `cache cap clamps to valid range`() = runTest {
        val tf = file("clamp")
        storeIn(tf.open()).setCacheSizeCapMb(0)
        assertEquals(1, storeIn(tf.restart()).cacheSizeCapMb.first())
        storeIn(tf.restart()).setCacheSizeCapMb(200_000)
        assertEquals(100_000, storeIn(tf.restart()).cacheSizeCapMb.first())
    }

    @Test
    fun `corrupt persisted theme falls back to SYSTEM`() = runTest {
        val tf = file("corrupt")
        val dataStore = tf.open()
        // Write an out-of-enum value directly under the hood.
        dataStore.edit { it[stringPreferencesKey("theme_mode")] = "NUCLEAR" }
        assertEquals(ThemeMode.SYSTEM, storeIn(dataStore).themeMode.first())
    }

    @Test
    fun `library view mode defaults to GRID`() = runTest {
        val store = storeIn(file("viewmode-default").open())
        assertEquals(LibraryViewMode.GRID, store.libraryViewMode.first())
    }

    @Test
    fun `library view mode round trips across store instances`() = runTest {
        val tf = file("viewmode")
        storeIn(tf.open()).setLibraryViewMode(LibraryViewMode.LIST)
        // New instance over the same file == cold restart.
        assertEquals(LibraryViewMode.LIST, storeIn(tf.restart()).libraryViewMode.first())
    }

    @Test
    fun `corrupt persisted view mode falls back to GRID`() = runTest {
        val tf = file("viewmode-corrupt")
        val dataStore = tf.open()
        dataStore.edit { it[stringPreferencesKey("library_view_mode")] = "CAROUSEL" }
        assertEquals(LibraryViewMode.GRID, storeIn(dataStore).libraryViewMode.first())
    }

    @Test
    fun `theme resolution against system setting`() {
        assertTrue(ThemeMode.SYSTEM.resolveDarkTheme(true))
        assertFalse(ThemeMode.SYSTEM.resolveDarkTheme(false))
        assertFalse(ThemeMode.LIGHT.resolveDarkTheme(true))
        assertFalse(ThemeMode.LIGHT.resolveDarkTheme(false))
        assertTrue(ThemeMode.DARK.resolveDarkTheme(false))
        assertTrue(ThemeMode.DARK.resolveDarkTheme(true))
    }

    // ---------- Task 13: recent searches + library sort ----------

    @Test
    fun `recent searches default to empty`() = runTest {
        val store = storeIn(file("recent-default").open())
        assertEquals(emptyList<String>(), store.recentSearches.first())
    }

    @Test
    fun `recent searches persist newest first deduped and capped`() = runTest {
        val tf = file("recent")
        val store = storeIn(tf.open())
        store.addRecentSearch("催眠音")
        store.addRecentSearch("夜晚助眠")
        store.addRecentSearch("催眠音")
        assertEquals(listOf("催眠音", "夜晚助眠"), store.recentSearches.first())

        // Cap at 10: adding 11 distinct terms keeps the newest 10.
        for (i in 1..11) store.addRecentSearch("term$i")
        val capped = store.recentSearches.first()
        assertEquals(SettingsStore.MAX_RECENT_SEARCHES, capped.size)
        assertEquals("term11", capped.first())
        assertTrue("催眠音" !in capped)
    }

    @Test
    fun `recent searches survive a store restart and can be cleared`() = runTest {
        val tf = file("recent-persist")
        storeIn(tf.open()).addRecentSearch("おやすみ")
        val restarted = storeIn(tf.restart())
        assertEquals(listOf("おやすみ"), restarted.recentSearches.first())

        restarted.clearRecentSearches()
        assertEquals(emptyList<String>(), storeIn(tf.restart()).recentSearches.first())
    }

    @Test
    fun `blank terms and separator characters are never stored`() = runTest {
        val store = storeIn(file("recent-blank").open())
        store.addRecentSearch("   ")
        store.addRecentSearch("")
        store.addRecentSearch("bad\u001Fterm")
        assertEquals(listOf("badterm"), store.recentSearches.first())
    }

    @Test
    fun `library sort defaults to id ascending`() = runTest {
        val store = storeIn(file("sort-default").open())
        assertEquals(com.oneasmr.app.data.local.WorkOrder.ID, store.librarySortOrder.first())
        assertEquals(false, store.librarySortDescending.first())
    }

    @Test
    fun `library sort round trips across store instances`() = runTest {
        val tf = file("sort")
        storeIn(tf.open()).setLibrarySort(com.oneasmr.app.data.local.WorkOrder.TITLE_SORT_KEY, descending = true)
        val restarted = storeIn(tf.restart())
        assertEquals(com.oneasmr.app.data.local.WorkOrder.TITLE_SORT_KEY, restarted.librarySortOrder.first())
        assertEquals(true, restarted.librarySortDescending.first())
    }

    @Test
    fun `corrupt persisted sort order falls back to id`() = runTest {
        val tf = file("sort-corrupt")
        val dataStore = tf.open()
        dataStore.edit { it[stringPreferencesKey("library_sort_order")] = "BY_MOON" }
        assertEquals(com.oneasmr.app.data.local.WorkOrder.ID, storeIn(dataStore).librarySortOrder.first())
    }
}
