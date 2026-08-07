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
    fun `theme resolution against system setting`() {
        assertTrue(ThemeMode.SYSTEM.resolveDarkTheme(true))
        assertFalse(ThemeMode.SYSTEM.resolveDarkTheme(false))
        assertFalse(ThemeMode.LIGHT.resolveDarkTheme(true))
        assertFalse(ThemeMode.LIGHT.resolveDarkTheme(false))
        assertTrue(ThemeMode.DARK.resolveDarkTheme(false))
        assertTrue(ThemeMode.DARK.resolveDarkTheme(true))
    }
}
