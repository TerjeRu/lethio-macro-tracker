package com.lethio.macros.domain.model

/** A named portion of a food ("1 slice" = 28 g) from the publisher's portion data. */
data class Serving(
    val label: String,
    val grams: Double,
    val isDefault: Boolean = false,
)

/**
 * The unit chosen when logging, kept with the resolved amount and mass so an entry reopens as typed
 * and exports name a portion people recognise.
 */
sealed interface MeasureUnit {

    /** Persisted form. For [Portion] this is the serving's own name. */
    val label: String

    data object Grams : MeasureUnit {
        override val label = "g"
    }

    data object Milliliters : MeasureUnit {
        override val label = "ml"
    }

    data object Ounces : MeasureUnit {
        override val label = "oz"
    }

    /** A named serving belonging to the food being logged. */
    data class Portion(val name: String, val gramsEach: Double) : MeasureUnit {
        override val label = name
    }

    companion object {
        const val GRAMS_PER_OUNCE = 28.349523125

        /**
         * Rebuilds a unit from its label against the food's servings. A portion that no longer
         * exists degrades to [Grams]; the logged mass is unaffected.
         */
        fun fromLabel(label: String, servings: List<Serving> = emptyList()): MeasureUnit =
            when (label) {
                Grams.label -> Grams
                Milliliters.label -> Milliliters
                Ounces.label -> Ounces
                else -> servings.firstOrNull { it.label == label }
                    ?.let { Portion(it.label, it.grams) }
                    ?: Grams
            }

        /**
         * Rebuilds a logged entry's unit from the entry alone: one portion weighs `grams / amount`.
         * The food's current servings are not consulted; the entry records what was eaten.
         */
        fun fromLoggedEntry(label: String, amount: Double, grams: Double?): MeasureUnit =
            when (label) {
                Grams.label -> Grams
                Milliliters.label -> Milliliters
                Ounces.label -> Ounces
                else -> if (amount > 0 && grams != null) Portion(label, grams / amount) else Grams
            }
    }
}
