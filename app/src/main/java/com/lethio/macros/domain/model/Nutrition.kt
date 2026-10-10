package com.lethio.macros.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The denominator of a food's composition; nutrient units remain kcal and grams. */
enum class NutritionBasis(val code: String) {
    MASS("g"), VOLUME("ml");

    companion object {
        fun fromCode(code: String): NutritionBasis = entries.singleOrNull { it.code == code }
            ?: throw IllegalArgumentException("Unknown nutrition basis")
    }
}

@Serializable
enum class Nutrient {
    CALORIES, PROTEIN, FAT, CARBS, FIBRE, SUGAR, SATURATED_FAT, SODIUM;

    val isMacro: Boolean get() = this in setOf(CALORIES, PROTEIN, FAT, CARBS)
}

@Serializable
enum class NutrientQualifier {
    TRACE, BELOW_DETECTION, BELOW_QUANTIFICATION, BELOW_DETECTION_OR_QUANTIFICATION,
    LOGICAL_ZERO, UNDECIDABLE, UNSPECIFIED, BOUNDED,
    BEST_ESTIMATE, AVERAGE, WEIGHTED,
}

@Serializable
data class NutrientReference(val code: String, val reference: String)

/** Selected-field provenance survives portion scaling and saved snapshots. */
@Serializable
data class NutrientOrigin(
    val dataset: String,
    val version: String,
    val foodId: String,
    val nutrientCode: String,
    val references: List<NutrientReference>,
) {
    init { require(listOf(dataset, version, foodId, nutrientCode).all { it.isNotEmpty() }) }
}

/**
 * A publisher figure or a sum of reported figures, in the app's canonical nutrient unit.
 * Null is missing, never zero. An incomplete sum can still have a known numeric subtotal.
 * A below-detection zero does NOT supply an upper bound of zero.
 * sourceValue/sourceUnit preserve input notation; they are not changed by portion scaling.
 */
@Serializable
data class NutrientValue(
    val value: Double? = null,
    val qualifiers: Set<NutrientQualifier> = emptySet(),
    val incomplete: Boolean = false,
    val lowerBound: Double? = null,
    val upperBound: Double? = null,
    val lowerInclusive: Boolean = true,
    val upperInclusive: Boolean = true,
    val sourceValue: String? = null,
    val sourceUnit: String? = null,
    val sourceValueType: String? = null,
    val sourceOrigin: NutrientOrigin? = null,
) {
    init {
        require(listOf(value, lowerBound, upperBound).all { it == null || it.isFinite() && it >= 0 })
        require(lowerBound == null || upperBound == null || lowerBound <= upperBound)
        require(lowerInclusive || lowerBound != null)
        require(upperInclusive || upperBound != null)
        require(NutrientQualifier.LOGICAL_ZERO !in qualifiers || value == 0.0)
    }

    val reportedValue: Double?
        get() = value.takeUnless { NutrientQualifier.UNDECIDABLE in qualifiers && !incomplete }
    val isIncomplete: Boolean get() = incomplete || reportedValue == null
    val isQualified: Boolean get() = qualifiers.any { it != NutrientQualifier.LOGICAL_ZERO }

    operator fun times(factor: Double): NutrientValue {
        require(factor.isFinite() && factor >= 0)
        return copy(value = value?.times(factor), lowerBound = lowerBound?.times(factor),
            upperBound = upperBound?.times(factor), lowerInclusive = factor == 0.0 || lowerInclusive,
            upperInclusive = factor == 0.0 || upperInclusive)
    }

    operator fun plus(other: NutrientValue): NutrientValue {
        val first = reportedValue
        val second = other.reportedValue
        // Raw inputs remain in individual snapshots, not in a combined day's source notation.
        return NutrientValue(
            value = if (first == null && second == null) null else (first ?: 0.0) + (second ?: 0.0),
            qualifiers = (qualifiers + other.qualifiers) - NutrientQualifier.LOGICAL_ZERO,
            incomplete = isIncomplete || other.isIncomplete,
            lowerBound = sumBound(other, upper = false),
            upperBound = sumBound(other, upper = true),
            lowerInclusive = sumBound(other, upper = false) == null || lowerInclusive && other.lowerInclusive,
            upperInclusive = sumBound(other, upper = true) == null || upperInclusive && other.upperInclusive,
        )
    }

    private fun sumBound(other: NutrientValue, upper: Boolean): Double? {
        fun bound(item: NutrientValue): Double? =
            (if (upper) item.upperBound else item.lowerBound)
                ?: item.reportedValue?.takeIf { !item.isIncomplete && !item.isQualified }
        // Never manufacture a detection limit or a complete bound from a partial subtotal.
        val a = bound(this) ?: return null
        val b = bound(other) ?: return null
        return (a + b).takeIf { (if (upper) upperBound else lowerBound) != null ||
            (if (upper) other.upperBound else other.lowerBound) != null }
    }
}

/** One versioned contract shared by catalogue metadata and saved nutrient snapshots. */
object NutritionCodec {
    @Serializable
    private data class Payload(val version: Int = 1, val nutrients: Map<Nutrient, NutrientValue>)
    private val json = Json { encodeDefaults = false }

    fun encode(values: Map<Nutrient, NutrientValue>): String? =
        values.takeIf { it.isNotEmpty() }?.let { json.encodeToString(Payload(nutrients = it)) }

    fun decode(raw: String?): Map<Nutrient, NutrientValue> {
        if (raw == null) return emptyMap()
        val payload = json.decodeFromString<Payload>(raw)
        require(payload.version == 1)
        return payload.nutrients
    }
}
