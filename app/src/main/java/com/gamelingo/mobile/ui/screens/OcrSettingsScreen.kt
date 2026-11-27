package com.gamelingo.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamelingo.mobile.R
import com.gamelingo.mobile.core.session.SessionState
import com.gamelingo.mobile.data.settings.SettingsConstraints
import com.gamelingo.mobile.data.settings.TextRegionPreset
import com.gamelingo.mobile.ui.components.GlCard
import com.gamelingo.mobile.ui.components.GlTextButton
import com.gamelingo.mobile.ui.components.HintText
import com.gamelingo.mobile.ui.components.RadioGroupCard
import com.gamelingo.mobile.ui.components.SectionHeader
import com.gamelingo.mobile.ui.components.SettingsColumn
import com.gamelingo.mobile.ui.components.SliderCard
import com.gamelingo.mobile.ui.components.StatusChip
import com.gamelingo.mobile.ui.components.StatusTone
import com.gamelingo.mobile.ui.components.SwitchCard
import com.gamelingo.mobile.ui.navigation.SessionViewModel
import com.gamelingo.mobile.ui.navigation.SettingsViewModel
import com.gamelingo.mobile.ui.system.rememberSessionController
import com.gamelingo.mobile.ui.theme.GlColors
import com.gamelingo.mobile.ui.theme.GlShapes
import com.gamelingo.mobile.ui.theme.GlSpacing
import kotlin.math.roundToInt

@Composable
fun OcrSettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    sessionViewModel: SessionViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val sessionState by sessionViewModel.sessionState.collectAsStateWithLifecycle()
    val controller = rememberSessionController()
    val region = if (settings.restrictToTextRegion) {
        settings.effectiveRegion
    } else {
        SettingsConstraints.FULL_SCREEN_REGION
    }

    SettingsColumn {
        // Real status of the capture pipeline and its permissions
        SectionHeader(stringResource(R.string.ocr_status_title))
        GlCard {
            Text(
                text = stringResource(R.string.ocr_projection),
                style = MaterialTheme.typography.labelMedium,
                color = GlColors.TextSecondary,
            )
            StatusChip(
                label = stringResource(
                    when (sessionState) {
                        SessionState.RUNNING -> R.string.ocr_projection_active
                        SessionState.STOPPED -> R.string.ocr_projection_inactive
                        SessionState.ERROR -> R.string.ocr_projection_error
                    },
                ),
                tone = when (sessionState) {
                    SessionState.RUNNING -> StatusTone.Success
                    SessionState.STOPPED -> StatusTone.Neutral
                    SessionState.ERROR -> StatusTone.Error
                },
                pulsing = sessionState == SessionState.RUNNING,
                modifier = Modifier.padding(top = GlSpacing.xs, bottom = GlSpacing.md),
            )
            Text(
                text = stringResource(R.string.ocr_permissions_title),
                style = MaterialTheme.typography.labelMedium,
                color = GlColors.TextSecondary,
            )
            PermissionStateRow(
                title = stringResource(R.string.perm_overlay),
                granted = controller.permissions.overlayGranted,
                actionLabel = stringResource(R.string.settings_permissions_manage),
                onAction = controller.openOverlaySettings,
            )
            PermissionStateRow(
                title = stringResource(R.string.perm_notifications),
                granted = controller.permissions.notificationsGranted,
                actionLabel = stringResource(R.string.perm_grant),
                onAction = controller.requestNotifications,
            )
        }

        // Where on the screen the OCR looks (driven by the real settings)
        SectionHeader(stringResource(R.string.ocr_region_preview_title))
        GlCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RegionPreview(top = region.top, bottom = region.bottom)
                Text(
                    text = if (!settings.restrictToTextRegion) {
                        stringResource(R.string.ocr_region_preview_off)
                    } else if (region.top <= 0f && region.bottom >= 1f) {
                        stringResource(R.string.ocr_region_preview_full)
                    } else {
                        stringResource(
                            R.string.ocr_region_preview_band,
                            (region.top * 100).roundToInt(),
                            (region.bottom * 100).roundToInt(),
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlColors.TextPrimary,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = GlSpacing.lg),
                )
            }
        }

        SliderCard(
            title = stringResource(R.string.ocr_capture_interval),
            valueLabel = stringResource(
                R.string.ocr_capture_interval_value,
                settings.captureIntervalMs,
            ),
            value = settings.captureIntervalMs.toFloat(),
            valueRange = SettingsConstraints.MIN_CAPTURE_INTERVAL_MS.toFloat()..
                SettingsConstraints.MAX_CAPTURE_INTERVAL_MS.toFloat(),
            onValueChange = { viewModel.setCaptureIntervalMs(it.toLong()) },
            subtitle = stringResource(R.string.ocr_capture_interval_hint),
        )

        SwitchCard(
            title = stringResource(R.string.ocr_region_only),
            subtitle = stringResource(R.string.ocr_region_hint),
            checked = settings.restrictToTextRegion,
            onCheckedChange = viewModel::setRestrictToTextRegion,
        )

        SwitchCard(
            title = stringResource(R.string.ocr_smart_detection),
            subtitle = stringResource(R.string.ocr_smart_detection_hint),
            checked = settings.smartTextDetection,
            onCheckedChange = viewModel::setSmartTextDetection,
        )

        CustomRegionSection(settings, viewModel)

        if (settings.restrictToTextRegion) {
            RadioGroupCard(
                title = stringResource(R.string.ocr_region_preset),
                options = TextRegionPreset.entries,
                selected = settings.regionPreset,
                optionLabel = { preset ->
                    stringResource(
                        when (preset) {
                            TextRegionPreset.FULL_SCREEN -> R.string.ocr_region_full
                            TextRegionPreset.LOWER_THIRD -> R.string.ocr_region_lower
                            TextRegionPreset.CUSTOM -> R.string.ocr_region_custom
                        },
                    )
                },
                onSelect = viewModel::setRegionPreset,
            )
        }

        if (settings.restrictToTextRegion &&
            settings.regionPreset == TextRegionPreset.CUSTOM
        ) {
            SliderCard(
                title = stringResource(R.string.ocr_region_top),
                valueLabel = stringResource(
                    R.string.ocr_region_percent,
                    (settings.regionTopFraction * 100).roundToInt(),
                ),
                value = settings.regionTopFraction,
                valueRange = 0f..(1f - SettingsConstraints.MIN_REGION_SPAN),
                onValueChange = viewModel::setRegionTopFraction,
            )
            SliderCard(
                title = stringResource(R.string.ocr_region_bottom),
                valueLabel = stringResource(
                    R.string.ocr_region_percent,
                    (settings.regionBottomFraction * 100).roundToInt(),
                ),
                value = settings.regionBottomFraction,
                valueRange = SettingsConstraints.MIN_REGION_SPAN..1f,
                onValueChange = viewModel::setRegionBottomFraction,
            )
        }

        SectionHeader(stringResource(R.string.ocr_accuracy_section))
        SwitchCard(
            title = stringResource(R.string.ocr_dedup),
            subtitle = stringResource(R.string.ocr_dedup_hint),
            checked = settings.dedupEnabled,
            onCheckedChange = viewModel::setDedupEnabled,
        )
        SwitchCard(
            title = stringResource(R.string.ocr_ignore_unstable),
            subtitle = stringResource(R.string.ocr_ignore_unstable_hint),
            checked = settings.ignoreUnstableText,
            onCheckedChange = viewModel::setIgnoreUnstableText,
        )
        SwitchCard(
            title = stringResource(R.string.ocr_battery_saver),
            subtitle = stringResource(R.string.ocr_battery_saver_hint),
            checked = settings.batterySaverEnabled,
            onCheckedChange = viewModel::setBatterySaverEnabled,
        )
        val context = androidx.compose.ui.platform.LocalContext.current
        var experiment by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(com.gamelingo.mobile.capture.OcrExperiment.get(context))
        }
        OcrExperimentCard(selected = experiment, onSelect = {
            experiment = it
            com.gamelingo.mobile.capture.OcrExperiment.set(context, it)
        })
    }
}

/** Experimental image preprocessing for OCR. Off by default; "off" is the reset. */
@Composable
fun OcrExperimentCard(
    selected: com.gamelingo.mobile.capture.OcrExperiment.Mode,
    onSelect: (com.gamelingo.mobile.capture.OcrExperiment.Mode) -> Unit,
) {
    SectionHeader(stringResource(R.string.ocr_experiment_title))
    com.gamelingo.mobile.ui.components.GlCard {
        com.gamelingo.mobile.ui.components.SegmentedSelector(
            options = com.gamelingo.mobile.capture.OcrExperiment.Mode.entries,
            selected = selected,
            optionLabel = { m ->
                stringResource(
                    when (m) {
                        com.gamelingo.mobile.capture.OcrExperiment.Mode.OFF -> R.string.ocr_experiment_off
                        com.gamelingo.mobile.capture.OcrExperiment.Mode.CONTRAST -> R.string.ocr_experiment_contrast
                        com.gamelingo.mobile.capture.OcrExperiment.Mode.SHARPEN -> R.string.ocr_experiment_sharpen
                        com.gamelingo.mobile.capture.OcrExperiment.Mode.THRESHOLD -> R.string.ocr_experiment_threshold
                    },
                )
            },
            onSelect = onSelect,
        )
        com.gamelingo.mobile.ui.components.HintText(
            text = stringResource(R.string.ocr_experiment_hint),
            modifier = androidx.compose.ui.Modifier.padding(top = GlSpacing.md),
        )
    }
}

@Composable
private fun PermissionStateRow(
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
            style = MaterialTheme.typography.bodyMedium,
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

/** A tiny phone outline with the scanned band highlighted. */
@Composable
private fun RegionPreview(top: Float, bottom: Float) {
    val topWeight = top.coerceIn(0f, 1f)
    val bandWeight = (bottom - top).coerceIn(0.01f, 1f)
    val bottomWeight = (1f - bottom).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .width(72.dp)
            .height(128.dp)
            .clip(GlShapes.Small)
            .background(GlColors.Background)
            .border(2.dp, GlColors.OutlineStrong, GlShapes.Small),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (topWeight > 0.001f) Spacer(Modifier.weight(topWeight))
            Box(
                modifier = Modifier
                    .weight(bandWeight)
                    .fillMaxWidth()
                    .background(GlColors.Secondary.copy(alpha = 0.28f))
                    .border(1.dp, GlColors.Secondary),
            )
            if (bottomWeight > 0.001f) Spacer(Modifier.weight(bottomWeight))
        }
    }
}
