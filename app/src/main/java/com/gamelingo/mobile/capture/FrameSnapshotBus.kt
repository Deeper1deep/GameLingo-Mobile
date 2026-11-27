package com.gamelingo.mobile.capture

import android.graphics.Bitmap

/**
 * One downscaled copy of the latest captured frame, handed to the region
 * selector in app-window mode so the rectangle is drawn on what OCR really
 * sees (the window content), not on the display. Only produced while the
 * selector asks for it; held in memory, never written to disk.
 */
object FrameSnapshotBus {
    @Volatile var wanted: Boolean = false
    @Volatile private var latest: Bitmap? = null
    private const val MAX_SIDE = 960

    @Synchronized fun publish(frame: Bitmap) {
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(frame.width, frame.height))
        val w = (frame.width * scale).toInt().coerceAtLeast(1)
        val h = (frame.height * scale).toInt().coerceAtLeast(1)
        val copy = Bitmap.createScaledBitmap(frame, w, h, true)
        latest = copy
    }

    @Synchronized fun take(): Bitmap? = latest

    @Synchronized fun clear() {
        wanted = false
        latest = null
    }
}
