package com.gamelingo.mobile.overlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.gamelingo.mobile.core.overlay.OverlayDragController
import com.gamelingo.mobile.core.overlay.PlacementPoint

/**
 * Root view of the translation window. It owns drag handling, which Compose
 * content inside cannot: a touch that Compose consumes never reaches a
 * listener on its parent, so a plain OnTouchListener (the earlier design)
 * never saw a single MOVE. Here a tap goes to Compose untouched; once the
 * finger passes the touch slop the parent steals the gesture (Compose gets a
 * CANCEL) and the window follows the finger. Holding still for
 * [STOP_HOLD_MS] stops the session, so a drag that starts with a short hold
 * can never end the session by accident.
 */
class DragFrameLayout(context: Context) : FrameLayout(context) {

    var windowPosition: () -> PlacementPoint = { PlacementPoint(0, 0) }
    var screenSize: () -> Pair<Int, Int> = { 0 to 0 }
    var isLocked: () -> Boolean = { false }
    var stopEnabled: () -> Boolean = { false }
    var onDragMove: (PlacementPoint) -> Unit = {}
    var onDragEnd: () -> Unit = {}
    var onHoldStop: () -> Unit = {}

    private val controller = OverlayDragController(ViewConfiguration.get(context).scaledTouchSlop)
    private val handler = Handler(Looper.getMainLooper())
    private val holdRunnable = Runnable {
        if (!controller.dragging && stopEnabled()) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onHoldStop()
        }
    }
    private var holdScheduled = false

    private fun begin(ev: MotionEvent) {
        val pos = windowPosition()
        controller.onDown(ev.rawX, ev.rawY, pos.left, pos.top)
        if (!holdScheduled) {
            holdScheduled = true
            handler.postDelayed(holdRunnable, STOP_HOLD_MS)
        }
    }

    private fun cancelHold() {
        holdScheduled = false
        handler.removeCallbacks(holdRunnable)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(ev)
            MotionEvent.ACTION_MOVE -> if (controller.exceedsSlop(ev.rawX, ev.rawY)) {
                cancelHold()
                // Locked: the gesture stays with Compose (nothing to drag).
                return !isLocked()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelHold()
                controller.onEnd()
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Nothing inside consumed it: this view takes it so the drag
                // that may follow is delivered here.
                begin(ev)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val (sw, sh) = screenSize()
                val next = controller.onMove(
                    ev.rawX, ev.rawY, width, height, sw, sh, isLocked(),
                )
                if (next != null) {
                    cancelHold()
                    onDragMove(next)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelHold()
                if (controller.onEnd()) onDragEnd()
                return true
            }
        }
        return true
    }

    /** The service may re-place the window only while no drag is in progress. */
    fun allowsExternalReposition(): Boolean = controller.allowsExternalReposition()

    override fun onDetachedFromWindow() {
        cancelHold()
        super.onDetachedFromWindow()
    }

    companion object {
        /** Long enough that dragging never trips it, short enough to be a deliberate stop. */
        const val STOP_HOLD_MS = 1_500L
    }
}
