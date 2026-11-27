package com.gamelingo.mobile.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.gamelingo.mobile.R
import com.gamelingo.mobile.core.capture.AppWindowCalibration
import com.gamelingo.mobile.core.capture.AppWindowCalibrationStore

/**
 * Lets the user say where the captured app window sits: an outline of exactly
 * the reported content size is dragged onto the window and confirmed. The
 * result is a user statement kept for this capture session only. Nothing is
 * inferred from sizes, and placement stays fixed until this is confirmed.
 */
class WindowAlignService : Service() {
    private var wm: WindowManager? = null
    private var outline: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val size = AppWindowCalibrationStore.contentSize
        if (outline != null) return START_NOT_STICKY
        if (!Settings.canDrawOverlays(this) || size == null) {
            Toast.makeText(this, R.string.align_need_session, Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        show(size.first, size.second)
        return START_NOT_STICKY
    }

    private fun show(contentW: Int, contentH: Int) {
        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager
        val (dw, dh) = ScreenGeometryProvider.realDisplaySize(this)
        val (frame, label) = AlignOutline.build(this, onConfirm = { confirm(contentW, contentH, dw, dh) }, onCancel = { stopSelf() })
        val lp = WindowManager.LayoutParams(
            contentW, contentH, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }
        params = lp
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        label.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (startX + (e.rawX - downX)).toInt().coerceIn(0, (dw - contentW).coerceAtLeast(0))
                    lp.y = (startY + (e.rawY - downY)).toInt().coerceIn(0, (dh - contentH).coerceAtLeast(0))
                    runCatching { manager.updateViewLayout(frame, lp) }
                }
            }
            true
        }
        try { manager.addView(frame, lp) } catch (e: Exception) { stopSelf(); return }
        outline = frame
    }

    private fun confirm(contentW: Int, contentH: Int, dw: Int, dh: Int) {
        val lp = params ?: return
        val c = AppWindowCalibration(lp.x, lp.y, contentW, contentH, dw, dh)
        if (!c.fitsDisplay) {
            Toast.makeText(this, R.string.region_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        AppWindowCalibrationStore.calibration = c
        com.gamelingo.mobile.core.diagnostics.GeometryDiagnostics.record(
            "app-window: user aligned window origin=(${c.originX},${c.originY}) content=${contentW}x$contentH display=${dw}x$dh",
        )
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { outline?.let { wm?.removeView(it) } }
        outline = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_ALIGN = "com.gamelingo.mobile.action.ALIGN_WINDOW"

        fun startIntent(context: Context) = Intent(context, WindowAlignService::class.java)
    }
}

/** The outline, hint and Confirm/Cancel row. Separate so it can be rendered and tested. */
object AlignOutline {
    fun build(context: Context, onConfirm: () -> Unit, onCancel: () -> Unit): Pair<FrameLayout, TextView> {
        val density = context.resources.displayMetrics.density
        val frame = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.argb(40, 124, 77, 255))
                setStroke((3 * density).toInt(), Color.rgb(124, 77, 255))
            }
        }
        val label = TextView(context).apply {
            setText(R.string.align_hint)
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(190, 20, 16, 32))
            val p = (8 * density).toInt()
            setPadding(p, p, p, p)
        }
        frame.addView(label, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        val row = android.widget.LinearLayout(context).apply { gravity = Gravity.CENTER }
        row.addView(Button(context).apply { setText(R.string.align_confirm); setOnClickListener { onConfirm() } })
        row.addView(Button(context).apply { setText(R.string.region_cancel); setOnClickListener { onCancel() } })
        frame.addView(row, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        return frame to label
    }
}
