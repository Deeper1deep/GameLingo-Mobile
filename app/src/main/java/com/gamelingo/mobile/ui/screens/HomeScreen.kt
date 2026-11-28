package com.gamelingo.mobile.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamelingo.mobile.R
import com.gamelingo.mobile.core.session.ResumeRequest
import com.gamelingo.mobile.core.session.SessionNotice
import com.gamelingo.mobile.core.session.SessionState
import com.gamelingo.mobile.core.session.waitingOnRateLimit
import com.gamelingo.mobile.core.status.SessionPhase
import com.gamelingo.mobile.core.status.resolveEngineReadiness
import com.gamelingo.mobile.core.status.resolveSessionPhase
import com.gamelingo.mobile.data.settings.AiMode
import com.gamelingo.mobile.ui.components.GlButton
import com.gamelingo.mobile.ui.components.GlButtonStyle
import com.gamelingo.mobile.ui.components.GlCard
import com.gamelingo.mobile.ui.components.GlTextButton
import com.gamelingo.mobile.ui.components.IconBadge
import com.gamelingo.mobile.ui.components.LogoMark
import com.gamelingo.mobile.ui.components.SectionHeader
import com.gamelingo.mobile.ui.components.StatusChip
import com.gamelingo.mobile.ui.components.StatusTile
import com.gamelingo.mobile.ui.components.StatusTone
import com.gamelingo.mobile.ui.navigation.GameLingoRoutes
import com.gamelingo.mobile.ui.navigation.HistoryViewModel
import com.gamelingo.mobile.ui.navigation.SessionViewModel
import com.gamelingo.mobile.ui.navigation.SettingsViewModel
import com.gamelingo.mobile.ui.navigation.StatusViewModel
import com.gamelingo.mobile.ui.system.rememberSessionController
import com.gamelingo.mobile.ui.theme.GlColors
import com.gamelingo.mobile.ui.theme.GlSpacing
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    historyViewModel: HistoryViewModel = hiltViewModel(),
    sessionViewModel: SessionViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    statusViewModel: StatusViewModel = hiltViewModel(),
) {
    val sessionState by sessionViewModel.sessionState.collectAsStateWithLifecycle()
    val sessionError by sessionViewModel.sessionError.collectAsStateWithLifecycle()
    val latestLine by sessionViewModel.latestLine.collectAsStateWithLifecycle()
    val translated by sessionViewModel.translatedLine.collectAsStateWithLifecycle()
    val failure by sessionViewModel.translationFailure.collectAsStateWithLifecycle()
    val workerStatus by sessionViewModel.workerStatus.collectAsStateWithLifecycle()
    val notice by sessionViewModel.notice.collectAsStateWithLifecycle()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val online by statusViewModel.online.collectAsStateWithLifecycle()
    val history by historyViewModel.history.collectAsStateWithLifecycle()
    val controller = rememberSessionController()

    // A tap on the "translation stopped - resume" notification re-asks capture
    // consent immediately; the banner below covers opening the app by hand.
    LaunchedEffect(Unit) {
        if (ResumeRequest.consumeAutoResume()) {
            controller.start()
        }
    }
    var resumeOffered by remember { mutableStateOf(ResumeRequest.interrupted) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeOffered = ResumeRequest.interrupted
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val running = sessionState == SessionState.RUNNING
    // The teardown after a failure flips the state back to STOPPED while the
    // reason is kept, so the failure must be judged by the reason, not the state.
    val phase = if (!running && sessionError != null) {
        SessionPhase.ERROR
    } else {
        resolveSessionPhase(sessionState, latestLine, translated, failure)
    }
    val readiness = resolveEngineReadiness(
        mode = settings.aiMode,
        cloudOptIn = settings.cloudTranslationOptIn,
        hasApiKey = settings.onlineApiKey.isNotBlank(),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(GlSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(GlSpacing.lg),
    ) {
        // Brand header
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoMark(size = 52.dp)
            Column(modifier = Modifier.padding(start = GlSpacing.md)) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    color = GlColors.TextPrimary,
                )
                Text(
                    text = stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlColors.TextSecondary,
                )
            }
        }

        // The system ended screen sharing mid-game: offer a one-tap resume.
        if (resumeOffered && !running) {
            GlCard {
                Column(verticalArrangement = Arrangement.spacedBy(GlSpacing.sm)) {
                    Text(
                        text = stringResource(R.string.resume_banner_text),
                        style = MaterialTheme.typography.bodyLarge,
                        color = GlColors.TextPrimary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(GlSpacing.md)) {
                        GlButton(
                            text = stringResource(R.string.resume_action),
                            onClick = {
                                ResumeRequest.dismiss()
                                resumeOffered = false
                                controller.start()
                            },
                        )
                        GlTextButton(
                            text = stringResource(R.string.resume_dismiss),
                            onClick = {
                                ResumeRequest.dismiss()
                                resumeOffered = false
                            },
                        )
                    }
                }
            }
        }

        // Smart status card: follows the real session signals.
        GlCard(elevated = true, contentPadding = PaddingValues(GlSpacing.xl)) {
            AnimatedContent(
                targetState = phase,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
                label = "phase",
            ) { current ->
                Column(verticalArrangement = Arrangement.spacedBy(GlSpacing.sm)) {
                    StatusChip(
                        label = phaseLabel(current),
                        tone = phaseTone(current),
                        pulsing = phaseIsLive(current),
                    )
                    Text(
text = when {
                            current == SessionPhase.ERROR && failure != null && running ->
                                stringResource(
                                    if (workerStatus.waitingOnRateLimit()) {
                                        R.string.home_translation_waiting
                                    } else {
                                        R.string.home_translation_failed
                                    },
                                )
                            current == SessionPhase.ERROR && sessionError != null ->
                                stringResource(sessionErrorMessageRes(sessionError!!))
                            else -> phaseDescription(current)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = GlColors.TextPrimary,
                    )
                    if (current == SessionPhase.ERROR && !running && sessionError != null) {
                        GlTextButton(
                            text = stringResource(R.string.session_error_dismiss),
                            onClick = sessionViewModel::dismissError,
                        )
                    }
                }
            }

            val shown = translated
            AnimatedVisibility(visible = running && shown != null) {
                if (shown != null) {
                    Column(modifier = Modifier.padding(top = GlSpacing.md)) {
                        Text(
                            text = shown.translated,
                            style = MaterialTheme.typography.titleMedium,
                            color = GlColors.Secondary,
                        )
                        Text(
                            text = shown.original,
                            style = MaterialTheme.typography.bodySmall,
                            color = GlColors.TextSecondary,
                        )
                    }
                }
            }

            notice?.let {
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
                    color = GlColors.Accent,
                    modifier = Modifier.padding(top = GlSpacing.md),
                )
            }

            Column(
                modifier = Modifier.padding(top = GlSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(GlSpacing.sm),
            ) {
                // The loudest element on the screen: start, or stop while running.
                if (running) {
                    GlButton(
                        text = stringResource(R.string.home_stop),
                        onClick = controller.stop,
                        style = GlButtonStyle.Danger,
                        icon = Icons.Filled.Close,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    GlButton(
                        text = stringResource(R.string.home_open_session),
                        onClick = { onNavigate(GameLingoRoutes.SESSION) },
                        style = GlButtonStyle.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    GlButton(
                        text = stringResource(R.string.home_start),
                        onClick = controller.start,
                        icon = Icons.Filled.PlayArrow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp),
                    )
                }
            }
        }

        // Status grid
        val translationValue = stringResource(
            when (sessionState) {
                SessionState.STOPPED -> R.string.value_stopped
                SessionState.RUNNING -> R.string.value_running
                SessionState.ERROR -> R.string.value_error
            },
        )
        val translationTone = when (sessionState) {
            SessionState.STOPPED -> StatusTone.Neutral
            SessionState.RUNNING -> StatusTone.Success
            SessionState.ERROR -> StatusTone.Error
        }
        val needsOnline = settings.aiMode == AiMode.ONLINE
        Column(verticalArrangement = Arrangement.spacedBy(GlSpacing.md)) {
            Row(
                modifier = Modifier.height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(GlSpacing.md),
            ) {
                StatusTile(
                    icon = Icons.Filled.PlayArrow,
                    title = stringResource(R.string.tile_translation),
                    value = translationValue,
                    tone = translationTone,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                StatusTile(
                    icon = Icons.Filled.Search,
                    title = stringResource(R.string.tile_ocr),
                    value = stringResource(
                        if (running) R.string.value_ocr_active else R.string.value_ocr_ready,
                    ),
                    tone = if (running) StatusTone.Success else StatusTone.Info,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
            Row(
                modifier = Modifier.height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(GlSpacing.md),
            ) {
                StatusTile(
                    icon = Icons.Filled.Share,
                    title = stringResource(R.string.tile_internet),
                    value = stringResource(
                        if (online) R.string.value_online else R.string.value_offline,
                    ),
                    tone = when {
                        online -> StatusTone.Success
                        needsOnline -> StatusTone.Error
                        else -> StatusTone.Neutral
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                StatusTile(
                    icon = Icons.Filled.Face,
                    title = stringResource(R.string.tile_ai),
                    value = engineReadinessShort(readiness),
                    tone = engineReadinessTone(readiness),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }

        // Missing permissions (only when something is actually missing)
        AnimatedVisibility(visible = !controller.permissions.allGranted) {
            GlCard {
                Text(
                    text = stringResource(R.string.home_permissions_missing_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = GlColors.TextPrimary,
                )
                if (!controller.permissions.overlayGranted) {
                    PermissionRow(
                        title = stringResource(R.string.perm_overlay),
                        description = stringResource(R.string.perm_overlay_needed),
                        actionLabel = stringResource(R.string.perm_overlay_open_settings),
                        onAction = controller.openOverlaySettings,
                    )
                }
                if (!controller.permissions.notificationsGranted) {
                    PermissionRow(
                        title = stringResource(R.string.perm_notifications),
                        description = stringResource(R.string.perm_notifications_needed),
                        actionLabel = stringResource(R.string.perm_grant),
                        onAction = controller.requestNotifications,
                    )
                }
            }
        }

        // Last saved translation
        SectionHeader(stringResource(R.string.home_last_title))
        val last = history.firstOrNull()
        GlCard(
            onClick = { onNavigate(GameLingoRoutes.HISTORY) },
        ) {
            if (last == null) {
                Text(
                    text = stringResource(R.string.home_last_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlColors.TextSecondary,
                )
            } else {
                Text(
                    text = DateFormat.getDateTimeInstance().format(Date(last.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = GlColors.TextSecondary,
                )
                Text(
                    text = last.translatedText,
                    style = MaterialTheme.typography.titleMedium,
                    color = GlColors.TextPrimary,
                    maxLines = 2,
                    modifier = Modifier.padding(top = GlSpacing.xs),
                )
                Text(
                    text = last.sourceText,
                    style = MaterialTheme.typography.bodySmall,
                    color = GlColors.TextSecondary,
                    maxLines = 2,
                )
            }
        }

        // Shortcuts
        Row(horizontalArrangement = Arrangement.spacedBy(GlSpacing.md)) {
            ShortcutCard(
                label = stringResource(R.string.home_shortcut_settings),
                icon = Icons.Filled.Settings,
                onClick = { onNavigate(GameLingoRoutes.SETTINGS) },
                modifier = Modifier.weight(1f),
            )
            ShortcutCard(
                label = stringResource(R.string.home_shortcut_history),
                icon = Icons.AutoMirrored.Filled.List,
                onClick = { onNavigate(GameLingoRoutes.HISTORY) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = GlSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = GlColors.TextPrimary,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = GlColors.TextSecondary,
            )
        }
        GlTextButton(text = actionLabel, onClick = onAction)
    }
}

@Composable
private fun ShortcutCard(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlCard(
        modifier = modifier,
        onClick = onClick,
        contentPadding = PaddingValues(GlSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon = icon, size = 36.dp)
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = GlColors.TextPrimary,
                modifier = Modifier.padding(start = GlSpacing.md),
            )
        }
    }
}
