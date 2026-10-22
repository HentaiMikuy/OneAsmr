package com.oneasmr.app.ui.common

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Wave G seek bar: a thin, fully-rounded track with a small thumb that
 * springs larger and gains a soft primary glow while it is being scrubbed.
 *
 * Why a skin instead of a plain [Slider]: the stock M3 slider is 4dp thick
 * with a 20dp thumb that grows into a 40dp halo — far too heavy for the
 * player's immersive, cover-forward bottom cluster and for fullscreen video.
 * This composable keeps the M3 Slider *behavior* (tap-to-seek, drag, keyboard
 * stepping, progress-bar semantics, RTL) and replaces only the two drawing
 * slots:
 *
 * - Track: 3dp at rest → 5dp while scrubbing, with the buffered range drawn
 *   as a muted segment between the played fill and the rest of the track, so
 *   the player page no longer needs its separate 2dp buffered bar.
 * - Thumb: 11dp at rest → 18dp while scrubbing (spring with a slight
 *   overshoot), ringed with the page background color so it separates from
 *   the played fill, plus a radial glow that fades in while scrubbing.
 *
 * The thumb slot is measured at a fixed [ThumbSlot] size — the thumb itself
 * animates from the draw phase inside that box — so growing it never
 * re-measures the layout, never shifts the track, and the 20dp slot keeps the
 * track inset the same as the stock slider it replaces (time labels keep
 * their previous alignment).
 *
 * Colors are injected because the app mixes two very different backdrops:
 * the themed page (amber on the app surface) and fullscreen video (white on
 * black).
 */
@Immutable
data class OneAsmrSeekBarColors(
    val activeTrack: Color,
    val bufferedTrack: Color,
    val inactiveTrack: Color,
    val thumb: Color,
    val thumbRing: Color,
    val glow: Color,
) {
    companion object {
        /** Themed palette for the audio player page. */
        @Composable
        fun player(): OneAsmrSeekBarColors {
            val scheme = MaterialTheme.colorScheme
            return OneAsmrSeekBarColors(
                activeTrack = scheme.primary,
                // Neutral buffer, like a player's "已缓存" band: it must read
                // as "not played yet" rather than as a second amber shade.
                bufferedTrack = scheme.onSurfaceVariant.copy(alpha = 0.42f),
                inactiveTrack = scheme.onSurfaceVariant.copy(alpha = 0.22f),
                thumb = scheme.primary,
                thumbRing = scheme.background,
                glow = scheme.primary,
            )
        }

        /** White-on-black palette for the fullscreen video overlay. */
        fun video(): OneAsmrSeekBarColors = OneAsmrSeekBarColors(
            activeTrack = Color.White,
            bufferedTrack = Color.White.copy(alpha = 0.38f),
            inactiveTrack = Color.White.copy(alpha = 0.28f),
            thumb = Color.White,
            thumbRing = Color.Black,
            glow = Color.White,
        )
    }
}

private val TrackHeightRest = 3.dp
private val TrackHeightActive = 5.dp
private val TrackSlotHeight = 12.dp
private val ThumbDiameterRest = 11.dp
private val ThumbDiameterActive = 18.dp

/** Fixed thumb slot; see the [OneAsmrSeekBar] KDoc for why it is constant. */
private val ThumbSlot = 20.dp
private val ThumbRingWidth = 2.dp

/**
 * A seek bar with [value] in [valueRange]. [bufferedFraction] is already
 * normalized to 0f..1f (0 means "nothing buffered ahead").
 *
 * @param onValueChangeFinished invoked once when a drag or tap ends, with the
 *   same contract as the Material3 slider callback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneAsmrSeekBar(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    bufferedFraction: Float = 0f,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: OneAsmrSeekBarColors = OneAsmrSeekBarColors.player(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interactionSource,
        valueRange = valueRange,
        track = { state ->
            SeekBarTrack(
                state = state,
                bufferedFraction = bufferedFraction,
                colors = colors,
                interactionSource = interactionSource,
            )
        },
        thumb = {
            SeekBarThumb(
                colors = colors,
                interactionSource = interactionSource,
            )
        },
    )
}

/**
 * Track: three stacked rounded segments (inactive → buffered → played). The
 * played fill is drawn last so it always covers the buffer up to the thumb.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBarTrack(
    state: SliderState,
    bufferedFraction: Float,
    colors: OneAsmrSeekBarColors,
    interactionSource: MutableInteractionSource,
) {
    val scrubbing = interactionSource.collectIsScrubbing()
    val density = LocalDensity.current
    val restHeightPx = with(density) { TrackHeightRest.toPx() }
    val activeHeightPx = with(density) { TrackHeightActive.toPx() }
    val thickness by animateFloatAsState(
        targetValue = if (scrubbing) activeHeightPx else restHeightPx,
        animationSpec = tween(durationMillis = 160),
        label = "seekTrackThickness",
    )
    val played = state.coercedValueAsFraction
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(TrackSlotHeight),
    ) {
        val trackHeight = thickness.coerceAtLeast(1f)
        val centerY = size.height / 2f
        val radius = CornerRadius(trackHeight / 2f)
        drawSegment(1f, colors.inactiveTrack, trackHeight, centerY, radius)
        drawSegment(bufferedFraction, colors.bufferedTrack, trackHeight, centerY, radius)
        drawSegment(played, colors.activeTrack, trackHeight, centerY, radius)
    }
}

/** One rounded segment of the track, from x=0 to `fraction` of the width. */
private fun DrawScope.drawSegment(
    fraction: Float,
    color: Color,
    height: Float,
    centerY: Float,
    cornerRadius: CornerRadius,
) {
    val width = size.width * fraction.coerceIn(0f, 1f)
    // Sub-pixel slivers would render as a stray dot rather than as progress.
    if (width <= 0.5f) return
    drawRoundRect(
        color = color,
        topLeft = Offset(0f, centerY - height / 2f),
        size = Size(width, height),
        cornerRadius = cornerRadius,
    )
}

/**
 * Thumb: soft glow + background-colored ring + solid dot. All geometry
 * animates in the draw phase inside the fixed [ThumbSlot] box.
 */
@Composable
private fun SeekBarThumb(
    colors: OneAsmrSeekBarColors,
    interactionSource: MutableInteractionSource,
) {
    val scrubbing = interactionSource.collectIsScrubbing()
    val diameterScale by animateFloatAsState(
        targetValue = if (scrubbing) {
            ThumbDiameterActive.value / ThumbDiameterRest.value
        } else {
            1f
        },
        // Slight overshoot: a tactile "pop" on grab without wobbling.
        animationSpec = spring(
            dampingRatio = 0.62f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "seekThumbDiameter",
    )
    val glowStrength by animateFloatAsState(
        targetValue = if (scrubbing) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "seekThumbGlow",
    )
    val density = LocalDensity.current
    val restRadiusPx = with(density) { ThumbDiameterRest.toPx() } / 2f
    val ringPx = with(density) { ThumbRingWidth.toPx() }
    Canvas(Modifier.size(ThumbSlot)) {
        val radius = restRadiusPx * diameterScale
        val slotRadius = size.minDimension / 2f
        // The halo is intentionally drawn *past* the 20dp thumb slot: the
        // slot exists only to keep the track inset stable, and Compose does
        // not clip drawing to it, so the glow can bloom over the track.
        val glowRadius = slotRadius * 1.6f
        if (glowStrength > 0.01f) {
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to Color.Transparent,
                        0.55f to colors.glow.copy(alpha = 0.42f * glowStrength),
                        1.0f to Color.Transparent,
                    ),
                    center = center,
                    radius = glowRadius,
                ),
                radius = glowRadius,
                center = center,
            )
        }
        // Ring sits under the dot and is one radius wider: the visible
        // difference is the separator stroke.
        drawCircle(
            color = colors.thumbRing.copy(alpha = 0.9f),
            radius = radius + ringPx,
            center = center,
        )
        drawCircle(color = colors.thumb, radius = radius, center = center)
    }
}

/**
 * True while the user is actively dragging OR pressing the slider. M3 emits a
 * press/release pair for tap-to-seek, so a tap also gets the enlarged-thumb
 * acknowledgement instead of a silent value jump.
 */
@Composable
private fun MutableInteractionSource.collectIsScrubbing(): Boolean {
    val dragged by collectIsDraggedAsState()
    val pressed by collectIsPressedAsState()
    return dragged || pressed
}
