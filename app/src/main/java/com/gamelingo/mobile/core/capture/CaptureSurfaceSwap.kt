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

/** A resize or surface swap on the single VirtualDisplay failed. The surface was left untouched. */
class CaptureSwapException(message: String, cause: Throwable) : RuntimeException(message, cause)

/**
 * The resize transaction for a changed frame size.
 *
 * Android allows exactly ONE createVirtualDisplay per MediaProjection (a second
 * call throws SecurityException on API 34+, and the token is single-use). So a
 * geometry change can only resize() the existing display and setSurface() to a
 * new reader; there is no recreate path. Guarantees:
 * 1. First build: create the display on the new reader; if that fails the new
 *    reader is closed and the surface stays empty.
 * 2. Later: resize, setSurface to the NEW reader, and only then close the OLD
 *    reader (its surface outlives the swap).
 * 3. If resize or setSurface fails: the NEW reader is closed (no leak), the
 *    display and the OLD reader are left exactly as they were (never close a
 *    surface that may still be attached), and [CaptureSwapException] is thrown.
 *    The caller must invalidate the published geometry so OCR pauses, and may
 *    retry (resize and setSurface are repeatable) or end the session.
 */
object CaptureSurfaceSwap {
    enum class Outcome { CREATED, REUSED }

    fun <R : SurfaceReader, D : SurfaceDisplay<R>> swap(
        surface: CaptureSurface<R, D>,
        width: Int,
        height: Int,
        densityDpi: Int,
        newReader: R,
        createDisplay: (R) -> D,
    ): Outcome {
        val existing = surface.display
        if (existing == null) {
            try {
                surface.display = createDisplay(newReader)
            } catch (error: Exception) {
                runCatching { newReader.close() }
                throw error
            }
            surface.reader = newReader
            return Outcome.CREATED
        }
        try {
            existing.resize(width, height, densityDpi)
            existing.setSurface(newReader)
        } catch (error: Exception) {
            runCatching { newReader.close() }
            throw CaptureSwapException("capture surface swap failed: ${error.javaClass.simpleName}", error)
        }
        val oldReader = surface.reader
        surface.reader = newReader
        runCatching { oldReader?.close() }
        return Outcome.REUSED
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
