package com.gamelingo.mobile.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamelingo.mobile.R
import com.gamelingo.mobile.data.settings.SettingsConstraints
import com.gamelingo.mobile.data.settings.TextRegionPreset
import com.gamelingo.mobile.ui.components.RadioGroupCard
import com.gamelingo.mobile.ui.components.SectionHeader
import com.gamelingo.mobile.ui.components.SettingsColumn
import com.gamelingo.mobile.ui.components.SliderCard
import com.gamelingo.mobile.ui.components.SwitchCard
import com.gamelingo.mobile.ui.navigation.SettingsViewModel
import kotlin.math.roundToInt

@Composable
fun OcrSettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsColumn {
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
    }
}
