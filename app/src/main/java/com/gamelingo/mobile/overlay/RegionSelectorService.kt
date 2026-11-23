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
import com.gamelingo.mobile.core.region.NormalizedRegion
import com.gamelingo.mobile.core.region.RegionEditor
import com.gamelingo.mobile.core.region.RegionPreviewBus
import com.gamelingo.mobile.core.session.SessionState
import com.gamelingo.mobile.core.session.TranslationSessionBus
import com.gamelingo.mobile.data.settings.AppSettings
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
 * Shows the translation-area selector over whatever is on screen (the game).
 * Confirm saves the rectangle for the current orientation and turns the
 * feature on; Cancel closes without saving; "use full screen" clears the
 * saved rectangle of this orientation. Nothing is translated while it is open.
 */
@AndroidEntryPoint
class RegionSelectorService : Service() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var windowManager: WindowManager? = null
    private var root: FrameLayout? = null
    private var selector: RegionSelectorView? = null
    private var hint: TextView? = null
    private var settings = AppSettings()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE) {
            stopSelf()
            return START_NOT_STICKY
        }
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
        settings = settingsRepository.settings.first()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val (sw, sh) = screenSize()
        val initial = settings.customRegions.forFrame(sw, sh) ?: RegionEditor.DEFAULT_START

        val density = resources.displayMetrics.density
        val view = RegionSelectorView(this).apply {
            region = initial
            showBorder = settings.showRegionBorder
        }
        selector = view
        val container = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        val hintView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            background = pill(Color.argb(190, 20, 16, 32), 12f * density)
            val pad = (8 * density).toInt()
            setPadding(pad * 2, pad, pad * 2, pad)
            text = getString(R.string.region_selector_hint)
        }
        hint = hintView

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val pad = (6 * density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(button(R.string.region_save, "#7C4DFF") { confirm() })
            addView(button(R.string.region_cancel, "#3A3350") { stopSelf() })
            addView(button(R.string.region_use_full_screen, "#3A3350") { useFullScreen() })
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(bar)
            addView(hintView)
        }
        container.addView(
            column,
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
        } catch (e: Exception) {
            stopSelf()
            return
        }
        root = container

        if (settings.regionPreview) startPreview(view, sw, sh)
    }

    /** Live preview: publish the rectangle, draw the verdicts the capture service sends back. */
    private fun startPreview(view: RegionSelectorView, sw: Int, sh: Int) {
        fun publish(r: NormalizedRegion) =
            RegionPreviewBus.setCandidate(RegionPreviewBus.Candidate(r, settings.regionSensitivity))
        publish(view.region)
        view.onRegionChanged = { publish(it) }
        if (TranslationSessionBus.state.value != SessionState.RUNNING) {
            hint?.text = getString(R.string.region_preview_needs_session)
        }
        scope.launch {
            RegionPreviewBus.result.collect { result ->
                view.previewBoxes = result?.boxes.orEmpty()
            }
        }
    }

    private fun confirm() {
        val view = selector ?: return
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val (sw, sh) = screenSize()
        val r = view.region
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        val saved = NormalizedRegion.fromWindowRect(
            r.left * w, r.top * h, r.right * w, r.bottom * h,
            loc[0].toFloat(), loc[1].toFloat(), sw, sh,
        )
        if (saved == null) {
            Toast.makeText(this, R.string.region_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            settingsRepository.setCustomRegion(landscape = sw > sh, region = saved)
            settingsRepository.setCustomRegionEnabled(true)
            Toast.makeText(this@RegionSelectorService, R.string.region_saved, Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun useFullScreen() {
        val (sw, sh) = screenSize()
        scope.launch {
            settingsRepository.setCustomRegion(landscape = sw > sh, region = null)
            Toast.makeText(this@RegionSelectorService, R.string.region_reset_done, Toast.LENGTH_SHORT).show()
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
        RegionPreviewBus.setCandidate(null)
        runCatching { root?.let { windowManager?.removeView(it) } }
        root = null
        selector = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CLOSE = "com.gamelingo.mobile.action.CLOSE_REGION_SELECTOR"

        fun startIntent(context: Context): Intent = Intent(context, RegionSelectorService::class.java)
    }
}
