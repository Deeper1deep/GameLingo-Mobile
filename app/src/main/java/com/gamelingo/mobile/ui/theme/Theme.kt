package com.gamelingo.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

private val GlColorScheme = darkColorScheme(
    primary = GlColors.Primary,
    onPrimary = GlColors.Background,
    primaryContainer = GlColors.PrimaryStrong,
    onPrimaryContainer = GlColors.TextPrimary,
    secondary = GlColors.Secondary,
    onSecondary = GlColors.Background,
    secondaryContainer = GlColors.SurfaceElevated,
    onSecondaryContainer = GlColors.TextPrimary,
    tertiary = GlColors.Accent,
    onTertiary = GlColors.Background,
    background = GlColors.Background,
    onBackground = GlColors.TextPrimary,
    surface = GlColors.Surface,
    onSurface = GlColors.TextPrimary,
    surfaceVariant = GlColors.SurfaceElevated,
    onSurfaceVariant = GlColors.TextSecondary,
    surfaceTint = GlColors.Primary,
    surfaceContainerLowest = GlColors.Background,
    surfaceContainerLow = GlColors.Surface,
    surfaceContainer = GlColors.Surface,
    surfaceContainerHigh = GlColors.SurfaceElevated,
    surfaceContainerHighest = GlColors.SurfaceElevated,
    outline = GlColors.OutlineStrong,
    outlineVariant = GlColors.Outline,
    error = GlColors.Error,
    onError = GlColors.TextPrimary,
    errorContainer = GlColors.Error,
    onErrorContainer = GlColors.TextPrimary,
)

/**
 * The GameLingo identity is dark-only, so the theme ignores the system
 * light/dark setting. The layout direction is pinned to RTL because the UI is
 * Arabic-first regardless of the device locale.
 */
@Composable
fun GameLingoTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = GlColorScheme,
        typography = GlTypography,
        shapes = GlMaterialShapes,
        content = {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                content = content,
            )
        },
    )
}
