package com.gamelingo.mobile.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamelingo.mobile.R
import com.gamelingo.mobile.data.settings.SettingsConstraints
import com.gamelingo.mobile.ui.components.GlCard
import com.gamelingo.mobile.ui.components.HintText
import com.gamelingo.mobile.ui.components.InfoRow
import com.gamelingo.mobile.ui.components.SectionHeader
import com.gamelingo.mobile.ui.components.SettingsColumn
import com.gamelingo.mobile.ui.components.SliderCard
import com.gamelingo.mobile.ui.components.SwitchCard
import com.gamelingo.mobile.ui.navigation.SettingsViewModel

@Composable
fun TranslationSettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsColumn {
        SectionHeader(stringResource(R.string.tr_languages_title))
        GlCard {
            InfoRow(
                label = stringResource(R.string.tr_source),
                value = stringResource(R.string.tr_source_value),
            )
            InfoRow(
                label = stringResource(R.string.tr_target),
                value = stringResource(R.string.tr_target_value),
            )
            InfoRow(
                label = stringResource(R.string.translation_target_dialect),
                value = stringResource(R.string.translation_dialect_egyptian),
            )
            HintText(stringResource(R.string.tr_fixed_hint))
        }

        SectionHeader(stringResource(R.string.translation_style_title))
        GlCard {
            InfoRow(
                label = stringResource(R.string.translation_style_title),
                value = stringResource(R.string.tr_style_value),
            )
            HintText(stringResource(R.string.translation_style_hint))
            HintText(stringResource(R.string.tr_style_note))
        }

        SwitchCard(
            title = stringResource(R.string.tr_context_title),
            subtitle = stringResource(R.string.tr_context_sub),
            checked = settings.contextLines > 0,
            onCheckedChange = { enabled ->
                viewModel.setContextLines(
                    if (enabled) SettingsConstraints.DEFAULT_CONTEXT_LINES else 0,
                )
            },
        )
        if (settings.contextLines > 0) {
            SliderCard(
                title = stringResource(R.string.ai_context_lines),
                valueLabel = stringResource(R.string.ai_context_lines_value, settings.contextLines),
                value = settings.contextLines.toFloat(),
                valueRange = 1f..SettingsConstraints.MAX_CONTEXT_LINES.toFloat(),
                onValueChange = { viewModel.setContextLines(it.toInt()) },
                subtitle = stringResource(R.string.ai_context_hint),
            )
        }

        SwitchCard(
            title = stringResource(R.string.translation_show_original),
            checked = settings.showOriginalText,
            onCheckedChange = viewModel::setShowOriginalText,
        )
    }
}
