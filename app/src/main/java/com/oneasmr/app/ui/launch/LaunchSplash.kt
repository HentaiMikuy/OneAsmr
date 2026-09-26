package com.oneasmr.app.ui.launch

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oneasmr.app.BuildConfig
import com.oneasmr.app.R

/**
 * Cold-start animation that hands the platform's branded starting window over
 * to the app without a visible seam.
 *
 * The pixel contract lives in [LaunchAnimationSpec]: on API 31+ the first
 * Compose frame is the identity frame (same `@color/ic_launcher_background`
 * fill, same [R.drawable.ic_splash] mark at its intrinsic 288dp, dead-center),
 * so the system window's exit animation and this overlay render the same
 * picture for [LaunchAnimationSpec.Companion.HOLD_MS] before anything moves.
 *
 * [started] is the hand-off gate. On API 31+ the system reparents its splash
 * view INTO the activity window, on top of the first Compose frame, and holds
 * it there while its own exit animation plays — which measurably swallowed the
 * first ~580ms of the animation before this gate existed (the wordmark only
 * got ~280ms of screen time). The activity therefore takes over the exit
 * (`SplashScreen.setOnExitAnimationListener`) and drops the system view
 * immediately; the pixels underneath are identical, so the cut is invisible —
 * and the timeline only starts at that moment, guaranteeing that the whole
 * animation is actually seen. See [requiresPlatformSplashHandoff].
 *
 * The overlay is composed ON TOP of [content] (which is laid out and painted
 * underneath from the first frame, so the library's first page loads *during*
 * the animation rather than after it) and is dropped from composition the
 * moment the exit fade completes — it therefore swallows touches for exactly
 * the length of the animation and never again.
 *
 * Only animated values are read inside `graphicsLayer`/draw lambdas, so a
 * frame step invalidates the render layer instead of recomposing the tree —
 * the same discipline the bottom-bar motion uses in OneAsmrNavHost.
 */
@Composable
internal fun LaunchAnimatedStart(
    active: Boolean,
    started: Boolean,
    modifier: Modifier = Modifier,
    onFinished: () -> Unit,
    content: @Composable () -> Unit,
) {
    val spec = remember {
        LaunchAnimationSpec(
            platformStartsBranded = requiresPlatformSplashHandoff(Build.VERSION.SDK_INT),
        )
    }
    // Frame 0 is the seam frame: it is what the very first render uses,
    // before the frame clock has produced a single tick.
    val frame = remember(spec) { mutableStateOf(spec.frameAt(0)) }

    LaunchedEffect(spec, active, started) {
        // Hold the identity frame until the system starting window is out of
        // the way (its pixels are identical, so this is a still image, not a
        // stall), then run the whole timeline in full view.
        if (!active || !started) return@LaunchedEffect
        val startNanos = withFrameNanos { it }
        while (true) {
            val nowNanos = withFrameNanos { it }
            val elapsedMs = ((nowNanos - startNanos) / 1_000_000L).toInt()
            val next = spec.frameAt(elapsedMs)
            frame.value = next
            if (elapsedMs >= spec.totalMs) break
        }
        onFinished()
    }

    Box(modifier.fillMaxSize()) {
        // [content] must keep the SAME composition slot whether or not the
        // splash is up. Branching the tree shape on `active` would make the
        // end of the animation tear down and rebuild everything inside it —
        // including the NavHost, i.e. the destination a deep link resolved
        // during the animation, the back stack, and every loaded state. Only
        // the modifier and a TRAILING sibling are conditional here, so the
        // content subtree is never recreated; outside the animation it also
        // carries no extra render layer at all.
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (active) {
                        Modifier.graphicsLayer {
                            val scale = frame.value.contentScale
                            scaleX = scale
                            scaleY = scale
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            content()
        }
        if (active) {
            LaunchSplashLayer(frame)
        }
    }
}

/**
 * The brand layer itself: background, mark (with bloom and light sweep) and
 * wordmark. Purely a function of [frame] — no timers live here.
 */
@Composable
private fun LaunchSplashLayer(frame: State<LaunchAnimationFrame>) {
    // Deliberately the SAME resource the platform splash and the window
    // background use (day/night variants included), so the three never drift.
    val background = colorResource(R.color.ic_launcher_background)
    val accent = Color(ACCENT_TEAL)
    val wordColor = Color(WORD_WHITE)

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val f = frame.value
                alpha = f.overlayAlpha
                scaleX = f.overlayScale
                scaleY = f.overlayScale
            }
            .background(background)
            // Swallow every touch for the life of the animation: the library
            // underneath must not react to taps aimed at a splash screen.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Soft bloom behind the mark (alpha 0 at both window edges).
        Box(
            Modifier
                .size(MARK_SIZE_DP.dp)
                .graphicsLayer { alpha = frame.value.glowAlpha }
                .background(
                    Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.5f), Color.Transparent),
                    ),
                    shape = CircleShape,
                ),
        )

        Box(Modifier.size(MARK_SIZE_DP.dp), contentAlignment = Alignment.Center) {
            // The mark（「暗夜声线」波形）。288dp 是平台启动图画 `ic_splash` 的
            // 图标预算；drawable 自身把波形收在画布中央（跨度 25–78 / 108），
            // 所以这里按原尺寸居中绘制即可与平台那一帧逐像素对齐。
            Image(
                painter = painterResource(R.drawable.ic_splash),
                contentDescription = null,
                modifier = Modifier
                    .size(MARK_SIZE_DP.dp)
                    .graphicsLayer {
                        val f = frame.value
                        scaleX = f.markScale
                        scaleY = f.markScale
                        alpha = f.markAlpha
                    },
            )
            // 掠过一层斜向柔光（玻璃反光）。波形没有"圆盘"可裁剪，所以光带
            // 直接铺在 288dp 标记框上：只在穿过波形与背景时各加一点点亮度。
            Box(
                Modifier
                    .size(MARK_SIZE_DP.dp)
                    .drawWithContent {
                        drawContent()
                        val f = frame.value
                        if (f.sweepAlpha > 0f) {
                            val band = size.width * 0.42f
                            val cx = size.width * (f.sweepX + 1f) / 2f
                            drawRect(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.White,
                                        Color.Transparent,
                                    ),
                                    start = Offset(cx - band / 2f, 0f),
                                    end = Offset(cx + band / 2f, size.height * 0.55f),
                                ),
                                alpha = f.sweepAlpha,
                            )
                        }
                    },
            )
        }

        // Wordmark: sits just below the mark's 288dp canvas, rises into place.
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    val f = frame.value
                    alpha = f.wordAlpha
                    translationY = WORD_OFFSET_DP.dp.toPx() +
                        f.wordRiseFraction * WORD_RISE_DP.dp.toPx()
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = wordColor,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 4.sp,
            )
            Spacer(Modifier.height(10.dp))
            // Accent rule growing out of the wordmark; scaleX only, so the
            // 2dp line never triggers a layout pass per frame.
            Box(
                Modifier
                    .size(width = 44.dp, height = 2.dp)
                    .graphicsLayer { scaleX = 1f - frame.value.wordRiseFraction }
                    .background(accent, RoundedCornerShape(1.dp)),
            )
        }
    }
}

/** Splash-screen icon budget the platform uses for `ic_splash` (288dp canvas). */
private const val MARK_SIZE_DP = 288f

/** Wordmark centre offset (below the mark's canvas) and its rise distance. */
private const val WORD_OFFSET_DP = 150f
private const val WORD_RISE_DP = 14f

private const val ACCENT_TEAL = 0xFF3FCFC4

/** Brand ink for the wordmark: cool white against the midnight navy. */
private const val WORD_WHITE = 0xFFF2F0F7

/**
 * Process-scoped launch-animation gate. One animation per cold start: the
 * platform splash has the same lifetime (it only appears while the process is
 * starting), so replaying ours on a warm activity start would be a bug.
 *
 * [EXTRA_SKIP_LAUNCH_ANIMATION] is the debug-only automation hook — QA and
 * deep-link walkthroughs that do not want to wait for the animation can pass
 * `--ez com.oneasmr.app.extra.SKIP_LAUNCH_ANIMATION true`; release builds
 * ignore it (and it is stripped from the flow by [BuildConfig.DEBUG]).
 */
internal object LaunchAnimationSession {
    private var gate = LaunchAnimationGate(debugBuild = BuildConfig.DEBUG)

    /** True when this process should play the launch animation. Consumes. */
    fun shouldPlay(skipRequested: Boolean): Boolean = gate.shouldPlay(skipRequested)

    /** Test-only: re-arm the gate (the singleton is otherwise per-process). */
    fun reset() {
        gate = LaunchAnimationGate(debugBuild = BuildConfig.DEBUG)
    }

    /** Launch intent extra understood only by debug builds. */
    const val EXTRA_SKIP_LAUNCH_ANIMATION = "com.oneasmr.app.extra.SKIP_LAUNCH_ANIMATION"
}
