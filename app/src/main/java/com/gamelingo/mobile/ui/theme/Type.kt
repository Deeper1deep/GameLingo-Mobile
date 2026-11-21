package com.gamelingo.mobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Arabic-first type scale. Line heights are generous (about 1.5x) because
 * Arabic glyphs carry tall ascenders and diacritics; every size is in sp so
 * the user's system font scale is respected.
 */
private val Family = FontFamily.Default

val GlTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Bold,
        fontSize = 30.sp, lineHeight = 42.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Bold,
        fontSize = 26.sp, lineHeight = 36.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp, lineHeight = 30.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp, lineHeight = 26.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 26.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Bold,
        fontSize = 15.sp, lineHeight = 22.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 20.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 18.sp,
    ),
)
