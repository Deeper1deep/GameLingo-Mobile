package com.gamelingo.mobile.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.gamelingo.mobile.R
import com.gamelingo.mobile.ui.components.GlCard
import com.gamelingo.mobile.ui.components.HintText
import com.gamelingo.mobile.ui.components.SettingsColumn
import com.gamelingo.mobile.ui.components.SwitchCard
import com.gamelingo.mobile.ui.theme.GlColors
import com.gamelingo.mobile.ui.theme.GlSpacing

/**
 * Opt-in "capture one app window" choice. Off by default. It states plainly
 * what changes: the translation area is re-picked on the window, and
 * follow/below/smart placement only works after the user aligns the window
 * outline (placement is fixed until then). Not shown anywhere until reviewed.
 */
@Composable
fun AppWindowCaptureSection(
    supported: Boolean,
    chosen: Boolean,
    aligned: Boolean,
    onChosen: (Boolean) -> Unit,
    onAlign: () -> Unit,
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
                HintText(stringResource(R.string.appwin_limits))
                HintText(stringResource(if (aligned) R.string.appwin_aligned else R.string.appwin_not_aligned))
                TextButton(onClick = onAlign, modifier = Modifier.padding(top = GlSpacing.sm)) {
                    Text(stringResource(if (aligned) R.string.appwin_realign else R.string.appwin_align))
                }
            }
        }
    }
}
