package com.gamelingo.mobile.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamelingo.mobile.R
import com.gamelingo.mobile.data.settings.AiMode
import com.gamelingo.mobile.data.settings.SettingsConstraints
import com.gamelingo.mobile.ui.components.RadioGroupCard
import com.gamelingo.mobile.ui.components.SectionHeader
import com.gamelingo.mobile.ui.components.SettingsColumn
import com.gamelingo.mobile.ui.components.SliderCard
import com.gamelingo.mobile.ui.components.SwitchCard
import com.gamelingo.mobile.translate.local.LocalAiModelState
import com.gamelingo.mobile.translate.local.LocalModelManager
import com.gamelingo.mobile.ui.navigation.SettingsViewModel

@Composable
fun AiSettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var keyVisible by remember { mutableStateOf(false) }
    var showCloudExplainer by remember { mutableStateOf(false) }
    var explainerShown by remember { mutableStateOf(false) }

    // The moment a key first exists without cloud consent is the natural time
    // for the one-time explainer: entering a key signals intent to go online.
    LaunchedEffect(settings.onlineApiKey.isNotBlank(), settings.cloudTranslationOptIn) {
        if (settings.onlineApiKey.isNotBlank() &&
            !settings.cloudTranslationOptIn &&
            !explainerShown
        ) {
            explainerShown = true
            showCloudExplainer = true
        }
    }

    if (showCloudExplainer) {
        AlertDialog(
            onDismissRequest = { showCloudExplainer = false },
            title = { Text(stringResource(R.string.ai_cloud_dialog_title)) },
            text = { Text(stringResource(R.string.ai_cloud_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setCloudTranslationOptIn(true)
                        showCloudExplainer = false
                    },
                ) {
                    Text(stringResource(R.string.ai_cloud_dialog_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloudExplainer = false }) {
                    Text(stringResource(R.string.ai_cloud_dialog_later))
                }
            },
        )
    }

    SettingsColumn {
        RadioGroupCard(
            title = stringResource(R.string.ai_mode_title),
            options = AiMode.entries,
            selected = settings.aiMode,
            optionLabel = { mode ->
                stringResource(
                    when (mode) {
                        AiMode.AUTO -> R.string.ai_mode_auto
                        AiMode.ONLINE -> R.string.ai_mode_online
                        AiMode.LOCAL -> R.string.ai_mode_local
                    },
                )
            },
            onSelect = viewModel::setAiMode,
        )

        Text(
            text = stringResource(R.string.ai_local_quality_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        SectionHeader(stringResource(R.string.ai_online_section))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.ai_api_key),
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    value = settings.onlineApiKey,
                    onValueChange = viewModel::setOnlineApiKey,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    singleLine = true,
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
                                if (settings.onlineApiKey.isBlank()) {
                                    R.string.ai_api_key_empty
                                } else {
                                    R.string.ai_api_key_set
                                },
                            ),
                        )
                    },
                    trailingIcon = {
                        TextButton(onClick = { keyVisible = !keyVisible }) {
                            Text(
                                stringResource(
                                    if (keyVisible) R.string.ai_key_hide else R.string.ai_key_show,
                                ),
                            )
                        }
                    },
                )
                Text(
                    text = stringResource(R.string.ai_api_key_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(
                        if (settings.cloudTranslationOptIn) {
                            R.string.ai_cloud_status_on
                        } else {
                            R.string.ai_cloud_status_off
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
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

        Text(
            text = stringResource(R.string.ai_provider_note),
            style = MaterialTheme.typography.bodySmall,
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
