package com.gamelingo.mobile.core.region

import com.gamelingo.mobile.core.capture.PixelRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The explicit mapping between the display space the user draws in and the
 * pixel space of the captured frame OCR reads.
 *
 * A VirtualDisplay that mirrors the display into a surface of a different
 * aspect ratio scales the picture uniformly to fit and centres it (bars on
 * the free axis). So for a display of D = (dw, dh) shown in a frame F = (fw, fh):
 *
 *     s  = min(fw / dw, fh / dh)
 *     ox = (fw - dw * s) / 2
 *     oy = (fh - dh * s) / 2
 *     frameX = displayX * s + ox
 *     frameY = displayY * s + oy
 *
 * When the frame is a faithful copy of the display (same aspect) this is
 * s = fw / dw, ox = oy = 0, i.e. the plain "fraction times frame size" mapping.
 * Normalized regions are fractions of the DISPLAY, so a region in display
 * pixels is (fraction * dw, fraction * dh) before the transform above.
 */
data class DisplayToCaptureTransform(
    val displayWidth: Int,
    val displayHeight: Int,
    val frameWidth: Int,
    val frameHeight: Int,
) {
    val isValid: Boolean
        get() = displayWidth > 0 && displayHeight > 0 && frameWidth > 0 && frameHeight > 0

    val scale: Float
        get() = if (!isValid) 0f else min(frameWidth.toFloat() / displayWidth, frameHeight.toFloat() / displayHeight)

    val offsetX: Float get() = if (!isValid) 0f else (frameWidth - displayWidth * scale) / 2f
    val offsetY: Float get() = if (!isValid) 0f else (frameHeight - displayHeight * scale) / 2f

    /** True when the frame is a faithful, full copy of the display (no bars, no crop). */
    val isFaithful: Boolean
        get() = isValid && abs(offsetX) < 1f && abs(offsetY) < 1f

    /** The part of the frame that actually shows the display (the rest are bars). */
    fun contentRect(): PixelRect? {
        if (!isValid) return null
        val l = offsetX.roundToInt().coerceIn(0, frameWidth - 1)
        val t = offsetY.roundToInt().coerceIn(0, frameHeight - 1)
        val r = (offsetX + displayWidth * scale).roundToInt().coerceIn(l + 1, frameWidth)
        val b = (offsetY + displayHeight * scale).roundToInt().coerceIn(t + 1, frameHeight)
        return PixelRect(l, t, r, b)
    }

    /**
     * The region as a pixel rectangle of the frame, inside the displayed
     * content. Null when the transform is invalid: the caller must treat
     * that as a geometry error, never as permission to read the whole frame.
     */
    fun toCapturePixels(region: NormalizedRegion): PixelRect? {
        val content = contentRect() ?: return null
        val s = scale
        val l = (region.left * displayWidth * s + offsetX).roundToInt().coerceIn(content.left, content.right - 1)
        val t = (region.top * displayHeight * s + offsetY).roundToInt().coerceIn(content.top, content.bottom - 1)
        val r = (region.right * displayWidth * s + offsetX).roundToInt().coerceIn(l + 1, content.right)
        val b = (region.bottom * displayHeight * s + offsetY).roundToInt().coerceIn(t + 1, content.bottom)
        return PixelRect(l, t, r, b)
    }

    /** A frame pixel rectangle as display fractions (the inverse mapping), clamped to 0..1. */
    fun frameRectToDisplayFractions(rect: PixelRect): FloatArray? {
        if (!isValid) return null
        val s = scale
        fun fx(x: Int) = ((x - offsetX) / s / displayWidth).coerceIn(0f, 1f)
        fun fy(y: Int) = ((y - offsetY) / s / displayHeight).coerceIn(0f, 1f)
        return floatArrayOf(fx(rect.left), fy(rect.top), fx(rect.right), fy(rect.bottom))
    }

    fun describe(): String =
        "display=${displayWidth}x$displayHeight frame=${frameWidth}x$frameHeight " +
            "scale=${"%.4f".format(scale)} offset=(${"%.1f".format(offsetX)},${"%.1f".format(offsetY)}) " +
            "faithful=$isFaithful"

    companion object {
        /** Aspect difference above which a captured surface is NOT a copy of the display (e.g. a single window). */
        const val CONTENT_ASPECT_TOLERANCE = 0.05f

        /** True when content of ([contentW] x [contentH]) has the display's aspect ratio. */
        fun sameAspect(displayW: Int, displayH: Int, contentW: Int, contentH: Int): Boolean {
            if (displayW <= 0 || displayH <= 0 || contentW <= 0 || contentH <= 0) return false
            val d = displayW.toFloat() / displayH
            val c = contentW.toFloat() / contentH
            return abs(d - c) / max(d, c) <= CONTENT_ASPECT_TOLERANCE
        }
    }
}
