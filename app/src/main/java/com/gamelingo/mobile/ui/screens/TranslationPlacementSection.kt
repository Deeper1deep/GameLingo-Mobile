package com.gamelingo.mobile.ui.screens

import android.content.Context
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.gamelingo.mobile.R
import com.gamelingo.mobile.core.overlay.PlacementAlign
import com.gamelingo.mobile.core.overlay.PlacementMode
import com.gamelingo.mobile.core.overlay.TranslationPlacement
import com.gamelingo.mobile.data.settings.AppSettings
import com.gamelingo.mobile.overlay.PlacementEditorService
import com.gamelingo.mobile.ui.components.GlNavRow
import com.gamelingo.mobile.ui.components.GlTextButton
import com.gamelingo.mobile.ui.components.HintText
import com.gamelingo.mobile.ui.components.RadioGroupCard
import com.gamelingo.mobile.ui.components.SectionHeader
import com.gamelingo.mobile.ui.components.SegmentedSelector
import com.gamelingo.mobile.ui.components.SliderCard
import com.gamelingo.mobile.ui.navigation.SettingsViewModel

private const val AUTO_WIDTH_BELOW = 0.2f

/**
 * Translation position settings. Everything here edits the translation
 * placement only; the scanned (OCR) area lives in the reading settings and is
 * not read or written by these controls.
 */
@Composable
fun TranslationPlacementSection(settings: AppSettings, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val placement = settings.translationPlacement
    fun update(block: (TranslationPlacement) -> TranslationPlacement) =
        viewModel.setTranslationPlacement(block(placement))

    SectionHeader(stringResource(R.string.placement_section))
    HintText(stringResource(R.string.placement_explain))
    RadioGroupCard(
        title = stringResource(R.string.placement_mode),
        options = PlacementMode.entries,
        selected = placement.mode,
        optionLabel = {
            stringResource(
                when (it) {
                    PlacementMode.FIXED -> R.string.placement_mode_fixed
                    PlacementMode.FOLLOW_ORIGINAL -> R.string.placement_mode_follow
                    PlacementMode.BELOW_ORIGINAL -> R.string.placement_mode_below
                    PlacementMode.SMART_AUTO -> R.string.placement_mode_smart
                },
            )
        },
        onSelect = { mode -> update { it.copy(mode = mode) } },
    )
    GlNavRow(
        icon = Icons.Filled.Create,
        title = stringResource(R.string.placement_move),
        subtitle = stringResource(R.string.placement_move_sub),
        onClick = { openEditor(context) },
    )
    SliderCard(
        title = stringResource(R.string.placement_offset_x, (placement.offsetX * 100).toInt()),
        valueLabel = "",
        value = placement.offsetX,
        valueRange = -TranslationPlacement.MAX_OFFSET..TranslationPlacement.MAX_OFFSET,
        onValueChange = { v -> update { it.copy(offsetX = v) } },
    )
    SliderCard(
        title = stringResource(R.string.placement_offset_y, (placement.offsetY * 100).toInt()),
        valueLabel = "",
        value = placement.offsetY,
        valueRange = -TranslationPlacement.MAX_OFFSET..TranslationPlacement.MAX_OFFSET,
        onValueChange = { v -> update { it.copy(offsetY = v) } },
    )
    val width = placement.widthFraction
    SliderCard(
        title = if (width == null) {
            stringResource(R.string.placement_width_auto)
        } else {
            stringResource(R.string.placement_width_value, (width * 100).toInt())
        },
        valueLabel = "",
        subtitle = stringResource(R.string.placement_width_hint),
        value = width ?: 0.1f,
        valueRange = 0.1f..1f,
        onValueChange = { v ->
            update { it.copy(widthFraction = if (v < AUTO_WIDTH_BELOW) null else v) }
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(com.gamelingo.mobile.ui.theme.GlSpacing.sm)) {
        HintText(stringResource(R.string.placement_align))
        SegmentedSelector(
            options = PlacementAlign.entries,
            selected = placement.align,
            optionLabel = {
                stringResource(
                    when (it) {
                        PlacementAlign.LEFT -> R.string.placement_align_left
                        PlacementAlign.CENTER -> R.string.placement_align_center
                        PlacementAlign.RIGHT -> R.string.placement_align_right
                    },
                )
            },
            onSelect = { a -> update { it.copy(align = a) } },
        )
    }
    GlTextButton(
        text = stringResource(R.string.placement_reset),
        onClick = { update { it.positionReset() } },
        modifier = Modifier.fillMaxWidth(),
    )
    HintText(stringResource(R.string.placement_ocr_hint))
}


private fun openEditor(context: Context) {
    if (!Settings.canDrawOverlays(context)) {
        Toast.makeText(context, R.string.region_need_overlay_permission, Toast.LENGTH_LONG).show()
        return
    }
    context.startService(PlacementEditorService.startIntent(context))
    // Step aside so the game is visible under the editor.
    var c: Context? = context
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) { c.moveTaskToBack(true); break }
        c = c.baseContext
    }
}
