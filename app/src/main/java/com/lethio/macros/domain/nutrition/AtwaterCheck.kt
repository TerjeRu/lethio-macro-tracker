package com.lethio.macros.domain.nutrition

import com.lethio.macros.domain.model.Macros
import kotlin.math.abs

/**
 * Checks stated energy against the macros (4 kcal/g protein and carbohydrate, 9 fat). A large
 * mismatch is almost always a unit error: kilojoules as kcal, or values scaled by 100. The build
 * pipeline rejects on it; manual entry warns.
 */
object AtwaterCheck {

    const val KCAL_PER_G_PROTEIN = 4.0
    const val KCAL_PER_G_CARBS = 4.0
    const val KCAL_PER_G_FAT = 9.0

    /** Energy the macros imply, ignoring the stated calorie figure. */
    fun predictedCalories(macros: Macros): Double =
        macros.proteinG * KCAL_PER_G_PROTEIN +
            macros.fatG * KCAL_PER_G_FAT +
            macros.carbsG * KCAL_PER_G_CARBS

    /**
     * Whether stated and predicted energy agree. Loose on purpose: fibre, sugar alcohols and label
     * rounding all cause honest deviation; the target is order-of-magnitude errors.
     *
     * @param absoluteFloor minimum slack, so low-calorie foods are not flagged for trivial gaps.
     * @param relativeTolerance proportional slack for everything else.
     */
    fun isConsistent(
        macros: Macros,
        absoluteFloor: Double = 35.0,
        relativeTolerance: Double = 0.30,
    ): Boolean {
        val predicted = predictedCalories(macros)
        val allowed = maxOf(absoluteFloor, relativeTolerance * macros.calories)
        return abs(macros.calories - predicted) <= allowed
    }

    /** Signed difference, for showing the user which direction is off. */
    fun discrepancy(macros: Macros): Double = macros.calories - predictedCalories(macros)
}
