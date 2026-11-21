package com.gamelingo.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gamelingo.mobile.core.model.OverlayColors
import com.gamelingo.mobile.ui.theme.GlColors
import com.gamelingo.mobile.ui.theme.GlShapes
import com.gamelingo.mobile.ui.theme.GlSpacing

/** Standard screen scaffold: a scrolling column with the app's page padding and gaps. */
@Composable
fun SettingsColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(GlSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(GlSpacing.md),
    ) {
        content()
    }
}

/** Small group title above a set of related controls. */
@Composable
fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = GlColors.Accent,
        modifier = Modifier.padding(top = GlSpacing.sm, start = GlSpacing.xs),
    )
}

/** Secondary explanatory text under a control. */
@Composable
fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** Label on the leading side, value on the trailing side. */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = GlColors.TextPrimary,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = GlSpacing.md),
        )
    }
}

@Composable
fun SwitchCard(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    GlCard(contentPadding = PaddingValues(horizontal = GlSpacing.lg, vertical = GlSpacing.md)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = GlSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    HintText(subtitle)
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.padding(start = GlSpacing.md),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = GlColors.PrimaryStrong,
                    checkedBorderColor = GlColors.PrimaryStrong,
                    uncheckedThumbColor = GlColors.TextSecondary,
                    uncheckedTrackColor = GlColors.SurfaceElevated,
                    uncheckedBorderColor = GlColors.OutlineStrong,
                ),
            )
        }
    }
}

@Composable
fun SliderCard(
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    subtitle: String? = null,
) {
    GlCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.titleSmall,
                color = GlColors.Secondary,
                modifier = Modifier.padding(start = GlSpacing.md),
            )
        }
        if (subtitle != null) {
            HintText(subtitle)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = GlColors.Primary,
                activeTrackColor = GlColors.Primary,
                inactiveTrackColor = GlColors.OutlineStrong,
            ),
        )
    }
}

/** A radio group rendered as one card with a row per option. */
@Composable
fun <T> RadioGroupCard(
    title: String,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    GlCard(contentPadding = PaddingValues(vertical = GlSpacing.sm)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = GlSpacing.lg, vertical = GlSpacing.sm),
        )
        Column(modifier = Modifier.selectableGroup()) {
            options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = GlSpacing.touchTarget)
                        .selectable(
                            selected = selected == option,
                            onClick = { onSelect(option) },
                            role = Role.RadioButton,
                        )
                        .padding(horizontal = GlSpacing.lg, vertical = GlSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected == option,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = GlColors.Primary,
                            unselectedColor = GlColors.TextSecondary,
                        ),
                    )
                    Text(
                        text = optionLabel(option),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = GlSpacing.md),
                    )
                }
            }
        }
    }
}

/**
 * Pill-style segmented selector. The selected segment is filled and bold, so
 * the state is readable without relying on color.
 */
@Composable
fun <T> SegmentedSelector(
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(GlShapes.Medium)
            .background(GlColors.Background)
            .border(1.dp, GlColors.Outline, GlShapes.Medium)
            .padding(GlSpacing.xs)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(GlSpacing.xs),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = GlSpacing.touchTarget)
                    .clip(GlShapes.Small)
                    .background(if (isSelected) GlColors.PrimaryStrong else Color.Transparent)
                    .selectable(
                        selected = isSelected,
                        onClick = { onSelect(option) },
                        role = Role.RadioButton,
                    )
                    .padding(horizontal = GlSpacing.sm, vertical = GlSpacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = optionLabel(option),
                    style = if (isSelected) {
                        MaterialTheme.typography.labelLarge
                    } else {
                        MaterialTheme.typography.labelMedium
                    },
                    color = if (isSelected) Color.White else GlColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Row of preset ARGB color swatches; the selected one shows a check mark. */
@Composable
fun ColorSwatchRow(
    title: String,
    colors: List<Long>,
    selected: Long,
    onSelect: (Long) -> Unit,
) {
    GlCard {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = GlSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(GlSpacing.md),
        ) {
            colors.forEach { argb ->
                val isSelected = argb == selected
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(argb.toInt()))
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) GlColors.Secondary else GlColors.OutlineStrong,
                            shape = CircleShape,
                        )
                        .semantics { contentDescription = OverlayColors.formatArgbHex(argb) }
                        .selectable(
                            selected = isSelected,
                            onClick = { onSelect(argb) },
                            role = Role.RadioButton,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}
