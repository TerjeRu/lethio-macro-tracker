package com.lethio.macros.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lethio.macros.R
import com.lethio.macros.domain.model.Macros
import com.lethio.macros.domain.model.Nutrient
import com.lethio.macros.domain.model.NutrientQualifier
import kotlin.math.roundToInt

/** Qualifiers meaning "present, too little to measure", as opposed to a published zero. */
private val TRACE_LIKE = setOf(
    NutrientQualifier.TRACE,
    NutrientQualifier.BELOW_DETECTION,
    NutrientQualifier.BELOW_QUANTIFICATION,
    NutrientQualifier.BELOW_DETECTION_OR_QUANTIFICATION,
)

/**
 * True when the publisher recorded [kind] as trace or below detection and stored 0.
 * Showing "0 g" there reads as a measured zero, which the source did not claim. Totals still add
 * it as 0; this only changes how one food's own figure is written.
 */
fun Macros.isTrace(kind: Nutrient): Boolean {
    val value = nutrients[kind] ?: return false
    return value.qualifiers.any { it in TRACE_LIKE } && (value.reportedValue ?: 0.0) == 0.0
}

/**
 * One compact macro cell, "P12g " -- or "F trace " for a trace figure, which takes no unit.
 * Shared by the search row and the diary row so the two cannot disagree. Trailing space included.
 */
@Composable
fun macroPart(prefix: String, macros: Macros, kind: Nutrient, grams: Double, unit: String): String =
    if (macros.isTrace(kind)) "$prefix ${stringResource(R.string.nutrient_trace)} "
    else "$prefix${grams.roundToInt()}$unit "
