package com.gamelingo.mobile.overlay

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.gamelingo.mobile.core.overlay.DoubleTapDetector
import com.gamelingo.mobile.core.overlay.OverlayDragController
import com.gamelingo.mobile.core.overlay.PlacementPoint

/**
 * Root view of the translation window. It owns drag handling, which Compose
 * content inside cannot: a touch that Compose consumes never reaches a
 * listener on its parent, so a plain OnTouchListener (the earlier design)
 * never saw a single MOVE. Here a tap goes to Compose untouched; once the
 * finger passes the touch slop the parent steals the gesture (Compose gets a
 * CANCEL) and the window follows the finger. Holding still never stops the
 * session (the long-press stop was removed on the user's request); the
 * explicit stop button is the only way to stop from the window. A double tap
 * toggles the position lock.
 */
class DragFrameLayout(context: Context) : FrameLayout(context) {

    var windowPosition: () -> PlacementPoint = { PlacementPoint(0, 0) }
    var screenSize: () -> Pair<Int, Int> = { 0 to 0 }
    var isLocked: () -> Boolean = { false }
    var onDragMove: (PlacementPoint) -> Unit = {}
    var onDragEnd: () -> Unit = {}

    /** Two quick taps: toggles the position lock (the service saves it). */
    var onDoubleTap: () -> Unit = {}

    private val controller = OverlayDragController(ViewConfiguration.get(context).scaledTouchSlop)
    private val doubleTap = DoubleTapDetector(ViewConfiguration.get(context).scaledTouchSlop)
    private fun begin(ev: MotionEvent) {
        val pos = windowPosition()
        controller.onDown(ev.rawX, ev.rawY, pos.left, pos.top)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                doubleTap.onDown(ev.eventTime, ev.rawX, ev.rawY)
                begin(ev)
            }
            MotionEvent.ACTION_MOVE -> if (controller.exceedsSlop(ev.rawX, ev.rawY)) {
                doubleTap.onMove(ev.rawX, ev.rawY)
                // Locked: the gesture stays with Compose (nothing to drag).
                return !isLocked()
            }
            MotionEvent.ACTION_UP -> {
                controller.onEnd()
                finishTap(ev)
            }
            MotionEvent.ACTION_CANCEL -> {
                controller.onEnd()
                doubleTap.onCancel()
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Nothing inside consumed it: this view takes it so the drag
                // that may follow is delivered here.
                doubleTap.onDown(ev.eventTime, ev.rawX, ev.rawY)
                begin(ev)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                doubleTap.onMove(ev.rawX, ev.rawY)
                val (sw, sh) = screenSize()
                val next = controller.onMove(
                    ev.rawX, ev.rawY, width, height, sw, sh, isLocked(),
                )
                if (next != null) {
                        onDragMove(next)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (controller.onEnd()) onDragEnd()
                finishTap(ev)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (controller.onEnd()) onDragEnd()
                doubleTap.onCancel()
                return true
            }
        }
        return true
    }

    /**
     * Both the intercept path (Compose consumed the press) and the touch path
     * (nothing did) end here, so a locked window, which never drags, still
     * receives the double tap that unlocks it. A UP seen by both paths is
     * counted once because the detector is cleared after the first.
     */
    private fun finishTap(ev: MotionEvent) {
        if (doubleTap.onUp(ev.eventTime)) {
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onDoubleTap()
        }
    }

    /** The service may re-place the window only while no drag is in progress. */
    fun allowsExternalReposition(): Boolean = controller.allowsExternalReposition()

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
    }

}
