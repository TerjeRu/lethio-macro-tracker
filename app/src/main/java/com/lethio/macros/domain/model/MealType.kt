package com.lethio.macros.domain.model

import java.time.LocalTime

enum class MealType(val value: Int) {
    BREAKFAST(1),
    LUNCH(2),
    DINNER(3),
    SNACK(4);

    companion object {

        fun fromValue(value: Int): MealType = entries.firstOrNull { it.value == value } ?: SNACK

        val ordered: List<MealType> = listOf(BREAKFAST, LUNCH, DINNER, SNACK)

        fun forTime(time: LocalTime): MealType = when {
            time.isBefore(LocalTime.of(11, 0)) -> BREAKFAST
            time.isBefore(LocalTime.of(15, 0)) -> LUNCH
            time.isBefore(LocalTime.of(17, 0)) -> SNACK
            time.isBefore(LocalTime.of(21, 0)) -> DINNER
            else -> SNACK
        }
    }
}
