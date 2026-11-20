package com.gamelingo.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamelingo.mobile.R
import com.gamelingo.mobile.core.model.OverlayColors
import com.gamelingo.mobile.data.settings.OverlayPosition
import com.gamelingo.mobile.data.settings.SettingsConstraints
import com.gamelingo.mobile.ui.components.ColorSwatchRow
import com.gamelingo.mobile.ui.components.RadioGroupCard
import com.gamelingo.mobile.ui.components.SectionHeader
import com.gamelingo.mobile.ui.components.SettingsColumn
import com.gamelingo.mobile.ui.components.SliderCard
import com.gamelingo.mobile.ui.navigation.SettingsViewModel

@Composable
fun OverlaySettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsColumn {
        OverlayPreview(
            textColorArgb = settings.overlayTextColor,
            backgroundArgb = settings.overlayBackgroundColor,
            opacity = settings.overlayOpacity,
            textSizeSp = settings.overlayTextSizeSp,
            showOriginal = settings.showOriginalText,
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
        SliderCard(
            title = stringResource(R.string.overlay_text_size),
            valueLabel = stringResource(R.string.overlay_text_size_value, settings.overlayTextSizeSp),
            value = settings.overlayTextSizeSp.toFloat(),
            valueRange = SettingsConstraints.MIN_OVERLAY_TEXT_SIZE_SP.toFloat()..
                SettingsConstraints.MAX_OVERLAY_TEXT_SIZE_SP.toFloat(),
            onValueChange = { viewModel.setOverlayTextSizeSp(it.toInt()) },
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
        Text(
            text = stringResource(R.string.overlay_drag_hint),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Live preview of the floating window styling, using the exact current settings. */
@Composable
private fun OverlayPreview(
    textColorArgb: Long,
    backgroundArgb: Long,
    opacity: Float,
    textSizeSp: Int,
    showOriginal: Boolean,
) {
    val backgroundColor = Color(backgroundArgb.toInt()).copy(
        alpha = (backgroundArgb ushr 56 and 0xFF).toFloat() / 255f * opacity,
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.overlay_preview),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(backgroundColor)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.overlay_preview_translated),
                    color = Color(textColorArgb.toInt()),
                    fontSize = textSizeSp.sp,
                )
                if (showOriginal) {
                    Text(
                        text = stringResource(R.string.overlay_preview_original),
                        color = Color(textColorArgb.toInt()).copy(alpha = 0.7f),
                        fontSize = (textSizeSp * 0.75f).sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
