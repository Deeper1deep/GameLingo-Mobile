package com.gamelingo.mobile.core.capture

import com.gamelingo.mobile.core.overlay.PlacementMode

/**
 * Pure rules for capturing ONE app window (Android 14+ "share one app").
 *
 * The platform reports the size of the captured window
 * (MediaProjection.Callback.onCapturedContentResize) but NOT where it sits on
 * the display. So these rules never derive an origin from a matching size:
 * the OCR region is read as fractions of the captured frame itself, and
 * anything that needs a display position says so explicitly.
 * Off by default: whole-screen capture is unchanged until the user opts in.
 */
object AppWindowCapture {

    /** The frame (ImageReader) size for [contentW]x[contentH], keeping its aspect and the long edge of [referenceLongEdge]. */
    fun frameFor(contentW: Int, contentH: Int, referenceLongEdge: Int): Pair<Int, Int>? {
        if (contentW <= 0 || contentH <= 0 || referenceLongEdge <= 0) return null
        val longEdge = maxOf(contentW, contentH)
        if (longEdge <= referenceLongEdge) return contentW to contentH
        val scale = referenceLongEdge.toDouble() / longEdge
        val w = Math.round(contentW * scale).toInt().coerceAtLeast(1)
        val h = Math.round(contentH * scale).toInt().coerceAtLeast(1)
        return w to h
    }

    sealed interface Startup {
        /** No content size yet: OCR must not run (the frame size is unknown). */
        data object Waiting : Startup
        data class Ready(val contentW: Int, val contentH: Int) : Startup
        /** The documented first callback never came within the wait: stop reading, say why. */
        data object TimedOut : Startup
    }

    /** Decides readiness from the first reported size and elapsed time since capture began. */
    fun startup(reported: Pair<Int, Int>?, elapsedMillis: Long, timeoutMillis: Long = STARTUP_TIMEOUT_MILLIS): Startup = when {
        reported != null && reported.first > 0 && reported.second > 0 -> Startup.Ready(reported.first, reported.second)
        elapsedMillis >= timeoutMillis -> Startup.TimedOut
        else -> Startup.Waiting
    }

    const val STARTUP_TIMEOUT_MILLIS = 5_000L

    /**
     * Placement that is actually possible. Follow, below and smart placement
     * need the original subtitle's position on the DISPLAY, which an app-window
     * capture cannot give. They are NOT silently replaced: the result carries
     * [Effective.degraded] = true so the UI can show the notice, and the saved
     * setting is left untouched (returning to whole-screen capture restores it).
     */
    data class Effective(val mode: PlacementMode, val degraded: Boolean)

    fun effectivePlacement(saved: PlacementMode, appWindowCapture: Boolean): Effective =
        if (appWindowCapture && saved != PlacementMode.FIXED) Effective(PlacementMode.FIXED, degraded = true)
        else Effective(saved, degraded = false)
}
