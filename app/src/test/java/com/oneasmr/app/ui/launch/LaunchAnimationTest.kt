package com.oneasmr.app.ui.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the launch-animation timeline contract.
 *
 * The load-bearing assertion is the first one: on API 31+ the app's very first
 * Compose frame must be the identity frame — the same background color, the
 * same 288dp mark, dead-centered, scale 1, alpha 1 — because that is exactly
 * what the platform's branded starting window shows. Anything else turns the
 * system-splash hand-off into a visible jump, which is the whole point of the
 * feature.
 */
class LaunchAnimationTest {

    private val branded = LaunchAnimationSpec(platformStartsBranded = true)
    private val unbranded = LaunchAnimationSpec(platformStartsBranded = false)

    // ---------------------------------------------------------------- seam

    @Test
    fun `first branded frame is pixel-identical to the platform splash`() {
        assertEquals(LaunchAnimationFrame.PlatformMatch, branded.frameAt(0))
        val first = branded.frameAt(0)
        assertEquals(1f, first.markScale, 0f)
        assertEquals(1f, first.markAlpha, 0f)
        assertEquals(1f, first.overlayAlpha, 0f)
        assertEquals(1f, first.overlayScale, 0f)
        assertEquals(1f, first.contentScale, 0f)
        assertEquals(0f, first.glowAlpha, 0f)
        assertEquals(0f, first.sweepAlpha, 0f)
        assertEquals(0f, first.wordAlpha, 0f)
    }

    @Test
    fun `branded spec holds that frame while the system starting window exits`() {
        for (t in 0..branded.holdMs) {
            assertEquals("elapsed=$t", LaunchAnimationFrame.PlatformMatch, branded.frameAt(t))
        }
        assertTrue("hold must be long enough to cover the exit", branded.holdMs >= 80)
    }

    @Test
    fun `unbranded first frame shows the brand background without a mark`() {
        val first = unbranded.frameAt(0)
        assertTrue(first.isMarkInvisible)
        assertEquals(1f, first.overlayAlpha, 0f)
        assertEquals(0f, first.glowAlpha, 0f)
        // Without a platform mark there is nothing to hold for, so the
        // unbranded variant is never LONGER than the branded one.
        assertTrue(unbranded.totalMs <= branded.totalMs)
        assertTrue(unbranded.holdMs == 0)
    }

    @Test
    fun `unbranded mark fades and scales in instead of popping`() {
        assertEquals(0f, unbranded.frameAt(0).markAlpha, 0f)
        val half = unbranded.frameAt(LaunchAnimationSpec.MARK_IN_MS / 2)
        assertTrue("half=${half.markAlpha}", half.markAlpha > 0f && half.markAlpha < 1f)
        assertTrue(half.markScale > LaunchAnimationSpec.MARK_IN_FROM_SCALE)
        assertTrue(half.markScale < 1.05f)
        val settled = unbranded.frameAt(LaunchAnimationSpec.MARK_IN_MS)
        assertEquals(1f, settled.markAlpha, 1e-4f)
    }

    // --------------------------------------------------------------- phases

    @Test
    fun `mark swells inside the breath window and returns to identity scale`() {
        val peak = branded.frameAt(branded.holdMs + LaunchAnimationSpec.MARK_MS / 2)
        assertTrue("peak=${peak.markScale}", peak.markScale > 1f)
        assertTrue(peak.markScale <= 1f + LaunchAnimationSpec.SWELL_AMPLITUDE + 1e-4f)
        assertEquals(1f, branded.frameAt(branded.markEndMs).markScale, 1e-4f)
        // Identity scale at the window's edges means the swell cannot
        // accumulate into a visible size change across the animation.
        assertEquals(1f, branded.frameAt(branded.holdMs).markScale, 1e-4f)
    }

    @Test
    fun `mark alpha stays at 1 on the branded path`() {
        for (t in 0..branded.totalMs step 10) {
            assertEquals("elapsed=$t", 1f, branded.frameAt(t).markAlpha, 0f)
        }
    }

    @Test
    fun `glow is invisible at both edges of the breath window`() {
        assertEquals(0f, branded.frameAt(branded.holdMs).glowAlpha, 0f)
        assertEquals(0f, branded.frameAt(branded.markEndMs).glowAlpha, 1e-4f)
        val peak = branded.frameAt(branded.holdMs + LaunchAnimationSpec.MARK_MS / 2).glowAlpha
        assertTrue("peak=$peak", peak > 0f && peak <= LaunchAnimationSpec.GLOW_PEAK)
    }

    @Test
    fun `sweep travels across the disc and is transparent at both ends`() {
        val start = branded.holdMs + LaunchAnimationSpec.MARK_MS / 4
        assertEquals(0f, branded.frameAt(start).sweepAlpha, 1e-4f)
        assertEquals(-1f, branded.frameAt(start).sweepX, 1e-4f)
        assertEquals(0f, branded.frameAt(branded.markEndMs).sweepAlpha, 1e-4f)
        assertEquals(1f, branded.frameAt(branded.markEndMs).sweepX, 1e-4f)
        val mid = branded.frameAt((start + branded.markEndMs) / 2)
        assertTrue("midAlpha=${mid.sweepAlpha}", mid.sweepAlpha > 0f)
        assertEquals(0f, mid.sweepX, 0.05f)
    }

    @Test
    fun `wordmark fades in monotonically and settles exactly`() {
        assertEquals(0f, branded.frameAt(branded.wordStartMs).wordAlpha, 1e-4f)
        assertEquals(1f, branded.frameAt(branded.wordStartMs).wordRiseFraction, 1e-4f)
        var previous = -1f
        for (t in branded.wordStartMs..branded.wordEndMs) {
            val alpha = branded.frameAt(t).wordAlpha
            assertTrue("elapsed=$t alpha=$alpha", alpha >= previous - 1e-6f)
            previous = alpha
        }
        assertEquals(1f, branded.frameAt(branded.wordEndMs).wordAlpha, 1e-4f)
        assertEquals(0f, branded.frameAt(branded.wordEndMs).wordRiseFraction, 1e-4f)
    }

    @Test
    fun `wordmark starts while the mark is still moving so motion is continuous`() {
        assertTrue(branded.wordStartMs < branded.markEndMs)
        assertTrue(branded.wordStartMs > branded.holdMs)
    }

    // ----------------------------------------------------------------- exit

    @Test
    fun `exit fades the splash away and leaves the content untouched`() {
        assertEquals(1f, branded.frameAt(branded.exitStartMs).overlayAlpha, 1e-4f)
        assertEquals(0f, branded.frameAt(branded.totalMs).overlayAlpha, 0f)
        assertTrue(branded.frameAt(branded.exitStartMs).overlayScale < 1.01f)
        assertTrue(branded.frameAt(branded.totalMs).overlayScale > 1f)
        assertFalse(branded.isFinished(branded.frameAt(branded.exitStartMs)))
        assertTrue(branded.isFinished(branded.frameAt(branded.totalMs)))
    }

    @Test
    fun `overlay alpha decreases monotonically through the exit`() {
        var previous = 2f
        for (t in branded.exitStartMs..branded.totalMs) {
            val alpha = branded.frameAt(t).overlayAlpha
            assertTrue("elapsed=$t alpha=$alpha", alpha <= previous + 1e-6f)
            previous = alpha
        }
    }

    @Test
    fun `content scale is exactly 1 outside the exit and breathes inside it`() {
        for (t in 0..branded.exitStartMs) {
            assertEquals("elapsed=$t", 1f, branded.frameAt(t).contentScale, 0f)
        }
        assertEquals(1f, branded.frameAt(branded.totalMs).contentScale, 1e-4f)
        val midExit = branded.frameAt((branded.exitStartMs + branded.totalMs) / 2).contentScale
        assertTrue("midExit=$midExit", midExit > 1f)
        assertTrue(midExit <= 1f + LaunchAnimationSpec.CONTENT_SWELL + 1e-4f)
    }

    // ------------------------------------------------------------ integrity

    @Test
    fun `elapsed time is clamped at both ends`() {
        assertEquals(branded.frameAt(0), branded.frameAt(-1))
        assertEquals(branded.frameAt(0), branded.frameAt(-10_000))
        assertEquals(branded.frameAt(branded.totalMs), branded.frameAt(branded.totalMs + 60_000))
        assertEquals(unbranded.frameAt(0), unbranded.frameAt(-1))
    }

    @Test
    fun `every animated value stays inside its documented range`() {
        for (spec in listOf(branded, unbranded)) {
            for (t in -100..(spec.totalMs + 100) step 5) {
                val f = spec.frameAt(t)
                assertTrue("markScale=${f.markScale}", f.markScale > 0.5f && f.markScale < 1.5f)
                assertTrue("markAlpha=${f.markAlpha}", f.markAlpha in 0f..1f)
                assertTrue("glowAlpha=${f.glowAlpha}", f.glowAlpha in 0f..1f)
                assertTrue("sweepAlpha=${f.sweepAlpha}", f.sweepAlpha in 0f..1f)
                assertTrue("sweepX=${f.sweepX}", f.sweepX in -1f..1f)
                assertTrue("wordAlpha=${f.wordAlpha}", f.wordAlpha in 0f..1f)
                assertTrue("wordRise=${f.wordRiseFraction}", f.wordRiseFraction in 0f..1f)
                assertTrue("overlayAlpha=${f.overlayAlpha}", f.overlayAlpha in 0f..1f)
                assertTrue("overlayScale=${f.overlayScale}", f.overlayScale in 1f..1.1f)
                assertTrue("contentScale=${f.contentScale}", f.contentScale in 1f..1.05f)
            }
        }
    }

    @Test
    fun `window ordering keeps the timeline coherent`() {
        assertEquals(0, unbranded.holdMs)
        assertEquals(LaunchAnimationSpec.HOLD_MS, branded.holdMs)
        assertTrue(branded.holdMs < branded.markEndMs)
        assertTrue(branded.wordStartMs < branded.markEndMs)
        assertTrue(branded.exitStartMs > branded.wordEndMs)
        assertEquals(branded.exitStartMs + LaunchAnimationSpec.EXIT_MS, branded.totalMs)
        assertEquals(unbranded.exitStartMs + LaunchAnimationSpec.EXIT_MS, unbranded.totalMs)
        // The branded hold is the only difference in length.
        assertEquals(branded.totalMs - unbranded.totalMs, LaunchAnimationSpec.HOLD_MS)
    }

    /**
     * The finished composition (mark + wordmark) must hold still before the
     * exit fade — user feedback on device was that ~0.9s of motion was over
     * before the animation could be read.
     */
    @Test
    fun `the finished composition lingers for one and a half seconds`() {
        assertEquals(1500, LaunchAnimationSpec.LINGER_MS)
        assertEquals(LaunchAnimationSpec.LINGER_MS, branded.exitStartMs - branded.wordEndMs)
        assertEquals(LaunchAnimationSpec.LINGER_MS, unbranded.exitStartMs - unbranded.wordEndMs)
    }

    @Test
    fun `every frame inside the linger is identical and fully settled`() {
        val settled = branded.frameAt(branded.wordEndMs)
        assertEquals(1f, settled.markScale, 1e-4f)
        assertEquals(1f, settled.markAlpha, 1e-4f)
        assertEquals(0f, settled.glowAlpha, 1e-4f)
        assertEquals(0f, settled.sweepAlpha, 1e-4f)
        assertEquals(1f, settled.wordAlpha, 1e-4f)
        assertEquals(0f, settled.wordRiseFraction, 1e-4f)
        assertEquals(1f, settled.overlayAlpha, 0f)
        assertEquals(1f, settled.contentScale, 0f)
        // Sampled through the whole pause: nothing moves.
        for (t in branded.wordEndMs..branded.exitStartMs step 50) {
            assertEquals("elapsed=$t", settled, branded.frameAt(t))
        }
    }

    @Test
    fun `the motion itself still fits the nine tenths of a second budget`() {
        // Motion = identity hold + breath + wordmark, i.e. everything up to the
        // start of the linger; the total is motion + linger + exit.
        assertTrue("motion=${branded.wordEndMs}", branded.wordEndMs in 600..900)
        assertEquals(
            branded.wordEndMs + LaunchAnimationSpec.LINGER_MS + LaunchAnimationSpec.EXIT_MS,
            branded.totalMs,
        )
        assertTrue(branded.wordEndMs < branded.totalMs)
        assertTrue(LaunchAnimationSpec.EXIT_MS < LaunchAnimationSpec.MARK_MS)
    }

    @Test
    fun `frames actually change over time`() {
        assertNotEquals(branded.frameAt(0), branded.frameAt(branded.holdMs + 100))
        assertNotEquals(branded.frameAt(branded.wordStartMs), branded.frameAt(branded.wordEndMs))
        assertNotEquals(branded.frameAt(branded.exitStartMs), branded.frameAt(branded.totalMs))
        assertNotEquals(unbranded.frameAt(0), unbranded.frameAt(LaunchAnimationSpec.MARK_IN_MS))
    }
}

/**
 * The gate decides *whether* the animation runs: once per process (a cold
 * start), with a debug-only skip hook for automation.
 */
class LaunchAnimationGateTest {

    @Test
    fun `plays exactly once per process`() {
        val gate = LaunchAnimationGate(debugBuild = false)
        assertTrue(gate.shouldPlay(skipRequested = false))
        assertFalse(gate.shouldPlay(skipRequested = false))
        assertFalse(gate.shouldPlay(skipRequested = true))
    }

    @Test
    fun `release builds ignore the skip extra`() {
        val gate = LaunchAnimationGate(debugBuild = false)
        assertTrue(gate.shouldPlay(skipRequested = true))
    }

    @Test
    fun `debug builds honour the skip extra and still consume the gate`() {
        val gate = LaunchAnimationGate(debugBuild = true)
        assertFalse(gate.shouldPlay(skipRequested = true))
        // A skipped launch must not arm the animation for the next activity.
        assertFalse(gate.shouldPlay(skipRequested = false))
    }

    @Test
    fun `session is armed for the first activity creation of the process`() {
        LaunchAnimationSession.reset()
        // Unit tests run against the debug variant: BuildConfig.DEBUG == true.
        assertTrue(LaunchAnimationSession.shouldPlay(skipRequested = false))
        assertFalse(LaunchAnimationSession.shouldPlay(skipRequested = false))
    }

    @Test
    fun `session skip hook suppresses the animation without arming it later`() {
        LaunchAnimationSession.reset()
        assertFalse(LaunchAnimationSession.shouldPlay(skipRequested = true))
        assertFalse(LaunchAnimationSession.shouldPlay(skipRequested = false))
    }

    @Test
    fun `skip extra name is a stable ascii marker for build verification`() {
        assertEquals(
            "com.oneasmr.app.extra.SKIP_LAUNCH_ANIMATION",
            LaunchAnimationSession.EXTRA_SKIP_LAUNCH_ANIMATION,
        )
    }
}

/**
 * The hand-off policy decides whether the timeline waits for the platform's
 * own splash view before it starts. Getting this wrong is not cosmetic: on the
 * device the system permanently reparented its splash view above the first
 * Compose frame and ran its own ~200ms exit on top, which swallowed the first
 * ~580ms of the animation (measured over device-clock logcat correlation) and
 * left the wordmark about 280ms of screen time. Both numbers below therefore
 * stay locked.
 */
class LaunchSplashHandoffPolicyTest {

    @Test
    fun `only API 31 and above have a platform splash view to hand over`() {
        assertFalse(requiresPlatformSplashHandoff(26))
        assertFalse(requiresPlatformSplashHandoff(30))
        assertTrue(requiresPlatformSplashHandoff(31))
        assertTrue(requiresPlatformSplashHandoff(35))
        assertEquals(31, PLATFORM_SPLASH_API)
    }

    @Test
    fun `handoff grace covers a slow cold start yet stays bounded`() {
        assertTrue("grace=$SPLASH_HANDOFF_GRACE_MS", SPLASH_HANDOFF_GRACE_MS >= 600)
        assertTrue("grace=$SPLASH_HANDOFF_GRACE_MS", SPLASH_HANDOFF_GRACE_MS <= 2000)
        // Measured on the Pixel_9 emulator: first Compose frame at onCreate
        // +334ms, platform splash exit callback a further ~250ms later, so the
        // grace must comfortably exceed the observed callback latency.
        assertTrue(SPLASH_HANDOFF_GRACE_MS > 600)
    }
}

/**
 * 设置页的「启动动画」开关是冷启动动画的第二道闸。闸门在 `onCreate` 同步
 * 决定"本次冷启动要不要动画",设置值却要等 DataStore 读完才到 —— 所以规则
 * 写成纯函数,把三个到达时刻不同的信号钉在一起。
 */
class LaunchAnimationSettingTest {

    @Test
    fun `setting off vetoes an animation the process gate armed`() {
        assertFalse(shouldShowLaunchSplash(armed = true, settingEnabled = false, finished = false))
    }

    @Test
    fun `enabled animation stays until the timeline reports finished`() {
        assertTrue(shouldShowLaunchSplash(armed = true, settingEnabled = true, finished = false))
        assertFalse(shouldShowLaunchSplash(armed = true, settingEnabled = true, finished = true))
    }

    @Test
    fun `a warm start never shows the layer whatever the setting says`() {
        assertFalse(shouldShowLaunchSplash(armed = false, settingEnabled = true, finished = false))
        assertFalse(shouldShowLaunchSplash(armed = false, settingEnabled = false, finished = true))
    }

    /**
     * 关闭路径必须经过"已武装"这一态:MainActivity 把"设置还没读出来"当开启
     * 处理(动画层从第 0 帧起就在合成树里),所以读到「关」之前那一层已经在屏幕
     * 上——它的第 0 帧与启动窗口逐像素相同,收掉才不可见。收掉与"系统启动窗口
     * 消失"的先后顺序由 MainActivity 挂住启动窗口来保证,与本函数无关。
     */
    @Test
    fun `the layer is armed before the persisted setting arrives`() {
        assertTrue(shouldShowLaunchSplash(armed = true, settingEnabled = true, finished = false))
        assertFalse(shouldShowLaunchSplash(armed = true, settingEnabled = false, finished = false))
    }
}
