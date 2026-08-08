package com.oneasmr.app.ui.common

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Custom shape scale: uniformly rounded corners, one step rounder than the
 * M3 defaults at each tier, for the softer cover-forward surfaces.
 *
 * Only the five public tiers are set; M3 1.4's internal increased/extra
 * tiers (largeIncreased, extraLargeIncreased, extraExtraLarge) keep their
 * defaults.
 */
internal val OneAsmrShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
