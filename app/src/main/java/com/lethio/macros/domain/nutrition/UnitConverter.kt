package com.lethio.macros.domain.nutrition

import com.lethio.macros.domain.model.Food
import com.lethio.macros.domain.model.MeasureUnit
import com.lethio.macros.domain.model.Quantity
import com.lethio.macros.domain.model.NutritionBasis

/** Resolves an entered unit into the food's native basis, and mass when known. The one place units convert. */
object UnitConverter {

    /**
     * @return the resolved [Quantity], or null when a mass/volume conversion needs an unknown
     *   density. Never assumes water: oil would be off by about 15%.
     */
    fun resolve(amount: Double, unit: MeasureUnit, food: Food?): Quantity? {
        if (!NumericInput.isUsable(amount)) return null
        val density = food?.densityGPerMl?.takeIf { it.isFinite() && it > 0 }
        val basis = food?.nutritionBasis ?: NutritionBasis.MASS
        val grams: Double? = when (unit) {
            MeasureUnit.Grams -> amount
            MeasureUnit.Ounces -> amount * MeasureUnit.GRAMS_PER_OUNCE
            MeasureUnit.Milliliters -> density?.let { amount * it }
                ?: if (basis == NutritionBasis.VOLUME) null else return null
            // A portion of zero grams converts nowhere; guarded as in [fromGrams].
            is MeasureUnit.Portion -> unit.gramsEach.takeIf { it.isFinite() && it > 0 }?.let { amount * it }
                ?: return null
        }
        if (grams != null && !grams.isFinite()) return null
        val basisAmount = when (basis) {
            NutritionBasis.MASS -> grams ?: return null
            NutritionBasis.VOLUME -> if (unit == MeasureUnit.Milliliters) amount
                else grams?.let { mass -> density?.let { mass / it } } ?: return null
        }
        if (!basisAmount.isFinite()) return null
        return Quantity(amount, unit, grams, basis, basisAmount)
    }

    /**
     * Converts a gram mass back into [unit], for pre-filling an editor when the original unit is
     * known but the amount needs recomputing.
     */
    fun fromGrams(grams: Double, unit: MeasureUnit, food: Food?): Double? = when (unit) {
        MeasureUnit.Grams -> grams
        MeasureUnit.Ounces -> grams / MeasureUnit.GRAMS_PER_OUNCE
        MeasureUnit.Milliliters -> food?.densityGPerMl?.takeIf { it.isFinite() && it > 0 }?.let { grams / it }
        is MeasureUnit.Portion -> unit.gramsEach.takeIf { it.isFinite() && it > 0 }?.let { grams / it }
    }
}
