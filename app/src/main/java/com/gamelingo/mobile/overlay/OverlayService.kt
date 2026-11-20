package com.gamelingo.mobile.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.gamelingo.mobile.core.overlay.OverlayLayout
import com.gamelingo.mobile.core.session.SessionState
import com.gamelingo.mobile.core.session.TranslationSessionBus
import com.gamelingo.mobile.data.settings.AppSettings
import com.gamelingo.mobile.data.settings.OverlayPosition
import com.gamelingo.mobile.data.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import android.app.Service

/**
 * The floating translation window. Shows the latest translated subtitle line
 * over the game, styled by the user's overlay settings (opacity, text size,
 * colors, anchor position) and draggable vertically. Started and stopped with
 * the capture session; if the overlay permission is missing it quietly does
 * nothing, because capture without the overlay is still useful on the home
 * screen preview.
 */
@AndroidEntryPoint
class OverlayService : Service() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val lifecycleOwner = OverlayLifecycleOwner()

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private val uiState = MutableStateFlow(OverlayUiState())
    private val settingsState = MutableStateFlow(AppSettings())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        lifecycleOwner.onServiceCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        addOverlayWindow()
        lifecycleOwner.onServiceStart()
        collectState()
    }

    private fun addOverlayWindow() {
        val density = resources.displayMetrics.density
        val marginPx = (EDGE_MARGIN_DP * density).toInt()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        layoutParams = params

        val view = ComposeView(this)
        view.setViewTreeLifecycleOwner(lifecycleOwner)
        view.setViewTreeViewModelStoreOwner(lifecycleOwner)
        view.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        view.setContent {
            val state by uiState.collectAsState()
            val settings by settingsState.collectAsState()
            OverlayContent(state = state, settings = settings)
        }
        attachDragHandler(view)

        applyAnchor(settingsState.value.overlayPosition, marginPx)
        windowManager?.addView(view, params)
        overlayView = view
    }

    /** Re-anchors the window when the user changes the position setting. */
    private fun applyAnchor(position: OverlayPosition, marginPx: Int? = null) {
        val params = layoutParams ?: return
        val margin = marginPx
            ?: (EDGE_MARGIN_DP * resources.displayMetrics.density).toInt()
        if (OverlayLayout.anchorsTop(position)) {
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.y = margin
        } else {
            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            params.y = margin
        }
        overlayView?.let { view ->
            runCatching { windowManager?.updateViewLayout(view, params) }
        }
    }

    /** Vertical drag: the window follows the finger, clamped on screen. */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragHandler(view: View) {
        var startOffset = 0
        var startTouchY = 0f
        view.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            val screenHeight = resources.displayMetrics.heightPixels
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startOffset = params.y
                    startTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    // For a bottom-anchored window, dragging up increases y.
                    val delta = if (params.gravity and Gravity.BOTTOM == Gravity.BOTTOM) {
                        startTouchY - event.rawY
                    } else {
                        event.rawY - startTouchY
                    }
                    params.y = OverlayLayout.clampVerticalOffset(
                        offsetY = (startOffset + delta).toInt(),
                        viewHeight = view.height,
                        screenHeight = screenHeight,
                    )
                    runCatching { windowManager?.updateViewLayout(view, params) }
                    true
                }

                else -> false
            }
        }
    }

    private fun collectState() {
        serviceScope.launch {
            settingsRepository.settings.collect { settings ->
                settingsState.value = settings
                applyAnchor(settings.overlayPosition)
            }
        }
        serviceScope.launch {
            TranslationSessionBus.translatedLine.collect { line ->
                if (line != null) {
                    uiState.value = OverlayUiState(
                        original = line.original,
                        translated = line.translated,
                    )
                }
            }
        }
        serviceScope.launch {
            TranslationSessionBus.translationFailure.collect { failure ->
                if (failure != null) {
                    uiState.value = uiState.value.copy(failure = failure)
                }
            }
        }
        serviceScope.launch {
            TranslationSessionBus.state.collect { sessionState ->
                uiState.value = uiState.value.copy(
                    sessionError = sessionState == SessionState.ERROR,
                )
            }
        }
    }

    override fun onDestroy() {
        overlayView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        overlayView = null
        serviceScope.cancel()
        lifecycleOwner.onServiceDestroy()
        super.onDestroy()
    }

    companion object {
        private const val EDGE_MARGIN_DP = 24

        fun startIntent(context: Context): Intent =
            Intent(context, OverlayService::class.java)
    }
}
