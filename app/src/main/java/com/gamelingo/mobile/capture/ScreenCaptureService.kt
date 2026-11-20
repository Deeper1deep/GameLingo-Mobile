package com.gamelingo.mobile.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.util.Log
import android.os.IBinder
import android.view.Display
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.gamelingo.mobile.R
import com.gamelingo.mobile.core.capture.BlockDynamicsTracker
import com.gamelingo.mobile.core.capture.DisplayGeometry
import com.gamelingo.mobile.core.capture.FailureTracker
import com.gamelingo.mobile.core.capture.SubtitleBlockScorer
import com.gamelingo.mobile.core.capture.translated
import com.gamelingo.mobile.core.capture.FrameChangeDetector
import com.gamelingo.mobile.core.capture.RegionMapper
import com.gamelingo.mobile.core.session.SessionError
import com.gamelingo.mobile.core.session.TranslationSessionBus
import com.gamelingo.mobile.core.text.Deduplicator
import com.gamelingo.mobile.core.text.TextNormalizer
import com.gamelingo.mobile.core.text.TextStabilityTracker
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
 * The capture surface tracks the display: rotation or size changes (opening a
 * fullscreen game/video after starting the session) rebuild the VirtualDisplay
 * and ImageReader, otherwise the mirrored frame letterboxes into stale
 * geometry and the OCR band lands on black bars. Repeated frame failures
 * surface as a session error instead of spinning silently.
 */
@AndroidEntryPoint
class ScreenCaptureService : Service() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var recognizerEngine: TextRecognizerEngine

    // A stray async exception must never reach the default uncaught handler:
    // that kills the whole app. The capture loop handles its own failures and
    // reports persistent ones to the user; anything else is logged and dropped.
    private val exceptionHandler = CoroutineExceptionHandler { _, _ -> }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)

    /** True once teardown has begun; stopCapture runs its body only once. */
    private val stopping = java.util.concurrent.atomic.AtomicBoolean(false)

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureJob: Job? = null

    /** Guards VirtualDisplay/ImageReader rebuilds against in-flight frame reads. */
    private val displayMutex = Mutex()
    private var currentGeometry: DisplayGeometry? = null

    private val frameChangeDetector = FrameChangeDetector()
    private val stabilityTracker = TextStabilityTracker()
    private val deduplicator = Deduplicator()
    private val failureTracker = FailureTracker()
    private val blockDynamicsTracker = BlockDynamicsTracker()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_STOP -> stopCapture()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    // No onConfigurationChanged hook: rebuilding the capture surface from the
    // config-change callback races in-flight frame reads, and any exception
    // thrown there crashes the process. The capture loop already re-checks the
    // display geometry every tick and rebuilds under its own error handling,
    // at most one capture interval after rotation.

    private fun startCapture(intent: Intent) {
        if (mediaProjection != null) return // already running
        // A previous teardown nulls every field synchronously before its
        // guarded releases, so reaching this point means it is safe to run
        // again; clear the flag so the next stop works.
        stopping.set(false)

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
            stopCapture()
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
                stopCapture()
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
            stopCapture()
            return
        }

        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture()
                }
            },
            null,
        )
        mediaProjection = projection

        TranslationSessionBus.onSessionStarted()
        startService(OverlayService.startIntent(this))
        captureJob = serviceScope.launch { captureLoop() }
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
     * Rebuilds the ImageReader + VirtualDisplay when the display geometry has
     * changed since the surface was created. The first call builds the surface.
     */
    private suspend fun rebuildDisplayIfNeeded() {
        val projection = mediaProjection ?: return
        val latest = latestGeometry()
        displayMutex.withLock {
            if (!DisplayGeometry.needsRebuild(currentGeometry, latest)) return
            // Release the old surface before building the new one. A failed
            // release must not skip or break the rebuild: the old objects are
            // dropped regardless and replaced with fresh ones.
            runCatching { virtualDisplay?.release() }
            virtualDisplay = null
            runCatching { imageReader?.close() }
            imageReader = null
            // Transient 0x0 metrics during a configuration change are real on
            // some devices; ImageReader rejects zero dimensions.
            val width = latest.width.coerceAtLeast(1)
            val height = latest.height.coerceAtLeast(1)
            val reader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2,
            )
            imageReader = reader
            // If createVirtualDisplay throws, the loop's failure tracking
            // retries on the next tick (the half-built reader is closed above)
            // and surfaces a session error if the failure persists.
            virtualDisplay = projection.createVirtualDisplay(
                "gamelingo-capture",
                width,
                height,
                resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null,
            )
            currentGeometry = latest
            // The frame stream restarts: stale digests, half-stable lines and
            // block-position history from the old geometry must not gate the
            // new one.
            frameChangeDetector.reset()
            stabilityTracker.reset()
            deduplicator.reset()
            blockDynamicsTracker.reset()
        }
    }

    private suspend fun captureLoop() {
        while (serviceScope.isActive) {
            // The settings read lives inside error handling too: a failing
            // DataStore must surface as a session error, not kill the loop
            // silently while the UI keeps showing RUNNING.
            val settings = try {
                settingsRepository.settings.first()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "settings read failed inside the capture loop", error)
                TranslationSessionBus.onSessionError(SessionError.SETTINGS_UNAVAILABLE)
                stopCapture()
                break
            }
            try {
                rebuildDisplayIfNeeded()
                val geometry = currentGeometry
                if (geometry != null) {
                    displayMutex.withLock {
                        val image = imageReader?.acquireLatestImage()
                        if (image != null) {
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
                if (failureTracker.onFailure()) {
                    Log.e(TAG, "frame processing keeps failing; stopping the session")
                    TranslationSessionBus.onSessionError(SessionError.CAPTURE_PERSISTENT_FAILURE)
                    stopCapture()
                    break
                }
            }
            delay(settings.captureIntervalMs)
        }
    }

    private suspend fun processFrame(
        image: Image,
        geometry: DisplayGeometry,
        settings: com.gamelingo.mobile.data.settings.AppSettings,
    ) {
        val fullBitmap = image.toBitmap(geometry.width, geometry.height)
        // Every bitmap this frame creates is released exactly once, whatever
        // happens below: the finally owns recycling, never the success path.
        var ocrBitmap: Bitmap? = null
        try {
            val shouldRunOcr = !settings.batterySaverEnabled ||
                frameChangeDetector.hasChanged(fullBitmap.toDownsampledBytes())
            if (!shouldRunOcr) {
                return
            }

            var cropTop = 0
            val workingBitmap = if (settings.restrictToTextRegion) {
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

            val rawText = if (settings.smartTextDetection) {
            // Smart mode: read positioned blocks, shift them into full-frame
            // coordinates, and let the scorer pick the real subtitle over
            // static corner watermarks and HUD labels, merging wrapped lines.
            val blocks = recognizerEngine.recognizeBlocks(InputImage.fromBitmap(workingBitmap, 0))
            val shifted = blocks.map { block ->
                block.copy(rect = block.rect.translated(0, cropTop))
            }
            val subtitle = SubtitleBlockScorer.selectSubtitle(
                blocks = shifted,
                frameWidth = geometry.width,
                frameHeight = geometry.height,
                dynamics = blockDynamicsTracker,
            )
            blockDynamicsTracker.offer(shifted)
            subtitle.orEmpty()
            } else {
                recognizerEngine.recognize(InputImage.fromBitmap(workingBitmap, 0))
            }

            val text = TextNormalizer.normalize(rawText)
            if (text.isEmpty()) return

            if (settings.ignoreUnstableText && !stabilityTracker.offer(text)) return
            if (settings.dedupEnabled && !deduplicator.shouldDeliver(text)) return

            TranslationSessionBus.onNewLine(
                text = text,
                capturedAtMillis = System.currentTimeMillis(),
            )
        } finally {
            // Never recycle the same bitmap twice: the cropped bitmap may BE
            // the full bitmap when no region crop applies.
            ocrBitmap?.let { cropped ->
                if (cropped !== fullBitmap && !cropped.isRecycled) cropped.recycle()
            }
            if (!fullBitmap.isRecycled) fullBitmap.recycle()
        }
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

    private fun stopCapture() {
        // Re-entry is expected: the stop button, the projection callback, an
        // OCR failure and onDestroy can all arrive together. Only the first
        // caller runs teardown.
        if (!stopping.compareAndSet(false, true)) return
        captureJob?.cancel()
        captureJob = null
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
            blockDynamicsTracker.reset()
            TranslationSessionBus.onSessionStopped()
            runCatching { stopService(OverlayService.startIntent(this@ScreenCaptureService)) }
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
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
        // Same guarded teardown as stopCapture: either path can run after the
        // other, and none of these components tolerate a second release.
        runCatching { captureJob?.cancel() }
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
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 1001

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)

        fun stopIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)
    }
}
