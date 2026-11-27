package com.gamelingo.mobile.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.padding
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamelingo.mobile.R
import com.gamelingo.mobile.audio.AsrModelState
import com.gamelingo.mobile.audio.AudioTranslationController
import com.gamelingo.mobile.ui.components.GlCard
import com.gamelingo.mobile.ui.components.HintText
import com.gamelingo.mobile.ui.components.SettingsColumn
import com.gamelingo.mobile.ui.components.SwitchCard
import com.gamelingo.mobile.ui.navigation.AudioViewModel
import com.gamelingo.mobile.ui.theme.GlColors
import com.gamelingo.mobile.ui.theme.GlSpacing

/**
 * Speech translation: everything is off until the user turns it on, and the
 * speech model is downloaded only when the user taps download.
 */
@Composable
fun VoiceTranslationScreen(viewModel: AudioViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val model by viewModel.model.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    VoiceTranslationContent(
        settings = settings, model = model, status = status,
        modelSizeBytes = viewModel.modelSizeBytes, modelLicense = modelLicense,
        onDownload = onDownload, onCancel = onCancel, onDelete = onDelete,
        onWifiOnly = onWifiOnly, onTts = onTts,
        onSpeech = { on ->
            viewModel.setSpeech(on)
            if (on) micPermission.launch(Manifest.permission.RECORD_AUDIO)
        },
    )
}

/** Stateless body, so the screen can be rendered and tested without Hilt. */
@Composable
fun VoiceTranslationContent(
    settings: com.gamelingo.mobile.audio.AudioSettings,
    model: AsrModelState,
    status: AudioTranslationController.Status,
    modelSizeBytes: Long,
    modelLicense: String,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onTts: (Boolean) -> Unit,
    onSpeech: (Boolean) -> Unit,
) {
    val sizeMb = modelSizeBytes / 1_000_000

    SettingsColumn {
        GlCard {
            Text(
                text = stringResource(R.string.voice_status_title),
                style = MaterialTheme.typography.titleMedium,
                color = GlColors.TextPrimary,
            )
            HintText(stringResource(R.string.voice_experimental))
            HintText(
                stringResource(
                    when (status) {
                        AudioTranslationController.Status.OFF -> R.string.voice_state_off
                        AudioTranslationController.Status.NO_MODEL -> R.string.voice_state_no_model
                        AudioTranslationController.Status.NO_PERMISSION -> R.string.voice_state_no_permission
                        AudioTranslationController.Status.LOADING -> R.string.voice_state_loading
                        AudioTranslationController.Status.LISTENING -> R.string.voice_state_listening
                        AudioTranslationController.Status.FAILED -> R.string.voice_state_failed
                    },
                ),
            )
        }

        GlCard {
            Text(
                text = stringResource(R.string.voice_model_title),
                style = MaterialTheme.typography.titleMedium,
                color = GlColors.TextPrimary,
            )
            HintText(stringResource(R.string.voice_model_info, sizeMb, modelLicense))
            when (val m = model) {
                is AsrModelState.NotInstalled, is AsrModelState.Failed -> {
                    if (m is AsrModelState.Failed) HintText(stringResource(R.string.voice_model_failed, m.reason))
                    TextButton(onClick = onDownload, modifier = Modifier.padding(top = GlSpacing.sm)) {
                        Text(stringResource(R.string.voice_model_download, sizeMb))
                    }
                }
                is AsrModelState.Downloading -> {
                    val pct = if (m.totalBytes > 0) (m.downloadedBytes * 100 / m.totalBytes).toInt() else 0
                    HintText(stringResource(R.string.voice_model_downloading, pct))
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.voice_model_cancel)) }
                }
                is AsrModelState.Verifying -> HintText(stringResource(R.string.voice_model_verifying))
                is AsrModelState.Installed -> {
                    HintText(stringResource(R.string.voice_model_installed, m.sizeBytes / 1_000_000))
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.voice_model_delete)) }
                }
            }
            SwitchCard(
                title = stringResource(R.string.voice_wifi_only),
                checked = settings.wifiOnlyDownload,
                onCheckedChange = onWifiOnly,
            )
        }

        SwitchCard(
            title = stringResource(R.string.voice_enable_title),
            subtitle = stringResource(R.string.voice_enable_sub),
            checked = settings.speechEnabled,
            onCheckedChange = onSpeech,
        )
        SwitchCard(
            title = stringResource(R.string.voice_tts_title),
            subtitle = stringResource(R.string.voice_tts_sub),
            checked = settings.ttsEnabled,
            onCheckedChange = onTts,
        )
        GlCard {
            HintText(stringResource(R.string.voice_limits))
        }
    }
}
