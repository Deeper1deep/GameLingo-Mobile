package com.gamelingo.mobile.core.capture

import kotlin.math.roundToInt

/** A pixel rectangle inside a captured frame. */
data class PixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Maps a vertical band expressed as fractions of screen height onto frame pixels. */
object RegionMapper {

    fun mapToPixels(
        topFraction: Float,
        bottomFraction: Float,
        frameWidth: Int,
        frameHeight: Int,
    ): PixelRect {
        require(frameWidth > 0 && frameHeight > 0) { "frame must have positive dimensions" }
        val top = (topFraction * frameHeight).roundToInt().coerceIn(0, frameHeight - 1)
        val bottom = (bottomFraction * frameHeight).roundToInt().coerceIn(top + 1, frameHeight)
        return PixelRect(left = 0, top = top, right = frameWidth, bottom = bottom)
    }
}
