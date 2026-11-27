package com.gamelingo.mobile.core.capture

/** The part of a VirtualDisplay the swap needs; a fake in tests. */
interface SurfaceDisplay<R> {
    fun resize(width: Int, height: Int, densityDpi: Int)
    fun setSurface(reader: R)
    fun release()
}

/** The part of an ImageReader the swap needs; a fake in tests. */
interface SurfaceReader {
    fun close()
}

/** The live capture surface: at most ONE display, and the reader feeding it. */
class CaptureSurface<R : SurfaceReader, D : SurfaceDisplay<R>>(
    var display: D? = null,
    var reader: R? = null,
)

/**
 * The resize transaction for a changed frame size. Order guarantees:
 * 1. The live display is resized and pointed at the NEW reader first; the old
 *    reader is closed only AFTER that succeeded (its surface must outlive the swap).
 * 2. If the swap fails, the dead display is released, the old reader is closed,
 *    and a fresh display is created on the new reader. There is never a second
 *    display next to the first.
 * 3. If even creating fails, the new reader is closed (no leak), the surface is
 *    left empty (no half state) and the error is rethrown for the retry budget.
 */
object CaptureSurfaceSwap {
    enum class Outcome { CREATED, REUSED, RECREATED }

    fun <R : SurfaceReader, D : SurfaceDisplay<R>> swap(
        surface: CaptureSurface<R, D>,
        width: Int,
        height: Int,
        densityDpi: Int,
        newReader: R,
        createDisplay: (R) -> D,
    ): Outcome {
        try {
            val existing = surface.display
            var recreated = false
            if (existing != null) {
                val oldReader = surface.reader
                try {
                    existing.resize(width, height, densityDpi)
                    existing.setSurface(newReader)
                    surface.reader = null
                    runCatching { oldReader?.close() }
                    surface.reader = newReader
                    return Outcome.REUSED
                } catch (error: RuntimeException) {
                    runCatching { existing.release() }
                    surface.display = null
                    recreated = true
                }
            }
            runCatching { surface.reader?.close() }
            surface.reader = null
            surface.display = createDisplay(newReader)
            surface.reader = newReader
            return if (recreated) Outcome.RECREATED else Outcome.CREATED
        } catch (error: Exception) {
            runCatching { newReader.close() }
            throw error
        }
    }
}

/** Decides whether the captured content provably is the whole display. */
object CaptureGeometryValidator {
    /** Size agreement needed to call a reported content size "the display": 1% per axis. */
    private const val SIZE_TOLERANCE = 0.01f

    private fun near(a: Int, b: Int): Boolean =
        a > 0 && b > 0 && kotlin.math.abs(a - b).toFloat() / maxOf(a, b) <= SIZE_TOLERANCE

    /**
     * True when the frame mirrors the whole display. With no reported content
     * (older Android, or no callback yet) the frame is the display we built it
     * from. With a reported content size it must equal the display size (or
     * the frame size we scaled it to) on BOTH axes: a single window of the
     * same aspect but another size is not accepted as the full display.
     */
    fun provesFullDisplay(
        displayW: Int, displayH: Int, frameW: Int, frameH: Int,
        contentW: Int, contentH: Int,
    ): Boolean {
        if (displayW <= 0 || displayH <= 0 || frameW <= 0 || frameH <= 0) return false
        if (contentW <= 0 || contentH <= 0) return true
        val matchesDisplay = near(contentW, displayW) && near(contentH, displayH)
        val matchesFrame = near(contentW, frameW) && near(contentH, frameH)
        return matchesDisplay || matchesFrame
    }
}
