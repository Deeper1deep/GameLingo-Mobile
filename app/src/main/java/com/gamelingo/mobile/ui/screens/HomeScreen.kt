package com.gamelingo.mobile.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import com.gamelingo.mobile.ui.navigation.GameLingoRoutes
import com.gamelingo.mobile.ui.navigation.HistoryViewModel
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
) {
    val historyCount by viewModel.historyCount.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val startPendingMessage = stringResource(R.string.home_start_pending)
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
                        text = stringResource(R.string.home_status_stopped),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            scope.launch { snackbarHostState.showSnackbar(startPendingMessage) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                        )
                        Text(
                            text = stringResource(R.string.home_start),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
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
