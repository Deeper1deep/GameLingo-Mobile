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

    private var controls: FrameLayout? = null

    private fun show(contentW: Int, contentH: Int) {
        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager
        val (dw, dh) = ScreenGeometryProvider.realDisplaySize(this)
        if (com.gamelingo.mobile.core.capture.AlignGeometry.check(contentW, contentH, dw, dh) !=
            com.gamelingo.mobile.core.capture.AlignGeometry.Check.Ok
        ) {
            // Never shrink the outline: a calibration must be for the true reported size.
            com.gamelingo.mobile.core.diagnostics.GeometryDiagnostics.record(
                "app-window: align refused, content=${contentW}x$contentH display=${dw}x$dh",
            )
            Toast.makeText(this, R.string.align_unsupported, Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        // Two windows: the outline is EXACTLY the reported size and carries nothing,
        // the controls live in their own bar at the top of the display, so they stay
        // reachable whatever size the window is.
        val frame = AlignOutline.buildOutline(this)
        val bar = AlignOutline.buildControls(this, onConfirm = { confirm(contentW, contentH, dw, dh) }, onCancel = { stopSelf() })
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val lp = WindowManager.LayoutParams(
            contentW, contentH, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }
        params = lp
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        frame.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (startX + (e.rawX - downX)).toInt().coerceIn(0, dw - contentW)
                    lp.y = (startY + (e.rawY - downY)).toInt().coerceIn(0, dh - contentH)
                    runCatching { manager.updateViewLayout(frame, lp) }
                }
            }
            true
        }
        val barLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (48 * resources.displayMetrics.density).toInt() }
        try {
            manager.addView(frame, lp)
            manager.addView(bar, barLp)
        } catch (e: Exception) { stopSelf(); return }
        outline = frame
        controls = bar
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
        runCatching { controls?.let { wm?.removeView(it) } }
        outline = null
        controls = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_ALIGN = "com.gamelingo.mobile.action.ALIGN_WINDOW"

        fun startIntent(context: Context) = Intent(context, WindowAlignService::class.java)
    }
}

/** The outline (stroke only, empty inside) and the separate hint/Confirm/Cancel bar. Separate so they can be rendered and tested. */
object AlignOutline {
    fun buildOutline(context: Context): FrameLayout {
        val density = context.resources.displayMetrics.density
        return FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT) // the game must stay fully visible inside the outline
                setStroke((3 * density).toInt(), Color.rgb(124, 77, 255))
            }
        }
    }

    fun buildControls(context: Context, onConfirm: () -> Unit, onCancel: () -> Unit): FrameLayout {
        val density = context.resources.displayMetrics.density
        val box = FrameLayout(context).apply { setBackgroundColor(Color.argb(210, 20, 16, 32)) }
        val column = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = (8 * density).toInt()
            setPadding(p, p, p, p)
        }
        column.addView(TextView(context).apply {
            setText(R.string.align_hint)
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
        })
        val row = android.widget.LinearLayout(context).apply { gravity = Gravity.CENTER }
        row.addView(Button(context).apply { setText(R.string.align_confirm); setOnClickListener { onConfirm() } })
        row.addView(Button(context).apply { setText(R.string.region_cancel); setOnClickListener { onCancel() } })
        column.addView(row)
        box.addView(column)
        return box
    }
}
