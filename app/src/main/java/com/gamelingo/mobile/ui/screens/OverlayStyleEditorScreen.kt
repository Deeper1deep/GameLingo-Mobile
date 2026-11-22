package com.gamelingo.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamelingo.mobile.R
import com.gamelingo.mobile.core.model.OverlayColors
import com.gamelingo.mobile.core.overlay.OverlayAppearance
import com.gamelingo.mobile.core.overlay.OverlayFont
import com.gamelingo.mobile.core.overlay.OverlayPresetId
import com.gamelingo.mobile.core.overlay.OverlayPresets
import com.gamelingo.mobile.core.overlay.OverlayStyle
import com.gamelingo.mobile.data.settings.AppSettings
import com.gamelingo.mobile.overlay.OverlayContent
import com.gamelingo.mobile.overlay.OverlayUiState
import com.gamelingo.mobile.ui.components.ColorSwatchRow
import com.gamelingo.mobile.ui.components.GlButton
import com.gamelingo.mobile.ui.components.GlButtonStyle
import com.gamelingo.mobile.ui.components.GlCard
import com.gamelingo.mobile.ui.components.HintText
import com.gamelingo.mobile.ui.components.RadioGroupCard
import com.gamelingo.mobile.ui.components.SectionHeader
import com.gamelingo.mobile.ui.components.SegmentedSelector
import com.gamelingo.mobile.ui.components.SettingsColumn
import com.gamelingo.mobile.ui.components.SliderCard
import com.gamelingo.mobile.ui.components.SwitchCard
import com.gamelingo.mobile.ui.navigation.SettingsViewModel
import com.gamelingo.mobile.ui.theme.GlColors
import com.gamelingo.mobile.ui.theme.GlSpacing

private fun AppSettings.toAppearance() = OverlayAppearance(
    style = overlayStyle,
    textColor = overlayTextColor,
    backgroundColor = overlayBackgroundColor,
    opacity = overlayOpacity,
    textSizeSp = overlayTextSizeSp,
)

private fun parseHex(raw: String): Long? {
    val h = raw.trim().removePrefix("#")
    if (h.length != 6 && h.length != 8) return null
    val v = h.toLongOrNull(16) ?: return null
    return if (h.length == 6) 0xFF000000L or v else v
}

@Composable
private fun presetLabel(id: OverlayPresetId) = stringResource(
    when (id) {
        OverlayPresetId.CLASSIC -> R.string.style_preset_classic
        OverlayPresetId.CINEMATIC -> R.string.style_preset_cinematic
        OverlayPresetId.NEON -> R.string.style_preset_neon
        OverlayPresetId.HIGH_CONTRAST -> R.string.style_preset_contrast
        OverlayPresetId.MINIMAL -> R.string.style_preset_minimal
        OverlayPresetId.ANIME -> R.string.style_preset_anime
        OverlayPresetId.SOFT_GLASS -> R.string.style_preset_glass
    },
)

/**
 * Subtitle appearance editor. Edits go to a local draft rendered by the real
 * overlay composable (so the preview is exact); nothing is saved until
 * Apply. The stop control is never part of what can be styled away.
 */
@Composable
fun OverlayStyleEditorScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var draftOverride by remember { mutableStateOf<OverlayAppearance?>(null) }
    val saved = settings.toAppearance()
    val draft = draftOverride ?: saved
    val dirty = draftOverride != null && draftOverride != saved
    fun edit(change: (OverlayAppearance) -> OverlayAppearance) { draftOverride = change(draft).sanitized() }
    fun editStyle(change: (OverlayStyle) -> OverlayStyle) = edit { it.copy(style = change(it.style)) }

    val previewSettings = settings.copy(
        overlayTextColor = draft.textColor,
        overlayBackgroundColor = draft.backgroundColor,
        overlayOpacity = draft.opacity,
        overlayTextSizeSp = draft.textSizeSp,
        overlayStyle = draft.style,
        showOriginalText = true,
        preserveSourceColors = false,
    )
    val sample = OverlayUiState(
        original = stringResource(R.string.style_sample_original),
        translated = stringResource(R.string.style_sample_translated),
        sessionRunning = false,
    )

    SettingsColumn {
        SectionHeader(stringResource(R.string.style_preview))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1B2540)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            OverlayContent(state = sample, settings = previewSettings)
        }
        if (dirty) HintText(stringResource(R.string.style_unsaved))

        SectionHeader(stringResource(R.string.style_presets))
        val ids = OverlayPresetId.values().toList()
        SegmentedSelector(
            options = ids.take(4), selected = ids.firstOrNull { OverlayPresets.appearance(it) == draft } ?: ids[0],
            optionLabel = { presetLabel(it) },
            onSelect = { draftOverride = OverlayPresets.appearance(it) },
        )
        SegmentedSelector(
            options = ids.drop(4), selected = ids.firstOrNull { OverlayPresets.appearance(it) == draft } ?: ids[4],
            optionLabel = { presetLabel(it) },
            onSelect = { draftOverride = OverlayPresets.appearance(it) },
        )
        settings.customOverlayPreset?.let { custom ->
            GlButton(
                text = stringResource(R.string.style_preset_custom),
                onClick = { draftOverride = custom },
                style = GlButtonStyle.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        RadioGroupCard(
            title = stringResource(R.string.style_font),
            options = OverlayFont.values().toList(),
            selected = draft.style.font,
            optionLabel = {
                stringResource(
                    when (it) {
                        OverlayFont.DEFAULT -> R.string.style_font_default
                        OverlayFont.SANS -> R.string.style_font_sans
                        OverlayFont.SERIF -> R.string.style_font_serif
                        OverlayFont.MONO -> R.string.style_font_mono
                        OverlayFont.CURSIVE -> R.string.style_font_cursive
                    },
                )
            },
            onSelect = { f -> editStyle { it.copy(font = f) } },
        )
        SwitchCard(stringResource(R.string.style_bold), draft.style.bold) { v -> editStyle { it.copy(bold = v) } }
        SwitchCard(stringResource(R.string.style_shadow), draft.style.shadowEnabled) { v -> editStyle { it.copy(shadowEnabled = v) } }
        SwitchCard(stringResource(R.string.style_lock), draft.style.positionLocked) { v -> editStyle { it.copy(positionLocked = v) } }

        SliderCard(
            title = stringResource(R.string.style_size, draft.textSizeSp), valueLabel = "",
            value = draft.textSizeSp.toFloat(), valueRange = 12f..30f,
            onValueChange = { v -> edit { it.copy(textSizeSp = v.toInt()) } },
        )
        SliderCard(
            title = stringResource(R.string.style_opacity, (draft.opacity * 100).toInt()), valueLabel = "",
            value = draft.opacity, valueRange = 0.2f..1f,
            onValueChange = { v -> edit { it.copy(opacity = v) } },
        )
        SliderCard(
            title = stringResource(R.string.style_outline, "%.1f".format(draft.style.outlineWidthDp)), valueLabel = "",
            value = draft.style.outlineWidthDp, valueRange = 0f..OverlayStyle.MAX_OUTLINE,
            onValueChange = { v -> editStyle { it.copy(outlineWidthDp = v) } },
        )
        SliderCard(
            title = stringResource(R.string.style_spacing, "%.1f".format(draft.style.letterSpacingSp)), valueLabel = "",
            value = draft.style.letterSpacingSp, valueRange = OverlayStyle.MIN_SPACING..OverlayStyle.MAX_SPACING,
            onValueChange = { v -> editStyle { it.copy(letterSpacingSp = v) } },
        )
        SliderCard(
            title = stringResource(R.string.style_line_height, "%.2f".format(draft.style.lineHeightMultiplier)), valueLabel = "",
            value = draft.style.lineHeightMultiplier, valueRange = OverlayStyle.MIN_LINE_HEIGHT..OverlayStyle.MAX_LINE_HEIGHT,
            onValueChange = { v -> editStyle { it.copy(lineHeightMultiplier = v) } },
        )
        SliderCard(
            title = stringResource(R.string.style_corner, draft.style.cornerRadiusDp), valueLabel = "",
            value = draft.style.cornerRadiusDp.toFloat(), valueRange = 0f..OverlayStyle.MAX_RADIUS.toFloat(),
            onValueChange = { v -> editStyle { it.copy(cornerRadiusDp = v.toInt()) } },
        )
        SliderCard(
            title = stringResource(R.string.style_padding, draft.style.paddingDp), valueLabel = "",
            value = draft.style.paddingDp.toFloat(),
            valueRange = OverlayStyle.MIN_PADDING.toFloat()..OverlayStyle.MAX_PADDING.toFloat(),
            onValueChange = { v -> editStyle { it.copy(paddingDp = v.toInt()) } },
        )

        ColorSwatchRow(stringResource(R.string.style_text_color), OverlayColors.TEXT_PRESETS, draft.textColor) { c -> edit { it.copy(textColor = c) } }
        ColorSwatchRow(stringResource(R.string.style_bg_color), OverlayColors.BACKGROUND_PRESETS, draft.backgroundColor) { c -> edit { it.copy(backgroundColor = c) } }
        ColorSwatchRow(stringResource(R.string.style_outline_color), OverlayColors.TEXT_PRESETS + 0xFF000000, draft.style.outlineColor) { c -> editStyle { it.copy(outlineColor = c) } }

        GlCard {
            var hex by remember(draft.textColor) { mutableStateOf("%06X".format(draft.textColor and 0xFFFFFF)) }
            OutlinedTextField(
                value = hex,
                onValueChange = { raw ->
                    hex = raw.take(9)
                    parseHex(raw)?.let { c -> edit { it.copy(textColor = c) } }
                },
                label = { Text(stringResource(R.string.style_hex)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        GlButton(
            text = stringResource(R.string.style_apply),
            onClick = { viewModel.applyAppearance(draft); draftOverride = null },
            enabled = dirty,
            modifier = Modifier.fillMaxWidth(),
        )
        GlButton(
            text = stringResource(R.string.style_cancel),
            onClick = { draftOverride = null },
            enabled = dirty,
            style = GlButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
        GlButton(
            text = stringResource(R.string.style_reset),
            onClick = { draftOverride = AppSettings().toAppearance() },
            style = GlButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
        GlButton(
            text = stringResource(R.string.style_save_custom),
            onClick = { viewModel.saveCustomPreset(draft) },
            style = GlButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth().padding(bottom = GlSpacing.lg),
        )
    }
}
