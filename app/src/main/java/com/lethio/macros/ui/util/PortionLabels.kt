package com.lethio.macros.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lethio.macros.R
import com.lethio.macros.domain.model.MeasureUnit
import kotlin.math.roundToInt

/**
 * Turns stored portion tokens (`serving`, `cup`, `slice`, `medium`) into labels in the reader's
 * language. Unknown labels pass through unchanged. [withGrams] adds the weight, since a US, UK and
 * metric cup all differ.
 */
object PortionLabels {

    private val CANONICAL = mapOf(
        "serving" to R.string.portion_serving,
        "cup" to R.string.portion_cup,
        "tablespoon" to R.string.portion_tablespoon,
        "teaspoon" to R.string.portion_teaspoon,
        "slice" to R.string.portion_slice,
        "piece" to R.string.portion_piece,
        "unit" to R.string.portion_unit,
        "bar" to R.string.portion_bar,
        "bottle" to R.string.portion_bottle,
        "can" to R.string.portion_can,
        "package" to R.string.portion_package,
        "small" to R.string.portion_small,
        "medium" to R.string.portion_medium,
        "large" to R.string.portion_large,
        "steak" to R.string.portion_steak,
        "roast" to R.string.portion_roast,
        "fillet" to R.string.portion_fillet,
        "jar" to R.string.portion_jar,
        "extra small" to R.string.portion_extra_small,
        "extra large" to R.string.portion_extra_large,
    )

    @Composable
    fun of(unit: MeasureUnit): String = when (unit) {
        MeasureUnit.Grams -> stringResource(R.string.grams_short)
        MeasureUnit.Milliliters -> stringResource(R.string.millilitres_short)
        MeasureUnit.Ounces -> stringResource(R.string.ounces_short)
        is MeasureUnit.Portion -> CANONICAL[unit.name.lowercase()]
            ?.let { stringResource(it) }
            ?: unit.name
    }

    /** The portion name with its weight ("cup · 225 g"); named portions only. */
    @Composable
    fun withGrams(unit: MeasureUnit): String = when (unit) {
        is MeasureUnit.Portion ->
            "${of(unit)} · ${unit.gramsEach.roundToInt()}${stringResource(R.string.grams_short)}"

        else -> of(unit)
    }
}
