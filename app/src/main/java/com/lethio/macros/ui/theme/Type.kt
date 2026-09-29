package com.lethio.macros.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

private const val TABULAR = "tnum"

private val LineHeightAlign = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun figureStyle(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    tracking: Double = 0.0,
) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
    fontFeatureSettings = TABULAR,
    lineHeightStyle = LineHeightAlign,
)

private fun proseStyle(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    tracking: Double = 0.0,
) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
    lineHeightStyle = LineHeightAlign,
)

internal val LethioTypography = Typography(

    displayLarge = figureStyle(57, 64, FontWeight.Normal, (-0.25)),
    displayMedium = figureStyle(45, 52, FontWeight.Normal),
    displaySmall = figureStyle(36, 44, FontWeight.Normal),

    headlineLarge = figureStyle(32, 40, FontWeight.Normal),
    headlineMedium = figureStyle(28, 36, FontWeight.Medium),
    headlineSmall = figureStyle(24, 32, FontWeight.Medium),

    titleLarge = proseStyle(22, 28, FontWeight.Medium),
    titleMedium = proseStyle(16, 24, FontWeight.Medium, 0.15),
    titleSmall = proseStyle(14, 20, FontWeight.Medium, 0.1),

    bodyLarge = proseStyle(16, 24, FontWeight.Normal, 0.5),
    bodyMedium = proseStyle(14, 20, FontWeight.Normal, 0.25),
    bodySmall = proseStyle(12, 16, FontWeight.Normal, 0.4),

    labelLarge = figureStyle(14, 20, FontWeight.Medium, 0.1),
    labelMedium = figureStyle(12, 16, FontWeight.Medium, 0.5),
    labelSmall = figureStyle(11, 16, FontWeight.Medium, 0.5),
)
