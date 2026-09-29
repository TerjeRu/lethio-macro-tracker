package com.lethio.macros.domain.nutrition

import com.lethio.macros.domain.model.Food
import com.lethio.macros.domain.model.MeasureUnit
import com.lethio.macros.domain.model.Quantity

object UnitConverter {

    fun resolve(amount: Double, unit: MeasureUnit, food: Food?): Quantity? {

        if (!NumericInput.isUsable(amount)) return null
        val grams = when (unit) {
            MeasureUnit.Grams -> amount
            MeasureUnit.Ounces -> amount * MeasureUnit.GRAMS_PER_OUNCE
            MeasureUnit.Milliliters -> food?.densityGPerMl?.let { amount * it } ?: return null

            is MeasureUnit.Portion -> unit.gramsEach.takeIf { it > 0 }?.let { amount * it }
                ?: return null
        }
        if (!grams.isFinite()) return null
        return Quantity(amount = amount, unit = unit, grams = grams)
    }

    fun fromGrams(grams: Double, unit: MeasureUnit, food: Food?): Double? = when (unit) {
        MeasureUnit.Grams -> grams
        MeasureUnit.Ounces -> grams / MeasureUnit.GRAMS_PER_OUNCE
        MeasureUnit.Milliliters -> food?.densityGPerMl?.takeIf { it > 0 }?.let { grams / it }
        is MeasureUnit.Portion -> unit.gramsEach.takeIf { it > 0 }?.let { grams / it }
    }
}
