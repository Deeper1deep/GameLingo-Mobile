package com.gamelingo.mobile.core.overlay

import kotlin.math.abs

/**
 * Pure double-tap recognizer for the translation window: no Android types,
 * so the timing and distance rules are unit-tested. The view forwards raw
 * screen coordinates and the event time.
 *
 * A tap is a press that ends quickly without leaving the touch slop and
 * without being cancelled. A second tap close in time and place is a double
 * tap. A drag, a long hold (the stop gesture) or a cancel never counts, and
 * a recognized double tap clears the state so a third tap starts over.
 */
class DoubleTapDetector(
    private val slopPx: Int,
    private val maxTapMillis: Long = MAX_TAP_MILLIS,
    private val maxGapMillis: Long = MAX_GAP_MILLIS,
) {
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var moved = false

    private var lastTapTime = NONE
    private var lastTapX = 0f
    private var lastTapY = 0f

    fun onDown(timeMillis: Long, rawX: Float, rawY: Float) {
        tracking = true
        moved = false
        downTime = timeMillis
        downX = rawX
        downY = rawY
    }

    fun onMove(rawX: Float, rawY: Float) {
        if (!tracking) return
        if (abs(rawX - downX) > slopPx || abs(rawY - downY) > slopPx) moved = true
    }

    /** The press ended at [timeMillis]; true when this tap completes a double tap. */
    fun onUp(timeMillis: Long): Boolean {
        if (!tracking) return false
        tracking = false
        val isTap = !moved && timeMillis - downTime <= maxTapMillis
        if (!isTap) {
            lastTapTime = NONE
            return false
        }
        val isSecond = lastTapTime != NONE &&
            downTime - lastTapTime <= maxGapMillis &&
            abs(downX - lastTapX) <= slopPx * 2 &&
            abs(downY - lastTapY) <= slopPx * 2
        if (isSecond) {
            lastTapTime = NONE
            return true
        }
        lastTapTime = timeMillis
        lastTapX = downX
        lastTapY = downY
        return false
    }

    /** Gesture cancelled by the system or taken over by another handler. */
    fun onCancel() {
        tracking = false
        lastTapTime = NONE
    }

    companion object {
        const val MAX_TAP_MILLIS = 250L
        const val MAX_GAP_MILLIS = 350L
        private const val NONE = Long.MIN_VALUE
    }
}
