package com.lethio.macros.domain.model

import java.time.Instant
import java.time.LocalDate

data class LogEntry(
    val id: Long,
    val date: LocalDate,
    val meal: MealType,
    val foodRef: FoodRef,
    val foodName: String,
    val brand: String? = null,
    val quantity: Quantity,
    val macros: Macros,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {

        const val NEW = 0L
    }
}

data class DayLog(
    val date: LocalDate,
    val entriesByMeal: Map<MealType, List<LogEntry>>,
    val totals: Macros,
) {
    val isEmpty: Boolean get() = entriesByMeal.values.all { it.isEmpty() }

    companion object {
        fun empty(date: LocalDate) = DayLog(date, emptyMap(), Macros.ZERO)
    }
}
