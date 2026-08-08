package com.oneasmr.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    /**
     * Deep-link intent observed by [OneAsmrNavHost].
     *
     * Set from `onCreate` (cold launch) and refreshed from `onNewIntent`
     * (warm delivery: the system can deliver a VIEW intent to the running
     * top instance instead of creating a new activity). Because it is
     * snapshot state, the NavHost's `LaunchedEffect(deepLinkIntent)` re-runs
     * `handleDeepLink` on every delivery; a plain `activity.intent` read
     * would never recompose and the warm deep link would be silently dropped.
     */
    var deepLinkIntent by mutableStateOf<Intent?>(null)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deepLinkIntent = intent
        enableEdgeToEdge()
        setContent {
            val themeMode by settingsStore.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val dynamicColor by settingsStore.dynamicColor.collectAsStateWithLifecycle(initialValue = true)
            OneAsmrTheme(
                darkTheme = themeMode.resolveDarkTheme(isSystemInDarkTheme()),
                dynamicColor = dynamicColor,
            ) {
                OneAsmrNavHost(deepLinkIntent = deepLinkIntent)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkIntent = intent
        Log.i(TAG, "onNewIntent: action=${intent.action} data=${intent.data}")
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
