package com.gamelingo.mobile.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

        Text(
            text = stringResource(R.string.ai_provider_note),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
