package com.lethio.macros.domain.model

/**
 * How much was logged, in two forms: what the user chose ([amount] of [unit], "1.5 slices") and the
 * resolved [basisAmount] and [grams]. Editing shows what was typed; totals scale saved snapshots
 * from [basisAmount], so later conversion changes cannot alter history.
 */
data class Quantity(
    val amount: Double,
    val unit: MeasureUnit,
    val grams: Double?,
    val basis: NutritionBasis = NutritionBasis.MASS,
    val basisAmount: Double = grams ?: amount,
) {
    companion object {
        fun ofGrams(grams: Double) = Quantity(grams, MeasureUnit.Grams, grams)
    }
}
