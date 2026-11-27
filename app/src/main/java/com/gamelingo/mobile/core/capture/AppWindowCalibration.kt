package com.gamelingo.mobile.core.capture

import com.gamelingo.mobile.core.overlay.NormalizedBox

/**
 * Where the captured app window sits on the display, as set by the USER by
 * aligning an outline of the reported content size onto the window. The
 * platform gives the window's size but not its origin, so this is a user
 * statement, never inferred. It is valid only for the exact content size and
 * display size it was made for (a different size or rotation means the window
 * changed and the user must align again).
 */
data class AppWindowCalibration(
    val originX: Int,
    val originY: Int,
    val contentWidth: Int,
    val contentHeight: Int,
    val displayWidth: Int,
    val displayHeight: Int,
) {
    fun isValidFor(contentW: Int, contentH: Int, displayW: Int, displayH: Int): Boolean =
        contentW == contentWidth && contentH == contentHeight &&
            displayW == displayWidth && displayH == displayHeight &&
            contentW > 0 && contentH > 0 && displayW > 0 && displayH > 0

    /** Whether the aligned window lies inside the display (a sanity check on the user's alignment). */
    val fitsDisplay: Boolean
        get() = originX >= 0 && originY >= 0 &&
            originX + contentWidth <= displayWidth && originY + contentHeight <= displayHeight

    /** A box in fractions of the captured frame, expressed as fractions of the display. */
    fun frameBoxToDisplay(box: NormalizedBox): NormalizedBox = NormalizedBox(
        left = (originX + box.left * contentWidth) / displayWidth,
        top = (originY + box.top * contentHeight) / displayHeight,
        right = (originX + box.right * contentWidth) / displayWidth,
        bottom = (originY + box.bottom * contentHeight) / displayHeight,
    )

    fun encode(): String = "$originX,$originY,$contentWidth,$contentHeight,$displayWidth,$displayHeight"

    companion object {
        fun decode(raw: String?): AppWindowCalibration? {
            val p = raw?.split(',')?.map { it.trim().toIntOrNull() ?: return null } ?: return null
            if (p.size != 6) return null
            return AppWindowCalibration(p[0], p[1], p[2], p[3], p[4], p[5])
        }
    }
}
