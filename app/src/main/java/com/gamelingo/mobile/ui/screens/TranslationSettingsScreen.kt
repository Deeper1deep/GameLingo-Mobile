package com.gamelingo.mobile.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import com.gamelingo.mobile.ui.theme.GlSpacing

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
            HintText(stringResource(R.string.tr_fixed_hint))
        }

        // Arabic style: one persisted choice, applied to every engine and
        // fallback, effective on the next line even mid-session.
        SectionHeader(stringResource(R.string.arabic_style_title))
        GlCard {
            com.gamelingo.mobile.ui.components.SegmentedSelector(
                options = com.gamelingo.mobile.data.settings.ArabicTranslationMode.entries,
                selected = settings.arabicTranslationMode,
                optionLabel = { mode ->
                    stringResource(
                        when (mode) {
                            com.gamelingo.mobile.data.settings.ArabicTranslationMode.MODERN_STANDARD_ARABIC ->
                                R.string.arabic_style_msa_short
                            com.gamelingo.mobile.data.settings.ArabicTranslationMode.EGYPTIAN_ARABIC ->
                                R.string.arabic_style_egy_short
                        },
                    )
                },
                onSelect = viewModel::setArabicTranslationMode,
            )
            HintText(
                text = stringResource(
                    when (settings.arabicTranslationMode) {
                        com.gamelingo.mobile.data.settings.ArabicTranslationMode.MODERN_STANDARD_ARABIC ->
                            R.string.arabic_style_msa_desc
                        com.gamelingo.mobile.data.settings.ArabicTranslationMode.EGYPTIAN_ARABIC ->
                            R.string.arabic_style_egy_desc
                    },
                ),
                modifier = Modifier.padding(top = GlSpacing.md),
            )
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
