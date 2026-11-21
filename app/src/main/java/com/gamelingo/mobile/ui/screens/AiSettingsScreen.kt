package com.gamelingo.mobile.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamelingo.mobile.R
import com.gamelingo.mobile.core.status.resolveEngineReadiness
import com.gamelingo.mobile.data.settings.AiMode
import com.gamelingo.mobile.data.settings.SettingsConstraints
import com.gamelingo.mobile.translate.AiTestResult
import com.gamelingo.mobile.ui.components.GlButton
import com.gamelingo.mobile.ui.components.GlButtonStyle
import com.gamelingo.mobile.ui.components.GlCard
import com.gamelingo.mobile.ui.components.GlDialog
import com.gamelingo.mobile.ui.components.GlTextButton
import com.gamelingo.mobile.ui.components.HintText
import com.gamelingo.mobile.ui.components.SectionHeader
import com.gamelingo.mobile.ui.components.SegmentedSelector
import com.gamelingo.mobile.ui.components.SettingsColumn
import com.gamelingo.mobile.ui.components.SkeletonBox
import com.gamelingo.mobile.ui.components.SliderCard
import com.gamelingo.mobile.ui.components.StatusChip
import com.gamelingo.mobile.ui.components.StatusTone
import com.gamelingo.mobile.ui.components.SwitchCard
import com.gamelingo.mobile.ui.components.glTextFieldColors
import com.gamelingo.mobile.ui.navigation.AiEngineViewModel
import com.gamelingo.mobile.ui.navigation.AiTestUiState
import com.gamelingo.mobile.ui.navigation.SessionViewModel
import com.gamelingo.mobile.ui.navigation.SettingsViewModel
import com.gamelingo.mobile.ui.navigation.StatusViewModel
import com.gamelingo.mobile.ui.theme.GlColors
import com.gamelingo.mobile.ui.theme.GlSpacing

@Composable
fun AiSettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    engineViewModel: AiEngineViewModel = hiltViewModel(),
    statusViewModel: StatusViewModel = hiltViewModel(),
    sessionViewModel: SessionViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val test by engineViewModel.test.collectAsStateWithLifecycle()
    val online by statusViewModel.online.collectAsStateWithLifecycle()
    val translated by sessionViewModel.translatedLine.collectAsStateWithLifecycle()
    var keyVisible by remember { mutableStateOf(false) }
    var showCloudExplainer by remember { mutableStateOf(false) }
    var explainerShown by remember { mutableStateOf(false) }

    val hasKey = settings.onlineApiKey.isNotBlank()
    val readiness = resolveEngineReadiness(settings.aiMode, settings.cloudTranslationOptIn, hasKey)

    // The moment a key first exists without cloud consent is the natural time
    // for the one-time explainer: entering a key signals intent to go online.
    LaunchedEffect(hasKey, settings.cloudTranslationOptIn) {
        if (hasKey && !settings.cloudTranslationOptIn && !explainerShown) {
            explainerShown = true
            showCloudExplainer = true
        }
    }

    if (showCloudExplainer) {
        GlDialog(
            title = stringResource(R.string.ai_cloud_dialog_title),
            onDismiss = { showCloudExplainer = false },
            confirmText = stringResource(R.string.ai_cloud_dialog_accept),
            dismissText = stringResource(R.string.ai_cloud_dialog_later),
            onConfirm = {
                viewModel.setCloudTranslationOptIn(true)
                showCloudExplainer = false
            },
        ) {
            Text(
                text = stringResource(R.string.ai_cloud_dialog_body),
                style = MaterialTheme.typography.bodyMedium,
                color = GlColors.TextSecondary,
            )
        }
    }

    SettingsColumn {
        // Live status
        SectionHeader(stringResource(R.string.ai_status_title))
        GlCard {
            StatusLine(
                label = stringResource(R.string.ai_current_engine),
                chip = engineReadinessLong(readiness),
                tone = engineReadinessTone(readiness),
            )
            StatusLine(
                label = stringResource(R.string.ai_connection),
                chip = stringResource(if (online) R.string.value_online else R.string.value_offline),
                tone = if (online) StatusTone.Success else StatusTone.Neutral,
            )
            StatusLine(
                label = stringResource(R.string.ai_api_status),
                chip = stringResource(
                    when {
                        !hasKey -> R.string.api_status_no_key
                        !settings.cloudTranslationOptIn -> R.string.api_status_no_consent
                        else -> R.string.api_status_ready
                    },
                ),
                tone = if (hasKey && settings.cloudTranslationOptIn) {
                    StatusTone.Success
                } else {
                    StatusTone.Neutral
                },
            )
            translated?.let {
                HintText(
                    text = stringResource(R.string.session_engine) + ": " + engineIdLabel(it.engineId),
                    modifier = Modifier.padding(top = GlSpacing.sm),
                )
            }
        }

        // Mode
        SectionHeader(stringResource(R.string.ai_mode_title))
        GlCard {
            SegmentedSelector(
                options = AiMode.entries,
                selected = settings.aiMode,
                optionLabel = { mode ->
                    stringResource(
                        when (mode) {
                            AiMode.AUTO -> R.string.ai_mode_auto_short
                            AiMode.ONLINE -> R.string.ai_mode_online_short
                            AiMode.LOCAL -> R.string.ai_mode_local_short
                        },
                    )
                },
                onSelect = viewModel::setAiMode,
            )
            Text(
                text = stringResource(
                    when (settings.aiMode) {
                        AiMode.AUTO -> R.string.ai_mode_auto
                        AiMode.ONLINE -> R.string.ai_mode_online
                        AiMode.LOCAL -> R.string.ai_mode_local
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = GlColors.TextPrimary,
                modifier = Modifier.padding(top = GlSpacing.md),
            )
            HintText(
                text = stringResource(R.string.ai_local_quality_note),
                modifier = Modifier.padding(top = GlSpacing.xs),
            )
        }

        // Key
        SectionHeader(stringResource(R.string.ai_key_section))
        GlCard {
            Text(
                text = stringResource(R.string.ai_api_key),
                style = MaterialTheme.typography.titleMedium,
                color = GlColors.TextPrimary,
            )
            OutlinedTextField(
                value = settings.onlineApiKey,
                onValueChange = viewModel::setOnlineApiKey,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = GlSpacing.sm),
                singleLine = true,
                colors = glTextFieldColors(),
                shape = MaterialTheme.shapes.medium,
                visualTransformation = if (keyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                placeholder = { Text(stringResource(R.string.ai_api_key_placeholder)) },
                supportingText = {
                    Text(
                        stringResource(
                            if (hasKey) R.string.ai_api_key_set else R.string.ai_api_key_empty,
                        ),
                    )
                },
                trailingIcon = {
                    GlTextButton(
                        text = stringResource(
                            if (keyVisible) R.string.ai_key_hide else R.string.ai_key_show,
                        ),
                        onClick = { keyVisible = !keyVisible },
                    )
                },
            )
            HintText(stringResource(R.string.ai_api_key_hint))
            HintText(
                text = stringResource(
                    if (settings.cloudTranslationOptIn) {
                        R.string.ai_cloud_status_on
                    } else {
                        R.string.ai_cloud_status_off
                    },
                ),
                modifier = Modifier.padding(top = GlSpacing.sm),
            )
        }

        SwitchCard(
            title = stringResource(R.string.ai_cloud_switch_title),
            subtitle = stringResource(R.string.ai_cloud_switch_subtitle),
            checked = settings.cloudTranslationOptIn,
            onCheckedChange = { enable ->
                if (enable) {
                    showCloudExplainer = true
                } else {
                    viewModel.setCloudTranslationOptIn(false)
                }
            },
        )

        // Connection test
        SectionHeader(stringResource(R.string.ai_test_title))
        GlCard {
            HintText(stringResource(R.string.ai_test_hint))
            Column(modifier = Modifier.padding(top = GlSpacing.md)) {
                GlButton(
                    text = stringResource(R.string.ai_test_button),
                    onClick = engineViewModel::runTest,
                    style = GlButtonStyle.Secondary,
                    icon = Icons.Filled.Refresh,
                    loading = test is AiTestUiState.Running,
                    modifier = Modifier.fillMaxWidth(),
                )
                when (val state = test) {
                    is AiTestUiState.Running -> SkeletonBox(
                        modifier = Modifier.padding(top = GlSpacing.md),
                        height = GlSpacing.lg,
                    )
                    is AiTestUiState.Done -> {
                        val success = state.result is AiTestResult.Success
                        StatusChip(
                            label = aiTestMessage(state.result),
                            tone = if (success) StatusTone.Success else StatusTone.Error,
                            modifier = Modifier.padding(top = GlSpacing.md),
                        )
                    }
                    AiTestUiState.Idle -> Unit
                }
            }
        }

        SliderCard(
            title = stringResource(R.string.ai_timeout),
            valueLabel = stringResource(R.string.ai_timeout_value, settings.requestTimeoutSec),
            value = settings.requestTimeoutSec.toFloat(),
            valueRange = SettingsConstraints.MIN_TIMEOUT_SEC.toFloat()..
                SettingsConstraints.MAX_TIMEOUT_SEC.toFloat(),
            onValueChange = { viewModel.setRequestTimeoutSec(it.toInt()) },
        )

        SectionHeader(stringResource(R.string.ai_quality_section))
        SwitchCard(
            title = stringResource(R.string.ai_cache),
            subtitle = stringResource(R.string.ai_cache_hint),
            checked = settings.cacheEnabled,
            onCheckedChange = viewModel::setCacheEnabled,
        )
        SliderCard(
            title = stringResource(R.string.ai_context_lines),
            valueLabel = stringResource(R.string.ai_context_lines_value, settings.contextLines),
            value = settings.contextLines.toFloat(),
            valueRange = SettingsConstraints.MIN_CONTEXT_LINES.toFloat()..
                SettingsConstraints.MAX_CONTEXT_LINES.toFloat(),
            onValueChange = { viewModel.setContextLines(it.toInt()) },
            subtitle = stringResource(R.string.ai_context_hint),
        )
    }
}

@Composable
private fun StatusLine(label: String, chip: String, tone: StatusTone) {
    Column(modifier = Modifier.padding(bottom = GlSpacing.md)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = GlColors.TextSecondary,
        )
        StatusChip(
            label = chip,
            tone = tone,
            modifier = Modifier.padding(top = GlSpacing.xs),
        )
    }
}

@Composable
private fun aiTestMessage(result: AiTestResult): String = when (result) {
    is AiTestResult.Success -> stringResource(R.string.ai_test_ok, result.elapsedMs.toInt())
    AiTestResult.NoKey -> stringResource(R.string.ai_test_no_key)
    AiTestResult.NoConsent -> stringResource(R.string.ai_test_no_consent)
    AiTestResult.BadKey -> stringResource(R.string.ai_test_bad_key)
    AiTestResult.Quota -> stringResource(R.string.ai_test_quota)
    AiTestResult.Unavailable -> stringResource(R.string.ai_test_unavailable)
    AiTestResult.Timeout -> stringResource(R.string.ai_test_timeout)
    AiTestResult.Network -> stringResource(R.string.ai_test_network)
    AiTestResult.Unknown -> stringResource(R.string.ai_test_unknown)
}
