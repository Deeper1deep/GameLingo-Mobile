package com.gamelingo.mobile.overlay

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.view.WindowInsets
import android.view.WindowManager
import com.gamelingo.mobile.core.region.ScreenGeometry

/**
 * The single place that reads the screen. The region selector, the overlay,
 * the position editor and the capture service all describe the display through
 * [ScreenGeometry], so they never mix sizes from different sources.
 */
object ScreenGeometryProvider {

    /** Real display size in pixels (including system bars), in the current rotation. */
    fun displaySize(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    /**
     * The one authoritative full-display size, in the current rotation. The
     * region selector, the capture surface and every saved fraction use THIS,
     * never the app window's current bounds or resources.displayMetrics:
     * those can exclude bars or follow a different window and so disagree
     * with the picture the capture frame mirrors.
     *
     * API 30+: the maximum window metrics (the full display area). Older:
     * the real display metrics. Insets are NOT subtracted here; they are
     * reported separately in [snapshot].
     */
    fun realDisplaySize(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    /** Current window bounds, for diagnostics only (never used for coordinates). */
    fun currentWindowSize(context: Context): Pair<Int, Int>? = runCatching {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else null
    }.getOrNull()

    /** Display rotation constant of the default display. */
    fun rotation(context: Context): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        return runCatching { wm.defaultDisplay.rotation }.getOrDefault(Surface.ROTATION_0)
    }

    /**
     * A snapshot for a capture frame of ([captureWidth] x [captureHeight]).
     * Pass the frame size the pipeline is using; selector callers pass the display size.
     */
    fun snapshot(context: Context, captureWidth: Int, captureHeight: Int): ScreenGeometry {
        val (w, h) = realDisplaySize(context)
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        var l = 0; var t = 0; var r = 0; var b = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val i = wm.currentWindowMetrics.windowInsets
                    .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                l = i.left; t = i.top; r = i.right; b = i.bottom
            }
        }
        @Suppress("DEPRECATION")
        val rotation = runCatching { wm.defaultDisplay.rotation }.getOrDefault(Surface.ROTATION_0)
        return ScreenGeometry(
            displayWidth = w, displayHeight = h, rotation = rotation,
            density = context.resources.displayMetrics.density,
            captureWidth = captureWidth, captureHeight = captureHeight,
            insetLeft = l, insetTop = t, insetRight = r, insetBottom = b,
        )
    }
}
