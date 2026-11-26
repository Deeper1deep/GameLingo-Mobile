package com.gamelingo.mobile.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import com.gamelingo.mobile.core.overlay.NormalizedBox
import com.gamelingo.mobile.core.overlay.PlacementAlign
import com.gamelingo.mobile.core.overlay.TranslationPlacement
import com.gamelingo.mobile.core.region.NormalizedRegion
import com.gamelingo.mobile.core.region.RegionEditor
import com.gamelingo.mobile.core.region.RegionHandle

/**
 * Edit surface for the translation position: a live sample of the subtitle
 * (real colours, text size, opacity, alignment) that the user drags to move
 * and resizes from its four corner handles. The OCR region is drawn dashed
 * as a read-only reference; nothing here can change it.
 */
class PlacementEditorView(context: Context) : View(context) {

    /** The sample box as screen fractions. */
    var box: NormalizedRegion = NormalizedRegion(0.2f, 0.7f, 0.8f, 0.82f)
        set(value) { field = value; invalidate() }

    /** Read-only reference: the area being scanned. */
    var ocrRegion: NormalizedBox? = null
        set(value) { field = value; invalidate() }

    var textColor: Int = Color.WHITE
    var backgroundColor: Int = Color.argb(200, 0, 0, 0)
    var textSizeSp: Float = 18f
    var align: PlacementAlign = PlacementAlign.CENTER
        set(value) { field = value; invalidate() }
    var arabicSample: String = ""
    var latinSample: String = ""

    /** True once the user changed the size (so an untouched auto size stays auto). */
    var sizeTouched = false
        private set

    private val density = resources.displayMetrics.density
    private val accent = Color.parseColor("#9B7BFF")
    private val dim = Paint().apply { color = Color.argb(90, 0, 0, 0) }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; style = Paint.Style.STROKE; strokeWidth = 2.5f * density
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val regionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50"); style = Paint.Style.STROKE; strokeWidth = 2f * density
        pathEffect = DashPathEffect(floatArrayOf(10f * density, 6f * density), 0f)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val handleRadius = 11f * density
    private val touchSlop = 30f * density
    private var active = RegionHandle.NONE
    private var lastX = 0f
    private var lastY = 0f

    private fun px() = RectF(box.left * width, box.top * height, box.right * width, box.bottom * height)

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        ocrRegion?.let {
            canvas.drawRect(it.left * width, it.top * height, it.right * width, it.bottom * height, regionPaint)
        }
        val r = px()
        bgPaint.color = backgroundColor
        canvas.drawRoundRect(r, 12f * density, 12f * density, bgPaint)

        // Live sample, clipped to the box like the real window.
        val pad = 10f * density
        val innerW = (r.width() - 2 * pad).toInt().coerceAtLeast(1)
        val alignment = when (align) {
            PlacementAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
            PlacementAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            PlacementAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }
        canvas.save()
        canvas.clipRect(r)
        canvas.translate(r.left + pad, r.top + pad)
        textPaint.color = textColor
        textPaint.textSize = textSizeSp * resources.displayMetrics.scaledDensity
        textPaint.isFakeBoldText = true
        val first = StaticLayout.Builder.obtain(arabicSample, 0, arabicSample.length, textPaint, innerW)
            .setAlignment(alignment).build()
        first.draw(canvas)
        canvas.translate(0f, first.height + 4f * density)
        textPaint.isFakeBoldText = false
        textPaint.textSize *= 0.75f
        textPaint.color = (textColor and 0x00FFFFFF) or (0xB3 shl 24)
        StaticLayout.Builder.obtain(latinSample, 0, latinSample.length, textPaint, innerW)
            .setAlignment(alignment).build().draw(canvas)
        canvas.restore()

        canvas.drawRoundRect(r, 12f * density, 12f * density, border)
        for ((x, y) in listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)) {
            canvas.drawCircle(x, y, handleRadius, handleFill)
            canvas.drawCircle(x, y, handleRadius, border)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width == 0 || height == 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val r = px()
                active = RegionEditor.hitTest(
                    NormalizedRegion(r.left, r.top, r.right, r.bottom), event.x, event.y, touchSlop,
                )
                lastX = event.x
                lastY = event.y
                return active != RegionHandle.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (active == RegionHandle.NONE) return false
                val updated = if (active == RegionHandle.MOVE) {
                    RegionEditor.move(box, (event.x - lastX) / width, (event.y - lastY) / height)
                } else {
                    RegionEditor.resize(box, active, event.x / width, event.y / height)
                }
                lastX = event.x
                lastY = event.y
                val resized = active != RegionHandle.MOVE
                val ok = !resized || (
                    updated.width >= TranslationPlacement.MIN_WIDTH &&
                        updated.height >= TranslationPlacement.MIN_HEIGHT
                    )
                if (ok && updated != box) {
                    if (resized) sizeTouched = true
                    box = updated
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
