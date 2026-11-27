package com.gamelingo.mobile.core.capture

/**
 * The display facts the capture surface is built from. When the frame size
 * or rotation changes, the VirtualDisplay and ImageReader must be rebuilt:
 * a mirrored frame letterboxed into a stale-geometry surface shifts the OCR
 * band onto black bars and reads nothing.
 *
 * [width] x [height] is the frame (ImageReader) size. [contentWidth] x
 * [contentHeight] is the size of the captured content as reported by the
 * system (MediaProjection.Callback.onCapturedContentResize on API 34+), or 0
 * when the system did not report one (then the content is the real display).
 * [generation] counts geometry changes; frames of an older generation are
 * rejected. It is not part of the rebuild decision.
 */
data class DisplayGeometry(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val displayWidth: Int = width,
    val displayHeight: Int = height,
    val contentWidth: Int = 0,
    val contentHeight: Int = 0,
    val generation: Long = 0L,
) {
    val hasReportedContent: Boolean get() = contentWidth > 0 && contentHeight > 0

    companion object {
        /** @return true when the capture surface must be rebuilt for [latest]. */
        fun needsRebuild(current: DisplayGeometry?, latest: DisplayGeometry): Boolean =
            current == null ||
                current.width != latest.width ||
                current.height != latest.height ||
                current.rotation != latest.rotation

        /** @return true when anything the OCR mapping depends on changed (surface rebuild or not). */
        fun needsUpdate(current: DisplayGeometry?, latest: DisplayGeometry): Boolean =
            needsRebuild(current, latest) ||
                current!!.displayWidth != latest.displayWidth ||
                current.displayHeight != latest.displayHeight ||
                current.contentWidth != latest.contentWidth ||
                current.contentHeight != latest.contentHeight
    }
}
