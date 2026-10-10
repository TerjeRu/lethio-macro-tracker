package com.lethio.macros.domain.nutrition

import com.lethio.macros.domain.model.Macros

/** Broad target-only typo guards. Logged intake is never clamped or rejected by these limits. */
object GoalTargetLimits {
    const val MAX_CALORIES = 20_000.0
    const val MAX_GRAMS = 5_000.0

    fun accepts(targets: Macros): Boolean =
        targets.calories.isFinite() && targets.calories in NumericInput.HARD_MIN_CALORIES..MAX_CALORIES &&
            listOf(targets.proteinG, targets.fatG, targets.carbsG).all {
                it.isFinite() && it in 0.0..MAX_GRAMS
            }
}
