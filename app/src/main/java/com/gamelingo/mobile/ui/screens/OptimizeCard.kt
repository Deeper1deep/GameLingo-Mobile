package com.gamelingo.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamelingo.mobile.R
import com.gamelingo.mobile.core.optimize.DeviceSnapshot
import com.gamelingo.mobile.core.optimize.OptimizationProfile
import com.gamelingo.mobile.core.optimize.TuningChange
import com.gamelingo.mobile.core.optimize.TuningField
import com.gamelingo.mobile.data.optimize.OptimizerState
import com.gamelingo.mobile.ui.components.GlButton
import com.gamelingo.mobile.ui.components.GlButtonStyle
import com.gamelingo.mobile.ui.components.GlCard
import com.gamelingo.mobile.ui.components.HintText
import com.gamelingo.mobile.ui.components.SegmentedSelector
import com.gamelingo.mobile.ui.navigation.OptimizeViewModel
import com.gamelingo.mobile.ui.theme.GlColors
import com.gamelingo.mobile.ui.theme.GlSpacing

@Composable
private fun profileLabel(p: OptimizationProfile): String = stringResource(
    when (p) {
        OptimizationProfile.SMART_AUTO -> R.string.optimize_profile_auto
        OptimizationProfile.BEST_QUALITY -> R.string.optimize_profile_quality
        OptimizationProfile.BALANCED -> R.string.optimize_profile_balanced
        OptimizationProfile.PERFORMANCE -> R.string.optimize_profile_performance
        OptimizationProfile.BATTERY_SAVER -> R.string.optimize_profile_battery
    },
)

@Composable
private fun fieldLabel(f: TuningField): String = stringResource(
    when (f) {
        TuningField.CAPTURE_INTERVAL -> R.string.optimize_field_interval
        TuningField.BATTERY_SAVER -> R.string.optimize_field_battery_saver
        TuningField.DEDUP -> R.string.optimize_field_dedup
        TuningField.IGNORE_UNSTABLE -> R.string.optimize_field_unstable
        TuningField.SMART_DETECTION -> R.string.optimize_field_smart
        TuningField.CACHE -> R.string.optimize_field_cache
        TuningField.CONTEXT_LINES -> R.string.optimize_field_context
        TuningField.GEMMA_HD -> R.string.optimize_field_gemma
        TuningField.TIMEOUT -> R.string.optimize_field_timeout
    },
)

@Composable
private fun valueLabel(raw: String): String = when (raw) {
    "true" -> stringResource(R.string.optimize_on)
    "false" -> stringResource(R.string.optimize_off)
    else -> raw
}

@Composable
private fun deviceLine(d: DeviceSnapshot): String {
    val battery = if (d.batteryPercent >= 0) "${d.batteryPercent}%" else stringResource(R.string.optimize_battery_unknown)
    val thermal = stringResource(
        when {
            d.thermalStatus >= 2 -> R.string.optimize_thermal_hot
            d.thermalStatus == 1 -> R.string.optimize_thermal_warm
            else -> R.string.optimize_thermal_normal
        },
    )
    return stringResource(R.string.optimize_device_line, d.totalRamMb, d.cpuCores, battery, thermal)
}

/** One-tap "best settings": analysis, applied report with real before -> after values, restore. */
@Composable
fun OptimizeCard(viewModel: OptimizeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hasBackup by viewModel.hasBackup.collectAsStateWithLifecycle()
    var profile by rememberSaveable { mutableStateOf(OptimizationProfile.SMART_AUTO) }
    val analyzing = state is OptimizerState.Analyzing

    GlCard(elevated = true) {
        Text(
            text = stringResource(R.string.optimize_title),
            style = MaterialTheme.typography.titleMedium,
            color = GlColors.TextPrimary,
        )
        HintText(stringResource(R.string.optimize_hint))
        Column(
            modifier = Modifier.padding(top = GlSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(GlSpacing.sm),
        ) {
            val all = OptimizationProfile.values().toList()
            SegmentedSelector(
                options = all.take(3),
                selected = profile,
                optionLabel = { profileLabel(it) },
                onSelect = { profile = it },
            )
            SegmentedSelector(
                options = all.drop(3),
                selected = profile,
                optionLabel = { profileLabel(it) },
                onSelect = { profile = it },
            )
            GlButton(
                text = stringResource(if (analyzing) R.string.optimize_analyzing else R.string.optimize_button),
                onClick = { if (!analyzing) viewModel.optimize(profile) },
                modifier = Modifier.fillMaxWidth(),
            )
            when (val s = state) {
                is OptimizerState.Applied -> {
                    Text(
                        text = stringResource(R.string.optimize_applied, profileLabel(s.report.applied)),
                        style = MaterialTheme.typography.titleSmall,
                        color = GlColors.TextPrimary,
                    )
                    HintText(deviceLine(s.device))
                    if (s.report.changes.isEmpty()) {
                        HintText(stringResource(R.string.optimize_no_changes))
                    } else {
                        s.report.changes.forEach { c: TuningChange ->
                            HintText(
                                stringResource(
                                    R.string.optimize_change_line,
                                    fieldLabel(c.field), valueLabel(c.before), valueLabel(c.after),
                                ),
                            )
                        }
                    }
                }
                OptimizerState.Restored -> HintText(stringResource(R.string.optimize_restored))
                else -> Unit
            }
            if (hasBackup) {
                GlButton(
                    text = stringResource(R.string.optimize_restore),
                    onClick = { viewModel.restore() },
                    style = GlButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
