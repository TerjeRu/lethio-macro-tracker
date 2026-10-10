package com.lethio.macros.domain.model

import java.time.LocalDate

/**
 * Daily targets, valid from [effectiveFrom] until the next set. Versioned, so changing a target
 * never rescores the past: goals on day D are the set with the greatest [effectiveFrom] not after D.
 */
data class Goals(
    val effectiveFrom: LocalDate,
    val targets: Macros,
    val source: Source,
) {
    enum class Source(val value: String) {
        /** Typed in by the user. */
        MANUAL("manual"),

        /** Derived from the profile by the goal calculator. */
        CALCULATED("calculated");

        companion object {
            fun fromValue(value: String?): Source =
                entries.firstOrNull { it.value == value } ?: MANUAL
        }
    }

    companion object {
        /** A placeholder for a user who has set no goals; not a recommendation. */
        fun default(from: LocalDate) = Goals(
            effectiveFrom = from,
            targets = Macros(calories = 2000.0, proteinG = 150.0, fatG = 65.0, carbsG = 250.0),
            source = Source.MANUAL,
        )
    }
}
