package com.oneasmr.app.ui.common

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Custom "dark immersive" design-token color schemes for OneAsmr.
 *
 * These replace the stock [darkColorScheme]/[lightColorScheme] defaults and
 * are used whenever the dynamic-color setting is off or the device runs below
 * Android 12. They are deliberately independent of the Material baseline
 * palette so the cover-forward redesign has a stable, brand-owned foundation.
 *
 * Accent rationale: the warm amber primary (#E5B36B dark / #8A5A13 light)
 * evokes stage lighting against near-black surfaces, matching the app's
 * listening-in-the-dark context while staying distinguishable from the
 * neutral warm-grey secondary/tertiary ramp.
 *
 * Accessibility: primary #E5B36B on background #0F0F13 has a contrast ratio
 * of ≈ 9:1, well above WCAG AA 4.5:1 for normal text; onBackground/onSurface
 * #F2F0EB on #0F0F13/#14141A exceeds 15:1. The light scheme mirrors this
 * with #8A5A13 on #FAF8F5 (≈ 5.6:1).
 */
internal val OneAsmrDarkColorScheme = darkColorScheme(
    primary = Color(0xFFE5B36B),
    onPrimary = Color(0xFF2A1D05),
    primaryContainer = Color(0xFF4D3A18),
    onPrimaryContainer = Color(0xFFF6DCA8),
    secondary = Color(0xFFB9B4AC),
    onSecondary = Color(0xFF23211D),
    secondaryContainer = Color(0xFF3B382F),
    onSecondaryContainer = Color(0xFFDCD7CE),
    tertiary = Color(0xFFA8A29A),
    onTertiary = Color(0xFF26241F),
    tertiaryContainer = Color(0xFF3F3B34),
    onTertiaryContainer = Color(0xFFE3DED4),
    background = Color(0xFF0F0F13),
    onBackground = Color(0xFFF2F0EB),
    surface = Color(0xFF14141A),
    onSurface = Color(0xFFF2F0EB),
    surfaceVariant = Color(0xFF1E1E26),
    onSurfaceVariant = Color(0xFFA8A6A1),
    surfaceContainerLowest = Color(0xFF0B0B0F),
    surfaceContainerLow = Color(0xFF17171E),
    surfaceContainer = Color(0xFF1B1B22),
    surfaceContainerHigh = Color(0xFF22222A),
    surfaceContainerHighest = Color(0xFF292932),
    outline = Color(0xFF3C3C46),
    outlineVariant = Color(0xFF2A2A32),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    inverseSurface = Color(0xFFE6E2DB),
    inverseOnSurface = Color(0xFF2E2C27),
    inversePrimary = Color(0xFF8A5A13),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFFE5B36B), // = primary
)

/** Light counterpart of [OneAsmrDarkColorScheme]; see its KDoc for rationale. */
internal val OneAsmrLightColorScheme = lightColorScheme(
    primary = Color(0xFF8A5A13),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF3DFBB),
    onPrimaryContainer = Color(0xFF3A2703),
    secondary = Color(0xFF6B6153),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEBE3D4),
    onSecondaryContainer = Color(0xFF3E3626),
    tertiary = Color(0xFF6F6A5E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE9E4D8),
    onTertiaryContainer = Color(0xFF39352B),
    background = Color(0xFFFAF8F5),
    onBackground = Color(0xFF1D1B16),
    surface = Color(0xFFFDFBF8),
    onSurface = Color(0xFF1D1B16),
    surfaceVariant = Color(0xFFECE7DE),
    onSurfaceVariant = Color(0xFF6B6659),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F1EB),
    surfaceContainer = Color(0xFFEEEAE2),
    surfaceContainerHigh = Color(0xFFE8E4DA),
    surfaceContainerHighest = Color(0xFFE2DED3),
    outline = Color(0xFFB5AE9E),
    outlineVariant = Color(0xFFD8D1C2),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    inverseSurface = Color(0xFF32302A),
    inverseOnSurface = Color(0xFFF5F1EA),
    inversePrimary = Color(0xFFE5B36B),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFF8A5A13), // = primary
)
