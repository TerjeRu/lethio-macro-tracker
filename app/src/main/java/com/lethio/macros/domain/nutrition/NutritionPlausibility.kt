package com.lethio.macros.domain.nutrition

import com.lethio.macros.domain.model.Macros

/**
 * The bounds a per-100 g composition must satisfy, as the build pipeline applies them to bundled
 * rows; here for products that arrive at runtime. Failing is the
 * ordinary case for crowd-sourced data and is quiet. [AtwaterCheck] catches what bounds cannot:
 * kilojoules entered as kcal, or macros scaled by 100.
 */
object NutritionPlausibility {

    /** Includes water and other genuine zero-energy foods, matching the catalogue pipeline. */
    private val KCAL_RANGE = 0.0..900.0

    /** No single macronutrient can exceed the mass it is measured in. */
    private val MACRO_RANGE = 0.0..100.0

    /**
     * Macros may sum past 100 g only by a rounding margin. Labels round each figure independently,
     * so an honest label can total slightly over; 105 is where that stops being rounding.
     */
    private const val MACRO_SUM_MAX = 105.0

    /** Protein/fat above trace cannot have zero energy; carbohydrate still uses AtwaterCheck. */
    private const val ZERO_KCAL_MACRO_LIMIT = 0.5

    /** Long enough to be a name, short enough not to be a paragraph of ingredients. */
    private val NAME_LENGTH = 2..120

    /** The four canonical GTIN lengths; intermediate digit counts are not assigned standards. */
    private val GTIN_LENGTHS = setOf(8, 12, 13, 14)

    fun isNameUsable(name: String?): Boolean {
        val trimmed = name?.trim().orEmpty()
        return trimmed.length in NAME_LENGTH
    }

    /**
     * The app's definition of a barcode: ASCII digits (`'0'..'9'`, not [Char.isDigit], which accepts
     * every Unicode digit), a GTIN length, and a valid check digit. Matches relay protocol v1.
     */
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

    /** Bounds only. [isPlausible] is what callers want; this exists so failures can be told apart. */
    fun isWithinBounds(macros: Macros): Boolean {
        val each = listOf(macros.proteinG, macros.fatG, macros.carbsG)
        return macros.calories in KCAL_RANGE &&
            each.all { it.isFinite() && it in MACRO_RANGE } &&
            each.sum() <= MACRO_SUM_MAX &&
            (macros.calories != 0.0 || (macros.proteinG <= ZERO_KCAL_MACRO_LIMIT && macros.fatG <= ZERO_KCAL_MACRO_LIMIT))
    }

    /** Bounds first, then Atwater: an out-of-range figure is meaningless input to the energy check. */
    fun isPlausible(macros: Macros): Boolean =
        isWithinBounds(macros) && AtwaterCheck.isConsistent(macros)
}
