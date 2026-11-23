package com.gamelingo.mobile.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.os.IBinder
import android.view.Display
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.gamelingo.mobile.MainActivity
import com.gamelingo.mobile.R
import com.gamelingo.mobile.core.capture.BlockDynamicsTracker
import com.gamelingo.mobile.core.capture.CaptureHealth
import com.gamelingo.mobile.core.capture.DisplayGeometry
import com.gamelingo.mobile.core.capture.FailureTracker
import com.gamelingo.mobile.core.capture.SubtitleBlockScorer
import com.gamelingo.mobile.core.capture.shifted
import com.gamelingo.mobile.core.capture.FrameChangeDetector
import com.gamelingo.mobile.core.capture.RegionMapper
import com.gamelingo.mobile.core.capture.isDeadProjectionError
import com.gamelingo.mobile.core.session.ResumeRequest
import com.gamelingo.mobile.core.session.SessionError
import com.gamelingo.mobile.core.session.TranslationSessionBus
import com.gamelingo.mobile.core.text.Deduplicator
import com.gamelingo.mobile.core.timing.PipelineTiming
import com.gamelingo.mobile.core.text.TextNormalizer
import com.gamelingo.mobile.core.text.TextStabilityTracker
import com.gamelingo.mobile.data.settings.AppSettings
import com.gamelingo.mobile.data.settings.SettingsRepository
import com.gamelingo.mobile.ocr.TextRecognizerEngine
import com.gamelingo.mobile.overlay.OverlayService
import com.google.mlkit.vision.common.InputImage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Foreground service that owns the MediaProjection, reads frames from a
 * VirtualDisplay, runs on-device OCR and publishes accepted lines to
 * [TranslationSessionBus]. Runs entirely on-device; no frame ever leaves it.
 *
 * One VirtualDisplay per MediaProjection: the capture surface tracks the
 * display through [VirtualDisplay.resize] and [VirtualDisplay.setSurface]
 * on a fresh ImageReader, never by stacking a second VirtualDisplay on the
 * same projection. Rotation or size changes (opening a fullscreen game or
 * video after starting the session) are picked up within one capture tick by
 * the loop's geometry check, or immediately via the DisplayListener.
 * Repeated frame failures surface as a session error instead of spinning
 * silently, and every pipeline stage stamps [CaptureHealth] so a starved
 * session is visible instead of looking "running".
 */
@AndroidEntryPoint
class ScreenCaptureService : Service() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var recognizerEngine: TextRecognizerEngine

    // A stray async exception must never reach the default uncaught handler:
    // that kills the whole app. The capture loop handles its own failures and
    // reports persistent ones to the user; anything else is logged (never
    // silently swallowed) and dropped.
    private val exceptionHandler = CoroutineExceptionHandler { _, error ->
        Log.e(TAG, "unhandled failure in capture service scope", error)
        CaptureHealth.onError("service scope: ${error.javaClass.simpleName}: ${error.message}")
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)

    /** True once teardown has begun; stopCapture runs its body only once. */
    private val stopping = java.util.concurrent.atomic.AtomicBoolean(false)

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureJob: Job? = null
    private var settingsJob: Job? = null

    /** Guards VirtualDisplay/ImageReader rebuilds against in-flight frame reads. */
    private val displayMutex = Mutex()
    private var currentGeometry: DisplayGeometry? = null

    /**
     * Latest settings, kept hot by a collector so the capture loop never hits
     * DataStore on every tick. Null until the first emission arrives.
     */
    @Volatile private var cachedSettings: AppSettings? = null

    private val frameChangeDetector = FrameChangeDetector()
    private val stabilityTracker = TextStabilityTracker()
    private val deduplicator = Deduplicator()

    /**
     * Set by the heartbeat when a translation was cleared (its source text is
     * gone): the capture loop then forgets its duplicate/stability memory so
     * the same subtitle appearing again is translated again. Consumed on the
     * capture loop's own thread, which owns those trackers.
     */
    private val resetLineGates = java.util.concurrent.atomic.AtomicBoolean(false)
    private var heartbeatJob: kotlinx.coroutines.Job? = null
    private val failureTracker = FailureTracker()

    /** Separate, larger budget for display rebuilds (full-screen/rotation). */
    private val rebuildFailureTracker = FailureTracker(maxConsecutiveFailures = REBUILD_MAX_FAILURES)
    private val blockDynamicsTracker = BlockDynamicsTracker()

    /** Identity of the translation area the last frame used; null before the first frame. */
    @Volatile
    private var appliedRegionKey: String? = null

    /** Watchdog: last time a stall was reported, so it logs once per stall. */
    private var lastStallReportAt = 0L

    /** Reacts to display changes (rotation, size) faster than the next tick. */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val job = captureJob
            if (job != null && job.isActive) {
                serviceScope.launch {
                    runCatching { rebuildDisplayIfNeeded() }
                        .onFailure { Log.w(TAG, "display-change rebuild failed", it) }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_STOP -> stopCapture(CaptureHealth.StopReasons.USER_BUTTON)
            else -> {
                // A stray or empty intent (system redelivery, a confused
                // caller) must never end a running session. Only stop the
                // service when nothing is capturing anyway.
                Log.w(TAG, "unexpected intent action=${intent?.action}; ignoring")
                if (mediaProjection == null) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        if (mediaProjection != null) return // already running
        // A previous teardown nulls every field synchronously before its
        // guarded releases, so reaching this point means it is safe to run
        // again; clear the flag so the next stop works.
        stopping.set(false)
        ResumeRequest.onSessionStarting()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }
        if (resultData == null) {
            Log.e(TAG, "startCapture without consent data")
            TranslationSessionBus.onSessionError(SessionError.MISSING_CONSENT_DATA)
            stopCapture(CaptureHealth.StopReasons.MISSING_CONSENT_DATA)
            return
        }

        // Android 14+ order is strict: the service must already be foreground
        // with the MEDIA_PROJECTION type BEFORE getMediaProjection, otherwise
        // the system throws SecurityException. startForeground must also
        // follow startForegroundService quickly, so it runs first here.
        runCatching { startForegroundWithNotification() }
            .onFailure {
                Log.e(TAG, "startForeground failed", it)
                TranslationSessionBus.onSessionError(SessionError.FOREGROUND_START_FAILED)
                stopCapture(CaptureHealth.StopReasons.FOREGROUND_START_FAILED)
                return
            }

        // getMediaProjection throws SecurityException on a reused or revoked
        // consent token; that is a session error to show, not a crash.
        val projectionManager =
            getSystemService(MediaProjectionManager::class.java)
        val projection = try {
            projectionManager.getMediaProjection(resultCode, resultData)
        } catch (error: SecurityException) {
            Log.e(TAG, "getMediaProjection rejected the consent token", error)
            null
        } catch (error: IllegalStateException) {
            Log.e(TAG, "getMediaProjection failed", error)
            null
        }
        if (projection == null) {
            TranslationSessionBus.onSessionError(SessionError.PROJECTION_REJECTED)
            stopCapture(CaptureHealth.StopReasons.PROJECTION_REJECTED)
            return
        }

        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    // The system ended the projection: token expired or
                    // consent revoked. That is a genuine expiry, so say why
                    // the session ended - but never when WE stopped it (the
                    // user's own Stop button stops the projection too).
                    if (!stopping.get()) {
                        TranslationSessionBus.onSessionError(SessionError.PROJECTION_ENDED)
                        // Offer a one-tap way back: the session died under
                        // the user, so surface a resume notification.
                        ResumeRequest.onUnexpectedInterruption()
                        postResumeNotification()
                    }
                    stopCapture(CaptureHealth.StopReasons.PROJECTION_ENDED)
                }
            },
            null,
        )
        mediaProjection = projection

        getSystemService(DisplayManager::class.java)
            ?.registerDisplayListener(displayListener, null)

        // Keep settings hot for the capture loop: one collector instead of a
        // DataStore read on every tick (~600 ms).
        settingsJob?.cancel()
        settingsJob = serviceScope.launch {
            try {
                settingsRepository.settings.collect { cachedSettings = it }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "settings collection failed", error)
                CaptureHealth.onError("settings collection: ${error.javaClass.simpleName}")
                TranslationSessionBus.onSessionError(SessionError.SETTINGS_UNAVAILABLE)
                stopCapture(CaptureHealth.StopReasons.SETTINGS_UNAVAILABLE)
            }
        }

        appliedRegionKey = null
        TranslationSessionBus.onSessionStarted()
        CaptureHealth.onSessionStarted()
        lastStallReportAt = 0L
        startService(OverlayService.startIntent(this))
        captureJob = serviceScope.launch { captureLoop() }
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch { presenceHeartbeat() }
    }

    /**
     * Cheap heartbeat (no OCR, no bitmap): a static screen produces no frames,
     * so after the subtitle vanishes nothing else would ever confirm the
     * absence. Four checks a second of a few comparisons is negligible.
     */
    private suspend fun presenceHeartbeat() {
        while (currentCoroutineContext().isActive) {
            delay(PRESENCE_HEARTBEAT_MS)
            if (TranslationSessionBus.onHeartbeat(System.currentTimeMillis())) {
                resetLineGates.set(true)
            }
        }
    }

    /** The display facts the capture surface must match right now. */
    private fun latestGeometry(): DisplayGeometry {
        val metrics = resources.displayMetrics
        val displayManager = getSystemService(DisplayManager::class.java)
        val rotation = displayManager
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.rotation
            ?: Surface.ROTATION_0
        return DisplayGeometry(
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            rotation = rotation,
        )
    }

    /**
     * Re-fits the capture surface when the display geometry changed since it
     * was created. The first call creates the surface; later changes resize
     * the SAME VirtualDisplay (one per MediaProjection, as required) and swap
     * in a new ImageReader via setSurface.
     */
    private suspend fun rebuildDisplayIfNeeded() {
        val projection = mediaProjection ?: return
        val latest = latestGeometry()
        displayMutex.withLock {
            if (!DisplayGeometry.needsRebuild(currentGeometry, latest)) return
            // Transient 0x0 metrics during a configuration change are real on
            // some devices. Never build a fake 1x1 surface to hide that: skip
            // the tick and retry once the metrics settle.
            if (latest.width <= 0 || latest.height <= 0) {
                Log.w(TAG, "display metrics not settled (${latest.width}x${latest.height}); skipping rebuild this tick")
                return
            }
            try {
                rebuildLocked(projection, latest)
                rebuildFailureTracker.onSuccess()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A full-screen or rotation switch can make the projection
                // refuse a new capture surface for a moment. That transition
                // must not eat the 5-failure session budget: rebuilds get
                // their own, much larger budget (about 12 s of retries at the
                // default capture interval) before the session is declared
                // broken.
                if (isDeadProjectionError(error)) {
                    // The system already ended this projection: every retry
                    // will throw the same SecurityException, and only fresh
                    // user consent can replace it. Treat it exactly like a
                    // projection-ended callback (which usually follows).
                    Log.e(TAG, "projection is dead (non-current); ending session", error)
                    CaptureHealth.onError("display rebuild: ${error.javaClass.simpleName}: ${error.message}")
                    TranslationSessionBus.onSessionError(SessionError.PROJECTION_ENDED)
                    ResumeRequest.onUnexpectedInterruption()
                    postResumeNotification()
                    stopCapture(CaptureHealth.StopReasons.PROJECTION_ENDED)
                    return
                }
                Log.w(TAG, "display rebuild failed; retrying next tick", error)
                CaptureHealth.onError("display rebuild: ${error.javaClass.simpleName}: ${error.message}")
                if (rebuildFailureTracker.onFailure()) {
                    Log.e(TAG, "display rebuild keeps failing; stopping the session")
                    TranslationSessionBus.onSessionError(SessionError.CAPTURE_PERSISTENT_FAILURE)
                    stopCapture(CaptureHealth.StopReasons.CAPTURE_PERSISTENT_FAILURE)
                }
            }
        }
    }

    /**
     * Builds or re-fits the capture surface for [latest]. Exactly one
     * VirtualDisplay exists per MediaProjection: a geometry change resizes it
     * and points it at a new ImageReader surface; only the first call (or a
     * failed resize) falls back to creating it.
     */
    private fun rebuildLocked(projection: MediaProjection, latest: DisplayGeometry) {
        val width = latest.width
        val height = latest.height
        val densityDpi = resources.displayMetrics.densityDpi
        val reader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2,
        )
        try {
            val existing = virtualDisplay
            var reused = false
            if (existing != null) {
                // Reuse the live display: resize first so its output matches
                // the new geometry, then swap the surface atomically, then
                // retire the old reader (its surface must outlive the swap).
                val oldReader = imageReader
                try {
                    existing.resize(width, height, densityDpi)
                    existing.setSurface(reader.surface)
                    imageReader = null
                    runCatching { oldReader?.close() }
                    reused = true
                } catch (error: RuntimeException) {
                    // The display could not be resized: drop it and create a
                    // fresh one below instead of retrying a dead display.
                    Log.w(TAG, "virtual display resize failed, recreating", error)
                    runCatching { existing.release() }
                    virtualDisplay = null
                }
            }
            if (!reused) {
                runCatching { imageReader?.close() }
                imageReader = null
                virtualDisplay = projection.createVirtualDisplay(
                    "gamelingo-capture",
                    width,
                    height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    null,
                )
            }
        } catch (error: Exception) {
            // Never leak the new reader when the display could not take it.
            runCatching { reader.close() }
            throw error
        }
        imageReader = reader
        currentGeometry = latest
        CaptureHealth.onDisplayRebuilt()
        // The frame stream restarts: stale digests, half-stable lines and
        // block-position history from the old geometry must not gate the
        // new one.
        frameChangeDetector.reset()
        stabilityTracker.reset()
        deduplicator.reset()
        blockDynamicsTracker.reset()
    }

    private suspend fun captureLoop() {
        while (serviceScope.isActive) {
            val tickStart = SystemClock.elapsedRealtime()
            // Settings come from the hot cache; only the very first ticks
            // (before the collector's first emission) fall back to a direct
            // read. A failing DataStore surfaces as a session error through
            // the collector, not as a silently dead loop.
            val settings = cachedSettings ?: try {
                settingsRepository.settings.first().also { cachedSettings = it }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "settings read failed inside the capture loop", error)
                TranslationSessionBus.onSessionError(SessionError.SETTINGS_UNAVAILABLE)
                stopCapture(CaptureHealth.StopReasons.SETTINGS_UNAVAILABLE)
                break
            }
            try {
                rebuildDisplayIfNeeded()
                val geometry = currentGeometry
                if (geometry != null) {
                    displayMutex.withLock {
                        val image = imageReader?.acquireLatestImage()
                        if (image != null) {
                            CaptureHealth.onFrameAcquired()
                            image.use { processFrame(it, geometry, settings) }
                        }
                    }
                }
                failureTracker.onSuccess()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A single bad frame or OCR failure must not kill the session,
                // but a persistent failure is a broken session: say so and stop.
                Log.w(TAG, "frame processing failed", error)
                CaptureHealth.onError("frame processing: ${error.javaClass.simpleName}: ${error.message}")
                if (failureTracker.onFailure()) {
                    Log.e(TAG, "frame processing keeps failing; stopping the session")
                    TranslationSessionBus.onSessionError(SessionError.CAPTURE_PERSISTENT_FAILURE)
                    stopCapture(CaptureHealth.StopReasons.CAPTURE_PERSISTENT_FAILURE)
                    break
                }
            }
            reportStallIfNeeded()
            // Time-aware pacing: the interval covers the whole tick (frame
            // read + OCR + publish), so processing time is subtracted instead
            // of stacked on top. A floor keeps the loop from spinning when
            // OCR takes longer than the interval.
            val elapsed = SystemClock.elapsedRealtime() - tickStart
            val waitMs = (settings.captureIntervalMs - elapsed)
                .coerceAtLeast(MIN_TICK_DELAY_MS)
            delay(waitMs)
        }
    }

    /**
     * Watchdog: while a session is running, frames must keep arriving. A
     * stalled ImageReader (surface dead after a failed resize, projection
     * half-stopped) otherwise looks like "running, just quiet" forever.
     * Reports once per stall through CaptureHealth and the log; the
     * diagnostics page turns it into user-visible state.
     */
    private fun reportStallIfNeeded() {
        if (mediaProjection == null || currentGeometry == null) return
        val snapshot = CaptureHealth.snapshot()
        val now = System.currentTimeMillis()
        val lastFrame = snapshot.lastFrameAcquiredAt
        if (lastFrame <= 0L) {
            // No frame has EVER arrived: a starved session, the worst stall.
            val startedAt = TranslationSessionBus.startedAtMillis.value ?: return
            val waited = now - startedAt
            if (waited < NO_FIRST_FRAME_MS) return
            if (now - lastStallReportAt < NO_FIRST_FRAME_MS) return
            lastStallReportAt = now
            Log.w(TAG, "no frame since session start (${waited}ms)")
            CaptureHealth.onError("capture stalled: no frame since the session started (${waited}ms)")
            return
        }
        // A VirtualDisplay produces no frames while the screen is static (a
        // paused game, a menu), so a gap after earlier frames is normal and
        // not an error. It is only logged, once per gap.
        val stalledFor = now - lastFrame
        if (stalledFor < STALL_REPORT_MS) return
        if (now - lastStallReportAt < STALL_REPORT_MS) return
        lastStallReportAt = now
        Log.d(TAG, "no new frames for ${stalledFor}ms (static screen or idle display)")
    }

    private suspend fun processFrame(
        image: Image,
        geometry: DisplayGeometry,
        settings: AppSettings,
    ) {
        val fullBitmap = PipelineTiming.timed("frame_decode") {
            image.toBitmap(geometry.width, geometry.height)
        }
        // Every bitmap this frame creates is released exactly once, whatever
        // happens below: the finally owns recycling, never the success path.
        var ocrBitmap: Bitmap? = null
        try {
            if (resetLineGates.compareAndSet(true, false)) {
                deduplicator.reset()
                stabilityTracker.reset()
            }
            // Custom translation area: the user's own rectangle for the
            // current orientation. Null = the classic scan band / full frame.
            val customRegion = if (settings.customRegionEnabled) {
                settings.customRegions.forFrame(geometry.width, geometry.height)
            } else {
                null
            }
            var regionChanged = false
            val regionKey = customRegion?.let {
                "${geometry.width}x${geometry.height}:${it.encode()}:${settings.regionSensitivity}"
            } ?: "full"
            if (regionKey != appliedRegionKey) {
                val firstFrame = appliedRegionKey == null
                regionChanged = !firstFrame
                appliedRegionKey = regionKey
                if (!firstFrame) {
                    // The area changed mid-session: drop what belonged to the
                    // old one (subtitle, in-flight translations, trackers) and
                    // carry on with the new one. No restart, no crash.
                    TranslationSessionBus.onRegionChanged()
                    deduplicator.reset()
                    stabilityTracker.reset()
                    blockDynamicsTracker.reset()
                }
            }
            val regionPx = customRegion?.toPixels(geometry.width, geometry.height)

            // Battery saver may skip OCR on an unchanged frame, but never the
            // pass that could notice the tracked text is gone: a static frame
            // is not proof the subtitle is still there.
            val shouldRunOcr = !settings.batterySaverEnabled || regionChanged ||
                PipelineTiming.timed("change_detect") {
                    frameChangeDetector.hasChanged(fullBitmap.toDownsampledBytes())
                } ||
                TranslationSessionBus.needsOcrVerification(System.currentTimeMillis())
            if (!shouldRunOcr) {
                // Battery saver skipped this frame. Stamp it: a session whose
                // OCR keeps being skipped must look starved, not healthy.
                CaptureHealth.onOcrSkipped()
                deduplicator.touch()
                if (TranslationSessionBus.onFrameUnchanged(System.currentTimeMillis())) {
                    deduplicator.reset()
                    stabilityTracker.reset()
                }
                return
            }

            var cropLeft = 0
            var cropTop = 0
            val workingBitmap = if (regionPx != null) {
                // OCR only the area (plus a small margin so a line crossing the
                // border is read whole and judged by the overlap rule, not cut).
                val margin = maxOf(REGION_MARGIN_MIN_PX, (regionPx.height * REGION_MARGIN_FRACTION).toInt())
                val l = (regionPx.left - margin).coerceAtLeast(0)
                val t = (regionPx.top - margin).coerceAtLeast(0)
                val r = (regionPx.right + margin).coerceAtMost(geometry.width)
                val b = (regionPx.bottom + margin).coerceAtMost(geometry.height)
                cropLeft = l
                cropTop = t
                Bitmap.createBitmap(fullBitmap, l, t, r - l, b - t).also { ocrBitmap = it }
            } else if (settings.restrictToTextRegion) {
                val region = settings.effectiveRegion
                val rect = RegionMapper.mapToPixels(
                    topFraction = region.top,
                    bottomFraction = region.bottom,
                    frameWidth = geometry.width,
                    frameHeight = geometry.height,
                )
                cropTop = rect.top
                Bitmap.createBitmap(fullBitmap, rect.left, rect.top, rect.width, rect.height)
                    .also { ocrBitmap = it }
            } else {
                fullBitmap
            }

            val ocrStartNanos = System.nanoTime()
            var shiftedBlocks: List<com.gamelingo.mobile.core.capture.RecognizedBlock> = emptyList()
            val rawText = if (settings.smartTextDetection || regionPx != null) {
                // Block mode: read positioned blocks and shift them into
                // full-frame coordinates.
                val blocks = recognizerEngine.recognizeBlocks(InputImage.fromBitmap(workingBitmap, 0))
                val shifted = blocks.map { block -> block.shifted(cropLeft, cropTop) }
                if (regionPx != null) {
                    // Rejected text is dropped HERE: it never reaches the
                    // scorer, the presence tracking or any translation request.
                    val accepted = com.gamelingo.mobile.core.region.RegionFilter.filter(
                        shifted, regionPx, settings.regionSensitivity,
                    )
                    shiftedBlocks = accepted
                    blockDynamicsTracker.offer(accepted)
                    // The user chose this area: everything accepted inside it
                    // is subtitle text, in its original line order.
                    accepted.joinToString(" ") { it.text }
                } else {
                    shiftedBlocks = shifted
                    val subtitle = SubtitleBlockScorer.selectSubtitle(
                        blocks = shifted,
                        frameWidth = geometry.width,
                        frameHeight = geometry.height,
                        dynamics = blockDynamicsTracker,
                    )
                    blockDynamicsTracker.offer(shifted)
                    subtitle.orEmpty()
                }
            } else {
                recognizerEngine.recognize(InputImage.fromBitmap(workingBitmap, 0))
            }
            CaptureHealth.onOcrRun()

            PipelineTiming.mark(
                "ocr",
                (System.nanoTime() - ocrStartNanos) / 1_000_000L,
                "smart=${settings.smartTextDetection}",
            )
            val text = TextNormalizer.normalize(rawText)
            // Presence first, before any early return: an EMPTY read is the
            // signal that the subtitle left the screen, and the dedupe and
            // stability gates below must not hide it from the lifecycle.
            if (TranslationSessionBus.onTextObserved(text, System.currentTimeMillis())) {
                deduplicator.reset()
                stabilityTracker.reset()
            }
            if (text.isEmpty()) return

            if (settings.ignoreUnstableText && !stabilityTracker.offer(text)) return
            if (settings.dedupEnabled && !deduplicator.shouldDeliver(text)) return

            // Colours only for a line that is really being published (new
            // text), and only when the user asked: the pixel pass is cheap
            // but not free, and the frame is recycled in the finally below.
            val sourceColors = if (settings.preserveSourceColors) {
                detectSourceColors(fullBitmap, shiftedBlocks, text)
            } else {
                emptyList()
            }
            TranslationSessionBus.onNewLine(
                text = text,
                capturedAtMillis = System.currentTimeMillis(),
                sourceColors = sourceColors,
            )
            CaptureHealth.onLinePublished()
            PipelineTiming.mark("line_published", 0, "len=${text.length}")
        } finally {
            // Never recycle the same bitmap twice: the cropped bitmap may BE
            // the full bitmap when no region crop applies.
            ocrBitmap?.let { cropped ->
                if (cropped !== fullBitmap && !cropped.isRecycled) cropped.recycle()
            }
            if (!fullBitmap.isRecycled) fullBitmap.recycle()
        }
    }

    /**
     * Samples the glyph colour of each word of the published subtitle from the
     * captured frame. Only chromatic colours are kept (white/grey is the
     * default look). Bounded work: at most [MAX_COLOR_WORDS] words, each box
     * sampled at a capped size.
     */
    private fun detectSourceColors(
        frame: Bitmap,
        blocks: List<com.gamelingo.mobile.core.capture.RecognizedBlock>,
        publishedText: String,
    ): List<com.gamelingo.mobile.core.color.SourceWordColor> {
        if (blocks.isEmpty()) return emptyList()
        val wanted = publishedText.lowercase()
        val out = ArrayList<com.gamelingo.mobile.core.color.SourceWordColor>()
        for (word in blocks.asSequence().flatMap { it.words.asSequence() }) {
            if (out.size >= MAX_COLOR_WORDS) break
            if (!wanted.contains(word.text.lowercase())) continue
            val left = word.rect.left.coerceIn(0, frame.width - 1)
            val top = word.rect.top.coerceIn(0, frame.height - 1)
            val w = (word.rect.right - left).coerceIn(2, MAX_WORD_SAMPLE_PX).coerceAtMost(frame.width - left)
            val h = (word.rect.bottom - top).coerceIn(2, MAX_WORD_SAMPLE_PX).coerceAtMost(frame.height - top)
            if (w < 2 || h < 2) continue
            val pixels = IntArray(w * h)
            runCatching { frame.getPixels(pixels, 0, w, left, top, w, h) }.getOrNull() ?: continue
            val color = com.gamelingo.mobile.core.color.SourceColorDetector.dominantTextColor(pixels, w, h)
            if (color != null && com.gamelingo.mobile.core.color.SourceColorDetector.isChromatic(color)) {
                out += com.gamelingo.mobile.core.color.SourceWordColor(word.text, color)
            }
        }
        return out
    }

    /** Converts an RGBA_8888 ImageReader frame into a Bitmap, handling row padding. */
    private fun Image.toBitmap(width: Int, height: Int): Bitmap {
        val plane = planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        return if (paddedWidth == width) {
            padded
        } else {
            val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
            padded.recycle()
            cropped
        }
    }

    /** Small grayscale digest of a bitmap, only for frame-change detection. */
    private fun Bitmap.toDownsampledBytes(): ByteArray {
        val sampleWidth = 64
        val sampleHeight = 64
        val scaled = Bitmap.createScaledBitmap(this, sampleWidth, sampleHeight, false)
        val bytes = ByteArray(sampleWidth * sampleHeight)
        val pixel = IntArray(1)
        for (y in 0 until sampleHeight) {
            for (x in 0 until sampleWidth) {
                scaled.getPixels(pixel, 0, 1, x, y, 1, 1)
                val argb = pixel[0]
                val r = argb shr 16 and 0xFF
                val g = argb shr 8 and 0xFF
                val b = argb and 0xFF
                bytes[y * sampleWidth + x] = ((r + g + b) / 3).toByte()
            }
        }
        scaled.recycle()
        return bytes
    }

    private fun stopCapture(reason: String) {
        // Re-entry is expected: the stop button, the projection callback, an
        // OCR failure and onDestroy can all arrive together. Only the first
        // caller runs teardown, so the first caller's reason is the one kept.
        if (!stopping.compareAndSet(false, true)) return
        CaptureHealth.onSessionStopped(reason)
        Log.i(TAG, "stopCapture: tearing down (reason=$reason)")
        captureJob?.cancel()
        captureJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        settingsJob?.cancel()
        settingsJob = null
        cachedSettings = null
        runCatching {
            getSystemService(DisplayManager::class.java)
                ?.unregisterDisplayListener(displayListener)
        }
        // Null the fields before releasing: when the system stops the
        // projection on app close, MediaProjection.Callback.onStop calls us
        // while onDestroy may follow right after. Every release step is
        // guarded, because stopping an already-stopped projection or closing
        // an already-released surface throws on the main thread and takes the
        // whole app down exactly when the user is leaving it.
        val display = virtualDisplay
        virtualDisplay = null
        val reader = imageReader
        imageReader = null
        val projection = mediaProjection
        mediaProjection = null
        currentGeometry = null
        // Teardown ordered behind the display mutex: a frame read or OCR pass
        // in flight finishes (or observes cancellation) before its surface is
        // released, so no resource is freed while in use.
        serviceScope.launch {
            displayMutex.withLock {
                runCatching { display?.release() }
                runCatching { reader?.close() }
            }
            runCatching { projection?.stop() }
            frameChangeDetector.reset()
            stabilityTracker.reset()
            deduplicator.reset()
            failureTracker.reset()
            rebuildFailureTracker.reset()
            blockDynamicsTracker.reset()
            TranslationSessionBus.onSessionStopped()
            runCatching { stopService(OverlayService.startIntent(this@ScreenCaptureService)) }
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
    }

    /**
     * High-priority "translation stopped, tap to resume" alert. The system
     * consent dialog cannot be relaunched from a service, so the tap opens
     * MainActivity with EXTRA_AUTO_RESUME and the home screen re-asks consent
     * immediately - two taps and the session is back.
     */
    private fun postResumeNotification() {
        runCatching {
            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    ALERTS_CHANNEL_ID,
                    getString(R.string.notif_channel_alerts_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = getString(R.string.notif_channel_alerts_desc)
                },
            )
            val launch = Intent(this, MainActivity::class.java)
                .putExtra(ResumeRequest.EXTRA_AUTO_RESUME, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pending = PendingIntent.getActivity(
                this,
                RESUME_REQUEST_CODE,
                launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification: Notification = NotificationCompat.Builder(this, ALERTS_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(getString(R.string.notif_resume_title))
                .setContentText(getString(R.string.notif_resume_text))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            manager.notify(RESUME_NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "could not post resume notification", it) }
    }

    private fun startForegroundWithNotification() {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_capture),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_capture_title))
            .setContentText(getString(R.string.notif_capture_text))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        // System kill or normal shutdown without a prior stopCapture: record it
        // so diagnostics can tell "service destroyed" apart from a user stop.
        if (stopping.compareAndSet(false, true)) {
            CaptureHealth.onSessionStopped(CaptureHealth.StopReasons.SERVICE_DESTROYED)
        }
        // Same guarded teardown as stopCapture: either path can run after the
        // other, and none of these components tolerate a second release.
        runCatching { captureJob?.cancel() }
        runCatching { settingsJob?.cancel() }
        runCatching {
            getSystemService(DisplayManager::class.java)
                ?.unregisterDisplayListener(displayListener)
        }
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { mediaProjection?.stop() }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.gamelingo.mobile.action.START_CAPTURE"
        const val ACTION_STOP = "com.gamelingo.mobile.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val TAG = "ScreenCapture"
        private const val REGION_MARGIN_MIN_PX = 16
        private const val REGION_MARGIN_FRACTION = 0.15f
        private const val CHANNEL_ID = "screen_capture"
        private const val ALERTS_CHANNEL_ID = "session_alerts"
        private const val RESUME_NOTIFICATION_ID = 42
        private const val RESUME_REQUEST_CODE = 4242

        /** About 12 s of rebuild retries at the default 600 ms interval. */
        private const val REBUILD_MAX_FAILURES = 20
        private const val NOTIFICATION_ID = 1001

        /** Watchdog: report when no frame arrived for this long while capturing. */
        private const val STALL_REPORT_MS = 5_000L
        private const val NO_FIRST_FRAME_MS = 8_000L

        /** Presence heartbeat period: far below OCR cost, fine for the 1 s grace. */
        private const val PRESENCE_HEARTBEAT_MS = 250L
        private const val MAX_COLOR_WORDS = 24
        private const val MAX_WORD_SAMPLE_PX = 96

        /** Floor for the capture tick delay when OCR outlasts the interval. */
        private const val MIN_TICK_DELAY_MS = 50L

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)

        fun stopIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)
    }
}
