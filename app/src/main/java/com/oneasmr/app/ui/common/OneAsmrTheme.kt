package com.oneasmr.app.ui.common

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * App-wide Material3 theme. Light/dark follows the system by default.
 *
 * Dynamic color stays opt-in per the settings toggle: when enabled on
 * Android 12+, the wallpaper-derived dynamic scheme is used exactly as
 * before; otherwise the custom OneAsmr schemes (see [OneAsmrDarkColorScheme]
 * / [OneAsmrLightColorScheme]) apply, together with [OneAsmrTypography] and
 * [OneAsmrShapes]. Note that typography and shapes intentionally apply in
 * BOTH branches — dynamic color is a color-only feature in M3, so the
 * redesign's type/shape tokens hold even under wallpaper-derived colors.
 */
@Composable
fun OneAsmrTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> OneAsmrDarkColorScheme
        else -> OneAsmrLightColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = OneAsmrTypography,
        shapes = OneAsmrShapes,
        content = content,
    )
}
