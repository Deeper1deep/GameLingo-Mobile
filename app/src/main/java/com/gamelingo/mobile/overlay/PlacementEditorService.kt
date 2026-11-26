package com.gamelingo.mobile.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.gamelingo.mobile.R
import com.gamelingo.mobile.core.overlay.NormalizedBox
import com.gamelingo.mobile.core.overlay.OverlayLayout
import com.gamelingo.mobile.core.overlay.PlacementMode
import com.gamelingo.mobile.core.region.NormalizedRegion
import com.gamelingo.mobile.data.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Position-editing mode for the translation: a full-screen surface over the
 * game with a live sample the user drags and resizes. Save writes ONLY the
 * translation placement (and switches to Fixed position, since a dragged spot
 * is a fixed one); the OCR region is shown for reference and never changed.
 */
@AndroidEntryPoint
class PlacementEditorService : Service() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var windowManager: WindowManager? = null
    private var root: FrameLayout? = null
    private var editor: PlacementEditorView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.region_need_overlay_permission, Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        if (root == null) scope.launch { show() }
        return START_NOT_STICKY
    }

    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    private suspend fun show() {
        val settings = settingsRepository.settings.first()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val (sw, sh) = screenSize()
        val density = resources.displayMetrics.density
        val placement = settings.translationPlacement

        val w = placement.widthFraction ?: DEFAULT_W
        val h = placement.heightFraction ?: DEFAULT_H
        val margin = 24f * density
        val left = ((placement.fixedX ?: 0.5f) - w / 2f).coerceIn(0f, 1f - w)
        val top = (placement.fixedY ?: run {
            if (OverlayLayout.anchorsTop(settings.overlayPosition)) margin / sh else 1f - h - margin / sh
        }).coerceIn(0f, 1f - h)

        val view = PlacementEditorView(this).apply {
            box = NormalizedRegion(left, top, left + w, top + h)
            textColor = settings.overlayTextColor.toInt()
            backgroundColor = OverlayLayout.backgroundWithOpacity(
                settings.overlayBackgroundColor, settings.overlayOpacity,
            ).toInt()
            textSizeSp = settings.overlayTextSizeSp.toFloat()
            align = placement.align
            arabicSample = getString(R.string.overlay_preview_translated)
            latinSample = getString(R.string.overlay_preview_original)
            // Reference only: the scanned area, drawn but never editable here.
            ocrRegion = if (settings.customRegionEnabled) {
                settings.customRegions.forFrame(sw, sh)?.let { NormalizedBox(it.left, it.top, it.right, it.bottom) }
            } else {
                null
            }
        }
        editor = view

        val container = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val hint = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            background = pill(Color.argb(190, 20, 16, 32), 12f * density)
            val pad = (8 * density).toInt()
            setPadding(pad * 2, pad, pad * 2, pad)
            text = getString(R.string.placement_editor_hint)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val pad = (6 * density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(button(R.string.placement_save, "#7C4DFF") { save(settings.translationPlacement) })
            addView(button(R.string.region_cancel, "#3A3350") { stopSelf() })
            addView(button(R.string.placement_reset, "#3A3350") { reset(settings.translationPlacement) })
        }
        container.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(bar)
                addView(hint)
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = (36 * density).toInt() },
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            wm.addView(container, params)
            root = container
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun save(current: com.gamelingo.mobile.core.overlay.TranslationPlacement) {
        val view = editor ?: return
        val b = view.box
        val updated = current.copy(
            mode = PlacementMode.FIXED,
            fixedX = (b.left + b.right) / 2f,
            fixedY = b.top,
            offsetX = 0f,
            offsetY = 0f,
            // An untouched size stays automatic (wrap text), as before.
            widthFraction = if (view.sizeTouched) b.width else current.widthFraction,
            heightFraction = if (view.sizeTouched) b.height else current.heightFraction,
        ).sanitized()
        scope.launch {
            settingsRepository.setTranslationPlacement(updated)
            Toast.makeText(this@PlacementEditorService, R.string.placement_saved, Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun reset(current: com.gamelingo.mobile.core.overlay.TranslationPlacement) {
        scope.launch {
            settingsRepository.setTranslationPlacement(current.positionReset())
            Toast.makeText(this@PlacementEditorService, R.string.placement_reset_done, Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun pill(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun button(textRes: Int, colorHex: String, onClick: () -> Unit): Button {
        val density = resources.displayMetrics.density
        return Button(this).apply {
            setText(textRes)
            setTextColor(Color.WHITE)
            textSize = 13f
            isAllCaps = false
            background = pill(Color.parseColor(colorHex), 14f * density)
            val pad = (12 * density).toInt()
            setPadding(pad, 0, pad, 0)
            minHeight = (40 * density).toInt()
            minimumHeight = (40 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = (4 * density).toInt(); marginEnd = (4 * density).toInt() }
            setOnClickListener { onClick() }
        }
    }

    override fun onDestroy() {
        runCatching { root?.let { windowManager?.removeView(it) } }
        root = null
        editor = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val DEFAULT_W = 0.6f
        private const val DEFAULT_H = 0.12f
        fun startIntent(context: Context): Intent = Intent(context, PlacementEditorService::class.java)
    }
}
