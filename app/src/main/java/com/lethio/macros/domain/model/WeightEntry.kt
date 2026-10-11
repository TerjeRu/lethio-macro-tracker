package com.lethio.macros.domain.model

import java.time.Instant
import java.time.LocalDate

/**
 * A recorded body weight.
 *
 * Always stored in kilograms, converted only for display — the same discipline as [Quantity.grams].
 * One entry per day, enforced by a unique index on the date: weighing yourself twice should correct
 * the day's figure, not add a second one.
 */
data class WeightEntry(
    val id: Long,
    val measuredOn: LocalDate,
    val weightKg: Double,
    val note: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        const val NEW = 0L
        const val KG_PER_POUND = 0.45359237
    }
}
