package com.gamelingo.mobile.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.gamelingo.mobile.R
import com.gamelingo.mobile.ui.components.GlCard
import com.gamelingo.mobile.ui.components.HintText
import com.gamelingo.mobile.ui.components.SettingsColumn
import com.gamelingo.mobile.ui.theme.GlColors
import com.gamelingo.mobile.ui.theme.GlSpacing

/**
 * Placeholder for the future audio-translation feature. The architecture is
 * ready for it, but it is not shipped as a finished feature yet.
 */
@Composable
fun VoiceTranslationScreen() {
    SettingsColumn {
        GlCard {
            Text(
                text = stringResource(R.string.voice_status_title),
                style = MaterialTheme.typography.titleMedium,
                color = GlColors.TextPrimary,
            )
            HintText(stringResource(R.string.voice_coming_soon))
        }
        GlCard {
            Text(
                text = stringResource(R.string.voice_planned_title),
                style = MaterialTheme.typography.titleMedium,
                color = GlColors.TextPrimary,
            )
            listOf(
                R.string.voice_planned_1,
                R.string.voice_planned_2,
                R.string.voice_planned_3,
            ).forEach { lineRes ->
                Text(
                    text = stringResource(R.string.voice_planned_line, stringResource(lineRes)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlColors.TextSecondary,
                    modifier = Modifier.padding(top = GlSpacing.sm),
                )
            }
        }
    }
}
