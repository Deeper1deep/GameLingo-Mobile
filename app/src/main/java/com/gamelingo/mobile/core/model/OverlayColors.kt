package com.gamelingo.mobile.core.model

/**
 * Color presets offered for the floating translation window, stored as ARGB longs.
 * Kept pure (no Android imports) so the choices are unit-testable.
 */
object OverlayColors {
    const val DEFAULT_TEXT: Long = 0xFFFFFFFF
    const val DEFAULT_BACKGROUND: Long = 0xAA000000

    val TEXT_PRESETS: List<Long> = listOf(
        0xFFFFFFFF, // white
        0xFFFFF176, // yellow
        0xFF80DEEA, // cyan
        0xFFA5D6A7, // green
        0xFFF48FB1, // pink
        0xFFFFAB91, // orange
    )

    val BACKGROUND_PRESETS: List<Long> = listOf(
        0xAA000000, // translucent black
        0xAA1A237E, // indigo
        0xAA004D40, // teal
        0xAA4A148C, // purple
        0xAA3E2723, // brown
        0xAA212121, // dark grey
    )

    fun isTextPreset(color: Long): Boolean = color in TEXT_PRESETS

    fun isBackgroundPreset(color: Long): Boolean = color in BACKGROUND_PRESETS

    /** Formats an ARGB color as "#AARRGGBB" for display and debugging. */
    fun formatArgbHex(argb: Long): String = "#%08X".format(argb and 0xFFFFFFFF)
}
