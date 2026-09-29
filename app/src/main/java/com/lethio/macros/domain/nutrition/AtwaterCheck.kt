package com.lethio.macros.domain.nutrition

import com.lethio.macros.domain.model.Macros
import kotlin.math.abs

object AtwaterCheck {

    const val KCAL_PER_G_PROTEIN = 4.0
    const val KCAL_PER_G_CARBS = 4.0
    const val KCAL_PER_G_FAT = 9.0

    fun predictedCalories(macros: Macros): Double =
        macros.proteinG * KCAL_PER_G_PROTEIN +
            macros.fatG * KCAL_PER_G_FAT +
            macros.carbsG * KCAL_PER_G_CARBS

    fun isConsistent(
        macros: Macros,
        absoluteFloor: Double = 35.0,
        relativeTolerance: Double = 0.30,
    ): Boolean {
        val predicted = predictedCalories(macros)
        val allowed = maxOf(absoluteFloor, relativeTolerance * macros.calories)
        return abs(macros.calories - predicted) <= allowed
    }

    fun discrepancy(macros: Macros): Double = macros.calories - predictedCalories(macros)
}
