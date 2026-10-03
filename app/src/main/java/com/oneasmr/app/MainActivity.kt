package com.oneasmr.app

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.window.SplashScreenView
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
import com.oneasmr.app.ui.launch.shouldShowLaunchSplash
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
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

    /**
     * 设置页「启动动画」开关的三态：`null` = 冷启动时还没从 DataStore 读出来，
     * `true`/`false` = 用户的选择。读出来之前动画层按「开」武装 —— 它的第 0 帧
     * 与系统启动窗口逐像素相同，武装着也看不见 —— 读到「关」再收掉。
     */
    private val launchAnimationSetting = mutableStateOf<Boolean?>(null)

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
        // 此处只按进程闸门决定：设置页的「启动动画」开关要等 DataStore 读出
        // 来才知道，读到「关」时动画层会被 showLaunchSplash 直接收掉（它的
        // 第 0 帧与启动窗口同像素，收掉看不见），交棒回调随后结束启动窗口 ——
        // 用户看到的就是静止的品牌图直接换成主界面，没有任何动画。
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
                    var launchAnimationFinished by remember { mutableStateOf(false) }
                    // 设置页的「启动动画」开关。冷启动时 DataStore 还没读过盘，
                    // 所以这里保留"未读出"这一态：动画层先按「开」武装（第 0 帧
                    // 就是启动窗口那一帧），读到「关」再由 shouldShowLaunchSplash
                    // 收掉它。收掉必须发生在系统启动窗口消失之前，否则静止的第一
                    // 帧会停在屏幕上 —— 那一步由 handOffPlatformSplash 在放行启动
                    // 窗口之前等设置到达来保证，而不是靠"读盘恰好比它快"。
                    val settingFlow = remember {
                        settingsStore.launchAnimationEnabled.map<Boolean, Boolean?> { it }
                    }
                    val settingOrUnknown by settingFlow.collectAsStateWithLifecycle(initialValue = null)
                    LaunchedEffect(settingOrUnknown) {
                        val enabled = settingOrUnknown ?: return@LaunchedEffect
                        // 记到字段上：系统启动窗口的退场回调不在合成里，只能同步读它。
                        launchAnimationSetting.value = enabled
                        Log.i(TAG, "onCreate: launchAnimationEnabled=$enabled")
                    }
                    val launchAnimationEnabled = settingOrUnknown != false
                    val showLaunchAnimation = shouldShowLaunchSplash(
                        armed = playLaunchAnimation,
                        settingEnabled = launchAnimationEnabled,
                        finished = launchAnimationFinished,
                    )
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
                    // 动画层离开屏幕后（播完，或被设置关闭）回到
                    // enableEdgeToEdge() 的默认样式（浅色模式 = 深色图标，
                    // 与各页面浅底一致）；动画期间是深色底 + 浅色图标。
                    LaunchedEffect(showLaunchAnimation) {
                        // 本次冷启动没有武装过动画（转屏/回前台/调试跳过）：
                        // onCreate 已经设过默认样式，这里无事可做。
                        if (!playLaunchAnimation || showLaunchAnimation) return@LaunchedEffect
                        enableEdgeToEdge()
                        Log.i(TAG, "onCreate: launch animation layer dismissed")
                    }
                    LaunchAnimatedStart(
                        active = showLaunchAnimation,
                        started = launchHandoffReady.value,
                        onFinished = {
                            launchAnimationFinished = true
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
     *
     * 设置页关掉启动动画时这条路径照常走：没有动画需要保护，移除启动窗口
     * 就只是结束它（`SplashScreenViewProvider` 必须由应用自己 remove，留着
     * 不调用是"启动图卡住"的经典写法，所以不区分两种情形）。
     *
     * 唯一的分支是"设置还没读出来"：那就不放行，按帧等它到达（见
     * [awaitSettingThenRelease]）。等它买到的是**顺序保证** —— 关闭动画时
     * 我们的动画层一定先撤、启动窗口一定后走，用户不会看到静止的第一帧
     * （早期版本靠"读盘比系统窗口退场快"撞运气，实测只剩 25ms 余量）。
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun handOffPlatformSplash() {
        splashScreen.setOnExitAnimationListener { view ->
            val known = launchAnimationSetting.value
            if (known == null) awaitSettingThenRelease(view) else releasePlatformSplash(view, known)
        }
    }

    /**
     * 系统启动窗口已准备退场，但「启动动画」设置还没从 DataStore 读出来：
     * 先不放行它，按帧轮询等设置到达，最坏 [SETTING_WAIT_MS] 后退回"按开启
     * 处理"（= 老行为），**绝不**把启动窗口留在屏幕上。
     *
     * 不调用 `keepShowing()` 的原因：公开 SDK 的 [SplashScreenView] 只有
     * `remove()`，那个方法是 @hide，反射也不可靠 —— 而"不 remove"本身就是
     * 最天然的挂住方式，且我们一定会 remove。
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun awaitSettingThenRelease(view: SplashScreenView) {
        Log.i(TAG, "onCreate: platform splash waits for the setting")
        val deadline = SystemClock.uptimeMillis() + SETTING_WAIT_MS
        val poll = object : Runnable {
            override fun run() {
                val known = launchAnimationSetting.value
                if (known == null && SystemClock.uptimeMillis() < deadline) {
                    view.postDelayed(this, FRAME_POLL_MS)
                    return
                }
                releasePlatformSplash(view, animationEnabled = known != false)
            }
        }
        view.post(poll)
    }

    /**
     * 移除系统启动窗口。动画该播时立即移除：窗口像素与动画层第 0 帧相同，
     * 这是一次看不见的切换，随后整段动画在前台播放。
     *
     * 关掉动画时反过来 —— 必须等两帧，让"动画层已经不在合成树里"的那一帧
     * 先画出来（设置写进 state → 重组 → 绘制），否则窗口消失时露出的还是
     * 静止的身份帧，也就是用户看到的那一帧。
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun releasePlatformSplash(view: SplashScreenView, animationEnabled: Boolean) {
        if (animationEnabled) {
            view.remove()
            launchHandoffReady.value = true
            Log.i(TAG, "onCreate: platform splash handoff (animation=true)")
            return
        }
        Choreographer.getInstance().postFrameCallback {
            Choreographer.getInstance().postFrameCallback {
                view.remove()
                Log.i(TAG, "onCreate: platform splash removed (animation=false, layer already gone)")
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

        /** 等「启动动画」设置读出来的上限（超过就按开启处理，绝不放任启动窗口留着）。 */
        const val SETTING_WAIT_MS = 600L

        /** 等待期间的轮询间隔：一帧。 */
        const val FRAME_POLL_MS = 16L
    }
}
