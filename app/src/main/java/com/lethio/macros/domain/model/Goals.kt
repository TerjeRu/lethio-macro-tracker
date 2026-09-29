package com.lethio.macros.domain.model

import java.time.LocalDate

data class Goals(
    val effectiveFrom: LocalDate,
    val targets: Macros,
    val source: Source,
) {
    enum class Source(val value: String) {

        MANUAL("manual"),

        CALCULATED("calculated");

        companion object {
            fun fromValue(value: String?): Source =
                entries.firstOrNull { it.value == value } ?: MANUAL
        }
    }

    companion object {

        fun default(from: LocalDate) = Goals(
            effectiveFrom = from,
            targets = Macros(calories = 2000.0, proteinG = 150.0, fatG = 65.0, carbsG = 250.0),
            source = Source.MANUAL,
        )
    }
}
