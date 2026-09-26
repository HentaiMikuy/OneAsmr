package com.oneasmr.app.ui.launch

import kotlin.math.PI
import kotlin.math.sin

/**
 * Pure (Compose-free, Android-free) timeline for the cold-start launch
 * animation, so the whole motion contract is unit-testable on the JVM.
 *
 * ## The seam rule (why this is more than decoration)
 *
 * On API 31+ the system paints the branded starting window before the app's
 * first frame exists: `@drawable/ic_splash` on `@color/ic_launcher_background`
 * (see res/values-v31/themes.xml). The app's own splash therefore MUST start
 * as a byte-for-byte clone of that window — same background color, same
 * 288dp mark, dead-centered, scale 1, alpha 1 — and only then move forward.
 * [LaunchAnimationSpec.frameAt] returns exactly that identity frame for
 * `elapsedMs == 0` on API 31+ (locked by test), which is what makes the
 * hand-off from the system window invisible instead of a visible "restart".
 *
 * On API 26-30 there is no platform mark (the starting window is only the
 * brand color), so the mark instead fades and scales in — see
 * [LaunchAnimationSpec.platformStartsBranded].
 *
 * ## Timeline (branded variant, total [LaunchAnimationSpec.totalMs] ≈ 880ms)
 *
 * ```
 *  0 ─── hold ─── mark window ─────────────── exit ─── end
 *  │     100ms    (breath + glow + sweep)     180ms
 *  │              ├─ wordmark fades/rises (overlaps the mark tail)
 *  └ identity frame == platform splash's last frame
 * ```
 *
 * Every value below is expressed relative to the spec's own windows, so
 * changing a duration constant keeps the phases consistent.
 */
internal data class LaunchAnimationFrame(
    /** Scale of the whole 288dp mark; 1f = identical to the platform splash. */
    val markScale: Float,
    /** Alpha of the mark; 1f = identical to the platform splash. */
    val markAlpha: Float,
    /** Soft teal bloom behind the mark (0f = invisible, i.e. seam-safe). */
    val glowAlpha: Float,
    /** Horizontal position of the light sweep across the disc: -1 left → 1 right. */
    val sweepX: Float,
    /** Alpha of the light sweep (0f = invisible). */
    val sweepAlpha: Float,
    /** Alpha of the wordmark block. */
    val wordAlpha: Float,
    /** Wordmark rise: 1f = at its lowest start offset, 0f = settled. */
    val wordRiseFraction: Float,
    /** Alpha of the whole splash layer (1f → 0f hands the screen to the app). */
    val overlayAlpha: Float,
    /** Scale of the splash layer during the exit (a gentle push-forward). */
    val overlayScale: Float,
    /**
     * Scale of the app content behind the splash: exactly 1f outside the exit
     * window, then a subtle swell (≤ 1 + [LaunchAnimationSpec.CONTENT_SWELL])
     * that returns to exactly 1f — the same "breath" language as the mark, so
     * the hand-off reads as one continuous motion instead of a cut.
     */
    val contentScale: Float,
) {
    internal companion object {
        /**
         * The identity frame: pixel-identical to the platform splash's last
         * frame. Returning this at `elapsedMs == 0` is the seam guarantee.
         */
        val PlatformMatch = LaunchAnimationFrame(
            markScale = 1f,
            markAlpha = 1f,
            glowAlpha = 0f,
            sweepX = -1f,
            sweepAlpha = 0f,
            wordAlpha = 0f,
            wordRiseFraction = 1f,
            overlayAlpha = 1f,
            overlayScale = 1f,
            contentScale = 1f,
        )

        /** First frame on API 26-30: brand background only, mark not yet drawn. */
        val UnbrandedStart = PlatformMatch.copy(
            markAlpha = 0f,
            markScale = LaunchAnimationSpec.MARK_IN_FROM_SCALE,
        )
    }

    /** True when this frame renders no mark at all. */
    internal val isMarkInvisible: Boolean get() = markAlpha <= 0f
}

internal class LaunchAnimationSpec(
    /**
     * True when the platform itself draws the branded starting window
     * (API 31+, i.e. the `windowSplashScreen*` theme attributes apply).
     */
    val platformStartsBranded: Boolean = true,
) {
    /** Hold that guarantees the system's starting-window exit finishes unseen. */
    val holdMs: Int = if (platformStartsBranded) HOLD_MS else 0

    /** Breathing window: the mark swells and settles back to scale 1. */
    val markEndMs: Int = holdMs + MARK_MS

    /** Wordmark starts before the mark settles, so the motion stays continuous. */
    val wordStartMs: Int = markEndMs - WORD_OVERLAP_MS
    val wordEndMs: Int = wordStartMs + WORD_MS

    /** The splash layer fades out while the app content pushes in. */
    val exitStartMs: Int = wordEndMs
    val totalMs: Int = exitStartMs + EXIT_MS

    /** Light-sweep sub-window inside the mark window. */
    private val sweepStartMs: Int = holdMs + MARK_MS / 4
    private val sweepEndMs: Int = markEndMs

    private val markInMs: Int = MARK_IN_MS.coerceAtMost(markEndMs - holdMs)

    /**
     * Maps elapsed time (milliseconds since the first Compose frame) to a
     * render frame. Clamped at both ends: negative → the first frame, beyond
     * [totalMs] → the final frame, so callers can drive it with raw frame
     * timestamps without extra guards.
     */
    fun frameAt(elapsedMs: Int): LaunchAnimationFrame {
        if (elapsedMs <= 0) {
            return if (platformStartsBranded) {
                LaunchAnimationFrame.PlatformMatch
            } else {
                LaunchAnimationFrame.UnbrandedStart
            }
        }
        val t = elapsedMs.coerceAtMost(totalMs)

        // Mark: identity when the platform already drew it, otherwise fade/scale in.
        val markIn = if (platformStartsBranded) 1f else easeOutCubic(fraction(t, 0, markInMs))
        val markSwell = sin(PI * fraction(t, holdMs, markEndMs).toDouble()).toFloat()
        val markScale = lerp(MARK_IN_FROM_SCALE, 1f, markIn) * (1f + SWELL_AMPLITUDE * markSwell)
        val markAlpha = if (platformStartsBranded) 1f else markIn

        // Glow: zero at both window edges (so it never breaks the seam).
        val glowAlpha = GLOW_PEAK * markSwell

        // Sweep: a band of light crossing the disc, alpha-faded at both ends.
        val sweepP = fraction(t, sweepStartMs, sweepEndMs)
        val sweepAlpha = SWEEP_PEAK * sin(PI * sweepP.toDouble()).toFloat()
        val sweepX = -1f + 2f * sweepP

        // Wordmark: fades in and rises into place.
        val wordP = easeOutCubic(fraction(t, wordStartMs, wordEndMs))
        val wordAlpha = wordP
        val wordRise = 1f - wordP

        // Exit: the splash layer leaves while the content swells and settles.
        val exitP = smoothStep(fraction(t, exitStartMs, totalMs))
        val overlayAlpha = 1f - exitP
        val overlayScale = 1f + EXIT_SCALE_GROWTH * exitP
        // sin() is 0 at both window edges: the content is untouched (exactly
        // 1f) before the exit and exactly 1f once it completes.
        val contentScale = 1f + CONTENT_SWELL * sin(PI * exitP.toDouble()).toFloat()

        return LaunchAnimationFrame(
            markScale = markScale,
            markAlpha = markAlpha,
            glowAlpha = glowAlpha,
            sweepX = sweepX,
            sweepAlpha = sweepAlpha,
            wordAlpha = wordAlpha,
            wordRiseFraction = wordRise,
            overlayAlpha = overlayAlpha,
            overlayScale = overlayScale,
            contentScale = contentScale,
        )
    }

    /**
     * False once the exit has fully faded the overlay; the caller then drops
     * it from composition (also the point where it stops swallowing touches).
     */
    fun isFinished(frame: LaunchAnimationFrame): Boolean = frame.overlayAlpha <= 0f

    internal companion object {
        /** Identity hold covering the system starting-window exit animation. */
        const val HOLD_MS = 100

        /** Breathing / glow / sweep window. */
        const val MARK_MS = 420

        /** The mark fades in over this window when the platform drew nothing. */
        const val MARK_IN_MS = 300

        /** Wordmark animation, overlapping the mark's tail by [WORD_OVERLAP_MS]. */
        const val WORD_MS = 320
        const val WORD_OVERLAP_MS = 140

        /** Splash fade-out (content push-in runs in the same window). */
        const val EXIT_MS = 180

        /** Peak extra scale of the mark during the breath (~4.5%). */
        const val SWELL_AMPLITUDE = 0.045f

        /** Mark start scale when the platform drew no mark (a small push-in). */
        const val MARK_IN_FROM_SCALE = 0.92f

        const val GLOW_PEAK = 0.55f
        const val SWEEP_PEAK = 0.30f
        const val EXIT_SCALE_GROWTH = 0.06f

        /** Peak swell of the content behind the fading splash (2%). */
        const val CONTENT_SWELL = 0.02f
    }
}

/**
 * One-shot gate: the launch animation plays for the FIRST activity creation
 * of a process (= a cold start) and never again, so returning from Recents,
 * a configuration change, or an `am start` deep link into the running app
 * does not replay it. The platform splash behaves the same way: it only
 * appears when the process is actually starting.
 *
 * [skipRequested] is the debug-only escape hatch used by automation
 * (`adb shell am start ... --ez com.oneasmr.app.extra.SKIP_LAUNCH_ANIMATION true`):
 * in a debug build it suppresses the animation without changing any other
 * behaviour; in release builds the extra is ignored.
 *
 * The gate consumes on the first call either way — a skipped launch must not
 * arm the animation for a later activity creation in the same process.
 */
internal class LaunchAnimationGate(private val debugBuild: Boolean) {
    private var consumed = false

    fun shouldPlay(skipRequested: Boolean): Boolean {
        if (consumed) return false
        consumed = true
        return !(debugBuild && skipRequested)
    }
}

/** Eased fraction of [value] inside `[from, to]`, clamped to 0..1. */
private fun fraction(value: Int, from: Int, to: Int): Float {
    if (to <= from) return if (value >= to) 1f else 0f
    return ((value - from).toFloat() / (to - from).toFloat()).coerceIn(0f, 1f)
}

private fun easeOutCubic(t: Float): Float {
    val inv = 1f - t
    return 1f - inv * inv * inv
}

private fun smoothStep(t: Float): Float = t * t * (3f - 2f * t)

private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

/**
 * True when the platform paints the branded starting window, which is also
 * exactly when the launch animation must wait for the system's splash view to
 * be handed over (API 31+, i.e. the `windowSplashScreen*` theme attributes in
 * res/values-v31/themes.xml). Below that the app's own overlay is the only
 * splash, so the timeline starts at the first frame.
 */
internal fun requiresPlatformSplashHandoff(sdkInt: Int): Boolean =
    sdkInt >= PLATFORM_SPLASH_API

/**
 * Upper bound on how long the animation waits for the platform's splash exit
 * callback before starting anyway. The callback normally arrives one frame
 * after the app draws (a few hundred ms into a cold start); this only covers
 * a platform that never reports an exit (e.g. no starting window at all), and
 * it must stay well above a slow cold start so the animation is never started
 * while the system splash still covers it.
 */
internal const val SPLASH_HANDOFF_GRACE_MS = 1200

/** First API level with the platform splash screen (`Activity.getSplashScreen`). */
internal const val PLATFORM_SPLASH_API = 31
