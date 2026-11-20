package com.gamelingo.mobile.core.capture

/**
 * The display facts the capture surface is built from. When any of them change
 * (rotation folds into width/height on most devices, but rotation is tracked
 * explicitly for safety), the VirtualDisplay and ImageReader must be rebuilt:
 * a mirrored frame letterboxed into a stale-geometry surface shifts the OCR
 * band onto black bars and reads nothing.
 */
data class DisplayGeometry(
    val width: Int,
    val height: Int,
    val rotation: Int,
) {
    companion object {
        /** @return true when the capture surface must be rebuilt for [latest]. */
        fun needsRebuild(current: DisplayGeometry?, latest: DisplayGeometry): Boolean =
            current == null || current != latest
    }
}
