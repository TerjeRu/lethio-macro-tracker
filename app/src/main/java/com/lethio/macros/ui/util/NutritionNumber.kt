package com.lethio.macros.ui.util

import java.text.NumberFormat
import java.util.Locale

/** Round only at the display edge, retaining large stored values. */
internal fun nutritionNumber(value: Double, fractionDigits: Int, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = fractionDigits
        minimumFractionDigits = 0
        roundingMode = java.math.RoundingMode.HALF_UP
    }.format(value)
