package com.lethio.macros.domain.model

data class Serving(
    val label: String,
    val grams: Double,
    val isDefault: Boolean = false,
)

sealed interface MeasureUnit {

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

    data class Portion(val name: String, val gramsEach: Double) : MeasureUnit {
        override val label = name
    }

    companion object {
        const val GRAMS_PER_OUNCE = 28.349523125

        fun fromLabel(label: String, servings: List<Serving> = emptyList()): MeasureUnit =
            when (label) {
                Grams.label -> Grams
                Milliliters.label -> Milliliters
                Ounces.label -> Ounces
                else -> servings.firstOrNull { it.label == label }
                    ?.let { Portion(it.label, it.grams) }
                    ?: Grams
            }

        fun fromLoggedEntry(label: String, amount: Double, grams: Double): MeasureUnit =
            when (label) {
                Grams.label -> Grams
                Milliliters.label -> Milliliters
                Ounces.label -> Ounces
                else -> if (amount > 0) Portion(label, grams / amount) else Grams
            }
    }
}
