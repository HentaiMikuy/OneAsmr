package com.oneasmr.app.ui.common

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Wave E motion primitives shared by the library / detail / player screens.
 */

/** Stable shared-element key for a work's cover (grid card / list thumb → detail hero). */
internal fun workCoverSharedKey(workId: String): String = "work-cover-$workId"

/**
 * Applies the cover shared-element transition between the library grid/list
 * and the work detail hero, keyed per work id so recomposition never flickers.
 *
 * `sharedElement` is chosen over `sharedBounds`: both endpoints are fixed,
 * already-laid-out images (the grid's 1:1 cover Box, the detail hero
 * AsyncImage), so there is no resize-to-fit negotiation to solve — the bounds
 * transform morphs position and size smoothly (explicit [tween] spec matching
 * the 220ms NavHost transitions; the default spring is physics-driven and can
 * overshoot/run long, and with the hero's shadow+clip it re-renders per
 * frame), and both ends render with `ContentScale.Crop`, so no
 * `ResizeMode.ScaleToBounds` letterbox compensation is needed. Navigation
 * Compose 2.9's per-destination [AnimatedContentScope] composes cleanly with
 * lazy grids here, so the bounds-transform fallback is unnecessary.
 *
 * Both scopes are nullable so previews and unit tests (which never install a
 * [SharedTransitionScope]) keep working unchanged: with either null the
 * modifier is a no-op.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.sharedWorkCover(
    sharedTransitionScope: SharedTransitionScope?,
    animatedContentScope: AnimatedContentScope?,
    workId: String,
): Modifier = if (sharedTransitionScope != null && animatedContentScope != null) {
    with(sharedTransitionScope) {
        this@sharedWorkCover.sharedElement(
            sharedContentState = rememberSharedContentState(key = workCoverSharedKey(workId)),
            animatedVisibilityScope = animatedContentScope,
            boundsTransform = { _, _ -> tween(220, easing = EaseOut) },
        )
    }
} else {
    this
}

/**
 * Tactile press feedback: remembers the interaction source and animates a
 * spring scale down to [pressedScale] while pressed (medium stiffness — quick
 * but not snappy). Apply the returned scale via `Modifier.graphicsLayer` and
 * pass the interaction source to the composable's `clickable` so the press
 * state is observed.
 */
@Composable
internal fun rememberPressScale(pressedScale: Float): Pair<MutableInteractionSource, Float> {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "pressScale",
    )
    return interactionSource to scale
}
