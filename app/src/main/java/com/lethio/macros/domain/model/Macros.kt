package com.lethio.macros.domain.model

data class Macros(
    val calories: Double = 0.0,
    val proteinG: Double = 0.0,
    val fatG: Double = 0.0,
    val carbsG: Double = 0.0,
) {
    operator fun plus(other: Macros) = Macros(
        calories = calories + other.calories,
        proteinG = proteinG + other.proteinG,
        fatG = fatG + other.fatG,
        carbsG = carbsG + other.carbsG,
    )

    operator fun times(factor: Double) = Macros(
        calories = calories * factor,
        proteinG = proteinG * factor,
        fatG = fatG * factor,
        carbsG = carbsG * factor,
    )

    fun forGrams(grams: Double) = this * (grams / 100.0)

    companion object {
        val ZERO = Macros()
    }
}
