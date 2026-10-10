package com.lethio.macros.domain.nutrition

/**
 * Decides whether a typed number is usable. New numeric fields should go through [parse].
 *
 * Three cases:
 * - Impossible (negative, NaN, Infinity): blocked, and checked again at save.
 * - Implausible (finite but far above any real entry): warned, never blocked. A whole cake really
 *   is 4,000 kcal.
 * - Unsafe: a daily target below [HARD_MIN_CALORIES] is blocked, and one between that and the
 *   conventional floor is warned. Applies to targets only: a 200 kcal lunch is a fact, a 200 kcal
 *   day is a plan.
 *
 * `Macros` has no constructor checks because it also carries intermediate arithmetic; validation
 * happens here, where a person typed a string.
 */
object NumericInput {

    /** Above these a single entry or target is questioned, not refused. */
    const val PLAUSIBLE_MAX_CALORIES = 5_000.0
    const val PLAUSIBLE_MAX_GRAMS = 1_000.0

    /**
     * The lowest daily target the app records: the conventional very-low-calorie-diet line, which
     * is a medically supervised protocol. Not applied to macro grams or logged foods.
     */
    const val HARD_MIN_CALORIES = 800.0

    /** What a typed field currently holds. */
    sealed interface Field {
        /** Nothing typed. Each caller decides what that means. */
        data object Empty : Field

        /** A number that could be a real measurement. */
        data class Number(val value: Double) : Field

        /** Text that is not a number, or a number no measurement can take. */
        data object Unusable : Field
    }

    /** True when [value] could be a real measurement: finite, and not negative. */
    fun isUsable(value: Double): Boolean = value.isFinite() && value >= 0

    /**
     * Reads one typed field. Blank is [Field.Empty], not zero: Quick Add reads it as none, the
     * goals editor as unchanged.
     */
    fun parse(text: String): Field {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Field.Empty
        val normalised = normaliseSeparator(trimmed) ?: return Field.Unusable
        val value = normalised.toDoubleOrNull() ?: return Field.Unusable
        return if (isUsable(value)) Field.Number(value) else Field.Unusable
    }

    /**
     * Accepts both `1,5` and `1.5` regardless of locale; people carry a keyboard from one country
     * and a phone from another. More than one separator (`1.234,5`) is ambiguous and refused; these
     * fields never need grouping.
     */
    private fun normaliseSeparator(text: String): String? {
        val normalised = text.replace(',', '.')
        return if (normalised.count { it == '.' } > 1) null else normalised
    }

    /** True when the field holds something that cannot be saved. Empty is not an error. */
    fun isUnusable(text: String): Boolean = parse(text) is Field.Unusable

    /** The value, or [fallback] when the field is empty. Null when it cannot be used at all. */
    fun valueOr(text: String, fallback: Double): Double? = when (val field = parse(text)) {
        is Field.Empty -> fallback
        is Field.Number -> field.value
        is Field.Unusable -> null
    }

    /** True when the field holds a usable number that is nonetheless worth questioning. */
    fun isImplausible(text: String, max: Double): Boolean =
        (parse(text) as? Field.Number)?.value?.let { it > max } == true

    /** True when the field holds a usable number below [min]. Empty is not below anything. */
    fun isBelow(text: String, min: Double): Boolean =
        (parse(text) as? Field.Number)?.value?.let { it < min } == true
}
