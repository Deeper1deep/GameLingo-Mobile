package com.gamelingo.mobile.core.capture

/**
 * Whether a window of the reported content size can be aligned at all. The
 * outline is always exactly the reported size; content larger than the display
 * is rejected, never shrunk, so a calibration is only ever made for the true size.
 */
object AlignGeometry {
    sealed interface Check {
        data object Ok : Check
        data object Invalid : Check
        data object LargerThanDisplay : Check
    }

    fun check(contentW: Int, contentH: Int, displayW: Int, displayH: Int): Check = when {
        contentW <= 0 || contentH <= 0 || displayW <= 0 || displayH <= 0 -> Check.Invalid
        contentW > displayW || contentH > displayH -> Check.LargerThanDisplay
        else -> Check.Ok
    }
}
