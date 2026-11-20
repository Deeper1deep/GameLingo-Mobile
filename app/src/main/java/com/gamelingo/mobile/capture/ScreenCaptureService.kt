package com.gamelingo.mobile.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Display
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.gamelingo.mobile.R
import com.gamelingo.mobile.core.capture.DisplayGeometry
import com.gamelingo.mobile.core.capture.FailureTracker
import com.gamelingo.mobile.core.capture.FrameChangeDetector
import com.gamelingo.mobile.core.capture.RegionMapper
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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_STOP -> stopCapture()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation after the session started: rebuild the capture surface.
        serviceScope.launch { rebuildDisplayIfNeeded() }
    }

    private fun startCapture(intent: Intent) {
        if (mediaProjection != null) return // already running

        startForegroundWithNotification()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }
        if (resultData == null) {
            TranslationSessionBus.onSessionError()
            stopCapture()
            return
        }

        val projectionManager =
            getSystemService(MediaProjectionManager::class.java)
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            TranslationSessionBus.onSessionError()
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
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            val reader = ImageReader.newInstance(
                latest.width,
                latest.height,
                PixelFormat.RGBA_8888,
                2,
            )
            imageReader = reader
            virtualDisplay = projection.createVirtualDisplay(
                "gamelingo-capture",
                latest.width,
                latest.height,
                resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null,
            )
            currentGeometry = latest
            // The frame stream restarts: stale digests and half-stable lines
            // from the old geometry must not gate the new one.
            frameChangeDetector.reset()
            stabilityTracker.reset()
            deduplicator.reset()
        }
    }

    private suspend fun captureLoop() {
        while (serviceScope.isActive) {
            val settings = settingsRepository.settings.first()
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
            } catch (_: Exception) {
                // A single bad frame or OCR failure must not kill the session,
                // but a persistent failure is a broken session: say so and stop.
                if (failureTracker.onFailure()) {
                    TranslationSessionBus.onSessionError()
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

        val shouldRunOcr = !settings.batterySaverEnabled ||
            frameChangeDetector.hasChanged(fullBitmap.toDownsampledBytes())
        if (!shouldRunOcr) {
            fullBitmap.recycle()
            return
        }

        val ocrBitmap = if (settings.restrictToTextRegion) {
            val region = settings.effectiveRegion
            val rect = RegionMapper.mapToPixels(
                topFraction = region.top,
                bottomFraction = region.bottom,
                frameWidth = geometry.width,
                frameHeight = geometry.height,
            )
            Bitmap.createBitmap(fullBitmap, rect.left, rect.top, rect.width, rect.height)
        } else {
            fullBitmap
        }

        val rawText = recognizerEngine.recognize(InputImage.fromBitmap(ocrBitmap, 0))
        if (ocrBitmap !== fullBitmap) ocrBitmap.recycle()
        fullBitmap.recycle()

        val text = TextNormalizer.normalize(rawText)
        if (text.isEmpty()) return

        if (settings.ignoreUnstableText && !stabilityTracker.offer(text)) return
        if (settings.dedupEnabled && !deduplicator.shouldDeliver(text)) return

        TranslationSessionBus.onNewLine(
            text = text,
            capturedAtMillis = System.currentTimeMillis(),
        )
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
        captureJob?.cancel()
        captureJob = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        currentGeometry = null
        mediaProjection?.stop()
        mediaProjection = null
        frameChangeDetector.reset()
        stabilityTracker.reset()
        deduplicator.reset()
        failureTracker.reset()
        TranslationSessionBus.onSessionStopped()
        stopService(OverlayService.startIntent(this))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        captureJob?.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.gamelingo.mobile.action.START_CAPTURE"
        const val ACTION_STOP = "com.gamelingo.mobile.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
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
