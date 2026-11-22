package com.gamelingo.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamelingo.mobile.BuildConfig
import com.gamelingo.mobile.R
import com.gamelingo.mobile.ui.components.GlButton
import com.gamelingo.mobile.ui.components.GlButtonStyle
import com.gamelingo.mobile.ui.components.GlCard
import com.gamelingo.mobile.ui.components.GlDialog
import com.gamelingo.mobile.ui.components.GlNavRow
import com.gamelingo.mobile.ui.components.GlTextButton
import com.gamelingo.mobile.ui.components.InfoRow
import com.gamelingo.mobile.ui.components.HintText
import com.gamelingo.mobile.ui.components.SectionHeader
import com.gamelingo.mobile.ui.components.StatusChip
import com.gamelingo.mobile.ui.components.StatusTone
import com.gamelingo.mobile.ui.navigation.GameLingoRoutes
import com.gamelingo.mobile.ui.navigation.StorageViewModel
import com.gamelingo.mobile.ui.system.LocalSnackbarHostState
import com.gamelingo.mobile.ui.system.rememberSessionController
import com.gamelingo.mobile.ui.theme.GlColors
import com.gamelingo.mobile.ui.theme.GlSpacing
import kotlinx.coroutines.launch

@Composable
fun SettingsHubScreen(
    onNavigate: (String) -> Unit,
    storageViewModel: StorageViewModel = hiltViewModel(),
) {
    val historyCount by storageViewModel.historyCount.collectAsStateWithLifecycle()
    val controller = rememberSessionController()
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val cacheClearedMessage = stringResource(R.string.storage_cache_cleared)
    var confirmClearHistory by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(GlSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(GlSpacing.md),
    ) {
        Text(
            text = stringResource(R.string.nav_settings),
            style = MaterialTheme.typography.headlineMedium,
            color = GlColors.TextPrimary,
        )

        OptimizeCard()

        SectionHeader(stringResource(R.string.settings_section_features))
        GlNavRow(
            icon = Icons.Filled.Settings,
            title = stringResource(R.string.nav_translation_settings),
            subtitle = stringResource(R.string.settings_translation_sub),
            onClick = { onNavigate(GameLingoRoutes.TRANSLATION_SETTINGS) },
        )
        GlNavRow(
            icon = Icons.Filled.Face,
            title = stringResource(R.string.nav_ai_settings),
            subtitle = stringResource(R.string.settings_ai_sub),
            onClick = { onNavigate(GameLingoRoutes.AI_SETTINGS) },
        )
        GlNavRow(
            icon = Icons.Filled.Search,
            title = stringResource(R.string.nav_ocr_settings),
            subtitle = stringResource(R.string.settings_ocr_sub),
            onClick = { onNavigate(GameLingoRoutes.OCR_SETTINGS) },
        )
        GlNavRow(
            icon = Icons.Filled.Create,
            title = stringResource(R.string.nav_overlay_settings),
            subtitle = stringResource(R.string.settings_overlay_sub),
            onClick = { onNavigate(GameLingoRoutes.OVERLAY_SETTINGS) },
        )
        GlNavRow(
            icon = Icons.Filled.PlayArrow,
            title = stringResource(R.string.nav_voice),
            subtitle = stringResource(R.string.settings_voice_sub),
            onClick = { onNavigate(GameLingoRoutes.VOICE) },
        )

        SectionHeader(stringResource(R.string.settings_section_app))
        GlCard {
            Text(
                text = stringResource(R.string.settings_language_title),
                style = MaterialTheme.typography.titleMedium,
                color = GlColors.TextPrimary,
            )
            HintText(stringResource(R.string.settings_language_value))
        }
        GlCard {
            Text(
                text = stringResource(R.string.settings_theme_title),
                style = MaterialTheme.typography.titleMedium,
                color = GlColors.TextPrimary,
            )
            HintText(stringResource(R.string.settings_theme_value))
        }

        SectionHeader(stringResource(R.string.settings_permissions_title))
        GlCard {
            PermissionLine(
                title = stringResource(R.string.perm_overlay),
                granted = controller.permissions.overlayGranted,
                actionLabel = stringResource(R.string.settings_permissions_manage),
                onAction = controller.openOverlaySettings,
            )
            PermissionLine(
                title = stringResource(R.string.perm_notifications),
                granted = controller.permissions.notificationsGranted,
                actionLabel = stringResource(R.string.perm_grant),
                onAction = controller.requestNotifications,
            )
        }

        SectionHeader(stringResource(R.string.settings_storage_title))
        GlCard {
            InfoRow(
                label = stringResource(R.string.storage_history),
                value = historyCount.toString(),
            )
            HintText(stringResource(R.string.storage_cache_hint))
            Column(
                modifier = Modifier.padding(top = GlSpacing.md),
                verticalArrangement = Arrangement.spacedBy(GlSpacing.sm),
            ) {
                GlButton(
                    text = stringResource(R.string.storage_clear_cache),
                    onClick = {
                        storageViewModel.clearTranslationCache()
                        scope.launch { snackbar?.showSnackbar(cacheClearedMessage) }
                    },
                    style = GlButtonStyle.Secondary,
                    icon = Icons.Filled.Refresh,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (historyCount > 0) {
                    GlButton(
                        text = stringResource(R.string.history_clear_all),
                        onClick = { confirmClearHistory = true },
                        style = GlButtonStyle.Danger,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        SectionHeader(stringResource(R.string.about_updates_title))
        GlNavRow(
            icon = Icons.Filled.Notifications,
            title = stringResource(R.string.nav_update_center),
            subtitle = stringResource(R.string.settings_updates_sub, BuildConfig.VERSION_NAME),
            onClick = { onNavigate(GameLingoRoutes.UPDATES) },
        )
        GlNavRow(
            icon = Icons.Filled.Build,
            title = stringResource(R.string.nav_diagnostics),
            subtitle = stringResource(R.string.settings_diagnostics_sub),
            onClick = { onNavigate(GameLingoRoutes.DIAGNOSTICS) },
        )
        GlNavRow(
            icon = Icons.Filled.Info,
            title = stringResource(R.string.nav_about),
            subtitle = stringResource(R.string.settings_about_sub),
            onClick = { onNavigate(GameLingoRoutes.ABOUT) },
        )
    }

    if (confirmClearHistory) {
        GlDialog(
            title = stringResource(R.string.history_clear_confirm_title),
            onDismiss = { confirmClearHistory = false },
            confirmText = stringResource(R.string.history_clear_all),
            dismissText = stringResource(R.string.cancel),
            danger = true,
            onConfirm = {
                storageViewModel.clearHistory()
                confirmClearHistory = false
            },
        ) {
            Text(
                text = stringResource(R.string.history_clear_confirm_body),
                style = MaterialTheme.typography.bodyMedium,
                color = GlColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun PermissionLine(
    title: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = GlColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        if (granted) {
            StatusChip(label = stringResource(R.string.perm_granted), tone = StatusTone.Success)
        } else {
            GlTextButton(text = actionLabel, onClick = onAction)
        }
    }
}
