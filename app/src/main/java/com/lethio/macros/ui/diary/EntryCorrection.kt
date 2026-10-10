package com.lethio.macros.ui.diary

import com.lethio.macros.domain.model.LogEntry
import com.lethio.macros.domain.model.MealType
import com.lethio.macros.domain.nutrition.NumericInput

/** Rescale the logged snapshot, never the current food catalogue or a previous draft. */
internal fun correctedEntry(original: LogEntry, amountText: String, meal: MealType): LogEntry? {
    val amount = (NumericInput.parse(amountText) as? NumericInput.Field.Number)?.value ?: return null
    if (amount == original.quantity.amount) return original.copy(meal = meal)
    if (amount <= 0 || original.quantity.amount <= 0 || !original.quantity.amount.isFinite()) return null
    val factor = amount / original.quantity.amount
    val grams = original.quantity.grams?.times(factor)
    val basisAmount = original.quantity.basisAmount * factor
    val macros = runCatching { original.macros * factor }.getOrNull() ?: return null
    if (!factor.isFinite() || !basisAmount.isFinite() || basisAmount <= 0 ||
        grams != null && (!grams.isFinite() || grams < 0) ||
        listOf(macros.calories, macros.proteinG, macros.fatG, macros.carbsG).any { !it.isFinite() || it < 0 }
    ) return null
    return original.copy(meal = meal, quantity = original.quantity.copy(amount = amount, grams = grams,
        basisAmount = basisAmount), macros = macros)
}

data class EntryEditState(
    val original: LogEntry,
    val amount: String = original.quantity.amount.toString(),
    val meal: MealType = original.meal,
    val saving: Boolean = false,
    val failed: Boolean = false,
)
