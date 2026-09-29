package com.lethio.macros.domain.nutrition

import com.lethio.macros.domain.model.Macros

object NutritionPlausibility {

    private val KCAL_RANGE = 0.0..900.0

    private val MACRO_RANGE = 0.0..100.0

    private const val MACRO_SUM_MAX = 105.0

    private const val ZERO_KCAL_MACRO_LIMIT = 0.5

    private val NAME_LENGTH = 2..120

    private val GTIN_LENGTHS = setOf(8, 12, 13, 14)

    fun isNameUsable(name: String?): Boolean {
        val trimmed = name?.trim().orEmpty()
        return trimmed.length in NAME_LENGTH
    }

    fun isBarcodeUsable(barcode: String?): Boolean {
        val trimmed = barcode?.trim().orEmpty()
        if (trimmed.length !in GTIN_LENGTHS || trimmed.any { it !in '0'..'9' }) return false

        val weightedSum = trimmed
            .dropLast(1)
            .reversed()
            .mapIndexed { index, character ->
                character.digitToInt() * if (index % 2 == 0) 3 else 1
            }
            .sum()
        val expectedCheckDigit = (10 - weightedSum % 10) % 10
        return trimmed.last().digitToInt() == expectedCheckDigit
    }

    fun isWithinBounds(macros: Macros): Boolean {
        val each = listOf(macros.proteinG, macros.fatG, macros.carbsG)
        return macros.calories in KCAL_RANGE &&
            each.all { it.isFinite() && it in MACRO_RANGE } &&
            each.sum() <= MACRO_SUM_MAX &&
            (macros.calories != 0.0 || (macros.proteinG <= ZERO_KCAL_MACRO_LIMIT && macros.fatG <= ZERO_KCAL_MACRO_LIMIT))
    }

    fun isPlausible(macros: Macros): Boolean =
        isWithinBounds(macros) && AtwaterCheck.isConsistent(macros)
}
