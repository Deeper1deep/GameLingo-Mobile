package com.gamelingo.mobile.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import com.gamelingo.mobile.translate.local.LocalAiModelState
import com.gamelingo.mobile.translate.local.LocalModelManager
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

        LocalAiSection(viewModel)
    }
}

/** Local AI engine section: model cards, download state, dictionary, tests. */
@Composable
private fun LocalAiSection(viewModel: SettingsViewModel) {
    val states by viewModel.localAiStates.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val testResult by viewModel.localTestResult.collectAsStateWithLifecycle()
    val testRunning by viewModel.localTestRunning.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshLocalAiStates() }

    SectionHeader(stringResource(R.string.local_ai_section))

    SwitchCard(
        title = stringResource(R.string.local_ai_wifi_only),
        subtitle = stringResource(R.string.local_ai_wifi_only_hint),
        checked = settings.localAiWifiOnly,
        onCheckedChange = viewModel::setLocalAiWifiOnly,
    )
    SwitchCard(
        title = stringResource(R.string.local_ai_auto_fallback),
        subtitle = stringResource(R.string.local_ai_auto_fallback_hint),
        checked = settings.autoOnlineFallback,
        onCheckedChange = viewModel::setAutoOnlineFallback,
    )

    for (spec in viewModel.localModelSpecs()) {
        LocalModelCard(
            spec = spec,
            state = states[spec.id] ?: LocalAiModelState.NotInstalled,
            onDownload = { viewModel.downloadLocalModel(spec.id) },
            onDelete = { viewModel.deleteLocalModel(spec.id) },
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.local_ai_dictionary_version, viewModel.dictionaryVersion()),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = { viewModel.testLocalTranslation() },
                enabled = !testRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text(
                    stringResource(
                        if (testRunning) R.string.local_ai_test_running else R.string.local_ai_test_button,
                    ),
                )
            }
            testResult?.let { result ->
                if (result.error != null) {
                    Text(
                        text = stringResource(R.string.local_ai_test_error, result.error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    Text(
                        text = result.translatedText,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        text = stringResource(
                            R.string.local_ai_test_meta,
                            result.engineId,
                            result.elapsedMs,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalModelCard(
    spec: LocalModelManager.ModelSpec,
    state: LocalAiModelState,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier
        .fillMaxWidth()
        .padding(top = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(spec.modelName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.local_ai_license, spec.license),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val totalBytes = spec.files.sumOf { it.size }
            Text(
                text = stringResource(R.string.local_ai_size, totalBytes / (1024 * 1024)),
                style = MaterialTheme.typography.bodySmall,
            )
            when (state) {
                is LocalAiModelState.NotInstalled -> {
                    Text(
                        text = stringResource(R.string.local_ai_not_installed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    OutlinedButton(onClick = onDownload, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(R.string.local_ai_download))
                    }
                }
                is LocalAiModelState.Downloading -> {
                    val progress = if (state.totalBytes > 0) {
                        state.downloadedBytes.toFloat() / state.totalBytes.toFloat()
                    } else {
                        0f
                    }
                    Text(
                        text = stringResource(
                            R.string.local_ai_downloading,
                            state.fileIndex,
                            state.fileCount,
                            (progress * 100).toInt(),
                            ((state.totalBytes - state.downloadedBytes).coerceAtLeast(0L) / (1024 * 1024)),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
                is LocalAiModelState.Verifying -> {
                    Text(
                        text = stringResource(R.string.local_ai_verifying),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                is LocalAiModelState.Installed -> {
                    Text(
                        text = stringResource(
                            R.string.local_ai_installed,
                            state.version,
                            state.sizeBytes / (1024 * 1024),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    OutlinedButton(onClick = onDelete, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(R.string.local_ai_delete))
                    }
                }
                is LocalAiModelState.Failed -> {
                    Text(
                        text = stringResource(R.string.local_ai_failed, state.reason),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    OutlinedButton(onClick = onDownload, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(R.string.local_ai_retry))
                    }
                }
            }
        }
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
