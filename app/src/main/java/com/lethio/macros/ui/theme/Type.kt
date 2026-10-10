package com.lethio.macros.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/*
 * Type scale. The system font family, so the user's font choice and scale are honoured. Tabular
 * numerals on every style that shows figures, so totals do not reflow and columns line up. All text
 * sizes resolve through this scale.
 */

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
    // Display — the big numbers: today's calorie total, and nothing else.
    displayLarge = figureStyle(57, 64, FontWeight.Normal, (-0.25)),
    displayMedium = figureStyle(45, 52, FontWeight.Normal),
    displaySmall = figureStyle(36, 44, FontWeight.Normal),

    // Headline — screen titles and section totals.
    headlineLarge = figureStyle(32, 40, FontWeight.Normal),
    headlineMedium = figureStyle(28, 36, FontWeight.Medium),
    headlineSmall = figureStyle(24, 32, FontWeight.Medium),

    // Title — card headers, meal section headers, list item titles.
    titleLarge = proseStyle(22, 28, FontWeight.Medium),
    titleMedium = proseStyle(16, 24, FontWeight.Medium, 0.15),
    titleSmall = proseStyle(14, 20, FontWeight.Medium, 0.1),

    // Body — prose. Proportional figures are correct here.
    bodyLarge = proseStyle(16, 24, FontWeight.Normal, 0.5),
    bodyMedium = proseStyle(14, 20, FontWeight.Normal, 0.25),
    bodySmall = proseStyle(12, 16, FontWeight.Normal, 0.4),

    // Label — buttons, chips, and the inline macro figures on list rows.
    labelLarge = figureStyle(14, 20, FontWeight.Medium, 0.1),
    labelMedium = figureStyle(12, 16, FontWeight.Medium, 0.5),
    labelSmall = figureStyle(11, 16, FontWeight.Medium, 0.5),
)
