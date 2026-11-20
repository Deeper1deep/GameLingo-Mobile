package com.gamelingo.mobile.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
    }
}
