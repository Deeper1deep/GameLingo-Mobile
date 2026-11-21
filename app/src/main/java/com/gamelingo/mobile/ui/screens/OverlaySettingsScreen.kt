package com.gamelingo.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamelingo.mobile.R
import com.gamelingo.mobile.core.model.OverlayColors
import com.gamelingo.mobile.core.overlay.OverlayLayout
import com.gamelingo.mobile.data.settings.OverlayPosition
import com.gamelingo.mobile.data.settings.SettingsConstraints
import com.gamelingo.mobile.ui.components.ColorSwatchRow
import com.gamelingo.mobile.ui.components.HintText
import com.gamelingo.mobile.ui.components.RadioGroupCard
import com.gamelingo.mobile.ui.components.SectionHeader
import com.gamelingo.mobile.ui.components.SettingsColumn
import com.gamelingo.mobile.ui.components.SliderCard
import com.gamelingo.mobile.ui.components.SwitchCard
import com.gamelingo.mobile.ui.navigation.SettingsViewModel
import com.gamelingo.mobile.ui.theme.GlColors
import com.gamelingo.mobile.ui.theme.GlShapes
import com.gamelingo.mobile.ui.theme.GlSpacing

@Composable
fun OverlaySettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsColumn {
        SectionHeader(stringResource(R.string.overlay_preview))
        OverlayPreview(
            textColorArgb = settings.overlayTextColor,
            backgroundArgb = settings.overlayBackgroundColor,
            opacity = settings.overlayOpacity,
            textSizeSp = settings.overlayTextSizeSp,
            showOriginal = settings.showOriginalText,
            position = settings.overlayPosition,
        )

        SwitchCard(
            title = stringResource(R.string.translation_show_original),
            checked = settings.showOriginalText,
            onCheckedChange = viewModel::setShowOriginalText,
        )
        SliderCard(
            title = stringResource(R.string.overlay_text_size),
            valueLabel = stringResource(R.string.overlay_text_size_value, settings.overlayTextSizeSp),
            value = settings.overlayTextSizeSp.toFloat(),
            valueRange = SettingsConstraints.MIN_OVERLAY_TEXT_SIZE_SP.toFloat()..
                SettingsConstraints.MAX_OVERLAY_TEXT_SIZE_SP.toFloat(),
            onValueChange = { viewModel.setOverlayTextSizeSp(it.toInt()) },
        )
        SliderCard(
            title = stringResource(R.string.overlay_opacity),
            valueLabel = stringResource(
                R.string.overlay_opacity_value,
                (settings.overlayOpacity * 100).toInt(),
            ),
            value = settings.overlayOpacity,
            valueRange = SettingsConstraints.MIN_OVERLAY_OPACITY..
                SettingsConstraints.MAX_OVERLAY_OPACITY,
            onValueChange = viewModel::setOverlayOpacity,
        )

        SectionHeader(stringResource(R.string.overlay_colors_section))
        ColorSwatchRow(
            title = stringResource(R.string.overlay_colors_text),
            colors = OverlayColors.TEXT_PRESETS,
            selected = settings.overlayTextColor,
            onSelect = viewModel::setOverlayTextColor,
        )
        ColorSwatchRow(
            title = stringResource(R.string.overlay_colors_bg),
            colors = OverlayColors.BACKGROUND_PRESETS,
            selected = settings.overlayBackgroundColor,
            onSelect = viewModel::setOverlayBackgroundColor,
        )

        SectionHeader(stringResource(R.string.overlay_position_section))
        RadioGroupCard(
            title = stringResource(R.string.overlay_position),
            options = OverlayPosition.entries,
            selected = settings.overlayPosition,
            optionLabel = { position ->
                stringResource(
                    when (position) {
                        OverlayPosition.TOP -> R.string.overlay_position_top
                        OverlayPosition.BOTTOM -> R.string.overlay_position_bottom
                    },
                )
            },
            onSelect = viewModel::setOverlayPosition,
        )
        HintText(stringResource(R.string.overlay_drag_hint))
    }
}

/**
 * Live preview of the floating window on a mock game frame, using the exact
 * current settings: Arabic translation on the first line, English second.
 */
@Composable
private fun OverlayPreview(
    textColorArgb: Long,
    backgroundArgb: Long,
    opacity: Float,
    textSizeSp: Int,
    showOriginal: Boolean,
    position: OverlayPosition,
) {
    // Same helper the real floating window uses, so the preview is exact.
    val backgroundColor = Color(OverlayLayout.backgroundWithOpacity(backgroundArgb, opacity))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(GlShapes.Medium)
            .background(Color(0xFF1B2540))
            .border(1.dp, GlColors.Outline, GlShapes.Medium)
            .padding(GlSpacing.md),
        contentAlignment = if (position == OverlayPosition.TOP) {
            Alignment.TopCenter
        } else {
            Alignment.BottomCenter
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(GlShapes.Small)
                .background(backgroundColor)
                .padding(GlSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(GlSpacing.xs),
        ) {
            Text(
                text = stringResource(R.string.overlay_preview_translated),
                color = Color(textColorArgb.toInt()),
                fontSize = textSizeSp.sp,
                lineHeight = (textSizeSp * 1.5f).sp,
                textAlign = TextAlign.Center,
            )
            if (showOriginal) {
                Text(
                    text = stringResource(R.string.overlay_preview_original),
                    color = Color(textColorArgb.toInt()).copy(alpha = 0.7f),
                    fontSize = (textSizeSp * 0.75f).sp,
                    lineHeight = (textSizeSp * 1.1f).sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
