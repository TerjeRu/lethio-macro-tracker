package com.lethio.macros.domain.model

/**
 * Energy and the three macronutrients, always kcal and grams, used for both composition and logged
 * totals. kJ and imperial are display conversions only.
 */
data class Macros(
    val calories: Double = 0.0,
    val proteinG: Double = 0.0,
    val fatG: Double = 0.0,
    val carbsG: Double = 0.0,
    /** Sparse for legacy rows. Includes selected optional nutrients when supplied. */
    val nutrients: Map<Nutrient, NutrientValue> = emptyMap(),
) {
    operator fun plus(other: Macros) = Macros(
        calories = calories + other.calories,
        proteinG = proteinG + other.proteinG,
        fatG = fatG + other.fatG,
        carbsG = carbsG + other.carbsG,
        nutrients = (nutrients.keys + other.nutrients.keys).associateWith {
            nutrient(it) + other.nutrient(it)
        },
    )

    /** Scales composition or snapshots without rounding. Food resolves the denominator first. */
    operator fun times(factor: Double) = Macros(
        calories = calories * factor,
        proteinG = proteinG * factor,
        fatG = fatG * factor,
        carbsG = carbsG * factor,
        nutrients = nutrients.mapValues { (_, value) -> value * factor },
    )

    /** Composition per 100g scaled to [grams]. */
    fun forGrams(grams: Double) = this * (grams / 100.0)

    fun nutrient(kind: Nutrient): NutrientValue = nutrients[kind] ?: when (kind) {
        Nutrient.CALORIES -> NutrientValue(calories)
        Nutrient.PROTEIN -> NutrientValue(proteinG)
        Nutrient.FAT -> NutrientValue(fatG)
        Nutrient.CARBS -> NutrientValue(carbsG)
        else -> NutrientValue()
    }

    fun isComplete(kind: Nutrient): Boolean = !nutrient(kind).isIncomplete

    /** Missing primary values contribute no known amount; metadata distinguishes that from zero. */
    fun withNutrients(values: Map<Nutrient, NutrientValue>): Macros = copy(
        calories = values[Nutrient.CALORIES]?.let { it.reportedValue ?: 0.0 } ?: calories,
        proteinG = values[Nutrient.PROTEIN]?.let { it.reportedValue ?: 0.0 } ?: proteinG,
        fatG = values[Nutrient.FAT]?.let { it.reportedValue ?: 0.0 } ?: fatG,
        carbsG = values[Nutrient.CARBS]?.let { it.reportedValue ?: 0.0 } ?: carbsG,
        nutrients = values,
    )

    companion object {
        val ZERO = Macros()

        /** An empty collection is zero intake, not a food with missing optional nutrients. */
        fun sum(values: Iterable<Macros>): Macros = values.reduceOrNull { a, b -> a + b } ?: ZERO
    }
}
