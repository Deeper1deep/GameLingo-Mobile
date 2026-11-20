package com.gamelingo.mobile.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamelingo.mobile.R
import com.gamelingo.mobile.capture.ScreenCaptureService
import com.gamelingo.mobile.core.session.SessionError
import com.gamelingo.mobile.core.session.SessionNotice
import com.gamelingo.mobile.core.session.SessionState
import com.gamelingo.mobile.ui.navigation.GameLingoRoutes
import com.gamelingo.mobile.ui.navigation.HistoryViewModel
import com.gamelingo.mobile.ui.navigation.SessionViewModel
import kotlinx.coroutines.launch

private fun hasOverlayPermission(context: Context): Boolean =
    Settings.canDrawOverlays(context)

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
    sessionViewModel: SessionViewModel = hiltViewModel(),
) {
    val historyCount by viewModel.historyCount.collectAsStateWithLifecycle()
    val sessionState by sessionViewModel.sessionState.collectAsStateWithLifecycle()
    val sessionError by sessionViewModel.sessionError.collectAsStateWithLifecycle()
    val latestLine by sessionViewModel.latestLine.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val captureCancelledMessage = stringResource(R.string.home_capture_cancelled)
    val overlayNeededMessage = stringResource(R.string.perm_overlay_needed_for_start)
    val context = LocalContext.current

    var overlayGranted by remember { mutableStateOf(hasOverlayPermission(context)) }
    var notificationsGranted by remember {
        mutableStateOf(hasNotificationPermission(context))
    }

    // Re-check permission state every time the user comes back from a system screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = hasOverlayPermission(context)
                notificationsGranted = hasNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = ScreenCaptureService.startIntent(
                context,
                result.resultCode,
                result.data!!,
            )
            ContextCompat.startForegroundService(context, intent)
        } else {
            scope.launch { snackbarHostState.showSnackbar(captureCancelledMessage) }
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsGranted = granted }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.home_headline),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.home_description),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            when (sessionState) {
                                SessionState.STOPPED -> R.string.home_status_stopped
                                SessionState.RUNNING -> R.string.home_status_running
                                SessionState.ERROR -> R.string.home_status_error
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // The reason survives the session teardown: a failed
                    // session settles back to STOPPED, but the user still
                    // needs to know why it died until they start again.
                    sessionError?.let { error ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(
                                when (error) {
                                    SessionError.MISSING_CONSENT_DATA ->
                                        R.string.session_error_missing_consent
                                    SessionError.FOREGROUND_START_FAILED ->
                                        R.string.session_error_foreground_failed
                                    SessionError.PROJECTION_REJECTED ->
                                        R.string.session_error_projection_rejected
                                    SessionError.CAPTURE_PERSISTENT_FAILURE ->
                                        R.string.session_error_capture_persistent
                                    SessionError.SETTINGS_UNAVAILABLE ->
                                        R.string.session_error_settings_unavailable
                                    SessionError.OVERLAY_PERMISSION_LOST ->
                                        R.string.session_error_overlay_permission
                                    SessionError.OVERLAY_WINDOW_FAILED ->
                                        R.string.session_error_overlay_window
                                    SessionError.PROJECTION_ENDED ->
                                        R.string.session_error_projection_ended
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (sessionState == SessionState.RUNNING) {
                        val translated by sessionViewModel.translatedLine.collectAsStateWithLifecycle()
                        val failure by sessionViewModel.translationFailure.collectAsStateWithLifecycle()
                        val notice by sessionViewModel.notice.collectAsStateWithLifecycle()
                        notice?.let {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(
                                    when (it) {
                                        SessionNotice.ONLINE_NOT_CONFIGURED ->
                                            R.string.overlay_notice_online_not_configured
                                        SessionNotice.FELL_BACK_TO_LOCAL ->
                                            R.string.overlay_notice_fell_back_local
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        when {
                            translated != null -> {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = translated!!.translated,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.home_latest_ocr,
                                        translated!!.original,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            failure != null -> {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.home_translation_failed),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            latestLine != null -> {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(
                                        R.string.home_latest_ocr,
                                        latestLine!!.text,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    SessionButton(
                        sessionState = sessionState,
                        onStart = {
                            // Never start a session the user cannot watch:
                            // without the overlay permission the translation
                            // window cannot appear, so ask for it first and
                            // send him straight to the right system screen.
                            if (!hasOverlayPermission(context)) {
                                scope.launch {
                                    snackbarHostState.showSnackbar(overlayNeededMessage)
                                }
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                }
                            } else {
                                val projectionManager =
                                    context.getSystemService(MediaProjectionManager::class.java)
                                projectionLauncher.launch(
                                    projectionManager.createScreenCaptureIntent(),
                                )
                            }
                        },
                        onStop = {
                            context.startService(ScreenCaptureService.stopIntent(context))
                        },
                    )
                }
            }

            SectionTitle(stringResource(R.string.home_permissions_title))
            PermissionCard(
                icon = Icons.Filled.Notifications,
                title = stringResource(R.string.perm_overlay),
                description = stringResource(R.string.perm_overlay_needed),
                granted = overlayGranted,
                grantedLabel = stringResource(R.string.perm_granted),
                actionLabel = stringResource(R.string.perm_overlay_open_settings),
                onAction = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                    context.startActivity(intent)
                },
            )
            PermissionCard(
                icon = Icons.Filled.Notifications,
                title = stringResource(R.string.perm_notifications),
                description = stringResource(R.string.perm_notifications_needed),
                granted = notificationsGranted,
                grantedLabel = stringResource(R.string.perm_granted),
                actionLabel = stringResource(R.string.perm_grant),
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )

            SectionTitle(stringResource(R.string.home_how_title))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    listOf(
                        R.string.home_how_step1,
                        R.string.home_how_step2,
                        R.string.home_how_step3,
                        R.string.home_how_step4,
                    ).forEachIndexed { index, stepRes ->
                        Text(
                            text = stringResource(
                                R.string.home_how_step_format,
                                index + 1,
                                stringResource(stepRes),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }

            SectionTitle(stringResource(R.string.home_sections_title))
            val entries = listOf(
                NavEntry(
                    GameLingoRoutes.TRANSLATION_SETTINGS,
                    R.string.nav_translation_settings,
                    Icons.Filled.Settings,
                ),
                NavEntry(GameLingoRoutes.OCR_SETTINGS, R.string.nav_ocr_settings, Icons.Filled.Search),
                NavEntry(GameLingoRoutes.AI_SETTINGS, R.string.nav_ai_settings, Icons.Filled.Face),
                NavEntry(
                    GameLingoRoutes.OVERLAY_SETTINGS,
                    R.string.nav_overlay_settings,
                    Icons.Filled.Create,
                ),
                NavEntry(GameLingoRoutes.HISTORY, R.string.nav_history, Icons.Filled.List),
                NavEntry(GameLingoRoutes.VOICE, R.string.nav_voice, Icons.Filled.PlayArrow),
                NavEntry(GameLingoRoutes.ABOUT, R.string.nav_about, Icons.Filled.Info),
            )
            entries.forEach { entry ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onNavigate(entry.route) },
                ) {
                    ListItem(
                        leadingContent = {
                            Icon(imageVector = entry.icon, contentDescription = null)
                        },
                        headlineContent = { Text(stringResource(entry.titleRes)) },
                        trailingContent = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = null,
                            )
                        },
                    )
                }
            }

            Text(
                text = stringResource(R.string.home_history_count, historyCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.padding(16.dp),
        )
    }
}

private data class NavEntry(
    val route: String,
    val titleRes: Int,
    val icon: ImageVector,
)

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    grantedLabel: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = icon, contentDescription = null)
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(
                    text = if (granted) grantedLabel else description,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (!granted) {
                OutlinedButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

/** Start/stop button that follows the live session state. */
@Composable
private fun SessionButton(
    sessionState: SessionState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val running = sessionState == SessionState.RUNNING
    Button(
        onClick = if (running) onStop else onStart,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = if (running) Icons.Filled.Close else Icons.Filled.PlayArrow,
            contentDescription = null,
        )
        Text(
            text = stringResource(
                if (running) R.string.home_stop else R.string.home_start,
            ),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
