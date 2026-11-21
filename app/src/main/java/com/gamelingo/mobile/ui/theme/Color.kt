package com.gamelingo.mobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * GameLingo color tokens ("Premium Gaming Technology").
 *
 * Screens never hard-code a color: they read these tokens (directly or through
 * MaterialTheme.colorScheme) so the whole identity can be retuned in one file.
 */
object GlColors {
    // Surfaces
    val Background = Color(0xFF090B12)
    val Surface = Color(0xFF111521)
    val SurfaceElevated = Color(0xFF191F2E)
    val Outline = Color(0xFF262D40)
    val OutlineStrong = Color(0xFF3A4360)

    // Brand
    val Primary = Color(0xFF8B5CF6)
    val PrimaryStrong = Color(0xFF6D35E8) // white text on this passes AA
    val Secondary = Color(0xFF22D3EE)
    val Accent = Color(0xFFA78BFA)

    // Text
    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)

    // State
    val Success = Color(0xFF22C55E)
    val Error = Color(0xFFEF4444)
}
