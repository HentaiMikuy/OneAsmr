package com.oneasmr.app

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneasmr.app.data.local.settings.SettingsStore
import com.oneasmr.app.data.local.settings.ThemeMode
import com.oneasmr.app.data.local.settings.resolveDarkTheme
import com.oneasmr.app.navigation.OneAsmrNavHost
import com.oneasmr.app.ui.common.LocalNsfwEnabled
import com.oneasmr.app.ui.common.OneAsmrTheme
import com.oneasmr.app.ui.launch.LaunchAnimatedStart
import com.oneasmr.app.ui.launch.LaunchAnimationSession
import com.oneasmr.app.ui.launch.SPLASH_HANDOFF_GRACE_MS
import com.oneasmr.app.ui.launch.requiresPlatformSplashHandoff
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay
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

    /**
     * Set once the platform starting window is out of the way (or once the
     * grace period proves it will not report an exit): the launch animation
     * starts its timeline only then, so none of it is spent hidden behind the
     * system's own splash view. Always true below API 31 and whenever the
     * animation does not play.
     */
    private val launchHandoffReady = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deepLinkIntent = intent
        // 冷启动动画覆盖的是品牌深蓝底：动画期间强制浅色状态栏/导航栏图标，
        // 否则浅色模式下深色图标压在深蓝上几乎不可见（平台启动窗口用的是
        // 浅色图标，这样两端不会在衔接处闪一次）。动画结束后恢复默认样式。
        val playLaunchAnimation = LaunchAnimationSession.shouldPlay(
            skipRequested = intent.getBooleanExtra(
                LaunchAnimationSession.EXTRA_SKIP_LAUNCH_ANIMATION,
                false,
            ),
        )
        Log.i(TAG, "onCreate: launchAnimation=$playLaunchAnimation")
        // API 31+ 的系统启动窗口会被重新挂到本窗口之上、并自带走完退出动画
        // （实测盖掉了动画的前 ~580ms）。这里接管退出：它的像素与我们的身份帧
        // 完全一致，所以立即 remove() 是一次看不见的切换，随后整段动画都在
        // 前台播放。未播放动画时无需等待。
        if (playLaunchAnimation && requiresPlatformSplashHandoff(Build.VERSION.SDK_INT)) {
            handOffPlatformSplash()
        } else {
            launchHandoffReady.value = true
        }
        if (playLaunchAnimation) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            )
        } else {
            enableEdgeToEdge()
        }
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
                    // 只有冷启动（本进程第一次创建 Activity）播放启动动画；
                    // 转屏/回前台/热深链都走 active=false 的零开销分支。
                    var showLaunchAnimation by remember { mutableStateOf(playLaunchAnimation) }
                    // 兜底：平台若始终不回报启动窗口退出（例如根本没有启动
                    // 窗口），到点也必须开播——绝不能让用户停在启动画面上。
                    LaunchedEffect(showLaunchAnimation) {
                        if (!showLaunchAnimation || launchHandoffReady.value) return@LaunchedEffect
                        delay(SPLASH_HANDOFF_GRACE_MS.toLong())
                        if (!launchHandoffReady.value) {
                            launchHandoffReady.value = true
                            Log.w(TAG, "onCreate: splash handoff grace expired")
                        }
                    }
                    LaunchAnimatedStart(
                        active = showLaunchAnimation,
                        started = launchHandoffReady.value,
                        onFinished = {
                            showLaunchAnimation = false
                            // 动画层消失后回到 enableEdgeToEdge() 的默认样式
                            // （浅色模式 = 深色图标，与各页面浅底一致）。
                            enableEdgeToEdge()
                            Log.i(TAG, "onCreate: launch animation finished")
                        },
                    ) {
                        OneAsmrNavHost(deepLinkIntent = deepLinkIntent)
                        if (!nsfwChoiceAsked) {
                            NsfwChoiceDialog(settingsStore = settingsStore)
                        }
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

    /**
     * Takes the platform starting window over (API 31+ only): the system
     * reparents its splash view into this activity's window, above the first
     * Compose frame, and runs its own ~200ms exit on top — measured on device
     * that hid the first ~580ms of the launch animation. The view's pixels are
     * the identity frame of that animation, so removing it immediately is an
     * invisible cut and lets the whole timeline play in full view. If the
     * platform never reports an exit (no starting window at all), the grace
     * period in `setContent` below starts the animation anyway.
     *
     * Isolated in its own `@RequiresApi` method so no pre-31 device ever loads
     * `android.window.SplashScreen`.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun handOffPlatformSplash() {
        splashScreen.setOnExitAnimationListener { view ->
            view.remove()
            launchHandoffReady.value = true
            Log.i(TAG, "onCreate: platform splash handoff")
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
