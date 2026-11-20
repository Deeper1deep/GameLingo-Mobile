package com.gamelingo.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

private val DarkColors = darkColorScheme(
    primary = GreenLight,
    surfaceTint = GreenLight,
)

private val LightColors = lightColorScheme(
    primary = GreenPrimary,
    surfaceTint = GreenPrimary,
)

/**
 * The app UI is Arabic-first, so the layout direction is pinned to RTL
 * regardless of the device locale.
 */
@Composable
fun GameLingoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                content = content,
            )
        },
    )
}
