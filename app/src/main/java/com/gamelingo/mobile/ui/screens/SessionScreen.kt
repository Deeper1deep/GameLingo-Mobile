package com.gamelingo.mobile.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamelingo.mobile.R
import com.gamelingo.mobile.core.session.SessionNotice
import com.gamelingo.mobile.core.session.SessionState
import com.gamelingo.mobile.core.status.SessionPhase
import com.gamelingo.mobile.core.status.formatElapsed
import com.gamelingo.mobile.core.status.resolveEngineReadiness
import com.gamelingo.mobile.core.status.resolveSessionPhase
import com.gamelingo.mobile.ui.components.GlButton
import com.gamelingo.mobile.ui.components.GlButtonStyle
import com.gamelingo.mobile.ui.components.GlCard
import com.gamelingo.mobile.ui.components.LogoMark
import com.gamelingo.mobile.ui.components.SkeletonBox
import com.gamelingo.mobile.ui.components.StatusChip
import com.gamelingo.mobile.ui.components.StatusTile
import com.gamelingo.mobile.ui.components.StatusTone
import com.gamelingo.mobile.ui.navigation.SessionViewModel
import com.gamelingo.mobile.ui.navigation.SettingsViewModel
import com.gamelingo.mobile.ui.system.rememberSessionController
import com.gamelingo.mobile.ui.theme.GlColors
import com.gamelingo.mobile.ui.theme.GlSpacing
import kotlinx.coroutines.delay

/**
 * Live view of the running session: the English text the OCR found, its
 * Arabic translation, the engine that produced it, elapsed time and the
 * number of translated lines. Everything shown comes from the real session
 * bus; nothing is simulated.
 */
@Composable
fun SessionScreen(
    sessionViewModel: SessionViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val sessionState by sessionViewModel.sessionState.collectAsStateWithLifecycle()
    val latestLine by sessionViewModel.latestLine.collectAsStateWithLifecycle()
    val translated by sessionViewModel.translatedLine.collectAsStateWithLifecycle()
    val failure by sessionViewModel.translationFailure.collectAsStateWithLifecycle()
    val notice by sessionViewModel.notice.collectAsStateWithLifecycle()
    val startedAt by sessionViewModel.startedAtMillis.collectAsStateWithLifecycle()
    val count by sessionViewModel.translatedCount.collectAsStateWithLifecycle()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val controller = rememberSessionController()

    val phase = resolveSessionPhase(sessionState, latestLine, translated, failure)
    val running = sessionState == SessionState.RUNNING
    val readiness = resolveEngineReadiness(
        mode = settings.aiMode,
        cloudOptIn = settings.cloudTranslationOptIn,
        hasApiKey = settings.onlineApiKey.isNotBlank(),
    )

    // Ticks once a second while a session runs; stops ticking when it ends.
    val elapsedMillis by produceState(initialValue = 0L, key1 = startedAt) {
        val start = startedAt
        if (start == null) {
            value = 0L
        } else {
            while (true) {
                value = System.currentTimeMillis() - start
                delay(1_000L)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(GlSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(GlSpacing.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.nav_session),
                style = MaterialTheme.typography.headlineMedium,
                color = GlColors.TextPrimary,
            )
            StatusChip(
                label = phaseLabel(phase),
                tone = phaseTone(phase),
                pulsing = phaseIsLive(phase),
            )
        }

        if (!running) {
            IdleCard(
                isError = phase == SessionPhase.ERROR,
                onStart = controller.start,
            )
            return@Column
        }

        // Duration and count
        Row(
            modifier = Modifier.height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(GlSpacing.md),
        ) {
            MetricCard(
                label = stringResource(R.string.session_duration),
                value = formatElapsed(elapsedMillis),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            MetricCard(
                label = stringResource(R.string.session_count),
                value = count.toString(),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }

        val line = latestLine
        val current = translated
        val translationIsCurrent = current != null &&
            (line == null || current.capturedAtMillis >= line.capturedAtMillis)

        // English text found by OCR
        GlCard {
            Text(
                text = stringResource(R.string.session_original_label),
                style = MaterialTheme.typography.labelMedium,
                color = GlColors.TextSecondary,
            )
            AnimatedContent(
                targetState = line?.text ?: current?.original,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                label = "original",
            ) { text ->
                Text(
                    text = text ?: stringResource(R.string.session_waiting_text),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (text == null) GlColors.TextSecondary else GlColors.TextPrimary,
                    modifier = Modifier.padding(top = GlSpacing.xs),
                )
            }
        }

        // Arabic translation
        GlCard(elevated = true) {
            Text(
                text = stringResource(R.string.session_translation_label),
                style = MaterialTheme.typography.labelMedium,
                color = GlColors.Secondary,
            )
            when {
                phase == SessionPhase.ERROR && failure != null -> Text(
                    text = stringResource(R.string.home_translation_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlColors.Error,
                    modifier = Modifier.padding(top = GlSpacing.xs),
                )
                phase == SessionPhase.TRANSLATING -> Column(
                    modifier = Modifier.padding(top = GlSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(GlSpacing.sm),
                ) {
                    SkeletonBox(height = 18.dp)
                    SkeletonBox(modifier = Modifier.fillMaxWidth(0.6f), height = 18.dp)
                }
                else -> AnimatedContent(
                    targetState = if (translationIsCurrent) current?.translated else null,
                    transitionSpec = {
                        (fadeIn(tween(240)) + scaleIn(initialScale = 0.96f, animationSpec = tween(240))) togetherWith
                            fadeOut(tween(120))
                    },
                    label = "translation",
                ) { text ->
                    Text(
                        text = text ?: stringResource(R.string.session_waiting_translation),
                        style = MaterialTheme.typography.titleLarge,
                        color = if (text == null) GlColors.TextSecondary else GlColors.TextPrimary,
                        modifier = Modifier.padding(top = GlSpacing.xs),
                    )
                }
            }
            if (translationIsCurrent && current != null) {
                Text(
                    text = stringResource(
                        R.string.session_engine,
                    ) + ": " + engineIdLabel(current.engineId) + " · " + stringResource(
                        if (current.fromCache) R.string.session_from_cache else R.string.history_cached_no,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = GlColors.TextSecondary,
                    modifier = Modifier.padding(top = GlSpacing.sm),
                )
            }
        }

        // OCR and AI status
        Row(
            modifier = Modifier.height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(GlSpacing.md),
        ) {
            StatusTile(
                icon = Icons.Filled.Search,
                title = stringResource(R.string.tile_ocr),
                value = stringResource(R.string.value_ocr_active),
                tone = StatusTone.Success,
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

        notice?.let {
            Text(
                text = stringResource(
                    when (it) {
                        SessionNotice.ONLINE_NOT_CONFIGURED -> R.string.overlay_notice_online_not_configured
                        SessionNotice.FELL_BACK_TO_LOCAL -> R.string.overlay_notice_fell_back_local
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = GlColors.Accent,
            )
        }

        GlButton(
            text = stringResource(R.string.session_stop),
            onClick = controller.stop,
            style = GlButtonStyle.Danger,
            icon = Icons.Filled.Close,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.session_overlay_hint),
            style = MaterialTheme.typography.bodySmall,
            color = GlColors.TextSecondary,
        )
    }
}

@Composable
private fun IdleCard(isError: Boolean, onStart: () -> Unit) {
    GlCard(elevated = true, contentPadding = PaddingValues(GlSpacing.xl)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(GlSpacing.md),
        ) {
            LogoMark(size = 72.dp)
            Text(
                text = stringResource(R.string.session_idle_title),
                style = MaterialTheme.typography.titleLarge,
                color = GlColors.TextPrimary,
            )
            Text(
                text = stringResource(
                    if (isError) R.string.phase_error_desc else R.string.session_idle_body,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = GlColors.TextSecondary,
            )
            GlButton(
                text = stringResource(R.string.home_start),
                onClick = onStart,
                icon = Icons.Filled.PlayArrow,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    GlCard(modifier = modifier, contentPadding = PaddingValues(GlSpacing.md)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = GlColors.TextSecondary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = GlColors.TextPrimary,
        )
    }
}
