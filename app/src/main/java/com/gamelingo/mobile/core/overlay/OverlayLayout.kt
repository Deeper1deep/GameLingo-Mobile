package com.gamelingo.mobile.core.overlay

import com.gamelingo.mobile.data.settings.OverlayPosition

/**
 * Pure layout math for the floating translation window. No Android imports so
 * the drag clamping and color math stay unit-testable.
 */
object OverlayLayout {

    /** Whether the window anchors to the top edge for the given position setting. */
    fun anchorsTop(position: OverlayPosition): Boolean = position == OverlayPosition.TOP

    /**
     * Applies the user's opacity setting to the background color's alpha
     * channel. The color's own alpha is scaled, not replaced, so the preset
     * translucency and the slider compose instead of fighting.
     */
    fun backgroundWithOpacity(backgroundArgb: Long, opacity: Float): Long {
        val clamped = opacity.coerceIn(0f, 1f)
        val baseAlpha = (backgroundArgb ushr 24) and 0xFF
        val alpha = (baseAlpha * clamped).toInt().coerceIn(0, 255)
        return (backgroundArgb and 0x00FFFFFF) or (alpha.toLong() shl 24)
    }

    /**
     * Clamps a horizontal drag offset so the window stays fully on screen.
     * [offsetX] is the distance from the horizontal center. A window as wide
     * as the screen cannot move; a narrower one moves within the free space.
     */
    fun clampHorizontalOffset(offsetX: Int, viewWidth: Int, screenWidth: Int): Int {
        val max = ((screenWidth - viewWidth) / 2).coerceAtLeast(0)
        return offsetX.coerceIn(-max, max)
    }

    /**
     * Clamps a vertical drag offset so the window stays fully on screen.
     * [offsetY] is the distance from the anchored edge (top offset for a
     * top-anchored window, distance above the bottom edge for a
     * bottom-anchored one).
     */
    fun clampVerticalOffset(offsetY: Int, viewHeight: Int, screenHeight: Int): Int {
        val max = (screenHeight - viewHeight).coerceAtLeast(0)
        return offsetY.coerceIn(0, max)
    }
}
