package com.gamelingo.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Spacing scale: every gap and padding in the app comes from here. */
object GlSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** Minimum touch target (Material and WCAG recommend at least 48dp). */
    val touchTarget = 48.dp
}

/** Corner shapes shared by cards, buttons, inputs and dialogs. */
object GlShapes {
    val Small = RoundedCornerShape(10.dp)
    val Medium = RoundedCornerShape(16.dp)
    val Large = RoundedCornerShape(24.dp)
    val Button = RoundedCornerShape(16.dp)
    val Pill = RoundedCornerShape(50)
}

val GlMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = GlShapes.Small,
    medium = GlShapes.Medium,
    large = GlShapes.Large,
    extraLarge = RoundedCornerShape(32.dp),
)
