package com.gamelingo.mobile.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.gamelingo.mobile.R
import com.gamelingo.mobile.ui.components.GlCard
import com.gamelingo.mobile.ui.components.HintText
import com.gamelingo.mobile.ui.components.SettingsColumn
import com.gamelingo.mobile.ui.components.SwitchCard
import com.gamelingo.mobile.ui.theme.GlColors

/**
 * Opt-in "capture one app window" choice. Off by default. It states plainly
 * what changes. Alignment is launched from the session notification (while
 * the game is visible), never from here, so it cannot be done against the wrong app. Not shown anywhere until reviewed.
 */
@Composable
fun AppWindowCaptureSection(
    supported: Boolean,
    chosen: Boolean,
    aligned: Boolean,
    onChosen: (Boolean) -> Unit,
) {
    SettingsColumn {
        SwitchCard(
            title = stringResource(R.string.appwin_title),
            subtitle = stringResource(if (supported) R.string.appwin_sub else R.string.appwin_unsupported),
            checked = chosen && supported,
            onCheckedChange = { if (supported) onChosen(it) },
        )
        if (chosen && supported) {
            GlCard {
                Text(
                    text = stringResource(R.string.appwin_limits_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = GlColors.TextPrimary,
                )
                HintText(stringResource(R.string.appwin_step1))
                HintText(stringResource(R.string.appwin_step2))
                HintText(stringResource(R.string.appwin_step3))
                HintText(stringResource(R.string.appwin_step4))
            }
            GlCard {
                Text(
                    text = stringResource(if (aligned) R.string.appwin_aligned else R.string.appwin_not_aligned),
                    style = MaterialTheme.typography.titleMedium,
                    color = GlColors.TextPrimary,
                )
            }
        }
    }
}

/** Stateful host: reads and writes the opt-in flag, no other setting. */
@Composable
fun AppWindowCaptureScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var chosen by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(com.gamelingo.mobile.capture.CaptureMode.appWindowChosen(context))
    }
    AppWindowCaptureSection(
        supported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        chosen = chosen,
        aligned = com.gamelingo.mobile.core.capture.AppWindowCalibrationStore.calibration != null,
        onChosen = {
            chosen = it
            com.gamelingo.mobile.capture.CaptureMode.setAppWindowChosen(context, it)
        },
    )
}
