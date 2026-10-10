package com.lethio.macros.domain.model

import java.time.LocalTime

/**
 * Which meal an entry belongs to. Values are 1-4 with no column default, so a caller must say which.
 * No string resources here, so the domain stays testable on the JVM.
 */
enum class MealType(val value: Int) {
    BREAKFAST(1),
    LUNCH(2),
    DINNER(3),
    SNACK(4);

    companion object {
        /** Never throws: one bad row must not crash the diary. */
        fun fromValue(value: Int): MealType = entries.firstOrNull { it.value == value } ?: SNACK

        /** Display order. */
        val ordered: List<MealType> = listOf(BREAKFAST, LUNCH, DINNER, SNACK)

        /** An initial guess from the clock for Log Entry and Quick Add. */
        fun forTime(time: LocalTime): MealType = when {
            time.isBefore(LocalTime.of(11, 0)) -> BREAKFAST
            time.isBefore(LocalTime.of(15, 0)) -> LUNCH
            time.isBefore(LocalTime.of(17, 0)) -> SNACK
            time.isBefore(LocalTime.of(21, 0)) -> DINNER
            else -> SNACK
        }
    }
}
