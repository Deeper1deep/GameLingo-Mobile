package com.gamelingo.mobile.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.gamelingo.mobile.R
import com.gamelingo.mobile.core.region.RegionSensitivity
import com.gamelingo.mobile.data.settings.AppSettings
import com.gamelingo.mobile.overlay.RegionSelectorService
import com.gamelingo.mobile.ui.components.GlButton
import com.gamelingo.mobile.ui.components.GlCard
import com.gamelingo.mobile.ui.components.GlTextButton
import com.gamelingo.mobile.ui.components.HintText
import com.gamelingo.mobile.ui.components.SegmentedSelector
import com.gamelingo.mobile.ui.components.SwitchCard
import com.gamelingo.mobile.ui.navigation.SettingsViewModel
import com.gamelingo.mobile.ui.theme.GlSpacing

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Settings block of the custom translation area (off by default). */
@Composable
fun CustomRegionSection(settings: AppSettings, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val hasRegion = settings.customRegions.portrait != null || settings.customRegions.landscape != null

    SwitchCard(
        title = stringResource(R.string.region_title),
        subtitle = stringResource(R.string.region_explain),
        checked = settings.customRegionEnabled,
        onCheckedChange = viewModel::setCustomRegionEnabled,
    )
    if (!settings.customRegionEnabled) return

    GlCard {
        Column(verticalArrangement = Arrangement.spacedBy(GlSpacing.sm)) {
            HintText(
                stringResource(if (hasRegion) R.string.region_status_saved else R.string.region_status_none),
            )
            GlButton(
                text = stringResource(if (hasRegion) R.string.region_edit else R.string.region_select),
                onClick = {
                    if (!Settings.canDrawOverlays(context)) {
                        Toast.makeText(context, R.string.region_need_overlay_permission, Toast.LENGTH_LONG).show()
                    } else {
                        context.startService(RegionSelectorService.startIntent(context))
                        // Step aside so the game (or the screen to frame) is visible.
                        context.findActivity()?.moveTaskToBack(true)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (hasRegion) {
                GlTextButton(
                    text = stringResource(R.string.region_use_full_screen),
                    onClick = {
                        viewModel.setCustomRegion(landscape = false, region = null)
                        viewModel.setCustomRegion(landscape = true, region = null)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    SwitchCard(
        title = stringResource(R.string.region_show_border),
        subtitle = stringResource(R.string.region_show_border_hint),
        checked = settings.showRegionBorder,
        onCheckedChange = viewModel::setShowRegionBorder,
    )
    SwitchCard(
        title = stringResource(R.string.region_preview),
        subtitle = stringResource(R.string.region_preview_hint),
        checked = settings.regionPreview,
        onCheckedChange = viewModel::setRegionPreview,
    )
    GlCard {
        Column(verticalArrangement = Arrangement.spacedBy(GlSpacing.sm)) {
            HintText(stringResource(R.string.region_sensitivity))
            SegmentedSelector(
                options = RegionSensitivity.entries,
                selected = settings.regionSensitivity,
                optionLabel = {
                    stringResource(
                        when (it) {
                            RegionSensitivity.STRICT -> R.string.region_sens_strict
                            RegionSensitivity.BALANCED -> R.string.region_sens_balanced
                            RegionSensitivity.LENIENT -> R.string.region_sens_lenient
                        },
                    )
                },
                onSelect = viewModel::setRegionSensitivity,
            )
            HintText(stringResource(R.string.region_sensitivity_hint))
        }
    }
}
