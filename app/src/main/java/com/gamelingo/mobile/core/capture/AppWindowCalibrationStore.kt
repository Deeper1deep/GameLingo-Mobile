package com.gamelingo.mobile.core.capture

/**
 * In-memory only: the window origin the user aligned for THIS capture session.
 * Nothing is saved, and package identity is unknown, so it is never reused for
 * another capture selection: it is cleared whenever capture starts or stops.
 */
object AppWindowCalibrationStore {
    @Volatile var contentSize: Pair<Int, Int>? = null
    @Volatile var calibration: AppWindowCalibration? = null

    /** The calibration, only while it still matches the current content and display sizes. */
    fun validFor(displayW: Int, displayH: Int): AppWindowCalibration? {
        val c = calibration ?: return null
        val s = contentSize ?: return null
        return c.takeIf { it.isValidFor(s.first, s.second, displayW, displayH) && it.fitsDisplay }
    }

    fun clear() {
        contentSize = null
        calibration = null
    }
}
