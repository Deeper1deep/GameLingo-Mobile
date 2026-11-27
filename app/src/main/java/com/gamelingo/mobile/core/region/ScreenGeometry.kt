package com.gamelingo.mobile.core.region

import com.gamelingo.mobile.core.capture.PixelRect
import kotlin.math.abs

/**
 * One consistent description of the screen and the capture frame, with the
 * explicit conversions between them. Selector rectangles, saved fractions
 * and OCR pixels all go through here, so no coordinate is ever divided by a
 * dimension of a different space.
 *
 * Normalized values are fractions of the DISPLAY. The capture frame mirrors
 * the whole display, so the same fractions apply to it.
 */
data class ScreenGeometry(
    val displayWidth: Int,
    val displayHeight: Int,
    val rotation: Int,
    val density: Float,
    val captureWidth: Int,
    val captureHeight: Int,
    val insetLeft: Int = 0,
    val insetTop: Int = 0,
    val insetRight: Int = 0,
    val insetBottom: Int = 0,
) {
    val isLandscape: Boolean get() = displayWidth > displayHeight

    /**
     * A selector rectangle measured inside its window (view pixels) as
     * display fractions. The window's on-screen origin is added exactly
     * once; insets are NOT added (the window is edge to edge, and its
     * origin already reflects any cutout offset).
     */
    fun selectorToNormalized(
        localLeft: Float, localTop: Float, localRight: Float, localBottom: Float,
        windowX: Float, windowY: Float,
    ): NormalizedRegion? = NormalizedRegion.fromWindowRect(
        localLeft, localTop, localRight, localBottom, windowX, windowY, displayWidth, displayHeight,
    )

    /** The region as a pixel rectangle of the capture frame (what OCR sees). */
    fun toCapturePixels(region: NormalizedRegion): PixelRect =
        region.toPixels(captureWidth, captureHeight)

    /** The same region in display pixels (what the user sees on screen). */
    fun toDisplayPixels(region: NormalizedRegion): PixelRect =
        region.toPixels(displayWidth, displayHeight)

    /**
     * True when the capture frame is not a faithful copy of the display
     * (aspect ratio differs by more than 1%): fractions would then not land
     * where the user drew them, which diagnostics must say.
     */
    fun captureAspectMismatch(): Boolean {
        if (displayHeight == 0 || captureHeight == 0) return true
        val d = displayWidth.toFloat() / displayHeight
        val c = captureWidth.toFloat() / captureHeight
        return abs(d - c) / d > 0.01f
    }

    fun describe(): String =
        "display=${displayWidth}x$displayHeight rot=$rotation dpi=$density " +
            "capture=${captureWidth}x$captureHeight " +
            "insets=[$insetLeft,$insetTop,$insetRight,$insetBottom]"
}

/** Decides whether a captured image still belongs to the geometry the pipeline is using. */
object FrameGeometryGuard {
    /** A frame from a surface of another size (taken just before/after a rotation) must be dropped. */
    fun matches(imageWidth: Int, imageHeight: Int, frameWidth: Int, frameHeight: Int): Boolean =
        imageWidth == frameWidth && imageHeight == frameHeight
}
