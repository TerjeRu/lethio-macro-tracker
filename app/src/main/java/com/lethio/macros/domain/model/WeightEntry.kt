package com.lethio.macros.domain.model

import java.time.Instant
import java.time.LocalDate

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
