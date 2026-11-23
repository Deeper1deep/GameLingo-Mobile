package com.gamelingo.mobile.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.gamelingo.mobile.core.region.NormalizedRegion
import com.gamelingo.mobile.core.region.RegionEditor
import com.gamelingo.mobile.core.region.RegionHandle
import com.gamelingo.mobile.core.region.RegionPreviewBus

/**
 * Full-screen, see-through surface on which the user draws the translation
 * area: a framed rectangle with four corner handles, the outside dimmed.
 * All geometry (hit test, move, resize, limits) lives in [RegionEditor]; this
 * view only draws and forwards touches.
 */
class RegionSelectorView(context: Context) : View(context) {

    var region: NormalizedRegion = RegionEditor.DEFAULT_START
        set(value) {
            field = value
            invalidate()
        }
    var showBorder: Boolean = true
        set(value) {
            field = value
            invalidate()
        }
    var previewBoxes: List<RegionPreviewBus.Box> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** Called on every change of the rectangle (drag in progress included). */
    var onRegionChanged: ((NormalizedRegion) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val accent = Color.parseColor("#9B7BFF")
    private val dim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; style = Paint.Style.STROKE; strokeWidth = 2.5f * density
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; style = Paint.Style.STROKE; strokeWidth = 2.5f * density
    }
    private val okPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50"); style = Paint.Style.STROKE; strokeWidth = 2f * density
    }
    private val noPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EF5350"); style = Paint.Style.STROKE; strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 5f * density), 0f)
    }
    private val okFill = Paint().apply { color = Color.argb(45, 76, 175, 80) }

    private val handleRadius = 11f * density
    private val touchSlop = 30f * density
    private var active = RegionHandle.NONE
    private var lastX = 0f
    private var lastY = 0f

    /** The rectangle in view pixels. */
    private fun pixelRect() = RectF(
        region.left * width, region.top * height, region.right * width, region.bottom * height,
    )

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = pixelRect()
        // Darken everything outside the rectangle.
        canvas.drawRect(0f, 0f, w, r.top, dim)
        canvas.drawRect(0f, r.bottom, w, h, dim)
        canvas.drawRect(0f, r.top, r.left, r.bottom, dim)
        canvas.drawRect(r.right, r.top, w, r.bottom, dim)

        // Live preview: accepted text green, rejected text red dashed.
        for (box in previewBoxes) {
            val b = RectF(box.left * w, box.top * h, box.right * w, box.bottom * h)
            if (box.accepted) {
                canvas.drawRect(b, okFill)
                canvas.drawRect(b, okPaint)
            } else {
                canvas.drawRect(b, noPaint)
            }
        }

        if (showBorder) canvas.drawRect(r, border)
        // Handles are always drawn: without them the area cannot be resized.
        for ((x, y) in listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)) {
            canvas.drawCircle(x, y, handleRadius, handleFill)
            canvas.drawCircle(x, y, handleRadius, handleRing)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width == 0 || height == 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val r = pixelRect()
                // Hit test in pixel space so the touch radius is round.
                active = RegionEditor.hitTest(
                    NormalizedRegion(r.left, r.top, r.right, r.bottom),
                    event.x, event.y, touchSlop,
                )
                lastX = event.x
                lastY = event.y
                return active != RegionHandle.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (active == RegionHandle.NONE) return false
                val updated = when (active) {
                    RegionHandle.MOVE -> RegionEditor.move(
                        region, (event.x - lastX) / width, (event.y - lastY) / height,
                    )
                    else -> RegionEditor.resize(region, active, event.x / width, event.y / height)
                }
                lastX = event.x
                lastY = event.y
                if (updated != region && updated.isValid()) {
                    region = updated
                    onRegionChanged?.invoke(updated)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = RegionHandle.NONE
                return true
            }
        }
        return false
    }
}
