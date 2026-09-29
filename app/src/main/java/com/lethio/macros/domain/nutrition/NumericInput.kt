package com.lethio.macros.domain.nutrition

object NumericInput {

    const val PLAUSIBLE_MAX_CALORIES = 5_000.0
    const val PLAUSIBLE_MAX_GRAMS = 1_000.0

    const val HARD_MIN_CALORIES = 800.0

    sealed interface Field {

        data object Empty : Field

        data class Number(val value: Double) : Field

        data object Unusable : Field
    }

    fun isUsable(value: Double): Boolean = value.isFinite() && value >= 0

    fun parse(text: String): Field {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Field.Empty
        val normalised = normaliseSeparator(trimmed) ?: return Field.Unusable
        val value = normalised.toDoubleOrNull() ?: return Field.Unusable
        return if (isUsable(value)) Field.Number(value) else Field.Unusable
    }

    private fun normaliseSeparator(text: String): String? {
        val normalised = text.replace(',', '.')
        return if (normalised.count { it == '.' } > 1) null else normalised
    }

    fun isUnusable(text: String): Boolean = parse(text) is Field.Unusable

    fun valueOr(text: String, fallback: Double): Double? = when (val field = parse(text)) {
        is Field.Empty -> fallback
        is Field.Number -> field.value
        is Field.Unusable -> null
    }

    fun isImplausible(text: String, max: Double): Boolean =
        (parse(text) as? Field.Number)?.value?.let { it > max } == true

    fun isBelow(text: String, min: Double): Boolean =
        (parse(text) as? Field.Number)?.value?.let { it < min } == true
}
