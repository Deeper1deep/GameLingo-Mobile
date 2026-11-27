package com.gamelingo.mobile.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.gamelingo.mobile.core.overlay.NormalizedBox
import com.gamelingo.mobile.core.overlay.OverlayLayout
import com.gamelingo.mobile.core.overlay.PlacementPoint
import com.gamelingo.mobile.core.overlay.PlacementResolver
import com.gamelingo.mobile.core.session.SessionError
import com.gamelingo.mobile.core.session.SessionState
import com.gamelingo.mobile.core.timing.PipelineTiming
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
    private var overlayView: DragFrameLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var sourceBox: NormalizedBox? = null

    private val uiState = MutableStateFlow(OverlayUiState())
    private val settingsState = MutableStateFlow(AppSettings())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Permission can be revoked while the capture session is alive. Never
        // run silently windowless: say so (the home screen shows the error
        // state) and stop.
        if (!Settings.canDrawOverlays(this)) {
            TranslationSessionBus.onSessionError(SessionError.OVERLAY_PERMISSION_LOST)
            stopSelf()
            return START_NOT_STICKY
        }
        // A restarted (or stale) overlay with no running session is a ghost
        // window over the user's game: never show one.
        if (TranslationSessionBus.state.value != SessionState.RUNNING) {
            stopSelf()
            return START_NOT_STICKY
        }
        // START_STICKY: if the system kills the overlay while capture runs,
        // it is recreated (onCreate re-attaches to the live session bus)
        // instead of leaving the session translating into nothing.
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            // Do not hide the failure: capture without the window leaves the
            // user staring at nothing with no explanation.
            TranslationSessionBus.onSessionError(SessionError.OVERLAY_PERMISSION_LOST)
            stopSelf()
            return
        }
        lifecycleOwner.onServiceCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (!addOverlayWindow()) {
            TranslationSessionBus.onSessionError(SessionError.OVERLAY_WINDOW_FAILED)
            stopSelf()
            return
        }
        lifecycleOwner.onServiceStart()
        collectState()
    }

    /** @return false when the window could not be created; never throws. */
    private fun addOverlayWindow(): Boolean {
        val density = resources.displayMetrics.density
        val marginPx = (EDGE_MARGIN_DP * density).toInt()
        val params = WindowManager.LayoutParams(
            // Wrap-content width keeps the bar draggable horizontally and
            // stops the window from claiming the whole screen width.
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // LAYOUT_IN_SCREEN / NO_LIMITS: x/y are absolute screen pixels, so
            // the saved fractions of the screen map to the same spot every time.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Full-screen video and games render into the display cutout;
            // without this the window is laid out as if the cutout bars
            // still existed and the bar lands off-centre or half-hidden.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        layoutParams = params

        val content = ComposeView(this)
        content.setViewTreeLifecycleOwner(lifecycleOwner)
        content.setViewTreeViewModelStoreOwner(lifecycleOwner)
        content.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        content.setContent {
            val state by uiState.collectAsState()
            val settings by settingsState.collectAsState()
            OverlayContent(
                state = state,
                settings = settings,
                onStopSession = { stopSessionFromOverlay() },
            )
        }
        // The window's root owns the drag (see DragFrameLayout). Compose
        // resolves its recomposer from the ROOT view, so the lifecycle owners
        // must be on the root as well as on the content.
        val view = DragFrameLayout(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            addView(
                content,
                android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            windowPosition = { PlacementPoint(params.x, params.y) }
            screenSize = { this@OverlayService.screenSize() }
            isLocked = { settingsState.value.overlayStyle.positionLocked }
            onDragMove = { point ->
                params.x = point.left
                params.y = point.top
                runCatching { windowManager?.updateViewLayout(this, params) }
            }
            onDragEnd = { saveDroppedPosition(this, params) }
            onDoubleTap = { toggleLock() }
        }

        return try {
            windowManager?.addView(view, params)
            overlayView = view
            // The window's real size is only known after layout: re-place then.
            view.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob ->
                if (r - l != or2 - ol || b - t != ob - ot) view.post { reposition() }
            }
            view.post { reposition() }
            true
        } catch (_: Exception) {
            // Permission revoked between check and add, or a dead window
            // manager: the caller reports the failure visibly.
            false
        }
    }

    private fun screenSize(): Pair<Int, Int> = ScreenGeometryProvider.displaySize(this)

    /** The scanned area as a box, read-only: placement never writes the region. */
    private fun regionBox(settings: AppSettings, sw: Int, sh: Int): NormalizedBox? {
        if (!settings.customRegionEnabled) return null
        val r = settings.resolveCustomRegion(sw, sh).region
        return toDisplay(NormalizedBox(r.left, r.top, r.right, r.bottom))
    }

    /** The user's confirmed window alignment for this session, only while it still matches. */
    private fun calibration() =
        if (com.gamelingo.mobile.capture.CaptureMode.appWindowActive(this)) {
            val (dw, dh) = ScreenGeometryProvider.realDisplaySize(this)
            com.gamelingo.mobile.core.capture.AppWindowCalibrationStore.validFor(dw, dh)
        } else null

    /** Frame fractions to display fractions; unchanged outside app-window capture. */
    private fun toDisplay(b: NormalizedBox): NormalizedBox =
        if (com.gamelingo.mobile.capture.CaptureMode.appWindowActive(this)) {
            calibration()?.frameBoxToDisplay(b) ?: b
        } else b

    /**
     * In app-window capture the original subtitle's position on the display is
     * unknown, so follow/below/smart place as fixed there. The saved mode is
     * untouched and applies again in whole-screen capture.
     */
    private fun effectiveForCapture(p: com.gamelingo.mobile.core.overlay.TranslationPlacement) =
        com.gamelingo.mobile.core.capture.AppWindowCapture
            .effectivePlacement(
                p.mode, com.gamelingo.mobile.capture.CaptureMode.appWindowActive(this), calibrated = calibration() != null,
            )
            .let { if (it.degraded) p.copy(mode = it.mode) else p }

    private fun marginPx() = (EDGE_MARGIN_DP * resources.displayMetrics.density).toInt()

    /**
     * Puts the window where the placement setting says: size from the
     * saved width/height, position from the mode (fixed / follow / below /
     * smart). Called on settings changes, new subtitles and size changes.
     */
    private fun reposition() {
        val view = overlayView ?: return
        // Never fight the finger: a drag owns the window position until it ends.
        if (!view.allowsExternalReposition()) return
        val params = layoutParams ?: return
        val settings = settingsState.value
        val (sw, sh) = screenSize()
        val placement = settings.translationPlacement
        params.width = placement.widthFraction?.let { (it * sw).toInt() }
            ?: WindowManager.LayoutParams.WRAP_CONTENT
        params.height = placement.heightFraction?.let { (it * sh).toInt() }
            ?: WindowManager.LayoutParams.WRAP_CONTENT
        val ow = if (params.width > 0) params.width else view.width
        val oh = if (params.height > 0) params.height else view.height
        val point = PlacementResolver.resolve(
            placement = effectiveForCapture(placement),
            screenWidth = sw, screenHeight = sh,
            overlayWidth = ow, overlayHeight = oh,
            source = sourceBox?.let(::toDisplay),
            ocrRegion = regionBox(settings, sw, sh),
            defaultAnchorTop = OverlayLayout.anchorsTop(settings.overlayPosition),
            marginPx = marginPx(),
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = point.left
        params.y = point.top
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    /**
     * Double tap on the window flips the position lock. Only the style flag is
     * written: the OCR region and the placement are never touched.
     */
    private fun toggleLock() {
        val settings = settingsState.value
        val style = settings.overlayStyle
        val updated = style.copy(positionLocked = !style.positionLocked)
        settingsState.value = settings.copy(overlayStyle = updated)
        serviceScope.launch { settingsRepository.setOverlayStyle(updated) }
    }

    /** Persists where a drag ended; only the placement is written, never the OCR region. */
    private fun saveDroppedPosition(view: View, params: WindowManager.LayoutParams) {
        val (sw, sh) = screenSize()
        val settings = settingsState.value
        val placement = settings.translationPlacement
        val base = PlacementResolver.resolve(
            placement = effectiveForCapture(placement), screenWidth = sw, screenHeight = sh,
            overlayWidth = view.width, overlayHeight = view.height,
            source = sourceBox?.let(::toDisplay), ocrRegion = regionBox(settings, sw, sh),
            defaultAnchorTop = OverlayLayout.anchorsTop(settings.overlayPosition),
            marginPx = marginPx(), applyOffsets = false,
        )
        val updated = PlacementResolver.afterDrag(
            placement, params.x, params.y, base, sw, sh, view.width,
        )
        // Apply at once: a subtitle arriving before the store echoes the new
        // value must not snap the window back to the old spot.
        settingsState.value = settings.copy(translationPlacement = updated)
        serviceScope.launch { settingsRepository.setTranslationPlacement(updated) }
    }

    private fun collectState() {
        serviceScope.launch {
            settingsRepository.settings.collect { settings ->
                settingsState.value = settings
                reposition()
            }
        }
        serviceScope.launch {
            TranslationSessionBus.translatedLine.collect { line ->
                if (line == null) {
                    // The source text left the screen (or the session ended):
                    // clear the content. The initial null before any line is
                    // not a clear - the waiting hint stays until a first line.
                    val current = uiState.value
                    if (current.translated != null || current.failure != null) {
                        uiState.value = current.copy(
                            original = null,
                            translated = null,
                            failure = null,
                            cleared = true,
                        )
                    }
                } else {
                    // The permission can be revoked while the session runs;
                    // the system then drops the window silently. Detect it on
                    // the next line and surface the reason instead of letting
                    // the translations vanish without a word.
                    if (!Settings.canDrawOverlays(this@OverlayService)) {
                        TranslationSessionBus.onSessionError(SessionError.OVERLAY_PERMISSION_LOST)
                        stopSelf()
                        return@collect
                    }
                    sourceBox = line.sourceBox
                    reposition()
                    uiState.value = OverlayUiState(
                        original = line.original,
                        translated = line.translated,
                        sessionRunning = uiState.value.sessionRunning,
                        notice = uiState.value.notice,
                        sourceColors = line.sourceColors,
                        glossary = settingsState.value.userGlossaryTerms
                            .associate { it.en.trim().lowercase() to it.ar },
                    )
                    PipelineTiming.endToEnd("overlay_displayed", line.capturedAtMillis)
                }
            }
        }
        serviceScope.launch {
            TranslationSessionBus.translationFailure.collect { failure ->
                uiState.value = uiState.value.copy(
                    failure = failure,
                    cleared = if (failure != null) false else uiState.value.cleared,
                )
            }
        }
        serviceScope.launch {
            TranslationSessionBus.state.collect { sessionState ->
                uiState.value = uiState.value.copy(
                    sessionError = sessionState == SessionState.ERROR,
                    sessionRunning = sessionState == SessionState.RUNNING,
                )
            }
        }
        serviceScope.launch {
            TranslationSessionBus.notice.collect { notice ->
                uiState.value = uiState.value.copy(notice = notice)
            }
        }
    }

    /** The overlay's stop affordance ends the whole session, exactly like the in-app stop button. */
    private fun stopSessionFromOverlay() {
        runCatching {
            startService(
                com.gamelingo.mobile.capture.ScreenCaptureService.stopIntent(this),
            )
        }
    }

    /** Rotation / display-size change: re-place from the saved fractions. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        overlayView?.post { reposition() }
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
