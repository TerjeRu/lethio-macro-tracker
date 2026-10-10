package com.lethio.macros.data.off

import com.lethio.macros.domain.model.Macros
import com.lethio.macros.domain.model.Nutrient
import com.lethio.macros.domain.nutrition.NutritionPlausibility

/** Protocol v1 still requires at least 1 kcal; lookup/local saving also accept valid zero energy. */
internal fun isRelayNutritionUsable(macros: Macros): Boolean =
    macros.calories >= 1.0 && NutritionPlausibility.isPlausible(macros) &&
        listOf(Nutrient.CALORIES, Nutrient.PROTEIN, Nutrient.FAT, Nutrient.CARBS).all {
            macros.isComplete(it) && !macros.nutrient(it).isQualified
        }

private const val MAX_RELAY_TEXT_LENGTH = 120

internal fun String.trimForRelay(): String =
    trim { character -> character.isWhitespace() || Character.isSpaceChar(character) }

internal fun isRelayTextUsable(value: String, minimumLength: Int): Boolean {
    val trimmed = value.trimForRelay()
    val codePointLength = Character.codePointCount(trimmed, 0, trimmed.length)
    if (codePointLength !in minimumLength..MAX_RELAY_TEXT_LENGTH) return false

    var offset = 0
    while (offset < trimmed.length) {
        val character = trimmed[offset]
        if (character.isSurrogate() &&
            !(character.isHighSurrogate() &&
                offset + 1 < trimmed.length &&
                trimmed[offset + 1].isLowSurrogate())
        ) {
            return false
        }
        val codePoint = Character.codePointAt(trimmed, offset)
        if (Character.getType(codePoint) in DISALLOWED_UNICODE_TYPES) return false
        offset += Character.charCount(codePoint)
    }
    return true
}

// These four categories are stable properties of a code point. UNASSIGNED is absent:
// it describes the Unicode table bundled with this particular runtime, so rejecting it made a
// current Android accept characters that the older relay runtime refused.
private val DISALLOWED_UNICODE_TYPES = setOf(
    Character.CONTROL.toInt(),
    Character.FORMAT.toInt(),
    Character.SURROGATE.toInt(),
    Character.PRIVATE_USE.toInt(),
)
