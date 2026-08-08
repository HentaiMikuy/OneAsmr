package com.oneasmr.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneasmr.app.data.local.settings.SettingsStore
import com.oneasmr.app.data.local.settings.ThemeMode
import com.oneasmr.app.data.local.settings.resolveDarkTheme
import com.oneasmr.app.navigation.OneAsmrNavHost
import com.oneasmr.app.ui.common.OneAsmrTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity host for the whole app. All navigation flows through
 * [OneAsmrNavHost]; the theme follows the persisted [ThemeMode] from
 * [SettingsStore] (Task 5), resolving SYSTEM against the device setting.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsStore: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by settingsStore.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val dynamicColor by settingsStore.dynamicColor.collectAsStateWithLifecycle(initialValue = true)
            OneAsmrTheme(
                darkTheme = themeMode.resolveDarkTheme(isSystemInDarkTheme()),
                dynamicColor = dynamicColor,
            ) {
                OneAsmrNavHost()
            }
        }
    }
}
