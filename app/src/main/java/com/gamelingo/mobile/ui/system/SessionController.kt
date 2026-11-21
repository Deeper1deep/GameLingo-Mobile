package com.gamelingo.mobile.ui.system

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gamelingo.mobile.R
import com.gamelingo.mobile.capture.ScreenCaptureService
import kotlinx.coroutines.launch

/** App-wide snackbar host, provided once by the root scaffold. */
val LocalSnackbarHostState = compositionLocalOf<SnackbarHostState?> { null }

private fun hasOverlayPermission(context: Context): Boolean =
    Settings.canDrawOverlays(context)

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

/** Live state of the two permissions the app needs; refreshed whenever the app resumes. */
@Stable
class PermissionStatus(private val context: Context) {
    var overlayGranted by mutableStateOf(hasOverlayPermission(context))
        private set
    var notificationsGranted by mutableStateOf(hasNotificationPermission(context))
        private set

    val allGranted: Boolean get() = overlayGranted && notificationsGranted

    fun refresh() {
        overlayGranted = hasOverlayPermission(context)
        notificationsGranted = hasNotificationPermission(context)
    }

    fun onNotificationResult(granted: Boolean) {
        notificationsGranted = granted
    }
}

@Composable
fun rememberPermissionStatus(): PermissionStatus {
    val context = LocalContext.current
    val status = remember { PermissionStatus(context) }
    // Re-check every time the user comes back from a system screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) status.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return status
}

/** Start/stop of the capture session plus the permission shortcuts the UI needs. */
@Stable
class SessionController(
    val permissions: PermissionStatus,
    val start: () -> Unit,
    val stop: () -> Unit,
    val requestNotifications: () -> Unit,
    val openOverlaySettings: () -> Unit,
)

/**
 * The same start flow the app has always used, shared by every screen that
 * can start a session: the overlay permission is required first (without it
 * the translation window cannot appear), then the system screen-capture
 * consent, then the foreground service starts. Nothing here touches the
 * capture, OCR or overlay logic itself.
 */
@Composable
fun rememberSessionController(): SessionController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = LocalSnackbarHostState.current
    val permissions = rememberPermissionStatus()
    val captureCancelledMessage = stringResource(R.string.home_capture_cancelled)
    val overlayNeededMessage = stringResource(R.string.perm_overlay_needed_for_start)

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val intent = ScreenCaptureService.startIntent(context, result.resultCode, data)
            ContextCompat.startForegroundService(context, intent)
        } else {
            scope.launch { snackbarHostState?.showSnackbar(captureCancelledMessage) }
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> permissions.onNotificationResult(granted) }

    return remember(permissions) {
        val openOverlaySettings = {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
            Unit
        }
        SessionController(
            permissions = permissions,
            start = {
                if (!hasOverlayPermission(context)) {
                    scope.launch { snackbarHostState?.showSnackbar(overlayNeededMessage) }
                    openOverlaySettings()
                } else {
                    val projectionManager =
                        context.getSystemService(MediaProjectionManager::class.java)
                    projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                }
            },
            stop = { context.startService(ScreenCaptureService.stopIntent(context)) },
            requestNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            openOverlaySettings = openOverlaySettings,
        )
    }
}
