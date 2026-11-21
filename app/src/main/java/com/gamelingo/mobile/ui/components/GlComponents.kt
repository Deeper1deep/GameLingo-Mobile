package com.gamelingo.mobile.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gamelingo.mobile.ui.theme.GlColors
import com.gamelingo.mobile.ui.theme.GlShapes
import com.gamelingo.mobile.ui.theme.GlSpacing

// ---------------------------------------------------------------------------
// Press feedback
// ---------------------------------------------------------------------------

/** Subtle scale-down while the element is pressed. Cheap: one animated float. */
@Composable
fun Modifier.pressScale(
    source: MutableInteractionSource,
    pressedScale: Float = 0.97f,
): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = tween(durationMillis = 90),
        label = "pressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

// ---------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------

enum class GlButtonStyle { Primary, Secondary, Danger }

/**
 * The one button of the app. Primary is the loudest element on a screen and
 * should appear once per screen; Secondary is outlined; Danger is destructive.
 */
@Composable
fun GlButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: GlButtonStyle = GlButtonStyle.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val contentColor = when (style) {
        GlButtonStyle.Primary -> Color.White
        GlButtonStyle.Secondary -> GlColors.TextPrimary
        GlButtonStyle.Danger -> GlColors.Error
    }
    val background: Modifier = when (style) {
        GlButtonStyle.Primary -> Modifier.background(
            Brush.horizontalGradient(listOf(GlColors.PrimaryStrong, GlColors.Primary)),
        )
        GlButtonStyle.Secondary -> Modifier
            .background(GlColors.SurfaceElevated)
            .border(BorderStroke(1.dp, GlColors.OutlineStrong), GlShapes.Button)
        GlButtonStyle.Danger -> Modifier
            .background(GlColors.Error.copy(alpha = 0.12f))
            .border(BorderStroke(1.dp, GlColors.Error.copy(alpha = 0.5f)), GlShapes.Button)
    }
    val active = enabled && !loading
    Row(
        modifier = modifier
            .heightIn(min = 52.dp)
            .pressScale(source)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .clip(GlShapes.Button)
            .then(background)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = active,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = GlSpacing.xl, vertical = GlSpacing.sm),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = contentColor,
                strokeWidth = 2.dp,
            )
        } else if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = contentColor)
        }
        if (loading || icon != null) Box(Modifier.size(GlSpacing.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            textAlign = TextAlign.Center,
        )
    }
}

/** Low-emphasis text button with a 48dp minimum height. */
@Composable
fun GlTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = GlSpacing.touchTarget),
    ) {
        Text(
            text = text,
            color = if (danger) GlColors.Error else GlColors.Accent,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

// ---------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------

/** The standard card: Surface (or Elevated) fill with a hairline border. */
@Composable
fun GlCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    elevated: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(GlSpacing.lg),
    content: @Composable ColumnScope.() -> Unit,
) {
    val color = if (elevated) GlColors.SurfaceElevated else GlColors.Surface
    val border = BorderStroke(1.dp, GlColors.Outline)
    if (onClick != null) {
        val source = remember { MutableInteractionSource() }
        Surface(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .pressScale(source, pressedScale = 0.985f),
            shape = GlShapes.Medium,
            color = color,
            border = border,
            interactionSource = source,
        ) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = GlShapes.Medium,
            color = color,
            border = border,
        ) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    }
}

/** A tappable row used by the settings hub: icon, title, optional subtitle, chevron. */
@Composable
fun GlNavRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    GlCard(
        modifier = modifier,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = GlSpacing.lg, vertical = GlSpacing.md),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = GlSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(icon = icon)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = GlSpacing.md),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            trailing?.invoke()
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = null,
                tint = GlColors.TextSecondary,
            )
        }
    }
}

/** Rounded square holding an icon in the brand tint. */
@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = GlColors.Accent,
    size: Dp = 40.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(GlShapes.Small)
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint)
    }
}

// ---------------------------------------------------------------------------
// Status
// ---------------------------------------------------------------------------

/** Meaning of a status. Color is never the only signal: every status also has text. */
enum class StatusTone { Neutral, Success, Error, Info, Accent }

fun StatusTone.color(): Color = when (this) {
    StatusTone.Neutral -> GlColors.TextSecondary
    StatusTone.Success -> GlColors.Success
    StatusTone.Error -> GlColors.Error
    StatusTone.Info -> GlColors.Secondary
    StatusTone.Accent -> GlColors.Accent
}

/** Pill with a dot and a text label. [pulsing] animates the dot for live states. */
@Composable
fun StatusChip(
    label: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false,
) {
    val color = tone.color()
    Row(
        modifier = modifier
            .clip(GlShapes.Pill)
            .background(color.copy(alpha = 0.12f))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.35f)), GlShapes.Pill)
            .padding(horizontal = GlSpacing.md, vertical = GlSpacing.xs + 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color = color, pulsing = pulsing)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(start = GlSpacing.sm),
        )
    }
}

@Composable
fun StatusDot(color: Color, pulsing: Boolean = false, size: Dp = 8.dp) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "dot")
        val value by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "dotAlpha",
        )
        value
    } else {
        1f
    }
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(color),
    )
}

/** Small tile for the Home status grid: icon, caption and a value with a tone. */
@Composable
fun StatusTile(
    icon: ImageVector,
    title: String,
    value: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    GlCard(
        modifier = modifier,
        contentPadding = PaddingValues(GlSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tone.color(),
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = GlSpacing.sm),
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = tone.color(),
            modifier = Modifier.padding(top = GlSpacing.xs),
        )
    }
}

// ---------------------------------------------------------------------------
// Inputs and dialogs
// ---------------------------------------------------------------------------

@Composable
fun glTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = GlColors.TextPrimary,
    unfocusedTextColor = GlColors.TextPrimary,
    focusedBorderColor = GlColors.Primary,
    unfocusedBorderColor = GlColors.OutlineStrong,
    cursorColor = GlColors.Secondary,
    focusedContainerColor = GlColors.Surface,
    unfocusedContainerColor = GlColors.Surface,
    focusedPlaceholderColor = GlColors.TextSecondary,
    unfocusedPlaceholderColor = GlColors.TextSecondary,
    focusedLeadingIconColor = GlColors.Accent,
    unfocusedLeadingIconColor = GlColors.TextSecondary,
    focusedSupportingTextColor = GlColors.TextSecondary,
    unfocusedSupportingTextColor = GlColors.TextSecondary,
)

/** Themed confirmation / information dialog. */
@Composable
fun GlDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    dismissText: String? = null,
    danger: Boolean = false,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = GlColors.SurfaceElevated,
        shape = GlShapes.Large,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = GlColors.TextPrimary,
            )
        },
        text = content,
        confirmButton = { GlTextButton(text = confirmText, onClick = onConfirm, danger = danger) },
        dismissButton = dismissText?.let { label ->
            { GlTextButton(text = label, onClick = onDismiss) }
        },
    )
}

// ---------------------------------------------------------------------------
// Loading
// ---------------------------------------------------------------------------

/** Skeleton block: a calm alpha pulse (no shader, no per-frame layout work). */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .graphicsLayer { this.alpha = alpha }
            .clip(GlShapes.Small)
            .background(GlColors.OutlineStrong),
    )
}

// ---------------------------------------------------------------------------
// Navigation
// ---------------------------------------------------------------------------

data class GlTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

/** Bottom navigation. Labels are always visible so state never relies on color alone. */
@Composable
fun GlBottomBar(
    tabs: List<GlTab>,
    currentRoute: String?,
    onSelect: (String) -> Unit,
) {
    Column {
        HorizontalDivider(color = GlColors.Outline)
        NavigationBar(
            containerColor = GlColors.Surface,
            tonalElevation = 0.dp,
        ) {
            tabs.forEach { tab ->
                NavigationBarItem(
                    selected = currentRoute == tab.route,
                    onClick = { onSelect(tab.route) },
                    icon = { Icon(imageVector = tab.icon, contentDescription = null) },
                    label = {
                        Text(text = tab.label, style = MaterialTheme.typography.labelSmall)
                    },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = GlColors.TextPrimary,
                        selectedTextColor = GlColors.TextPrimary,
                        unselectedIconColor = GlColors.TextSecondary,
                        unselectedTextColor = GlColors.TextSecondary,
                        indicatorColor = GlColors.Primary.copy(alpha = 0.28f),
                    ),
                )
            }
        }
    }
}

/** Page background: flat Background with one soft violet glow at the top. */
fun Modifier.glScreenBackground(): Modifier = this
    .background(GlColors.Background)
    .drawBehind {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(GlColors.Primary.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(size.width * 0.5f, 0f),
                radius = size.width * 0.9f,
            ),
        )
    }

// ---------------------------------------------------------------------------
// Brand mark
// ---------------------------------------------------------------------------

private const val LOGO_BUBBLE =
    "M43,31H65A13,13 0 0 1 78,44V56A13,13 0 0 1 65,69H56L38,80L44,69H43A13,13 0 0 1 30,56V44A13,13 0 0 1 43,31Z"
private const val LOGO_G_ARC = "M61.1,42.9A10.2,10.2 0 1 0 64.2,51"
private const val LOGO_G_SPUR = "M64.2,51H58.6"

/**
 * The GameLingo mark, drawn with the same geometry as the launcher icon
 * (bubble + G + translation waves). With [withTile] it also draws the dark
 * rounded tile behind it, like the icon on a home screen.
 */
@Composable
fun LogoMark(
    size: Dp,
    modifier: Modifier = Modifier,
    withTile: Boolean = true,
) {
    val bubble = remember { PathParser().parsePathString(LOGO_BUBBLE).toPath() }
    val arc = remember { PathParser().parsePathString(LOGO_G_ARC).toPath() }
    val spur = remember { PathParser().parsePathString(LOGO_G_SPUR).toPath() }
    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = "GameLingo" },
    ) {
        val unit = this.size.minDimension / 108f
        scale(scale = unit, pivot = Offset.Zero) {
            if (withTile) {
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF0B0E1A), Color(0xFF171C36)),
                        start = Offset.Zero,
                        end = Offset(108f, 108f),
                    ),
                    size = androidx.compose.ui.geometry.Size(108f, 108f),
                    cornerRadius = CornerRadius(24f, 24f),
                )
                drawRoundRect(
                    brush = Brush.radialGradient(
                        colors = listOf(GlColors.Secondary.copy(alpha = 0.28f), Color.Transparent),
                        center = Offset(78f, 84f),
                        radius = 60f,
                    ),
                    size = androidx.compose.ui.geometry.Size(108f, 108f),
                    cornerRadius = CornerRadius(24f, 24f),
                )
                drawRoundRect(
                    brush = Brush.radialGradient(
                        colors = listOf(GlColors.Primary.copy(alpha = 0.30f), Color.Transparent),
                        center = Offset(27f, 22f),
                        radius = 65f,
                    ),
                    size = androidx.compose.ui.geometry.Size(108f, 108f),
                    cornerRadius = CornerRadius(24f, 24f),
                )
            }
            drawPath(
                path = bubble,
                brush = Brush.linearGradient(
                    colors = listOf(GlColors.Accent, Color(0xFF7C3AED)),
                    start = Offset(34f, 31f),
                    end = Offset(74f, 80f),
                ),
            )
            val gStroke = Stroke(width = 4.4f, cap = StrokeCap.Round)
            drawPath(path = arc, color = GlColors.TextPrimary, style = gStroke)
            drawPath(path = spur, color = GlColors.TextPrimary, style = gStroke)
            val barStroke = Stroke(width = 2.7f, cap = StrokeCap.Round)
            listOf(46.4f to 2.8f, 50.2f to 7.8f, 54f to 4.2f).forEach { (x, h) ->
                drawLine(
                    color = GlColors.Secondary,
                    start = Offset(x, 51f - h / 2f),
                    end = Offset(x, 51f + h / 2f),
                    strokeWidth = barStroke.width,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
