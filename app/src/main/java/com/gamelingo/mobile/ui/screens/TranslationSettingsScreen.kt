package com.gamelingo.mobile.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

        // My terms: the user's pinned names and terms. A pinned rendering
        // wins over the bundled dictionary, in both Arabic modes.
        SectionHeader(stringResource(R.string.user_glossary_title))
        GlCard {
            if (settings.userGlossaryTerms.isEmpty()) {
                HintText(stringResource(R.string.user_glossary_empty))
            } else {
                settings.userGlossaryTerms.forEach { term ->
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${term.en} = ${term.ar}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.removeUserGlossaryTerm(term.en) }) {
                            Text(stringResource(R.string.user_glossary_remove))
                        }
                    }
                }
            }
            var newEn by remember { mutableStateOf("") }
            var newAr by remember { mutableStateOf("") }
            OutlinedTextField(
                value = newEn,
                onValueChange = { newEn = it },
                label = { Text(stringResource(R.string.user_glossary_en_hint)) },
                singleLine = true,
                modifier = Modifier.padding(top = GlSpacing.md),
            )
            OutlinedTextField(
                value = newAr,
                onValueChange = { newAr = it },
                label = { Text(stringResource(R.string.user_glossary_ar_hint)) },
                singleLine = true,
                modifier = Modifier.padding(top = GlSpacing.sm),
            )
            TextButton(
                onClick = {
                    viewModel.addUserGlossaryTerm(newEn, newAr)
                    newEn = ""
                    newAr = ""
                },
                enabled = newEn.isNotBlank() && newAr.isNotBlank(),
                modifier = Modifier.padding(top = GlSpacing.sm),
            ) {
                Text(stringResource(R.string.user_glossary_add))
            }
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
