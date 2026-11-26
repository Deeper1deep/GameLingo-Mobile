package com.gamelingo.mobile.core.overlay

import kotlin.math.abs

/**
 * Pure state machine of dragging the translation window: no Android types, so
 * every rule (touch slop, no jump when the drag starts, screen limits,
 * position lock, "do not re-place the window while the finger is down") is
 * unit-tested. The view only forwards raw screen coordinates.
 */
class OverlayDragController(private val slopPx: Int) {

    var dragging: Boolean = false
        private set

    private var downRawX = 0f
    private var downRawY = 0f
    private var originX = 0
    private var originY = 0
    private var down = false

    /** A finger touched the window at ([rawX], [rawY]); the window is at ([windowX], [windowY]). */
    fun onDown(rawX: Float, rawY: Float, windowX: Int, windowY: Int) {
        if (down) return
        down = true
        dragging = false
        downRawX = rawX
        downRawY = rawY
        originX = windowX
        originY = windowY
    }

    /** True once the finger moved farther than the touch slop (a tap never is). */
    fun exceedsSlop(rawX: Float, rawY: Float): Boolean =
        abs(rawX - downRawX) > slopPx || abs(rawY - downRawY) > slopPx

    /**
     * New window position for a finger at ([rawX], [rawY]), or null while it
     * is still a tap, or when the position is [locked]. The drag origin is
     * re-anchored where the slop was crossed so the window does not jump.
     */
    fun onMove(
        rawX: Float, rawY: Float,
        viewWidth: Int, viewHeight: Int,
        screenWidth: Int, screenHeight: Int,
        locked: Boolean,
    ): PlacementPoint? {
        if (!down || locked) return null
        if (!dragging) {
            if (!exceedsSlop(rawX, rawY)) return null
            dragging = true
            downRawX = rawX
            downRawY = rawY
        }
        return PlacementResolver.clamp(
            x = originX + (rawX - downRawX).toInt(),
            y = originY + (rawY - downRawY).toInt(),
            sw = screenWidth, sh = screenHeight, ow = viewWidth, oh = viewHeight,
        )
    }

    /** The finger left. @return whether this touch was a drag (its position must now be saved). */
    fun onEnd(): Boolean {
        val was = dragging
        dragging = false
        down = false
        return was
    }

    /** Settings and subtitle updates may re-place the window only while no drag is in progress. */
    fun allowsExternalReposition(): Boolean = !dragging
}
