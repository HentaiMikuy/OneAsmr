package com.oneasmr.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneasmr.app.data.local.settings.SettingsStore
import com.oneasmr.app.data.local.settings.ThemeMode
import com.oneasmr.app.data.local.settings.resolveDarkTheme
import com.oneasmr.app.navigation.OneAsmrNavHost
import com.oneasmr.app.ui.common.LocalNsfwEnabled
import com.oneasmr.app.ui.common.OneAsmrTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

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
            val nsfwEnabled by settingsStore.nsfwEnabled.collectAsStateWithLifecycle(initialValue = true)
            val nsfwChoiceAsked by settingsStore.nsfwChoiceAsked.collectAsStateWithLifecycle(initialValue = false)
            // 安全模式开关必须在任何封面渲染之前注入(LocalNsfwEnabled 的
            // 默认值是"开启",提前提供否则首帧会泄露敏感封面)。
            CompositionLocalProvider(LocalNsfwEnabled provides nsfwEnabled) {
                OneAsmrTheme(
                    darkTheme = themeMode.resolveDarkTheme(isSystemInDarkTheme()),
                    dynamicColor = dynamicColor,
                ) {
                    OneAsmrNavHost(deepLinkIntent = deepLinkIntent)
                    if (!nsfwChoiceAsked) {
                        NsfwChoiceDialog(settingsStore = settingsStore)
                    }
                }
            }
        }
    }

    /**
     * 首次启动的 NSFW 模式选择对话框:非可关闭(onDismissRequest = {}),
     * 必须二选一,答案持久化后永不再问。不启用 = 安全模式(敏感作品封面
     * 与曲名隐藏,适合公共场合)。
     */
    @Composable
    private fun NsfwChoiceDialog(settingsStore: SettingsStore) {
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = {},
            title = { Text("启用 NSFW 模式？") },
            text = {
                Text(
                    "启用后完整显示全部作品的封面与曲名。" +
                        "不启用将进入安全模式：未分级、R15 与 R18 作品的封面与曲名会被隐藏，适合公共场合。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            settingsStore.setNsfwEnabled(true)
                            settingsStore.setNsfwChoiceAsked()
                        }
                    },
                ) { Text("启用") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            settingsStore.setNsfwEnabled(false)
                            settingsStore.setNsfwChoiceAsked()
                        }
                    },
                ) { Text("不启用") }
            },
        )
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
