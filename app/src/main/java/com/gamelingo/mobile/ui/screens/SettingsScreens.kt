package com.gamelingo.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamelingo.mobile.R
import com.gamelingo.mobile.data.settings.AiMode
import com.gamelingo.mobile.data.settings.AppSettings
import com.gamelingo.mobile.ui.navigation.SettingsViewModel

@Composable
fun TranslationSettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsColumn {
        Card(modifier = Modifier.fillMaxWidth()) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.translation_target_dialect)) },
                supportingContent = { Text(stringResource(R.string.translation_dialect_egyptian)) },
            )
        }
        SwitchCard(
            title = stringResource(R.string.translation_show_original),
            checked = settings.showOriginalText,
            onCheckedChange = viewModel::setShowOriginalText,
        )
    }
}

@Composable
fun OcrSettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsColumn {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.ocr_capture_interval),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(
                        R.string.ocr_capture_interval_value,
                        settings.captureIntervalMs,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = settings.captureIntervalMs.toFloat(),
                    onValueChange = { viewModel.setCaptureIntervalMs(it.toLong()) },
                    valueRange = AppSettings.MIN_CAPTURE_INTERVAL_MS.toFloat()..
                        AppSettings.MAX_CAPTURE_INTERVAL_MS.toFloat(),
                )
            }
        }
        SwitchCard(
            title = stringResource(R.string.ocr_region_only),
            subtitle = stringResource(R.string.ocr_region_hint),
            checked = settings.restrictToTextRegion,
            onCheckedChange = viewModel::setRestrictToTextRegion,
        )
    }
}

@Composable
fun AiSettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsColumn {
        Text(
            text = stringResource(R.string.ai_mode_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Column(modifier = Modifier.selectableGroup()) {
            AiMode.entries.forEach { mode ->
                val labelRes = when (mode) {
                    AiMode.AUTO -> R.string.ai_mode_auto
                    AiMode.ONLINE -> R.string.ai_mode_online
                    AiMode.LOCAL -> R.string.ai_mode_local
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = settings.aiMode == mode,
                            onClick = { viewModel.setAiMode(mode) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = settings.aiMode == mode,
                        onClick = null,
                    )
                    Text(
                        text = stringResource(labelRes),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.ai_no_keys_hint),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun OverlaySettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsColumn {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.overlay_opacity),
                    style = MaterialTheme.typography.titleMedium,
                )
                Slider(
                    value = settings.overlayOpacity,
                    onValueChange = viewModel::setOverlayOpacity,
                    valueRange = 0.2f..1f,
                )
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.overlay_text_size),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(
                        R.string.overlay_text_size_value,
                        settings.overlayTextSizeSp,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = settings.overlayTextSizeSp.toFloat(),
                    onValueChange = { viewModel.setOverlayTextSizeSp(it.toInt()) },
                    valueRange = AppSettings.MIN_OVERLAY_TEXT_SIZE_SP.toFloat()..
                        AppSettings.MAX_OVERLAY_TEXT_SIZE_SP.toFloat(),
                )
            }
        }
    }
}

@Composable
private fun SettingsColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}

@Composable
private fun SwitchCard(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = subtitle?.let { { Text(it) } },
            trailingContent = {
                Switch(checked = checked, onCheckedChange = onCheckedChange)
            },
        )
    }
}
